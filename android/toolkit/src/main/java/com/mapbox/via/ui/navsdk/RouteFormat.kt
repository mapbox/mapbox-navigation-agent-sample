package com.mapbox.via.ui.navsdk

import kotlin.math.roundToInt

private const val SECONDS_PER_MINUTE = 60.0
private const val METERS_PER_MILE = 1609.344
private const val FEET_PER_METER = 3.28084
private const val FEET_ROUNDING = 50
private const val MIN_MILES_FOR_MILE_DISPLAY = 0.1
private const val ONE_DECIMAL_BELOW_MILES = 10.0

// Below this gap to the fastest route, rounding to whole minutes can make two routes look
// identical or off-by-one; the alternative reads as "Similar ETA" instead.
private const val SIMILAR_ETA_THRESHOLD_SECONDS = 60.0

/** "20 min · 11 mi" — the same shape Dash's destination card used. */
internal fun routeSummaryLabel(durationSeconds: Double, distanceMeters: Double): String {
    val minutes = (durationSeconds / SECONDS_PER_MINUTE).roundToInt()
    return "$minutes min · ${distanceLabel(distanceMeters)}"
}

/** Like [routeSummaryLabel]; a non-fastest candidate within [SIMILAR_ETA_THRESHOLD_SECONDS] reads "Similar ETA". */
internal fun routeSummaryLabel(
    durationSeconds: Double,
    distanceMeters: Double,
    fastestDurationSeconds: Double,
    isFastest: Boolean,
): String {
    // isFastest is passed rather than inferred from the gap: minByOrNull returns the first
    // minimum, so a second candidate exactly tied with the fastest would otherwise get gap == 0
    // and fall through to its own (identical) minute label.
    val gap = durationSeconds - fastestDurationSeconds
    val durationLabel = if (!isFastest && gap < SIMILAR_ETA_THRESHOLD_SECONDS) {
        "Similar ETA"
    } else {
        "${(durationSeconds / SECONDS_PER_MINUTE).roundToInt()} min"
    }
    return "$durationLabel · ${distanceLabel(distanceMeters)}"
}

// Below MIN_MILES_FOR_MILE_DISPLAY, mile output collapses to "0.0 mi", so short distances (a
// maneuver, a nearby POI) render in feet instead. Only integers are interpolated into the output,
// since Kotlin/JS's Double.toString() drops a trailing ".0" that JVM/Native keep.
internal fun distanceLabel(distanceMeters: Double): String {
    val miles = distanceMeters / METERS_PER_MILE
    return when {
        miles < MIN_MILES_FOR_MILE_DISPLAY -> {
            val feet = ((distanceMeters * FEET_PER_METER / FEET_ROUNDING).roundToInt()) * FEET_ROUNDING
            "$feet ft"
        }
        miles < ONE_DECIMAL_BELOW_MILES -> {
            val tenths = (miles * ONE_DECIMAL_BELOW_MILES).roundToInt()
            val whole = tenths / 10
            val fraction = tenths % 10
            if (fraction == 0) "$whole mi" else "$whole.$fraction mi"
        }
        else -> "${miles.roundToInt()} mi"
    }
}
