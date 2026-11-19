// Process-lifetime sync; there is no caller-provided dispatcher to inject.
@file:Suppress("InjectDispatcher")

package com.mapbox.via.ui.navsdk

import com.mapbox.via.kit.logging.ViaLog
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.context.ViaNavigationState
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.ui.mapsession.MapSession
import com.mapbox.via.ui.mapsession.MapSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Projects [MapSessionStore] guidance state into [ViaContextHolder] for the lifetime of the host
 * process, so a conversation started mid-trip still sees the trip. Registered on Android; iOS
 * reads the native route through its own `IosNavigationContextSync`. Idle while the Dash IPC
 * backend is selected, where [com.mapbox.via.ui.ipc.DashContextSync] owns the navigation context.
 */
class NavigationContextSync(
    mapSessionStore: MapSessionStore,
    backendSelection: NavigationBackendSelection,
    private val holder: ViaContextHolder,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    init {
        combine(backendSelection.backend, mapSessionStore.state) { backend, session ->
            session.toNavigationState().takeIf { backend == NavigationBackend.NavSdk }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { state ->
                ViaLog.i { "Navigation context: state updated -> ${state::class.simpleName}" }
                holder.update { context ->
                    context.copy(navigation = context.navigation.copy(state = state))
                }
            }
            .launchIn(scope)
    }

    fun shutdown() {
        scope.cancel()
    }
}

/** Destination last: the context renderer reads it off the tail. */
internal fun MapSession.toNavigationState(): ViaNavigationState {
    val destination = listOfNotNull(destination?.toWaypoint(destinationMapboxId))
    if (!activeGuidance) return ViaNavigationState.FreeDrive(waypoints = destination)
    val stops = addedStops.map { it.point.toWaypoint(it.mapboxId) }
    return ViaNavigationState.ActiveGuidance(waypoints = stops + destination)
}

/**
 * For a host whose native route is the record of which stops made it on: [liveStops] in route
 * order, destination last. Falls back to the session's destination while that route is unknown.
 */
internal fun MapSession.toNativeRouteState(liveStops: List<ViaPoint>): ViaNavigationState {
    if (!activeGuidance) return ViaNavigationState.FreeDrive()
    val stops = liveStops.ifEmpty { listOfNotNull(destination) }
    return ViaNavigationState.ActiveGuidance(waypoints = stops.map { it.toWaypoint(mapboxId = null) })
}

private fun ViaPoint.toWaypoint(mapboxId: String?): ViaSearchResult = ViaSearchResult(
    id = mapboxId ?: "navsdk-$latitude-$longitude",
    coordinate = this,
    address = null,
    name = name ?: "$latitude, $longitude",
    type = "place",
    categoryIds = null,
    description = null,
    distanceMeters = null,
    isOffline = false,
    isTransient = true,
    mapboxId = mapboxId,
)
