package com.mapbox.via.ui.screens.guidance

import com.mapbox.via.ui.mapsession.MapSessionStore

/** Distance within which the trip is treated as arrived (matches typical nav-SDK thresholds). */
internal const val GUIDANCE_ARRIVAL_DISTANCE_THRESHOLD_METERS = 40.0

internal fun markArrivedWhenNearDestination(
    mapSessionStore: MapSessionStore,
    remainingDistanceMeters: Double,
) {
    mapSessionStore.clearArrivalRelatchGuardIfEnRoute(
        remainingDistanceMeters = remainingDistanceMeters,
        thresholdMeters = GUIDANCE_ARRIVAL_DISTANCE_THRESHOLD_METERS,
    )
    if (mapSessionStore.isArrivalRelatchBlocked()) return
    if (remainingDistanceMeters <= GUIDANCE_ARRIVAL_DISTANCE_THRESHOLD_METERS) {
        mapSessionStore.markArrivedAtDestination()
    }
}
