package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.conversation.ConversationContinuityWriter
import com.mapbox.via.kit.geomemories.PlaceSearchKind
import com.mapbox.via.kit.geomemories.SearchMemoryRecorder
import com.mapbox.via.core.plugins.agent.actions.search.OPEN_NOW_TOOL_GUIDANCE
import com.mapbox.via.kit.actions.POPULARITY_CATEGORY_POOL
import com.mapbox.via.kit.models.PlaceLookup
import com.mapbox.via.kit.actions.SearchCategoryArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.search.canonicalizeCategoryId
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.context.updateLastToolSearchResults
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.actions.fetchLimit
import com.mapbox.via.kit.actions.rankedBy
import com.mapbox.via.kit.place.OpeningHoursLookup
import com.mapbox.via.kit.place.openNowFetchLimit
import com.mapbox.via.kit.search.timeSearchApi
import com.mapbox.via.kit.mapsession.MapMarkerEventSource
import com.mapbox.via.kit.search.MatrixRepository
import com.mapbox.via.kit.search.rankByTravelTime
import com.mapbox.via.ui.mapsession.MapPin
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.SearchCaption
import com.mapbox.via.ui.mapsession.formatMapPinSubtitle
import com.mapbox.via.ui.mapsession.pinIdFor
import com.mapbox.via.ui.navsdk.NavSdkService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlin.time.Clock
import kotlin.time.Instant

