package com.mapbox.toolkit.live

import co.touchlab.kermit.Logger
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.context.ViaNavigationState
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.actions.ViaActionRegistry
import com.mapbox.via.core.plugins.agent.prompts.CLEARED_CONTEXT_SECTION
import com.mapbox.via.core.plugins.agent.prompts.buildContextAppends
import com.mapbox.via.core.plugins.agent.prompts.buildContextSection
import com.mapbox.via.core.plugins.agent.prompts.contextSignature
import com.mapbox.via.core.plugins.agent.prompts.stabilizeContextLocation
import com.mapbox.via.core.plugins.director.openai.ToolRoundLimiter
import com.mapbox.via.core.plugins.director.openai.live.decodeLive
import com.mapbox.via.core.plugins.director.openai.live.encodeLive
import com.mapbox.via.core.plugins.director.openai.live.eventType
import com.mapbox.via.core.plugins.director.openai.live.models.client.ContextAppendEvent
import com.mapbox.via.core.plugins.director.openai.live.models.client.ResponseCreateEvent
import com.mapbox.via.core.plugins.director.openai.live.models.client.ResponseItemCreateEvent
import com.mapbox.via.core.plugins.director.openai.live.models.client.ResponsesDelegation
import com.mapbox.via.core.plugins.director.openai.live.models.server.ResponseEventEnvelope
import com.mapbox.via.core.plugins.director.openai.live.parseLiveFrame
import com.mapbox.via.core.plugins.director.openai.responses.models.FunctionCall
import com.mapbox.via.core.plugins.director.openai.responses.models.ResponsesInputItem
import com.mapbox.via.core.plugins.director.openai.responses.models.ResponsesStreamEvent
import com.mapbox.via.core.plugins.director.openai.responses.models.toResponsesStreamEvent
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The backend side of a GPT-Live session that uses responses delegation, as the navigation assistant does: the voice
 * model delegates to a backend Responses model, which calls [actions] as function tools.
 *
 * Start the session with [liveInstructions] and [delegation], then create a [Session] for it. The app owns the
 * transport: it passes every server event to [Session.onServerEvent], and sends the events the session produces.
 */
