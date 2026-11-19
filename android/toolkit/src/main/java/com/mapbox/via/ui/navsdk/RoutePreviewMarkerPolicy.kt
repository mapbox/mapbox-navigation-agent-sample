package com.mapbox.via.ui.navsdk

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.ui.mapsession.RoutePreviewState

/**
 * While a route is previewed only the previewed place stays on the map. The filter is applied when
 * rendering, so the marker lists stay intact and dismissing the preview brings everything back.
 */
object RoutePreviewMarkerPolicy {
    fun previewedDestination(state: RoutePreviewState): ViaPoint? = when (state) {
        is RoutePreviewState.Idle -> null
        is RoutePreviewState.Loading -> state.destination
        is RoutePreviewState.Ready -> state.destination
    }

    /**
     * Same [DestinationMarkerPolicy.isNear] gate that suppresses the destination marker, so the
     * previewed place always has exactly one marker.
     */
    fun <T> visibleMarkers(
        markers: List<T>,
        previewedDestination: ViaPoint?,
        pointOf: (T) -> ViaPoint,
    ): List<T> {
        val destination = previewedDestination ?: return markers
        return markers.filter { marker ->
            val point = pointOf(marker)
            DestinationMarkerPolicy.isNear(destination.latitude, destination.longitude, point.latitude, point.longitude)
        }
    }
}