@Suppress("LongParameterList")
class SearchCategoryAction(
    private val service: NavSdkService,
    private val contextHolder: ViaContextHolder,
    private val mapSessionStore: MapSessionStore,
    private val mapMarkerEventSource: MapMarkerEventSource = MapMarkerEventSource.Empty,
    private val matrixRepository: MatrixRepository = MatrixRepository.NoOp,
    private val searchMemories: SearchMemoryRecorder = SearchMemoryRecorder.NoOp,
    private val continuity: ConversationContinuityWriter = ConversationContinuityWriter.NoOp,
    private val openingHours: OpeningHoursLookup = OpeningHoursLookup.None,
    private val clock: Clock = Clock.System,
) : ViaAction<SearchCategoryArgs> {
    override val id: String = ID
    override val name: String = "Search by category"
    // Enables concurrent multi-category batches (DirectorTools.supportsConcurrentBatch); the
    // on-map commit sequence below is serialized separately via mapCommitMutex.
    override val supportsConcurrentExecution: Boolean = true
    private val mapCommitMutex = Mutex()
    override val description: String = """
        Search for places by category. Returns a list of nearby locations in that category,
        and stores them in the MapGPT search context for set-destination, navigate-to, or
        add-waypoint — unless called with showOnMap: false (see below), which keeps a call
        internal: off the map, out of the search context, and out of nav_sdk_get_search_results,
        the same contract nav_sdk_search_places' showOnMap: false already has.
        Returns the 10 closest matches by default so more options are pinned than you need to
        mention out loud — speak or print at most three; set limit only when the user asks for a
        specific number of places, and that many are returned in full. On the default browse
        (limit unset), a result can be tagged with more than one category, so at most two
        results sharing the same name are ever returned, keeping any one business from crowding
        out the variety a browse is meant to surface.
        For a popular or most popular request, pass rankBy: POPULARITY. The tool then searches
        the widest set Search Box returns, not just the closest places, and returns the limit
        most popular, already sorted most popular first; recommend them in that order.
        Use only for descriptive category intents (gas, coffee, farmers market, hardware store,
        chinese food, parking, etc.), including a
        product or service need phrased without a venue word — infer the shop or service that
        sells it (a bike helmet or bike repair is bicycle_shop, toys is toy_store, running shoes
        is shoe_store, oil paints is arts_and_craft_store). Coffee is the coffee category, which
        covers cafes as well as coffee shops; coffee_shop alone leaves the cafes out.
        Multiword venue types are still categories. Do not use this tool for named cities or
        distinctive business, chain, or place names; use place/geocode search instead.
        Pass proximity (a lat/lng point) to anchor the search. For a nearby or closest
        search around the user, pass their current coordinates as proximity — leaving
        it unset does not reliably rank by distance. With proximity set, results are ranked by
        driving distance from that point, not straight-line distance, so a namesake across a
        bay or bridge won't outrank a shorter drive. When the user scopes the category to a
        named area they are not already in ('coffee shops in Palo Alto'), pass the area's
        city, town, or neighborhood name as inArea. This tool resolves inArea internally
        and searches from those coordinates, even when the area is nearby; without the anchor
        the search is not aimed at that area. When the user is already inside that area, pass
        their current coordinates as proximity rather than the area centroid. If Current
        context already has User location, copy those
        coordinates as proximity — do not AREA-resolve the current city or neighborhood
        with nav_sdk_search_places.
        categoryId must be a canonical Mapbox Search Box category ID; always choose the
        most specific ID matching the intent — a cuisine or subtype must never degrade
        to a generic bucket like food or restaurant.
        Do not invent a categoryId. If it is not already written in the cuisine list or
        the common examples below, call category_list_tool for the full list of Mapbox
        category canonical IDs, then pass the matching ID from that list as categoryId
        in the same turn. When the ID is already one of the common examples below, call
        this tool directly and do not call category_list_tool.
        Cuisine IDs follow <cuisine>_restaurant, where cuisine is one of: african, american,
        asian, barbeque, brazilian, breakfast, brunch, buffet, burger, carribean, chinese,
        creole, cuban, diner, english, filipino, fish_and_chips, french, german, gluten_free,
        greek, hawaiian, indian, indonesian, italian, japanese, korean, korean_barbeque,
        latin_american, mediterranean, mexican, middle_eastern, noodle, persian, peruvian,
        pizza, portuguese, ramen, seafood, spanish, sushi, tapas, thai, turkish, vietnamese.
        Other common IDs: food, restaurant, fast_food, coffee, cafe, coffee_shop, bar, bakery,
        dessert_shop, grocery, supermarket, market, convenience_store, gas_station, charging_station,
        parking_lot, hotel, motel, hospital, pharmacy, bank, atm, airport, airport_terminal,
        railway_station, bus_station, auto_repair, locksmith, car_wash, car_dealership, shopping_mall,
        shopping, park, museum, tourist_attraction, rest_area, police_station, fire_station,
        library, fitness_center, hardware_store, liquor_store, pet_store, bicycle_shop,
        toy_store, shoe_store, arts_and_craft_store, hobby_shop, clothing_store,
        department_store, electronics_shop, book_store, jewelry_store, gift_shop, florist.
        When you need results from more than one category for the same answer (for example
        restaurants and bars for date-night options), search each category with
        showOnMap: false, then pin every result you plan to recommend together in a single
        nav_sdk_add_map_markers call, copying each result's latitude, longitude, name, and category
        from its numbered line — one nav_sdk_search_category call per
        category still auto-pins and renders its own map card by default, so calling it that way
        for two categories in the same turn shows two separate, redundant map cards instead of one.
        $OPEN_NOW_TOOL_GUIDANCE
    """.trimIndent()
    override val argsSerializer: KSerializer<SearchCategoryArgs> = SearchCategoryArgs.serializer()
    override val examples: List<String> = listOf(
        "Find gas stations nearby.",
        "Show me coffee shops.",
        "Show me farmers markets around here.",
        "Where can I buy a kids' bicycle helmet?",
    )

    override suspend fun execute(args: SearchCategoryArgs): Result<String> {
        val limit = args.limit.coerceAtLeast(1)
        val userLocation = contextHolder.state.value.user?.location
        val searchProximity = resolveProximity(args, userLocation).getOrElse { return Result.failure(it) }
        val areaScoped = !args.inArea.isNullOrBlank()
        val rankingOrigin = if (areaScoped) searchProximity else userLocation ?: args.proximity
        val fetchLimit = openNowFetchLimit(args.rankBy.fetchLimit(limit, POPULARITY_CATEGORY_POOL), args.openNow)
        val (categoryId, searchResult) = searchWithCanonicalFallback(args, searchProximity, fetchLimit)
        // The provider silently caps its raw response around 10 regardless of what's requested
        // (same ceiling as SearchPlacesAction's Dash.forward() comment below), so a chain-dense
        // area can return fewer than `limit` distinct results after the per-name cap below.
        return searchResult.mapSuspending { all ->
            actionLog.d {
                "Raw $categoryId candidates (${all.size}): " +
                    all.joinToString { it.toCategoryDebugString() }
            }
            // The destination-exclusion fix lives in each platform's searchCategory instead of
            // here: this action only sees whatever the service already capped to limit, so
            // filtering afterwards could only shrink the reply, never backfill a replacement —
            // and, on iOS, the service's own getSearchResults() cache is written before this
            // action ever runs, so filtering here would leave the excluded destination reachable
            // again through nav_sdk_get_search_results.
            // A result can carry categories beyond the one requested (a Starbucks matching
            // "bakery"), so cap same-name repeats on the default browse only — an explicit count
            // ("show me 6 bakeries") must be delivered in full, not silently thinned for variety.
            val pool = all.rankedBy(args.rankBy)
            val candidates = if (args.limit == SearchCategoryArgs.DEFAULT_LIMIT) {
                pool.capPerName(MAX_RESULTS_PER_NAME)
            } else {
                pool
            }
            // Reorders by driving distance instead of straight-line distance. The service returned
            // at most `fetchLimit`, so a distance search only reorders those survivors -- it cannot
            // recover a same-side match the cap excluded for a namesake closer by air -- while a
            // popularity search's wider pool lets ranking decide who survives take(limit).
            val now = clock.now()
            val results = candidates
                .rankByTravelTime(rankingOrigin, userLocation != null && !areaScoped, matrixRepository)
                .rankedBy(args.rankBy)
                .withHoursKeepingOpenIf(args.openNow, openingHours, now)
                .take(limit)
            continuity.recordSearch(results, args.showOnMap)
            val pins = results
                .map { r ->
                    // The result's own category is the server-resolved Maki icon name;
                    // the tool's categoryId is only a fallback for providers that don't
                    // set one (it's agent vocabulary, not guaranteed to match an icon).
                    val enriched = r.copy(
                        coordinate = r.coordinate.copy(
                            name = r.name,
                            category = r.coordinate.category ?: categoryId,
                        ),
                    )
                    MapPin(
                        id = pinIdFor(enriched),
                        point = enriched.coordinate,
                        // A readable category label, matching what marker-widget pins carry: this
                        // field is rendered as secondary text under the place name, so an address
                        // would read as the place's title on the pin chips.
                        subtitle = formatMapPinSubtitle(enriched.coordinate.category),
                        result = enriched,
                    )
                }
                // Two results sharing a place id would collide in MapSessionStore and crash
                // PoiCardCarousel's keyed pager; the tool text still reports every result.
                .distinctBy { it.id }
            // showOnMap=false keeps the search fully internal, mirroring SearchPlacesAction: no
            // pins, no map, no search context — the caller pins what it keeps itself.
            // cacheCandidates keeps the enriched pins resolvable later (see MapSession.candidatePins).
            if (args.showOnMap) {
                // Serializes concurrent calls' commits so store/markers/context stay in sync.
                mapCommitMutex.withLock {
                    // Store before render: showSearchPins suspends through its framing animation,
                    // and pins are tappable the moment they draw — writing the store afterwards
                    // left a window where a tap resolved against the previous search's pins and
                    // was swallowed.
                    mapSessionStore.setPins(pins, caption = searchCaption(args))
                    // The service cached the whole popularity pool; nav_sdk_get_search_results must list what's pinned.
                    if (fetchLimit > limit) service.rememberSearchResults(results)
                    service.showSearchPins(pins)
                    if (pins.isNotEmpty()) {
                        mapMarkerEventSource.publish(pins.map { it.point }, pins.map { it.result?.mapboxId })
                    }
                    results.updateSearchContext(contextHolder)
                }
                searchMemories.searched(PlaceSearchKind.Category, args.displayName, categoryId, results)
            } else {
                mapSessionStore.cacheCandidates(pins)
                contextHolder.updateLastToolSearchResults(results)
            }
            // Disclosed so a wrong correction is recoverable within the turn: silently swapping in
            // a substitute category and reporting its results as if they were what was asked for
            // would leave the model with no evidence anything was corrected, even if the result
            // list obviously doesn't match displayName (see searchWithCanonicalFallback's kdoc).
            formatResults(args, categoryId, results, candidates.size, now)
        }.also { actionLog.d { "$it" } }
    }

    private fun searchCaption(args: SearchCategoryArgs): SearchCaption = SearchCaption.around(
        label = args.displayName,
        inArea = args.inArea,
        proximity = args.proximity,
        destination = mapSessionStore.state.value.destination,
    )

    private suspend fun resolveProximity(args: SearchCategoryArgs, userLocation: ViaPoint?): Result<ViaPoint?> {
        val area = args.inArea?.trim()?.takeIf(String::isNotEmpty)
            ?: return Result.success(args.proximity ?: userLocation)
        return timeSearchApi {
            service.searchPlaces(
                query = area,
                proximity = args.proximity ?: userLocation,
                limit = AREA_RESOLVE_LIMIT,
                lookup = PlaceLookup.AREA,
                cache = false,
            )
        }.mapCatching { results ->
            results.firstOrNull()?.coordinate
                ?: error("No area found for '$area'.")
        }
    }

    private fun formatResults(
        args: SearchCategoryArgs,
        categoryId: String,
        results: List<ViaSearchResult>,
        candidateCount: Int,
        now: Instant,
    ): String {
        val correctionNote = if (categoryId != args.categoryId) {
            "Searched canonical category '$categoryId' for '${args.displayName}'.\n"
        } else {
            ""
        }
        val scope = args.inArea?.trim()?.takeIf(String::isNotEmpty)
        val location = scope?.let { "in $it" } ?: "nearby"
        val body = when {
            results.isNotEmpty() ->
                results.mapIndexed { i, result -> "${i + 1}. ${result.toResultLine(categoryId, now)}" }
                    .joinToString("\n", prefix = "Found ${results.size} ${args.displayName} $location:\n")
            args.openNow && candidateCount > 0 -> noneOpenMessage(candidateCount, args.displayName)
            else -> "No ${args.displayName} found $location."
        }
        return correctionNote + body
    }

    /**
     * Searches [args.categoryId] first, trusting the model's own guess even when it isn't in
     * [MAPBOX_CATEGORY_CATALOG]'s snapshot — the snapshot is a dated capture with no refresh path,
     * so an id the model got right but that postdates the snapshot must not be rewritten just
     * because it looks unfamiliar to us. Only when that search comes back empty (or fails outright
     * — a genuinely invalid id and "no results near here" aren't distinguishable from the result
     * alone) is [canonicalizeCategoryId] consulted and, if it disagrees with [SearchCategoryArgs.categoryId],
     * retried once. [canonicalizeCategoryId] itself already returns an unchanged id when nothing
     * clears its confidence threshold, so this never retries with the same id twice.
     */
    private suspend fun searchWithCanonicalFallback(
        args: SearchCategoryArgs,
        proximity: ViaPoint?,
        limit: Int,
    ): Pair<String, Result<List<ViaSearchResult>>> {
        val primary = timeSearchApi { service.searchCategory(args.categoryId, args.displayName, proximity, limit) }
        val corrected = if (primary.getOrNull().isNullOrEmpty()) {
            canonicalizeCategoryId(args.categoryId, args.displayName)
        } else {
            args.categoryId
        }
        if (corrected == args.categoryId) return args.categoryId to primary
        actionLog.d {
            "categoryId '${args.categoryId}' returned no results for '${args.displayName}'; retrying as '$corrected'"
        }
        return corrected to timeSearchApi { service.searchCategory(corrected, args.displayName, proximity, limit) }
    }

    companion object {
        const val ID: String = "nav_sdk_search_category"
        private const val AREA_RESOLVE_LIMIT = 1
        private const val MAX_RESULTS_PER_NAME = 2
    }
}

