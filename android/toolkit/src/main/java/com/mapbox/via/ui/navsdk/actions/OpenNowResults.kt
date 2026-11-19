package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.place.OpeningHoursLookup
import com.mapbox.via.kit.place.filterOpenNow
import com.mapbox.via.kit.place.withOpeningHours
import com.mapbox.via.kit.models.ViaSearchResult
import kotlin.time.Instant

internal suspend fun List<ViaSearchResult>.withHoursKeepingOpenIf(
    openNow: Boolean,
    lookup: OpeningHoursLookup,
    now: Instant,
): List<ViaSearchResult> {
    val withHours = withOpeningHours(lookup)
    return if (openNow) withHours.filterOpenNow(now) { it.openingHours } else withHours
}

internal fun noneOpenMessage(found: Int, label: String): String =
    "Found $found $label, but none is open now. Nothing was pinned."
