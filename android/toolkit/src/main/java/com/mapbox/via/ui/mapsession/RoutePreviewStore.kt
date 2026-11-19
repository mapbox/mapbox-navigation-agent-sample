package com.mapbox.via.ui.mapsession

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaRouteCandidate
import com.mapbox.via.kit.models.ViaRouteProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Route preview state, separate from [MapSessionStore] so the preview flow stays independently
 * testable and the session store is untouched.
 *
 * There is no Failed state on purpose: a failed route fetch never enters preview at all — the
 * caller surfaces the error through the existing error snackbar and the store stays [Idle].
 */
sealed interface RoutePreviewState {
    data object Idle : RoutePreviewState
    data class Loading(
        val destination: ViaPoint,
        val profile: ViaRouteProfile,
        // Set by an explicit navigate command; false for browsing.
        val autoStartNavigation: Boolean = false,
        val waypoints: List<ViaPoint> = emptyList(),
        val avoidHighways: Boolean = false,
    ) : RoutePreviewState
    data class Ready(
        val destination: ViaPoint,
        val profile: ViaRouteProfile,
        val candidates: List<ViaRouteCandidate>,
        val selectedId: String,
        val autoStartNavigation: Boolean = false,
        val waypoints: List<ViaPoint> = emptyList(),
        val avoidHighways: Boolean = false,
    ) : RoutePreviewState
}

class RoutePreviewStore {
    private val _state = MutableStateFlow<RoutePreviewState>(RoutePreviewState.Idle)
    val state: StateFlow<RoutePreviewState> = _state.asStateFlow()

    /** Read before starting navigation: guidance becoming active resets the store to [RoutePreviewState.Idle]. */
    val previewedWaypoints: List<ViaPoint>
        get() = (_state.value as? RoutePreviewState.Ready)?.waypoints.orEmpty()

    fun beginLoading(
        destination: ViaPoint,
        profile: ViaRouteProfile,
        autoStartNavigation: Boolean = false,
        waypoints: List<ViaPoint> = emptyList(),
        avoidHighways: Boolean = false,
    ) {
        _state.value = RoutePreviewState.Loading(destination, profile, autoStartNavigation, waypoints, avoidHighways)
    }

    /**
     * An empty list means the router returned nothing usable, so there is no preview to show.
     * Destination, profile, and waypoints carry forward from the current [Loading] state — [setCandidates] is
     * only ever called as the result of a fetch that [beginLoading] started.
     */
    fun setCandidates(candidates: List<ViaRouteCandidate>) {
        val loading = _state.value as? RoutePreviewState.Loading ?: return
        val first = candidates.firstOrNull()
        _state.value = if (first == null) {
            RoutePreviewState.Idle
        } else {
            RoutePreviewState.Ready(
                destination = loading.destination,
                profile = loading.profile,
                candidates = candidates,
                selectedId = first.id,
                autoStartNavigation = loading.autoStartNavigation,
                waypoints = loading.waypoints,
                avoidHighways = loading.avoidHighways,
            )
        }
    }

    /** Puts a previously-seen [RoutePreviewState.Ready] back after a failed re-fetch. */
    fun restore(state: RoutePreviewState.Ready) {
        _state.value = state
    }

    fun selectCandidate(id: String) {
        _state.update { current ->
            if (current !is RoutePreviewState.Ready) return@update current
            if (current.candidates.none { it.id == id }) return@update current
            current.copy(selectedId = id)
        }
    }

    fun reset() {
        _state.value = RoutePreviewState.Idle
    }
}
