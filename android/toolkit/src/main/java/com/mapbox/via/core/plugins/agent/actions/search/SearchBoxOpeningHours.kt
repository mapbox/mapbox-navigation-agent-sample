package com.mapbox.via.core.plugins.agent.actions.search

import com.mapbox.via.kit.place.OpeningHoursLookup
import com.mapbox.via.kit.place.filterOpenNow
import com.mapbox.via.kit.place.lookupOrEmpty
import com.mapbox.via.kit.search.SearchBoxResult
import kotlin.time.Instant

internal fun SearchBoxResult.isPoi(): Boolean = featureType == null || featureType.equals("poi", ignoreCase = true)

internal suspend fun List<SearchBoxResult>.withOpeningHours(lookup: OpeningHoursLookup): List<SearchBoxResult> {
    val hours = lookup.lookupOrEmpty(filter { it.isPoi() }.map { it.mapboxId })
    if (hours.isEmpty()) return this
    return map { result -> hours[result.mapboxId]?.let { result.copy(openingHours = it) } ?: result }
}

internal fun List<SearchBoxResult>.filterOpenNowIf(openNow: Boolean, now: Instant): List<SearchBoxResult> =
    if (openNow) filterOpenNow(now) { it.openingHours } else this

/** Hours guidance every place search tool description includes. */
const val HOURS_TOOL_GUIDANCE: String =
    "Each place result carries hours: open or closed right now, or unknown when no opening " +
        "hours are on record. Never say a place with unknown hours is open or closed; say its hours " +
        "are unknown."

const val OPEN_NOW_TOOL_GUIDANCE: String =
    HOURS_TOOL_GUIDANCE + " Pass openNow: true whenever the user needs the place open now (\"open now\", " +
        "\"still open\", \"right now\"): closed places are dropped from the results and the map, and " +
        "places with unknown hours are kept only when none is known to be open."