class LiveDirector internal constructor(
    actions: ViaActionRegistry,
    private val contextHolder: ViaContextHolder,
) {
    private val tools = DirectorTools(actions).withExtra(listOf(endConversationToolSpec))
    private val actionNames = actions.actions.associate { it.id to it.name }

    init {
        log.d { "Advertising ${tools.specs.size} tools: ${tools.specs.joinToString { it.name }}" }
    }

    /** Instructions for the voice model, listing every action so it knows which requests to delegate. */
    val liveInstructions: String = prompt("LIVE_PROMPT.md")
        .replace(ACTIONS_PLACEHOLDER, actions.actions.filter { tools.contains(it.id) }.joinToString { it.name })

    /** Backend configuration for the session: model, instructions, and the actions as function tools. */
    val delegation: ResponsesDelegation = ResponsesDelegation(
        ResponsesDelegation.Responses(
            model = BACKEND_MODEL,
            instructions = prompt("BACKEND_PROMPT.md"),
            tools = tools.toBackendTools(),
            toolChoice = "auto",
            parallelToolCalls = true,
            reasoning = ResponsesDelegation.Reasoning(REASONING_EFFORT),
        ),
    )

    /**
     * Runs the backend side of one session in [scope]. Events for the server go to [send]. [onEndConversation] is
     * called once the backend ends the conversation and the voice model has finished speaking. [onActionCall] is called
     * with an action's name each time the backend calls it, before it runs.
     */
    fun session(
        scope: CoroutineScope,
        send: suspend (String) -> Unit,
        onEndConversation: suspend () -> Unit,
        onActionCall: (name: String) -> Unit = {},
    ): Session = Session(scope, send, onEndConversation, onActionCall)

    inner class Session internal constructor(
        private val scope: CoroutineScope,
        private val send: suspend (String) -> Unit,
        private val onEndConversation: suspend () -> Unit,
        private val onActionCall: (name: String) -> Unit,
    ) {
        private val pendingCalls = mutableListOf<FunctionCall>()
        private val toolRounds = ToolRoundLimiter()
        private var contextJob: Job? = null
        private var goodbyeJob: Job? = null
        private var lastAssistantSpeech = TimeSource.Monotonic.markNow()

        private var lastContextSignature: String? = null
        private var lastVoiceContextSignature: String? = null
        private var lastEmittedLocation: ViaPoint? = null
        private var tripAnnouncedActive = false
        private var tripEndedUnacknowledged = false

        /** Handles one server event. Call it from the coroutine that reads the events, in order. */
        fun onServerEvent(frame: String) {
            val json = frame.parseLiveFrame()
            when (json.eventType()) {
                SESSION_STARTED -> startContextWatch()
                OUTPUT_TRANSCRIPT_DELTA -> lastAssistantSpeech = TimeSource.Monotonic.markNow()
                ResponseEventEnvelope.TYPE -> onBackendEvent(json.decodeLive<ResponseEventEnvelope>())
            }
        }

        /** Stops the session's background work. */
        fun close() {
            contextJob?.cancel()
            goodbyeJob?.cancel()
        }

        private fun onBackendEvent(envelope: ResponseEventEnvelope) {
            when (val event = envelope.event.toResponsesStreamEvent()) {
                is ResponsesStreamEvent.Created -> pendingCalls.clear()
                is ResponsesStreamEvent.OutputItemDone -> event.item.asFunctionCall()?.let { pendingCalls += it }
                is ResponsesStreamEvent.Terminal -> onBackendTerminal(event)
                else -> Unit
            }
        }

        private fun onBackendTerminal(event: ResponsesStreamEvent.Terminal) {
            val calls = pendingCalls.toList()
            pendingCalls.clear()
            val failure = event.response.failureMessage()
            when {
                failure != null -> {
                    log.w { "Backend response failed: $failure" }
                    toolRounds.reset()
                }
                calls.isEmpty() -> toolRounds.reset()
                else -> {
                    val continueBackend = toolRounds.recordCallRound()
                    if (!continueBackend) {
                        log.w { "Tool-call round limit reached; not continuing the backend after these calls" }
                        toolRounds.reset()
                    }

                    // Function calls run on their own job so a slow action doesn't stall the event loop.
                    scope.launch { runFunctionCalls(calls, continueBackend) }
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun runFunctionCalls(calls: List<FunctionCall>, continueBackend: Boolean) {
            try {
                executeFunctionCalls(calls).forEach { output ->
                    send(ResponseItemCreateEvent(item = output, eventId = nextEventId()).encodeLive())
                }
                if (continueBackend) send(ResponseCreateEvent(eventId = nextEventId()).encodeLive())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.w(error) { "Function calls ${calls.map { it.name }} could not be returned to the backend" }
            }
        }

        /** Parallel when every call opted in to concurrent execution; otherwise sequential. */
        private suspend fun executeFunctionCalls(calls: List<FunctionCall>): List<ResponsesInputItem.FunctionCallOutput> =
            if (tools.supportsConcurrentBatch(calls.map { it.name })) {
                coroutineScope { calls.map { call -> async { executeFunctionCall(call) } }.awaitAll() }
            } else {
                calls.map { executeFunctionCall(it) }
            }

        private suspend fun executeFunctionCall(call: FunctionCall): ResponsesInputItem.FunctionCallOutput {
            log.d { "Function call ${call.name} args=${call.arguments}" }
            actionNames[call.name]?.let(onActionCall)
            val outcome = when {
                call.name == END_CONVERSATION_TOOL_NAME -> DirectorTools.Outcome(endConversation())
                else -> tools.execute(call.name, call.arguments)
            }
            outcome.error?.let { log.w { "Function call ${call.name} failed: $it" } }
            return ResponsesInputItem.FunctionCallOutput(
                callId = call.callId,
                output = outcome.output,
                failed = outcome.error != null,
            )
        }

        /** Ends the session once the voice model has been quiet for a moment, so its goodbye isn't cut off. */
        private fun endConversation(): String {
            log.i { "Model requested to end the conversation" }
            goodbyeJob?.cancel()
            goodbyeJob = scope.launch {
                val start = TimeSource.Monotonic.markNow()
                delay(GOODBYE_QUIET)
                while (start.elapsedNow() < GOODBYE_MAX_WAIT && lastAssistantSpeech.elapsedNow() < GOODBYE_QUIET) {
                    delay(GOODBYE_QUIET)
                }
                onEndConversation()
            }
            return "Ending the conversation now."
        }

        private fun startContextWatch() {
            lastContextSignature = null
            lastVoiceContextSignature = null
            lastEmittedLocation = null
            contextJob?.cancel()
            contextJob = scope.launch { contextHolder.state.collect { publishContext() } }
        }

        /**
         * Sends the actions' context when it changes: one developer message for the backend, and short notes for the
         * voice model. The voice model gets notes only when something besides the user's location changed, since it
         * answers every note it gets and would otherwise keep saying where the trip is headed while driving.
         */
        private suspend fun publishContext() {
            val update = contextUpdateIfChanged() ?: return
            log.i { "Sending context update (${update.thinkingSections.size} appends)" }
            update.thinkingSections.forEach { section ->
                send(ContextAppendEvent.thinking(section, eventId = nextEventId()).encodeLive())
            }
            send(
                ResponseItemCreateEvent(
                    item = ResponsesInputItem.Message.developer(update.section),
                    eventId = nextEventId(),
                ).encodeLive(),
            )
        }

        private fun contextUpdateIfChanged(): ContextUpdate? {
            val context = contextHolder.state.value
            val signature = contextSignature(context, lastEmittedLocation)
            if (signature == lastContextSignature) return null

            val stabilized = stabilizeContextLocation(context, lastEmittedLocation)
            lastContextSignature = signature
            lastEmittedLocation = stabilized.user?.location

            val voiceSignature = contextSignature(context.copy(user = context.user?.copy(location = null)))
            val voiceChanged = voiceSignature != lastVoiceContextSignature
            lastVoiceContextSignature = voiceSignature

            val section = buildContextSection(stabilized, replacesEarlier = true)
            val tripActive = context.navigation.state is ViaNavigationState.ActiveGuidance
            val justEnded = tripAnnouncedActive && !tripActive
            tripAnnouncedActive = tripActive
            tripEndedUnacknowledged = (tripEndedUnacknowledged || justEnded) && !tripActive

            val sectionNote = NAVIGATION_ENDED_NOTE.takeIf { tripEndedUnacknowledged }
            val appendNote = NAVIGATION_ENDED_NOTE.takeIf { justEnded }
            return ContextUpdate(
                section = listOfNotNull(section ?: CLEARED_CONTEXT_SECTION, sectionNote).joinToString("\n"),
                thinkingSections = if (voiceChanged) {
                    (
                        buildContextAppends(stabilized, maxChars = LIVE_CONTEXT_APPEND_MAX_CHARS)
                            ?: listOf(CLEARED_CONTEXT_SECTION)
                        ) + listOfNotNull(appendNote)
                } else {
                    emptyList()
                },
            )
        }
    }

    private class ContextUpdate(val section: String, val thinkingSections: List<String>)

    private companion object {
        val log = Logger.withTag("MBX_TOOLKIT_LIVE")

        const val BACKEND_MODEL = "gpt-5.6-terra"

        /** Delegated replies are spoken as they arrive, so latency outranks depth of reasoning. */
        const val REASONING_EFFORT = "low"

        const val ACTIONS_PLACEHOLDER = "{{ACTIONS}}"

        const val SESSION_STARTED = "session.started"
        const val OUTPUT_TRANSCRIPT_DELTA = "session.output_transcript.delta"

        /** GPT-Live rejects `session.thinking.append` over 500 tokens, so context is split into short appends. */
        const val LIVE_CONTEXT_APPEND_MAX_CHARS = 600

        const val NAVIGATION_ENDED_NOTE =
            "Navigation ended: there is no active route or destination now. Any trip mentioned earlier in " +
                "this conversation is over; start a new one only if the user asks."

        const val END_CONVERSATION_TOOL_NAME = "end_conversation"

        val endConversationToolSpec = FunctionToolSpec.noArgs(
            name = END_CONVERSATION_TOOL_NAME,
            description = "Ends the conversation immediately, exactly as if the user pressed the app's stop button. " +
                "Call this as soon as the user signals they are done talking to you — for example \"that's all\", " +
                "\"goodbye\", or \"end the call\" — rather than replying and waiting for confirmation. Don't call " +
                "it when the user only asks you to stop talking, or to stop navigation.",
        )

        val GOODBYE_QUIET = 1.5.seconds
        val GOODBYE_MAX_WAIT = 8.seconds

        fun nextEventId(): String = "evt_${UUID.randomUUID()}"

        /** Reads a prompt bundled as a Java resource in this package. */
        fun prompt(name: String): String =
            checkNotNull(LiveDirector::class.java.getResourceAsStream(name)) { "Missing prompt resource: $name" }
                .bufferedReader()
                .use { it.readText() }
                .trim()
    }
}
