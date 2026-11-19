package com.mapbox.via.ui.navsdk

import kotlin.time.Instant

/** Live remaining time and distance while guidance is active. */
data class GuidanceProgress(
    val durationRemainingSeconds: Double,
    val distanceRemainingMeters: Double,
    /** When [durationRemainingSeconds] was observed. Remaining time is measured from here. */
    val observedAt: Instant,
)
