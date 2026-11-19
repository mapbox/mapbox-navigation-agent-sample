package com.mapbox.via.core.plugins.director.openai.live.models.client

import com.mapbox.via.core.plugins.director.openai.responses.models.ResponsesInputItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * First frame on a Live WebSocket. Everything in [session] except the
 * Responses delegation settings is immutable after `session.started`.
 */
@Serializable
data class SessionStartEvent(
    val session: LiveSessionConfig,
    @SerialName("event_id") val eventId: String? = null,
    val type: String = TYPE,
) {
    companion object {
        const val TYPE: String = "session.start"
    }
}

/** Updates `delegation.responses` on a running session; every other startup field is immutable. */
@Serializable
data class SessionUpdateEvent(
    val session: Session,
    @SerialName("event_id") val eventId: String? = null,
    val type: String = TYPE,
) {
    @Serializable
    data class Session(val delegation: ResponsesDelegation)

    companion object {
        const val TYPE: String = "session.update"
    }
}

/** Base64 raw PCM in the session's `audio.format`. Never acknowledged. */
@Serializable
data class InputAudioAppendEvent(
    val audio: String,
    val type: String = TYPE,
) {
    companion object {
        const val TYPE: String = "session.input_audio.append"
    }
}

/**
 * Plain-string context for the live model. `delegation_id` must be present even
 * when null, so it is carried as a [JsonElement] rather than an omitted optional.
 *
 * - `session.instructions.append`: trusted behavioural guidance; may interrupt speech.
 * - `session.thinking.append`: quiet context the model may use later.
 * - `session.commentary.append`: information the model should say aloud.
 *
 * [content] is limited to 500 tokens per append.
 */
@Serializable
data class ContextAppendEvent(
    val type: String,
    val content: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("delegation_id") val delegationId: JsonElement = JsonNull,
) {
    companion object {
        const val INSTRUCTIONS: String = "session.instructions.append"
        const val THINKING: String = "session.thinking.append"
        const val COMMENTARY: String = "session.commentary.append"

        fun instructions(content: String, eventId: String, delegationId: String? = null): ContextAppendEvent =
            ContextAppendEvent(INSTRUCTIONS, content, eventId, delegationId.toJson())

        fun thinking(content: String, eventId: String, delegationId: String? = null): ContextAppendEvent =
            ContextAppendEvent(THINKING, content, eventId, delegationId.toJson())

        fun commentary(content: String, eventId: String, delegationId: String? = null): ContextAppendEvent =
            ContextAppendEvent(COMMENTARY, content, eventId, delegationId.toJson())

        private fun String?.toJson(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
    }
}

/** Queues a Responses input item for the delegated backend (function results, typed messages). */
@Serializable
data class ResponseItemCreateEvent(
    val item: ResponsesInputItem,
    @SerialName("event_id") val eventId: String? = null,
    val type: String = TYPE,
) {
    companion object {
        const val TYPE: String = "response.item.create"
    }
}

/**
 * Starts or continues delegated backend work with the session's configured
 * backend. Takes no body: model, tools and instructions come from
 * `delegation.responses`.
 */
@Serializable
data class ResponseCreateEvent(
    @SerialName("event_id") val eventId: String? = null,
    val type: String = TYPE,
) {
    companion object {
        const val TYPE: String = "response.create"
    }
}

@Serializable
data class SessionCloseEvent(
    @SerialName("event_id") val eventId: String? = null,
    val type: String = TYPE,
) {
    companion object {
        const val TYPE: String = "session.close"
    }
}

/**
 * The `session` object of [SessionStartEvent]. [audio] is required over a
 * WebSocket (one PCM format for both directions); [input] seeds text history
 * for a replacement session.
 */
@Serializable
data class LiveSessionConfig(
    val model: String,
    val instructions: String? = null,
    val audio: Audio? = null,
    val delegation: ResponsesDelegation? = null,
    val input: List<ResponsesInputItem>? = null,
) {
    @Serializable
    data class Audio(
        val format: Format,
        val output: Output? = null,
    ) {
        /** Live PCM supports 16000 or 24000 Hz. */
        @Serializable
        data class Format(
            val rate: Int,
            val type: String = "audio/pcm",
        )

        @Serializable
        data class Output(val voice: String)
    }
}

/**
 * `delegation: { type: "responses", responses: {...} }`. All [responses] fields
 * are optional on update; [Responses.model] is required at startup.
 */
@Serializable
data class ResponsesDelegation(
    val responses: Responses,
    val type: String = "responses",
) {
    @Serializable
    data class Responses(
        val model: String? = null,
        val instructions: String? = null,
        val tools: List<BackendTool>? = null,
        @SerialName("tool_choice") val toolChoice: String? = null,
        @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
        val reasoning: Reasoning? = null,
    )

    @Serializable
    data class Reasoning(val effort: String)
}

/**
 * A tool the Responses backend may call. `delegation.responses.tools` accepts
 * exactly two shapes, [Function] (executed by Via, results returned with
 * `response.item.create`) and [WebSearch] (hosted by OpenAI). [Mcp] is the
 * Responses hosted MCP tool: rejected in the delegation config, but accepted
 * inside an `additional_tools` input item and in a direct Responses request.
 */
@Serializable
sealed interface BackendTool {
    @Serializable
    @SerialName("function")
    data class Function(
        val name: String,
        val description: String,
        val parameters: JsonElement,
        val strict: Boolean = false,
    ) : BackendTool

    @Serializable
    @SerialName("web_search")
    data object WebSearch : BackendTool

    /** A remote MCP server OpenAI connects to and runs tools on, server-side. */
    @Serializable
    @SerialName("mcp")
    data class Mcp(
        @SerialName("server_label") val serverLabel: String,
        @SerialName("server_url") val serverUrl: String,
        val headers: Map<String, String>? = null,
        @SerialName("require_approval") val requireApproval: String = "never",
        @SerialName("allowed_tools") val allowedTools: List<String>? = null,
    ) : BackendTool
}
