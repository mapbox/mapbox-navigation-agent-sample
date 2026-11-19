package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.geomemories.GeoMemoryRecorder
import com.mapbox.via.kit.geomemories.NavigationStartedMemory
import com.mapbox.via.kit.geomemories.NavigationStoppedMemory
import com.mapbox.via.kit.geomemories.RouteRequestKind
import com.mapbox.via.kit.geomemories.RouteRequestMemory
import com.mapbox.via.kit.geomemories.StopsAddedMemory
import com.mapbox.via.kit.geomemories.TripPlace
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaRouteProfile
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.models.isNullIsland
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Records the user's trips, whether the agent or the UI drove them, one new memory per call. */
class TripMemoryRecorder(
    private val recorder: GeoMemoryRecorder,
    private val contextHolder: ViaContextHolder,
) {

    fun routeRequested(
        kind: RouteRequestKind,
        destination: ViaSearchResult,
        waypoints: List<ViaPoint>,
        profile: ViaRouteProfile,
    ) {
        val origin = userLocation()
        recorder.record { now ->
            RouteRequestMemory(
                id = newMemoryId(),
                kind = kind,
                origin = origin,
                destination = TripPlace(destination.name, destination.coordinate.bare(), destination.mapboxId),
                waypoints = waypoints.map { it.toTripPlace(mapboxId = null) },
                profile = profile,
                startedAt = now,
            )
        }
    }

    internal fun stopsAdded(stops: List<ResolvedWaypoint>) {
        val location = userLocation()
        recorder.record { now ->
            StopsAddedMemory(
                id = newMemoryId(),
                location = location,
                stops = stops.map { it.point.toTripPlace(it.mapboxId) },
                startedAt = now,
            )
        }
    }

    fun guidanceChanged(active: Boolean) {
        val location = userLocation()
        recorder.record { now ->
            if (active) {
                NavigationStartedMemory(newMemoryId(), location, now)
            } else {
                NavigationStoppedMemory(newMemoryId(), location, now)
            }
        }
    }

    private fun userLocation(): ViaPoint? =
        contextHolder.state.value.user?.location?.takeUnless { it.isNullIsland() }?.bare()
}

private fun ViaPoint.toTripPlace(mapboxId: String?) = TripPlace(name?.takeIf { it.isNotBlank() }, bare(), mapboxId)

private fun ViaPoint.bare() = ViaPoint(latitude, longitude)

@OptIn(ExperimentalUuidApi::class)
private fun newMemoryId(): String = Uuid.random().toString()
