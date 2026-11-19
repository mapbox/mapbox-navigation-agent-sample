package com.mapbox.toolkit.example.ui.main

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mapbox.toolkit.example.BuildConfig
import com.mapbox.toolkit.example.R
import com.mapbox.toolkit.example.ToolkitApplication
import com.mapbox.toolkit.example.live.LiveAudioConfig
import com.mapbox.toolkit.example.live.LiveAudioOutput
import com.mapbox.toolkit.example.live.LiveServerEvent
import com.mapbox.toolkit.example.live.LiveSessionClient
import com.mapbox.toolkit.example.live.LiveSessionConfig
import com.mapbox.toolkit.example.settings.OpenAiApiKeyStore
import com.mapbox.toolkit.example.tools.DeviceTimeAction
import com.mapbox.toolkit.example.tools.MapboxCategoryListAction
import com.mapbox.toolkit.example.tools.MapboxFeedbackAction
import com.mapbox.toolkit.example.tools.MapboxMcpClient
import com.mapbox.toolkit.live.LiveDirector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class MainViewModel(
    private val appContext: Context,
    private val apiKeyStore: OpenAiApiKeyStore,
    private val director: LiveDirector,
) : ViewModel() {
    private val sessionConfig =
        LiveSessionConfig(
            instructions = director.liveInstructions,
            audio = LiveAudioConfig(LiveAudioOutput(voice = "marin")),
            delegation = director.delegation,
        )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _actionCalls = Channel<String>(Channel.UNLIMITED)

    /** The name of each action the assistant calls, so the screen can show that a reply is based on it. */
    val actionCalls: Flow<String> = _actionCalls.receiveAsFlow()

    private var sessionJob: Job? = null
    private var client: LiveSessionClient? = null

    fun start() {
        if (sessionJob?.isActive == true) return
        _uiState.value = MainUiState(status = LiveStatus.Connecting)
        sessionJob =
            viewModelScope.launch {
                try {
                    val apiKey = resolvedOpenAiApiKey()
                    if (apiKey.isBlank()) {
                        _uiState.update {
                            it.copy(status = LiveStatus.Idle, error = appContext.getString(R.string.error_openai_key_missing))
                        }
                        return@launch
                    }
                    val liveClient = LiveSessionClient(appContext, apiKey).also { client = it }
                    coroutineScope {
                        val backend =
                            director.session(
                                this,
                                send = liveClient::send,
                                onEndConversation = ::stop,
                                onActionCall = { _actionCalls.trySend(it) },
                            )
                        try {
                            liveClient.connect(sessionConfig).collect { frame ->
                                onEvent(LiveServerEvent.parse(frame))
                                backend.onServerEvent(frame)
                            }
                        } finally {
                            backend.close()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Live session failed", e)
                    _uiState.update { it.copy(error = e.message ?: e.toString()) }
                } finally {
                    client?.disconnect()
                    client = null
                    _uiState.update { it.copy(status = LiveStatus.Idle) }
                }
            }
    }

    private suspend fun resolvedOpenAiApiKey(): String =
        BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() } ?: apiKeyStore.awaitPersistedKey()

    fun onPermissionsDenied() {
        _uiState.update { it.copy(error = "Microphone and precise location permissions are required.") }
    }

    fun onErrorShown() {
        _uiState.update { it.copy(error = null) }
    }

    fun stop() {
        if (_uiState.value.status != LiveStatus.Connected) {
            sessionJob?.cancel()
            return
        }

        _uiState.update { it.copy(status = LiveStatus.Closing) }
        val job = sessionJob
        viewModelScope.launch {
            client?.requestClose()
            // Fall back to tearing the session down if the server never acknowledges the close.
            delay(CLOSE_TIMEOUT)
            job?.cancel()
        }
    }

    private fun onEvent(event: LiveServerEvent) {
        when (event) {
            is LiveServerEvent.SessionStarted -> _uiState.update { it.copy(status = LiveStatus.Connected) }
            is LiveServerEvent.Error -> _uiState.update { it.copy(error = event.message) }.also { Log.w(TAG, "Server error: $event") }
            is LiveServerEvent.SessionClosed -> sessionJob?.cancel()
            is LiveServerEvent.InputTranscriptDelta, is LiveServerEvent.OutputTranscriptDelta, is LiveServerEvent.Other -> Unit
        }
    }

    override fun onCleared() {
        client?.close()
        client = null
    }

    companion object {
        private const val TAG = "MainViewModel"
        private val CLOSE_TIMEOUT = 5.seconds

        /**
         * Creates the view model with the app's live session client and the assistant's actions: the navigation
         * actions, plus the app's own device time action and two Mapbox MCP server tools: the category list that
         * `nav_sdk_search_category` points to, and feedback.
         */
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val application = this[APPLICATION_KEY] as ToolkitApplication
                    val navigationActions = application.navigationActions
                    val mcp = MapboxMcpClient(application::peekMapboxAccessToken)
                    val appActions = listOf(DeviceTimeAction(), MapboxCategoryListAction(mcp), MapboxFeedbackAction(mcp))
                    val director = navigationActions.liveDirector(navigationActions.actions + appActions)
                    MainViewModel(application, application.openAiApiKeyStore, director).apply {
                        addCloseable(mcp)
                    }
                }
            }
    }
}
