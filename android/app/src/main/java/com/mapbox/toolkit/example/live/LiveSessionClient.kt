package com.mapbox.toolkit.example.live

import android.content.Context
import android.util.Log
import com.mapbox.toolkit.example.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.webrtc.AndroidWebRtc
import io.ktor.client.webrtc.WebRtc
import io.ktor.client.webrtc.WebRtcClient
import io.ktor.client.webrtc.WebRtcDataChannel
import io.ktor.client.webrtc.WebRtcMedia
import io.ktor.client.webrtc.WebRtcPeerConnection
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ExperimentalKtorApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Minimal GPT-Live client over WebRTC.
 *
 * Development only: the app exchanges the SDP offer with OpenAI directly using a standard API key.
 */
@OptIn(ExperimentalKtorApi::class)
class LiveSessionClient(context: Context, private val apiKey: String) : AutoCloseable {
    private val audioRouter = LiveAudioRouter(context)

    private val httpClient =
        HttpClient(Android) {
            expectSuccess = true
            install(ContentNegotiation) { json(LiveJson) }
        }

    private val rtcClient =
        WebRtcClient(AndroidWebRtc) {
            this.context = context.applicationContext
            defaultConnectionConfig = {
                iceServers = listOf(WebRtc.IceServer(urls = listOf(STUN_SERVER)))
                bundlePolicy = WebRtc.BundlePolicy.MAX_BUNDLE
            }
        }

    private var peerConnection: WebRtcPeerConnection? = null
    private var microphone: WebRtcMedia.AudioTrack? = null
    private var dataChannel: WebRtcDataChannel? = null

    /** Starts a session and returns its server events as JSON. The flow completes once the data channel closes. */
    suspend fun connect(config: LiveSessionConfig): Flow<String> {
        check(apiKey.isNotBlank()) { "OpenAI API key is missing" }

        val connection = rtcClient.createPeerConnection().also { peerConnection = it }
        val track = rtcClient.createAudioTrack { echoCancellation = true }.also { microphone = it }
        connection.addTrack(track)
        val channel = connection.createDataChannel(DATA_CHANNEL_LABEL).also { dataChannel = it }
        connection.setLocalDescription(connection.createOffer())
        connection.awaitIceCandidates()
        val offer = checkNotNull(connection.localDescription) { "Missing local description" }

        val response: LiveSessionResponse = httpClient
            .post(SESSIONS_URL) {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(LiveSessionRequest(session = config, transport = LiveTransport(sdp = offer.sdp)))
            }.body()

        audioRouter.start()
        connection.setRemoteDescription(
            WebRtc.SessionDescription(
                WebRtc.SessionDescriptionType.ANSWER,
                response.transport.sdp
            )
        )

        return flow {
            while (true) {
                val message = try {
                    channel.receive()
                } catch (_: WebRtc.DataChannelClosedException) {
                    break
                }
                if (message is WebRtc.DataChannel.Message.Text) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "<- ${message.data}")
                    emit(message.data)
                }
            }
        }
    }

    suspend fun send(event: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "-> $event")
        dataChannel?.send(event)
    }

    /** Asks the server to end the session; keep collecting events until [LiveServerEvent.SessionClosed]. */
    suspend fun requestClose() = send(LiveClientEvents.sessionClose())

    /** Tears down media and the connection. */
    fun disconnect() {
        dataChannel?.close()
        microphone?.close()
        peerConnection?.close()
        dataChannel = null
        microphone = null
        peerConnection = null
        audioRouter.stop()
    }

    override fun close() {
        disconnect()
        rtcClient.close()
        httpClient.close()
    }

    /**
     * Waits until gathering completes or no new candidate arrives for [ICE_SETTLE_TIMEOUT]. Host and STUN candidates
     * arrive within milliseconds, while libwebrtc may take 10+ seconds to report completion.
     */
    @OptIn(FlowPreview::class)
    private suspend fun WebRtcPeerConnection.awaitIceCandidates() {
        withTimeoutOrNull(ICE_GATHERING_TIMEOUT) {
            merge(
                iceGatheringState.filter { it == WebRtc.IceGatheringState.COMPLETE },
                iceCandidates.debounce(ICE_SETTLE_TIMEOUT)
            ).first()
        }
    }

    private companion object {
        const val TAG = "LiveSessionClient"
        const val SESSIONS_URL = "https://api.openai.com/v1/live/sessions"
        const val DATA_CHANNEL_LABEL = "oai-events"
        const val STUN_SERVER = "stun:stun.l.google.com:19302"
        val ICE_SETTLE_TIMEOUT = 500.milliseconds
        val ICE_GATHERING_TIMEOUT = 5.seconds
    }
}
