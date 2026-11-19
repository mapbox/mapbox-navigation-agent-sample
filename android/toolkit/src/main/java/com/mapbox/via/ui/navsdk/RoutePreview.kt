package com.mapbox.via.ui.navsdk

import com.mapbox.via.kit.models.ViaPoint

/** Destination and travel estimates for a previewed or active route. */
data class RoutePreview(
    val destination: ViaPoint,
    val durationSeconds: Double,
    val distanceMeters: Double,
)
