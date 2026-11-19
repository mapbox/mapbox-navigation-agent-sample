package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.conversation.ConversationContinuityWriter
import com.mapbox.via.kit.geomemories.PlaceSearchKind
import com.mapbox.via.kit.geomemories.SearchMemoryRecorder
import com.mapbox.via.core.plugins.agent.actions.search.OPEN_NOW_TOOL_GUIDANCE
import com.mapbox.via.kit.models.GeoBbox
import com.mapbox.via.kit.actions.MAX_SEARCH_BOX_FORWARD_LIMIT
import com.mapbox.via.kit.models.PlaceLookup
import com.mapbox.via.kit.actions.SearchPlacesArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.context.updateLastToolSearchResults
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.models.city
import com.mapbox.via.kit.actions.fetchLimit
import com.mapbox.via.kit.actions.rankedBy
import com.mapbox.via.kit.place.OpeningHoursLookup
import com.mapbox.via.kit.place.openNowFetchLimit
import com.mapbox.via.kit.mapsession.MapMarkerEventSource
import com.mapbox.via.kit.search.DistantMatchCorroborator
import com.mapbox.via.kit.search.LOCAL_BRANCH_RADIUS_METERS
import com.mapbox.via.kit.search.SearchAnchor
import com.mapbox.via.kit.search.MatrixRepository
import com.mapbox.via.kit.search.SearchBoxCoordinates
import com.mapbox.via.kit.search.STREET_SUFFIXES
import com.mapbox.via.kit.search.SearchBoxRepository
import com.mapbox.via.kit.search.canReplaceFarAddress
import com.mapbox.via.kit.search.cityRecallHits
import com.mapbox.via.kit.search.needsCityRecall
import com.mapbox.via.kit.search.noPlacesFoundMessage
import com.mapbox.via.kit.search.rankByTravelTime
import com.mapbox.via.kit.search.withLocalBranches
import com.mapbox.via.ui.mapsession.MapPin
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.SearchCaption
import com.mapbox.via.ui.mapsession.pinIdFor
import com.mapbox.via.ui.navsdk.NavSdkService
import com.mapbox.via.ui.navsdk.isSameFixAs
import com.mapbox.via.ui.navsdk.metersBetween
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlin.math.roundToLong
import kotlin.time.Clock

