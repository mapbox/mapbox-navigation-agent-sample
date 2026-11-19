package com.mapbox.toolkit.example.live

import com.mapbox.via.core.plugins.director.openai.live.models.client.ResponsesDelegation
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val LiveJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
}

/** Body of `POST /v1/live/sessions`. */
@Serializable internal data class LiveSessionRequest(val session: LiveSessionConfig, val transport: LiveTransport)

/** Startup-only session configuration. Audio format is omitted because WebRTC negotiates it through SDP. */
@Serializable
data class LiveSessionConfig(
  val model: String = "gpt-live-1",
  val instructions: String? = null,
  val audio: LiveAudioConfig? = null,
  val delegation: ResponsesDelegation,
)

@Serializable data class LiveAudioConfig(val output: LiveAudioOutput)

@Serializable data class LiveAudioOutput(val voice: String)

@Serializable internal data class LiveTransport(val type: String = "webrtc", val sdp: String)

@Serializable internal data class LiveSessionResponse(val session: LiveSessionInfo, val transport: LiveTransport)

@Serializable internal data class LiveSessionInfo(val id: String)

/** Server events received on the `oai-events` data channel. Only the events used by the app are modeled. */
sealed interface LiveServerEvent {
  data class SessionStarted(val sessionId: String?) : LiveServerEvent

  data class SessionClosed(val reason: String?) : LiveServerEvent

  data class InputTranscriptDelta(val delta: String) : LiveServerEvent

  data class OutputTranscriptDelta(val delta: String) : LiveServerEvent

  data class Error(val message: String, val clientEventId: String?) : LiveServerEvent

  data class Other(val type: String) : LiveServerEvent

  companion object {
    fun parse(text: String): LiveServerEvent {
      val json = LiveJson.parseToJsonElement(text).jsonObject
      return when (val type = json.string("type")) {
        "session.started" -> SessionStarted(json.obj("session")?.string("id"))
        "session.closed" -> SessionClosed(json.string("reason"))
        "session.input_transcript.delta" -> InputTranscriptDelta(json.string("delta").orEmpty())
        "session.output_transcript.delta" -> OutputTranscriptDelta(json.string("delta").orEmpty())
        "error" -> json.obj("error").let { Error(it?.string("message") ?: text, it?.string("client_event_id")) }
        else -> Other(type.orEmpty())
      }
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
  }
}

/** Client events sent on the `oai-events` data channel. */
internal object LiveClientEvents {
  fun sessionClose(): String = event("session.close") {}

  /** Every client event gets an `event_id` so that acks and errors can be matched through `client_event_id`. */
  private fun event(type: String, fields: JsonObjectBuilder.() -> Unit): String =
    buildJsonObject {
        put("type", type)
        put("event_id", "evt_${UUID.randomUUID()}")
        fields()
      }
      .toString()
}
