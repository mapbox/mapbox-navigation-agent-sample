package com.mapbox.via.core.plugins.director.openai.live.models.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `session.started`: the session is ready for audio and commands. */
@Serializable
data class SessionStartedEvent(
    val session: Session = Session(),
    @SerialName("client_event_id") val clientEventId: String? = null,
    val type: String = TYPE,
) {
    @Serializable
    data class Session(
        val id: String? = null,
        val model: String? = null,
        /** Unix seconds at which the server will end the session (`expired`). */
        @SerialName("expires_at") val expiresAt: Long? = null,
    )

    fun toLog(): String = "$type id=${session.id} model=${session.model} expiresAt=${session.expiresAt}"

    companion object {
        const val TYPE: String = "session.started"
    }
}

@Serializable
data class SessionUpdatedEvent(
    @SerialName("client_event_id") val clientEventId: String? = null,
    val type: String = TYPE,
) {
    companion object {
        const val TYPE: String = "session.updated"
    }
}

/** Acknowledgement for a context append; arrives after estimated context injection, not after speech. */
@Serializable
data class ContextAppendedEvent(
    val type: String,
    @SerialName("client_event_id") val clientEventId: String? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
) {
    fun toLog(): String = "$type client_event_id=$clientEventId"

    companion object {
        const val INSTRUCTIONS: String = "session.instructions.appended"
        const val THINKING: String = "session.thinking.appended"
        const val COMMENTARY: String = "session.commentary.appended"
    }
}

/** Base64 PCM in the session's audio format. There is no output-audio-done event. */
@Serializable
data class OutputAudioDeltaEvent(
    val delta: String,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    val type: String = TYPE,
) {
    companion object {
        const val TYPE: String = "session.output_audio.delta"
    }
}

/**
 * A transcript fragment for one speaker. [startMs]/[endMs] are an interval on
 * the session timeline, not turn boundaries: user and assistant fragments can
 * overlap and a fragment is not a complete utterance.
 */
@Serializable
data class TranscriptDeltaEvent(
    val type: String,
    val delta: String,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
) {
    val isUser: Boolean get() = type == INPUT

    companion object {
        const val INPUT: String = "session.input_transcript.delta"
        const val OUTPUT: String = "session.output_transcript.delta"
    }
}

/**
 * The live model asked the backend for help. With Responses delegation
 * [Delegation.target] is `responses` and the backend's streaming events follow
 * inside [ResponseEventEnvelope]s carrying the same [Delegation.id].
 */
@Serializable
data class DelegationCreatedEvent(
    val delegation: Delegation,
    @SerialName("offset_ms") val offsetMs: Long? = null,
    val type: String = TYPE,
) {
    @Serializable
    data class Delegation(
        val id: String,
        val target: String? = null,
        @SerialName("response_id") val responseId: String? = null,
    )

    fun toLog(): String = "$type id=${delegation.id} target=${delegation.target} offsetMs=$offsetMs"

    companion object {
        const val TYPE: String = "session.delegation.created"
    }
}

/** Envelope for a Responses streaming event produced by the delegated backend. */
@Serializable
data class ResponseEventEnvelope(
    val event: JsonObject,
    @SerialName("delegation_id") val delegationId: String? = null,
    val type: String = TYPE,
) {
    companion object {
        const val TYPE: String = "response.event"
    }
}

/** Cumulative voice seconds; snapshots, not increments. */
@Serializable
data class UsageUpdatedEvent(
    val usage: Usage = Usage(),
    @SerialName("context_window") val contextWindow: ContextWindow? = null,
    val type: String = TYPE,
) {
    @Serializable
    data class Usage(val seconds: Double? = null)

    @Serializable
    data class ContextWindow(@SerialName("usage_ratio") val usageRatio: Double? = null)

    fun toLog(): String = "$type seconds=${usage.seconds} contextUsage=${contextWindow?.usageRatio}"

    companion object {
        const val TYPE: String = "session.usage.updated"
    }
}

/** Final event of a session; confirms finalisation and carries the final voice usage. */
@Serializable
data class SessionClosedEvent(
    val reason: String? = null,
    val usage: UsageUpdatedEvent.Usage? = null,
    @SerialName("client_event_id") val clientEventId: String? = null,
    val type: String = TYPE,
) {
    fun toLog(): String = "$type reason=$reason seconds=${usage?.seconds}"

    companion object {
        const val TYPE: String = "session.closed"
        const val REASON_CLOSE_REQUESTED: String = "close_requested"
        const val REASON_EXPIRED: String = "expired"
        const val REASON_CONTENT: String = "content"
        const val REASON_CONNECTION_LOST: String = "connection_lost"
    }
}

/** A rejected command or session problem. [Error.clientEventId] names the command when present. */
@Serializable
data class ErrorEvent(
    val error: Error = Error(),
    val type: String = TYPE,
) {
    @Serializable
    data class Error(
        val type: String? = null,
        val code: String? = null,
        val message: String = "unknown live error",
        val param: String? = null,
        @SerialName("client_event_id") val clientEventId: String? = null,
    )

    fun toLog(): String = buildString {
        append(type).append(": ").append(error.message)
        error.code?.let { append(" (").append(it).append(')') }
        error.param?.let { append(" param=").append(it) }
        error.clientEventId?.let { append(" client_event_id=").append(it) }
    }

    companion object {
        const val TYPE: String = "error"
    }
}

@Serializable
data class InfoEvent(
    val code: String? = null,
    val message: String? = null,
    val type: String = TYPE,
) {
    fun toLog(): String = "$type $code: $message"

    companion object {
        const val TYPE: String = "info"
    }
}