@Suppress("LongParameterList")
class SearchPlacesAction(
    private val service: NavSdkService,
    private val contextHolder: ViaContextHolder,
    private val mapSessionStore: MapSessionStore,
    private val mapMarkerEventSource: MapMarkerEventSource = MapMarkerEventSource.Empty,
    private val matrixRepository: MatrixRepository = MatrixRepository.NoOp,
    private val searchMemories: SearchMemoryRecorder = SearchMemoryRecorder.NoOp,
    private val continuity: ConversationContinuityWriter = ConversationContinuityWriter.NoOp,
    private val searchBoxRepository: SearchBoxRepository? = null,
    // Fail-open: a caller that does not wire a real corroborator keeps a distant match rather than
    // silently rejecting every one. The real corroborator is what rejects a bogus distant match.
    private val distantMatchCorroborator: DistantMatchCorroborator = DistantMatchCorroborator { _, _ -> true },
    private val openingHours: OpeningHoursLookup = OpeningHoursLookup.None,
    private val clock: Clock = Clock.System,
) : ViaAction<SearchPlacesArgs> {
    override val id: String = ID
    override val name: String = "Search places"
    override val argsSerializer: KSerializer<SearchPlacesArgs> = SearchPlacesArgs.serializer()
    override val description: String =
        "Search for places by name, city, neighborhood, or address to get " +
            "coordinates. Use this for distinctive named businesses, chains, and places, not categories " +
            "or descriptive venue types such as gas station, hardware store, or farmers market; " +
            "a venue type remains a category even when it is multiword. " +
            "Returns the 10 best matches by default so more options are pinned than " +
            "you need to mention out loud — speak or print at most three. Results are biased toward the " +
            "user's current location unless you pass proximity (a lat/lng point to " +
            "anchor the search). For a nearby or closest search around the user, still pass their current " +
            "coordinates as proximity so ranking is explicit rather than relying on that default bias — " +
            "with proximity set, results are ranked by driving distance from that point, not " +
            "straight-line distance, so a namesake across a bay or bridge won't outrank a shorter drive. " +
            "A request of the form 'X in Y', where X is a real " +
            "business or landmark and Y is a town, city, or neighborhood, " +
            "takes two calls: resolve Y first (lookup: AREA, showOnMap: false), then " +
            "search for X alone, passing Y's coordinates as proximity and Y's name as " +
            "inArea. Never put Y in the query — the whole string is matched at once, " +
            "so a town or street sharing the area's name outranks the place you meant. " +
            "inArea then drops results filed under a different area, which proximity " +
            "alone does not do. A generic word like \"stop\" or \"waypoint\" is not an " +
            "X — it names nothing to search for, so \"add a stop in Y\" is just Y " +
            "itself, one call, not this two-call pattern. " +
            "For that area-resolution lookup, and when verifying a name from web " +
            "search or checking a place exists before recommending it, pass " +
            "showOnMap: false — those lookups are internal plumbing, kept off the " +
            "map and out of the search context; read coordinates from the result " +
            "text. Leave showOnMap true (the default) only for the places you are " +
            "presenting to the user. " +
            "Spell inArea as the user said it. " +
            "Set lookup to say what kind of thing the name is, because a road often " +
            "shares it: AREA for a city, town, or neighborhood you are resolving; " +
            "NAMED for a business or landmark (Union Square, Golden Gate Bridge); " +
            "ADDRESS for a house number or street address, even without a city or " +
            "Street suffix, so POIs and places do not outrank it; " +
            "a road name with no house number is not ADDRESS — leave ANY and the " +
            "default limit so street features are not dropped; " +
            "leave the default ANY when the kind of thing is mixed or unknown. " +
            "When the user names one unique place — a landmark, venue, museum, or " +
            "one-of-a-kind business (Presidio Theatre, Golden Gate Bridge, MoMA) — " +
            "set limit to 1 to pin just that place; famous institutions qualify " +
            "even when the name is short or abbreviated, resolving to the nearest " +
            "one when multiple candidates share that name. Only do this when searching " +
            "near the user or with a proximity anchored to the named area; otherwise " +
            "keep the default so one wrong top match cannot become the entire answer. " +
            "For chains or brands with many locations (Philz Coffee), set limit to 3 " +
            "so the nearest few branches appear rather than the full default — more " +
            "than a handful of pins from the same brand adds clutter, not real choice. " +
            "Japan indexes most businesses only under their Japanese names, so a chain searched " +
            "in Latin letters near a user in Japan (\"McDonald's\") finds only the few branches " +
            "that also carry a Latin name, often far away. Near a user in Japan, search a chain " +
            "by its full katakana storefront " +
            "name, whatever language the user speaks: スターバックス コーヒー, ファミリーマート, " +
            "セブン-イレブン. " +
            "The word \"closest\" or \"nearest\" in the user's own request is a ranking " +
            "cue, not this rule: it never turns a chain or brand search into limit 1. " +
            "For a popular or most popular request, pass rankBy: POPULARITY. The tool then " +
            "searches the widest set Search Box returns, not just the closest places, and returns " +
            "the limit most popular, already sorted most popular first; recommend them in that order. " +
            "Results are stored in the MapGPT " +
            "search context for set-destination, navigate-to, or add-waypoint. " +
            OPEN_NOW_TOOL_GUIDANCE

    override val examples: List<String> = listOf(
        "Find Mill Valley.",
        "Search for 400 Broad Street.",
        "Where is Golden Gate Park?",
        "Find Willow Cattery in Palo Alto.",
    )

    override suspend fun execute(args: SearchPlacesArgs): Result<String> {
        val limit = args.limit.coerceAtLeast(1)
        val fetchLimit = openNowFetchLimit(
            args.rankBy.fetchLimit(limit, MAX_SEARCH_BOX_FORWARD_LIMIT),
            args.openNow && args.lookup.canHaveOpeningHours(),
        )
        // The hint is never inferred. showOnMap: false marks any internal lookup, not only an area
        // resolve, and the type filter is exclusive: coercing those calls to AREA answered "Golden
        // Gate Bridge" with a Golden Gate neighborhood in Oakland.
        val userLocation = contextHolder.state.value.user?.location
        // User wins over an area proximity so sort and card miles share one Matrix call from
        // the puck. Area proximity still biases the search; it is the Matrix origin only when
        // there is no user fix.
        val rankingOrigin = userLocation ?: args.proximity
        val (scopedArgs, searchProximity) = args.scopeNamedArea(userLocation)
        val (firstPage, localBranches) = service.searchWithLocalBranches(args, searchProximity, limit, fetchLimit)
        return firstPage.mapSuspending { first ->
            // Cap here because Android's Dash.forward() exposes no limit. Rename before collapsing,
            // so the ids that reach the store are the ones deduped, and dedupe before the cap, so a
            // duplicate cannot eat a slot.
            // ADDRESS is applied here because Dash drops PlaceLookup. Empty means the provider
            // didn't type rows as ADDRESS (street-level hits, missing houseNumber), so fall
            // through rather than answer nothing — same convention as inArea.
            val firstTyped = first.filter { it.matchesLookup(args.lookup) }.ifEmpty { first }
            val recalled = service.recallWithCity(
                searchBoxRepository,
                scopedArgs,
                cityRecallOrigin(userLocation, searchProximity, scopedArgs.inArea),
                recallLocalAddresses(scopedArgs, searchProximity, first, firstTyped, fetchLimit),
                fetchLimit,
            )
            val all = service.adoptLocalBranches(recalled, localBranches, args.query)
            val byLookup = all.filter { it.matchesLookup(args.lookup) }
            val ranked = narrowToSearchArea(byLookup.ifEmpty { all }, scopedArgs, searchProximity)
                .namedAndDeduped(args.query)
                // Reorders by driving distance instead of straight-line distance, then take(limit)
                // keeps the closest drives. Android's service caps its own page at `limit`
                // (Dash.forward() honors none), so only merged local branches give ranking more
                // than `limit` rows to choose from there.
                .rankByTravelTime(rankingOrigin, userLocation != null, matrixRepository)
                .rankedBy(args.rankBy)
            val hoursAt = clock.now().takeIf { args.lookup.canHaveOpeningHours() }
            val results = (hoursAt?.let { ranked.withHoursKeepingOpenIf(args.openNow, openingHours, it) } ?: ranked)
                .take(limit)
            continuity.recordSearch(results, args.showOnMap)
            val pins = results.map { MapPin(id = pinIdFor(it), point = it.coordinate, result = it) }
            // showOnMap=false means internal on every visible surface: the search context
            // feeds GetSearchResultsAction's "currently displayed on the map"
            // contract, the agent prompt, resolveDestination's fallback, the
            // chat map's marker events, and the map session's rendered pins, so a silent
            // lookup must not touch any of them. Its rich candidates are cached separately so
            // a later marker call can preserve place identity and details.
            if (args.showOnMap) {
                // Store before render: showSearchPins suspends through its framing animation, and
                // pins are tappable the moment they draw — writing the store afterwards left a
                // window where a tap resolved against the previous search's pins and was swallowed.
                mapSessionStore.setPins(
                    pins,
                    caption = SearchCaption.around(
                        label = args.query,
                        inArea = args.inArea,
                        proximity = args.proximity,
                        destination = mapSessionStore.state.value.destination,
                    ),
                )
                // The service cached the whole popularity pool; nav_sdk_get_search_results must list what's pinned.
                if (fetchLimit > limit) service.rememberSearchResults(results)
                service.showSearchPins(pins)
                if (pins.isNotEmpty()) {
                    mapMarkerEventSource.publish(pins.map { it.point }, pins.map { it.result?.mapboxId })
                }
                results.updateSearchContext(contextHolder)
                searchMemories.searched(PlaceSearchKind.Place, args.query, categoryId = null, results)
            } else {
                mapSessionStore.cacheCandidates(pins)
                contextHolder.updateLastToolSearchResults(results)
            }
            when {
                results.isNotEmpty() ->
                    results.mapIndexed { i, result -> "${i + 1}. ${result.toResultLine(hoursAt)}" }
                        .joinToString("\n", prefix = "Found ${results.size} place(s) for '${args.query}':\n")
                ranked.isNotEmpty() -> noneOpenMessage(ranked.size, "place(s) for '${args.query}'")
                else -> args.noPlacesFoundMessage()
            }
        }.also { actionLog.d { "$it" } }
    }

    /**
     * Free-text search only biases by proximity, so a namesake on the other side of the planet
     * ranks alongside the real match ("VUE Rooftop" DC / Jakarta).
     */
    private suspend fun narrowToSearchArea(
        all: List<ViaSearchResult>,
        args: SearchPlacesArgs,
        origin: ViaPoint?,
    ): List<ViaSearchResult> {
        // Measured from where the search was aimed. args.proximity is often omitted on the
        // live address path ("74 Collins" from SF); without filling the driver's location
        // here, a null provider distance becomes 0 m and a Memphis namesake looks local.
        // inArea known-admin hits first so a named-city match outside the driver window is not
        // discarded in favor of a closer namesake. Address-less rows are unknown, not in-area:
        // keep them only when they sit in the same window as those known hits. Empty known
        // still falls through (index wording).
        val known = args.inArea?.let { area -> all.filter { it.address != null && it.isFiledUnder(area) } }
        val pool = if (known.isNullOrEmpty()) all else known + nearOrigin(all.filter { it.address == null }, origin)
        val nearAnchor = nearOrigin(pool, origin)
        // Two escapes: an area resolve (showOnMap: false) is aimed away from the user by design,
        // and when nothing is near the anchor the search was aimed elsewhere — "1600 Pennsylvania
        // Avenue" from SF, or Palo Alto from Redmond. Local namesakes (SF Collins vs Memphis)
        // already survived nearAnchor. Window still applies on the in-area pool so "New York"
        // cannot pull Buffalo.
        return when {
            !args.showOnMap -> pool
            nearAnchor.isNotEmpty() -> nearAnchor
            else -> {
                val distant =
                    pool.clusterAroundDistantMatch(
                        args.query,
                        OUTLIER_WINDOW_METERS,
                        distantMatchCorroborator,
                        searchAnchor(origin, args.inArea),
                    )
                val namedArea = args.inArea?.takeIf { known.isNullOrEmpty() }?.let { resolveArea(it, origin) }
                if (namedArea == null) {
                    distant
                } else {
                    distant.filter { metersBetween(namedArea, it.coordinate) <= OUTLIER_WINDOW_METERS }
                }
            }
        }
    }

    private fun searchAnchor(origin: ViaPoint?, inArea: String?): SearchAnchor? {
        val driver = contextHolder.state.value.user?.location
        return when {
            origin != null && !origin.isSameFixAs(driver) -> SearchAnchor.aimedAt(origin, inArea ?: origin.name)
            driver != null -> SearchAnchor.driver(driver)
            else -> null
        }
    }

    private suspend fun resolveArea(area: String, origin: ViaPoint?): ViaPoint? =
        service.searchPlaces(area, origin, limit = 1, lookup = PlaceLookup.AREA, cache = false)
            .getOrNull()
            ?.firstOrNull()
            ?.coordinate

    /**
     * Every administrative field, so the same test serves a hamlet and a county. Containment, not
     * equality: the caller passes the user's wording and the index is more formal. Unknown
     * (no address components) is not filed under the named city — the empty-pool fallthrough
     * still lets the distance window place it.
     */
    private fun ViaSearchResult.isFiledUnder(area: String): Boolean {
        val named = area.trim().lowercase()
        val address = address
        return when {
            named.isEmpty() || address == null -> false
            else -> listOfNotNull(
                address.neighborhood,
                address.locality,
                address.place,
                address.district,
                address.region,
            ).any { it.lowercase().contains(named) }
        }
    }

    private suspend fun recallLocalAddresses(
        args: SearchPlacesArgs,
        origin: ViaPoint?,
        first: List<ViaSearchResult>,
        firstTyped: List<ViaSearchResult>,
        limit: Int,
    ): List<ViaSearchResult> {
        val retried = origin
            ?.takeIf { shouldRetryWithMetroBbox(args, it, firstTyped) }
            ?.let { GeoBbox.around(it) }
            ?.let { box ->
                service.searchPlaces(
                    args.query,
                    origin,
                    limit,
                    args.lookup,
                    cache = false,
                    bbox = box,
                ).getOrNull()
            }
        val local = if (origin == null || retried == null) {
            emptyList()
        } else {
            val typed = retried.filter { it.matchesLookup(args.lookup) }.ifEmpty { retried }
            nearOrigin(typed, origin)
        }
        val firstHit = firstTyped.firstOrNull() ?: first.firstOrNull()
        val adopt = retried != null && local.any { it.canReplaceFarHit(firstHit) }
        if (adopt) {
            if (args.showOnMap) service.rememberSearchResults(retried)
            return retried
        }
        return first
    }

    private fun shouldRetryWithMetroBbox(
        args: SearchPlacesArgs,
        origin: ViaPoint,
        typed: List<ViaSearchResult>,
    ): Boolean =
        args.showOnMap &&
            nearOrigin(typed, origin).isEmpty() &&
            when (args.lookup) {
                PlaceLookup.ADDRESS -> true
                PlaceLookup.ANY -> typed.firstOrNull()?.isStreetOrAddressFeature() == true
                PlaceLookup.AREA, PlaceLookup.NAMED -> false
            }

    private fun ViaSearchResult.isStreetOrAddressFeature(): Boolean =
        type.equals("street", ignoreCase = true) ||
            type.equals("ADDRESS", ignoreCase = true)

    private fun nearOrigin(all: List<ViaSearchResult>, origin: ViaPoint?): List<ViaSearchResult> =
        all.filter { result ->
            val fromAnchor = origin?.let { metersBetween(it, result.coordinate) }
                ?: result.distanceMeters
            (fromAnchor ?: 0.0) <= OUTLIER_WINDOW_METERS
        }

    /**
     * Android's Dash.forward() drops [PlaceLookup], so ADDRESS has to be applied here.
     * iOS already filtered at Search Box; matching again is a no-op.
     */
    private fun ViaSearchResult.matchesLookup(lookup: PlaceLookup): Boolean {
        if (lookup != PlaceLookup.ADDRESS) return true
        return type.equals("ADDRESS", ignoreCase = true) ||
            !address?.houseNumber.isNullOrBlank()
    }

    private fun ViaSearchResult.canReplaceFarHit(other: ViaSearchResult?): Boolean =
        other == null || canReplaceFarAddress(address, name, other.address, other.name)

    companion object {
        const val ID: String = "nav_sdk_search_places"

        // Same 300 km outlier threshold as the MapGPT iOS app.
        private const val OUTLIER_WINDOW_METERS = 300_000.0

        private const val DEDUPE_CELLS_PER_DEGREE = 10_000.0

        private fun List<ViaSearchResult>.namedAndDeduped(query: String): List<ViaSearchResult> =
            map { result ->
                val name = result.name.ifBlank { query }
                result.copy(name = name, coordinate = result.coordinate.copy(name = name))
            }
                // Two pins sharing an id collide in MapSessionStore and crash PoiCardCarousel.
                .distinctBy { pinIdFor(it) }
                // One venue also arrives as several rows with distinct place ids (two SFMOMA
                // entries 0.2 m apart), which the id above cannot see.
                .distinctBy { it.name to dedupeCell(it.coordinate) }

        private fun dedupeCell(point: ViaPoint): Pair<Long, Long> =
            (point.latitude * DEDUPE_CELLS_PER_DEGREE).roundToLong() to
                (point.longitude * DEDUPE_CELLS_PER_DEGREE).roundToLong()
    }
}

