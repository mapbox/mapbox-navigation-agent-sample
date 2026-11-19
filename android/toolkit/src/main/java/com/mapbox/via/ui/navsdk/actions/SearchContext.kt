package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.place.hoursSummary
import com.mapbox.via.kit.actions.AddWaypointArgs
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.context.updateSearchResults
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.models.city
import com.mapbox.via.kit.models.roundedUpEtaMinutes
import com.mapbox.via.ui.navsdk.distanceLabel
import com.mapbox.via.ui.navsdk.samePositionAs
import kotlin.time.Instant

internal const val NO_DESTINATION_MESSAGE: String =
    "No destination available in search context. " +
        "Pass destination coordinates or search for a place first."

internal fun stopWord(count: Int): String = if (count == 1) "stop" else "stops"

/**
 * Null when the provider gave no travel time, which is most of the time — only Android category
 * results carry one, from the Matrix API. Rounded up so a drive is never spoken short.
 */
internal fun driveTimeLabel(etaSeconds: Long?): String? =
    etaSeconds?.let { "${roundedUpEtaMinutes(it)} min drive" }

/**
 * "street, city, drive time, road distance" for a model-facing result line, falling back to the
 * provider's straight-line distance only when there's no Matrix drive time. Without street or
 * city, a house-number fragment (e.g. two "74 Collins" hits) left the model nothing to tell local
 * matches apart by except list position.
 */
internal fun ViaSearchResult.spokenDetails(): String {
    val driveTime = driveTimeLabel(etaSeconds)
    val distance = routedDistanceMeters?.let { "${distanceLabel(it)} by road" }
        ?: distanceMeters?.takeIf { driveTime == null }?.let { "${distanceLabel(it)} away" }
    return listOfNotNull(address?.street, address?.city(), driveTime, distance).joinToString(", ")
}

/**
 * Trailing `rating`/`popularity` for a model-facing result line, empty when the provider supplied
 * neither — which is most of the time. Opaque place ids stay on the client; the 1-based index in
 * the prefixed list is the handle for nav_sdk_* and place_details_batch_tool resultIndices.
 * Shared by this file's [toResultLine] and `SearchCategoryAction`'s so both result-line grammars
 * carry the same trailing fields.
 */
internal fun ViaSearchResult.metadataSuffix(hoursAt: Instant? = null): String = buildString {
    rating?.let { append(", rating=$it") }
    popularity?.let { append(", popularity=$it") }
    hoursAt?.let { append(", hours=${hoursSummary(openingHours, it)}") }
}

/** One model-facing result line: name/type plus street and drive time/distance when known. */
internal fun ViaSearchResult.toResultLine(hoursAt: Instant? = null): String {
    val details = spokenDetails()
    val label = if (details.isEmpty()) "$name — ${type.lowercase()}" else "$name — ${type.lowercase()}, $details"
    return "$label (lat=${coordinate.latitude}, lng=${coordinate.longitude}${metadataSuffix(hoursAt)})"
}

internal suspend fun <T, R> Result<T>.mapSuspending(transform: suspend (T) -> R): Result<R> {
    val value = getOrElse { return Result.failure(it) }
    return Result.success(transform(value))
}

/** Publishes already-rich search results (address, ETA) without flattening them to points. */
internal fun List<ViaSearchResult>.updateSearchContext(
    contextHolder: ViaContextHolder,
): List<ViaSearchResult> = also { results ->
    contextHolder.updateSearchResults(results)
    actionLog.d { "Updated search context with ${results.size} result(s)" }
}

/**
 * Resolves waypoints for [AddWaypointAction], retaining Search Box identity for indexed results.
 * Explicit coordinates come first, followed by [AddWaypointArgs.resultIndices] in their own
 * order. An out-of-range index is dropped rather than failing the whole call.
 */
internal fun AddWaypointArgs.resolveWaypoints(contextHolder: ViaContextHolder): List<ResolvedWaypoint> {
    val results = contextHolder.state.value.search.indexedResults
    val fromIndices = resultIndices.mapNotNull { index ->
        results.getOrNull(index - 1)?.let { ResolvedWaypoint(it.coordinate, it.mapboxId, fromSearchResult = true) }
    }
    return waypoints.map { ResolvedWaypoint(it) } + fromIndices
}

internal data class ResolvedWaypoint(
    val point: ViaPoint,
    val mapboxId: String? = null,
    val fromSearchResult: Boolean = false,
)

internal fun ViaPoint.toSearchResult(
    query: String = "Destination",
    categoryIds: List<String>? = null,
): ViaSearchResult = ViaSearchResult(
    id = "navsdk-$latitude-$longitude",
    coordinate = this,
    address = null,
    name = name ?: query,
    type = "place",
    categoryIds = categoryIds,
    description = query,
    distanceMeters = null,
    isOffline = false,
    isTransient = true,
)
