package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.place.HomeWorkPlacesRepository
import com.mapbox.via.kit.search.mentionsPlaceName
import com.mapbox.via.core.plugins.agent.prompts.LOCATION_REFRESH_METERS
import com.mapbox.via.kit.actions.DestinationArgs
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.context.selectSearchResult
import com.mapbox.via.kit.context.updateSearchResults
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.models.withRoutingHintsFrom
import com.mapbox.via.ui.mapsession.MapSession
import com.mapbox.via.ui.navsdk.metersBetween
import com.mapbox.via.ui.navsdk.samePositionAs

private const val SAME_RESULT_METERS = 5.0

/**
 * Resolves the destination for [SetDestinationAction]/[NavigateToAction]:
 * [DestinationArgs.savedPlace] reads the stored home/work place and fails when
 * none is saved; otherwise an explicit [DestinationArgs.destination] wins and
 * replaces the search context (keeping it the single source of truth for the
 * selected place); otherwise [DestinationArgs.resultIndex] (1-based) picks a
 * specific search-context result — an out-of-range index is a resolution
 * failure, never a silent fall through to a different result; otherwise the
 * selected result from a prior call, then the first search-context result.
 * Explicit coordinates are the only way destinations reach the device from
 * search backends whose results only exist as agent tool text (e.g. the
 * Navigation Agent MCP tool). A [resultIndex] pick records the selection without
 * replacing the search results, so later numbered references still use the displayed list,
 * while a no-arg call resolves to the place that was actually selected. A named destination
 * within [LOCATION_REFRESH_METERS] of the user, the most the location the model was shown can lag,
 * resolves to the known place whose name contains that name or that sits at those exact
 * coordinates, and fails when no search result or pin does.
 */
internal suspend fun DestinationArgs.resolveDestination(
    contextHolder: ViaContextHolder,
    homeWorkPlaces: HomeWorkPlacesRepository,
    session: MapSession,
): Result<ViaSearchResult> {
    val saved = savedPlace
    val explicit = destination
    val placeName = name ?: explicit?.name
    val userLocation = contextHolder.state.value.user?.location
    return when {
        saved != null -> homeWorkPlaces.resolveSavedDestination(saved, contextHolder)
        explicit == null -> resolveFromSearchContext(contextHolder)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException(NO_DESTINATION_MESSAGE))
        placeName != null && userLocation?.samePositionAs(explicit, LOCATION_REFRESH_METERS) == true ->
            resolveNamedPlaceAtUserLocation(placeName, explicit, contextHolder, session)
        else -> Result.success(resolveExplicitDestination(explicit, contextHolder, session))
    }
}

private fun DestinationArgs.resolveFromSearchContext(contextHolder: ViaContextHolder): ViaSearchResult? {
    val search = contextHolder.state.value.search
    val index = resultIndex
    return if (index != null) {
        search.indexedResults.getOrNull(index - 1)?.also(contextHolder::selectSearchResult)
    } else {
        search.selectedResult ?: search.results.firstOrNull()
    }
}

private fun resolveNamedPlaceAtUserLocation(
    placeName: String,
    explicit: ViaPoint,
    contextHolder: ViaContextHolder,
    session: MapSession,
): Result<ViaSearchResult> {
    val search = contextHolder.state.value.search
    val pinned = (session.pins + session.candidatePins).mapNotNull { it.result }
    val known = (search.indexedResults + search.results + pinned).distinctBy { it.id }
    val match = known
        .filter { mentionsPlaceName(it.name, placeName) }
        .minByOrNull { metersBetween(it.coordinate, explicit) }
        ?: known.firstOrNull { it.coordinate.samePositionAs(explicit, SAME_RESULT_METERS) }
        ?: return Result.failure(IllegalStateException(notARecentResultMessage(placeName)))
    contextHolder.selectSearchResult(match)
    return Result.success(match)
}

internal fun notARecentResultMessage(placeName: String): String =
    "$placeName is not in Recent search results, and these coordinates are the user's current " +
        "location, not that place. Pass resultIndex for the place the user picked from Recent search " +
        "results, or search for $placeName first."

private fun DestinationArgs.resolveExplicitDestination(
    explicit: ViaPoint,
    contextHolder: ViaContextHolder,
    session: MapSession,
): ViaSearchResult {
    // Explicit coordinates arrive without routing hints; a search-context twin at the same spot has them.
    val twin = contextHolder.state.value.search.results.firstOrNull { it.coordinate.samePositionAs(explicit) }
    val point = explicit.copy(name = name ?: explicit.name)
        .let { p -> twin?.let { p.withRoutingHintsFrom(it.coordinate) } ?: p }
    val result = point.toSearchResult(query = "Destination")
        .let { bare -> twin?.let { bare.copy(address = it.address, mapboxId = it.mapboxId) } ?: bare }
        .enrichFromKnownPlaces(session)
    contextHolder.updateSearchResults(listOf(result))
    return result
}