// Preserves the provider's rank order (nearest/most relevant first) instead of grouping, which
// would move every repeat of a name next to its first occurrence and scramble that ordering.
private fun List<ViaSearchResult>.capPerName(maxPerName: Int): List<ViaSearchResult> {
    val seenCounts = mutableMapOf<String, Int>()
    return filter { result ->
        val count = seenCounts.getOrElse(result.name) { 0 }
        (count < maxPerName).also { keep -> if (keep) seenCounts[result.name] = count + 1 }
    }
}

private fun ViaSearchResult.toCategoryDebugString(): String =
    "$name [icon=${coordinate.category ?: "?"}, categoryIds=${categoryIds.orEmpty()}, mapboxId=${mapboxId ?: "?"}]"

/**
 * One model-facing result line: spoken details (street, drive time) when the SDK
 * provides them, plus the coordinates and category the model needs for map-marker
 * and set-destination tool calls (never spoken, per the assistant prompt). [fallbackCategoryId]
 * mirrors the same "server category, else the tool's categoryId" fallback [execute] applies to
 * pins, so a batched nav_sdk_add_map_markers call after a showOnMap: false search always has a
 * category to copy into each marker — without it, the maki icon would silently fall back to a
 * generic pin whenever the provider didn't set one.
 *
 * [ViaSearchResult.rating]/[ViaSearchResult.popularity], appended by [metadataSuffix], carry the
 * same trailing fields as `SearchContext.kt`'s `toResultLine`.
 */
private fun ViaSearchResult.toResultLine(fallbackCategoryId: String, now: Instant): String {
    val details = spokenDetails()
    val label = if (details.isEmpty()) name else "$name — $details"
    val category = coordinate.category ?: fallbackCategoryId
    return "$label (lat=${coordinate.latitude}, lng=${coordinate.longitude}, category=$category${metadataSuffix(now)})"
}