private suspend fun NavSdkService.recallWithCity(
    searchBoxRepository: SearchBoxRepository?,
    args: SearchPlacesArgs,
    origin: ViaPoint?,
    first: List<ViaSearchResult>,
    limit: Int,
): List<ViaSearchResult> {
    val eligible = args.showOnMap && args.lookup != PlaceLookup.AREA && args.lookup != PlaceLookup.ADDRESS
    val city = if (eligible && origin != null && needsCityRecall(args.query, first, origin)) {
        cityNear(searchBoxRepository, origin)
    } else {
        null
    }
    val retried = if (city == null || origin == null) {
        null
    } else {
        searchPlaces(
            query = "${args.query} $city",
            proximity = origin,
            limit = limit,
            lookup = args.lookup,
            cache = false,
        ).getOrNull()
    }
    val adopted = if (origin == null || retried == null) {
        emptyList()
    } else {
        cityRecallHits(args.query, retried, origin)
    }
    if (adopted.isNotEmpty()) rememberSearchResults(adopted)
    return adopted.ifEmpty { first }
}

private suspend fun cityNear(repository: SearchBoxRepository?, origin: ViaPoint): String? {
    val searchBox = repository ?: return null
    return searchBox.reverse(
        SearchBoxCoordinates(latitude = origin.latitude, longitude = origin.longitude),
        types = listOf("place"),
    ).getOrNull()?.firstNotNullOfOrNull { it.address?.city() }
}

