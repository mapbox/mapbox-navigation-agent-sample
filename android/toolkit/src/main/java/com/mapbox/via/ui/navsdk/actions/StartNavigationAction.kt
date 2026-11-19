package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.RoutePreviewState
import com.mapbox.via.ui.mapsession.RoutePreviewStore
import com.mapbox.via.ui.navsdk.NavSdkService

class StartNavigationAction(
    private val service: NavSdkService,
    private val mapSessionStore: MapSessionStore,
    private val routePreviewStore: RoutePreviewStore,
) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_start_navigation"
    override val name: String = "Start navigation"
    override val description: String =
        "Start turn-by-turn navigation to the current destination. Prefer nav_sdk_navigate_to " +
            "when the destination is already in search context and the user just confirmed."
    override val examples: List<String> = listOf("Start navigation.", "Let's go.", "Begin the route.")

    override suspend fun execute(): Result<String> {
        val waypoints = routePreviewStore.previewedWaypoints
        return startGuidance().map {
            mapSessionStore.startActiveGuidance()
            mapSessionStore.recordAddedStops(waypoints)
            // startActiveGuidance keeps browse pins during an already-active trip; starting
            // navigation after a new destination search must still drop them from the session.
            service.clearSearchPins().onSuccess { mapSessionStore.setPins(emptyList()) }
            "Navigation started."
        }
            .also { actionLog.d { "$it" } }
    }

    private suspend fun startGuidance(): Result<Unit> {
        val session = mapSessionStore.state.value
        val pendingDestination = session.destination.takeIf { session.routePreviewActive && !session.activeGuidance }
        val previewClosed = routePreviewStore.state.value is RoutePreviewState.Idle
        return if (pendingDestination != null && previewClosed) {
            service.navigateTo(pendingDestination, session.addedStops.map { it.point }).map { Unit }
        } else {
            service.startNavigation()
        }
    }
}
