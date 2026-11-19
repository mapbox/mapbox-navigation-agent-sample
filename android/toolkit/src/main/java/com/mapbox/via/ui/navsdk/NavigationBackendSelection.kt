package com.mapbox.via.ui.navsdk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers which navigation backend the user picked. Defaults to [NavigationBackend.NavSdk]
 * (direct in-app SDK control). Switching to [NavigationBackend.Dash] routes actions through
 * the Dash IPC connection instead.
 *
 * State is in-memory only and resets when the app restarts.
 */
class NavigationBackendSelection {
    private val _backend = MutableStateFlow(NavigationBackend.NavSdk)
    val backend: StateFlow<NavigationBackend> = _backend.asStateFlow()

    fun select(backend: NavigationBackend) {
        _backend.value = backend
    }
}
