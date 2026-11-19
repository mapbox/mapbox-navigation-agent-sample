package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.conversation.ConversationContinuityWriter
import com.mapbox.via.kit.geomemories.PlaceSearchKind
import com.mapbox.via.kit.geomemories.SearchMemoryRecorder
import com.mapbox.via.core.plugins.agent.actions.search.HOURS_TOOL_GUIDANCE
import com.mapbox.via.kit.actions.OpenNowFilter
import com.mapbox.via.kit.actions.SearchAlongRouteArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.context.updateLastToolSearchResults
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.search.canonicalizeCategoryId
import com.mapbox.via.kit.place.OpeningHoursLookup
import com.mapbox.via.kit.place.filterOpenNow
import com.mapbox.via.kit.place.openNowFetchLimit
import com.mapbox.via.kit.place.withOpeningHours
import com.mapbox.via.kit.mapsession.MapMarkerEventSource
import com.mapbox.via.ui.mapsession.MapPin
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.SearchCaption
import com.mapbox.via.ui.mapsession.SearchScope
import com.mapbox.via.ui.mapsession.pinIdFor
import com.mapbox.via.ui.navsdk.NavSdkService
import kotlinx.serialization.KSerializer
import kotlin.time.Clock

/**
 * Auto-pins its own results with [service.showSearchPins], same as [SearchPlacesAction]/
 * [SearchCategoryAction] — both are in the assistant prompt's mandatory-re-pin exemption list.
 */