/** The service cache holds only the first page, so a merged branch is remembered to resolve by id too. */
private suspend fun NavSdkService.adoptLocalBranches(
    page: List<ViaSearchResult>,
    local: List<ViaSearchResult>,
    query: String,
): List<ViaSearchResult> =
    page.withLocalBranches(local, query, { it.name }, { it.id }, { it.type.equals("poi", ignoreCase = true) })
        .also { if (it !== page) rememberSearchResults(it) }

/**
 * Runs the main search and, for a named or open lookup, a second one boxed to the nearby streets.
 * A unique place (limit 1) skips the box, and so does a search aimed at a named area, which the
 * box around the anchor would not cover.
 */
private suspend fun NavSdkService.searchWithLocalBranches(
    args: SearchPlacesArgs,
    searchProximity: ViaPoint?,
    limit: Int,
    fetchLimit: Int,
): Pair<Result<List<ViaSearchResult>>, List<ViaSearchResult>> = coroutineScope {
    val box = searchProximity
        ?.takeIf { args.showOnMap && limit > 1 && args.lookup in LOCAL_BRANCH_LOOKUPS && args.inArea == null }
        ?.let { GeoBbox.around(it, LOCAL_BRANCH_RADIUS_METERS) }
    val local = box?.let {
        async { searchPlaces(args.query, searchProximity, limit, PlaceLookup.NAMED, cache = false, bbox = it) }
    }
    val first = searchPlaces(args.query, searchProximity, fetchLimit, args.lookup, cache = args.showOnMap)
    first to local?.await()?.getOrNull().orEmpty()
}