@Suppress("LongParameterList")
class SearchAlongRouteAction(
    private val service: NavSdkService,
    private val contextHolder: ViaContextHolder,
    private val mapSessionStore: MapSessionStore,
    private val mapMarkerEventSource: MapMarkerEventSource = MapMarkerEventSource.Empty,
    private val searchMemories: SearchMemoryRecorder = SearchMemoryRecorder.NoOp,
    private val continuity: ConversationContinuityWriter = ConversationContinuityWriter.NoOp,
    private val openingHours: OpeningHoursLookup = OpeningHoursLookup.None,
    private val clock: Clock = Clock.System,
) : ViaAction<SearchAlongRouteArgs> {
    override val id: String = ID
    override val name: String = "Search along route"
    override val argsSerializer: KSerializer<SearchAlongRouteArgs> = SearchAlongRouteArgs.serializer()
    override val description: String = """
        Search for a place or category along the currently active route — not around the
        user's current location. Use this whenever the request scopes the search to the
        route itself ("along the way", "on the way", "along the route", "before I get
        there"), instead of nav_sdk_search_places/nav_sdk_search_category, which only
        search near a point. Fails if no route is active. Returns the 3 best matches by
        default, and stores them in the MapGPT search context for set-destination,
        navigate-to, or add-waypoint — unless called with showOnMap: false, which keeps a
        call internal: off the map and out of the search context, the same contract
        nav_sdk_search_places' showOnMap: false has. Only pass showOnMap: false when you
        are not presenting these results to the user.
        Pass exactly one of: query (a named place, e.g. "Ferry Building") or categoryId plus
        displayName (a category intent, e.g. categoryId florist, displayName "florist") —
        apply the same category-vs-place split nav_sdk_search_category/nav_sdk_search_places
        use, never query for a category: a category word rarely matches any place's name, so
        querying it as free text returns no results even when real matches sit on the route.
        $HOURS_TOOL_GUIDANCE For a category search, openNow defaults to true, because a stop along the
        active route is a stop made on this trip: closed places are dropped from the results and the
        map, and places with unknown hours are kept only when none is known to be open. For a query,
        open places are preferred by default, but when every place found is closed they are still
        returned, so a place the user named is found even when it is closed. When every place in the
        results is closed and the user did not name it, tell the user no open option was found
        instead of recommending a closed one. Pass openNow: false only when the user asks about
        places regardless of whether they are open now.
    """.trimIndent()

    override val examples: List<String> = listOf(
        "Find a coffee shop along my route.",
        "Is there a gas station on the way?",
        "Any florists along the way?",
    )

    override suspend fun execute(args: SearchAlongRouteArgs): Result<String> {
        val limit = args.limit.coerceAtLeast(1)
        val requestedCategoryId = args.categoryId
        val query = args.query
        val filter = args.openNowFilter
        val fetchLimit = openNowFetchLimit(limit, filter != OpenNowFilter.Off)
        // Falls back to categoryId, not "", when the model omits displayName in category mode —
        // an empty label would reply "No places found for ''".
        val label = args.displayName?.takeIf { it.isNotBlank() } ?: requestedCategoryId ?: query.orEmpty()
        val (categoryId, searchResult) = when {
            !requestedCategoryId.isNullOrBlank() ->
                searchCategoryWithCanonicalFallback(requestedCategoryId, label, fetchLimit)
            !query.isNullOrBlank() -> null to service.searchAlongRoute(query, fetchLimit)
            else -> return Result.failure(
                IllegalArgumentException("Pass either query or categoryId/displayName."),
            )
        }
        return searchResult.mapSuspending { all ->
            val now = clock.now()
            val withHours = all.withOpeningHours(openingHours)
            val open = if (filter == OpenNowFilter.Off) withHours else withHours.filterOpenNow(now) { it.openingHours }
            val kept = if (filter == OpenNowFilter.PreferOpen) open.ifEmpty { withHours } else open
            val results = kept
                .take(limit)
                .map { result ->
                    val name = result.name.ifBlank { label }
                    result.copy(name = name, coordinate = result.coordinate.copy(name = name))
                }
                // Two pins sharing an id collide in MapSessionStore and crash PoiCardCarousel.
                .distinctBy { pinIdFor(it) }
            continuity.recordSearch(results, args.showOnMap)
            val pins = results.map { MapPin(id = pinIdFor(it), point = it.coordinate, result = it) }
            if (args.showOnMap) {
                mapSessionStore.setPins(pins, caption = SearchCaption(label, SearchScope.AlongRoute))
                service.showSearchPins(pins, includeActiveRoute = true)
                if (pins.isNotEmpty()) {
                    mapMarkerEventSource.publish(pins.map { it.point }, pins.map { it.result?.mapboxId })
                }
                results.updateSearchContext(contextHolder)
                searchMemories.searched(PlaceSearchKind.AlongRoute, label, categoryId, results)
            } else {
                // Keeps the enriched pins resolvable later (see MapSession.candidatePins),
                // mirroring SearchCategoryAction's showOnMap: false path.
                mapSessionStore.cacheCandidates(pins)
                contextHolder.updateLastToolSearchResults(results)
            }
            when {
                results.isNotEmpty() ->
                    results.mapIndexed { i, result -> "${i + 1}. ${result.toResultLine(now)}" }
                        .joinToString("\n", prefix = "Found ${results.size} place(s) along the route:\n")
                all.isNotEmpty() -> noneOpenMessage(all.size, "place(s) for '$label' along the route")
                else -> "No places found for '$label' along the route."
            }
        }.also { actionLog.d { "$it" } }
    }

    /**
     * Mirrors SearchCategoryAction's searchWithCanonicalFallback: trusts [categoryId] first, and
     * only consults [canonicalizeCategoryId] — retrying once — when that search comes back
     * *successfully* empty. A failure (e.g. no active route) is returned as-is and never retried
     * — [Result.getOrNull] is also null for a failure, so checking emptiness alone would retry
     * (and, on Android, re-emit the same "No active route…" user message) on every failure too.
     */
    private suspend fun searchCategoryWithCanonicalFallback(
        categoryId: String,
        displayName: String,
        limit: Int,
    ): Pair<String, Result<List<ViaSearchResult>>> {
        val primary = service.searchCategoryAlongRoute(categoryId, displayName, limit)
        val corrected = if (primary.isSuccess && primary.getOrNull().isNullOrEmpty()) {
            canonicalizeCategoryId(categoryId, displayName)
        } else {
            categoryId
        }
        if (corrected == categoryId) return categoryId to primary
        actionLog.d {
            "categoryId '$categoryId' returned no along-route results for '$displayName'; retrying as '$corrected'"
        }
        return corrected to service.searchCategoryAlongRoute(corrected, displayName, limit)
    }

    companion object {
        const val ID: String = "nav_sdk_search_along_route"
    }
}