// User-facing searches fill the driver's location so Android Dash.forward actually
// biases (iOS already does this in searchBias). Silent AREA resolves and ADDRESS
// queries that already name a city must not: driver GPS ranks a closer namesake
// over the town the caller asked for.
private fun SearchPlacesArgs.scopeNamedArea(
    userLocation: ViaPoint?,
): Pair<SearchPlacesArgs, ViaPoint?> {
    val extracted = query.namedAreaOrNull().takeIf { lookup == PlaceLookup.ADDRESS }
    val namedArea = (inArea ?: extracted).takeIf { lookup == PlaceLookup.ADDRESS }
    val scoped = if (extracted != null && inArea == null) copy(inArea = extracted) else this
    val driverGps = proximity.isSameFixAs(userLocation)
    val searchProximity = when {
        lookup == PlaceLookup.AREA && !showOnMap && driverGps -> null
        namedArea != null && (proximity == null || driverGps) -> null
        else -> proximity ?: userLocation.takeIf { showOnMap }
    }
    return scoped to searchProximity
}

private fun String.namedAreaOrNull(): String? {
    val match = NAMED_AREA_IN_QUERY.find(trim())
    val area = match?.groupValues?.get(1)?.trim()?.substringBefore(',')?.trim().orEmpty()
    val tokens = area.lowercase().split(NON_ALNUM).filter { token -> token.isNotBlank() }
    val suffixOnly = tokens.isNotEmpty() && tokens.all { token -> token in STREET_SUFFIXES }
    return area.takeIf { it.isNotEmpty() && !suffixOnly }
}

/**
 * City recall anchors on the driver's city. Skip when the caller already aimed at another
 * area (`inArea` or a non-GPS proximity) so "Galleria in Roseville" cannot become Crocker.
 */
private fun cityRecallOrigin(
    userLocation: ViaPoint?,
    searchProximity: ViaPoint?,
    inArea: String?,
): ViaPoint? {
    val aimedAtDriver = inArea.isNullOrBlank() &&
        (searchProximity == null || searchProximity.isSameFixAs(userLocation))
    return if (aimedAtDriver) userLocation ?: searchProximity else null
}

private val NAMED_AREA_IN_QUERY = Regex("""(?:\s+in\s+|,\s*)(.+)$""", RegexOption.IGNORE_CASE)
private val NON_ALNUM = Regex("[^a-z0-9]+")
// A brand comes in as NAMED or with no lookup at all; an area or an address is never a branch.
private val LOCAL_BRANCH_LOOKUPS = setOf(PlaceLookup.NAMED, PlaceLookup.ANY)

private fun PlaceLookup.canHaveOpeningHours(): Boolean = this != PlaceLookup.AREA && this != PlaceLookup.ADDRESS
