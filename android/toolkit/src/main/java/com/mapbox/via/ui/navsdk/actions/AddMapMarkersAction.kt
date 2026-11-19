package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.AddMapMarkersArgs
import com.mapbox.via.kit.actions.MapMarkerArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.context.updateSearchResults
import com.mapbox.via.kit.models.withRoutingHintsFrom
import com.mapbox.via.ui.mapsession.MapPin
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.formatMapPinSubtitle
import com.mapbox.via.ui.mapsession.couldBeSamePlaceAs
import com.mapbox.via.ui.mapsession.mergeMapMarkers
import com.mapbox.via.ui.mapsession.minimalSearchResultFor
import com.mapbox.via.ui.mapsession.toLookupPins
import com.mapbox.via.ui.mapsession.pinIdFor
import com.mapbox.via.ui.mapsession.samePlaceAs
import com.mapbox.via.ui.mapsession.sharedPlaceIds
import com.mapbox.via.ui.navsdk.NavSdkService
import com.mapbox.via.ui.navsdk.metersBetween
import com.mapbox.via.ui.navsdk.samePositionAs
import kotlinx.serialization.KSerializer

class AddMapMarkersAction(
    private val service: NavSdkService,
    private val mapSessionStore: MapSessionStore,
    private val contextHolder: ViaContextHolder,
) : ViaAction<AddMapMarkersArgs> {
    override val id: String = "nav_sdk_add_map_markers"
    override val name: String = "Add markers"
    override val description: String =
        "Add visual marker(s) at the given location(s) on the map. By default replaces all " +
            "previously shown markers — use this for search results. Pass replace=false to keep " +
            "existing markers and add these. The camera adjusts automatically to show all markers. " +
            "Each marker's optional 'category' picks its icon (e.g. cafe, restaurant, fuel, " +
            "charging_station, parking, hotel, grocery, hospital, pharmacy, bank, airport); " +
            "omit it for a generic pin. Copy latitude and longitude from a numbered search result " +
            "line; the client keeps place identity and rich details."
    override val argsSerializer: KSerializer<AddMapMarkersArgs> = AddMapMarkersArgs.serializer()
    override val examples: List<String> = listOf(
        "Pin these places on the map.",
        "Show the results on the map.",
        "Mark this location.",
        "Pin this spot on the map.",
    )

    override suspend fun execute(args: AddMapMarkersArgs): Result<String> {
        if (args.markers.isEmpty()) {
            return Result.success("No markers provided; nothing pinned.").also { actionLog.d { "$it" } }
        }
        val markers = emptyList<MapMarkerArgs>().mergeMapMarkers(args.markers)
        val sharedIds = markers.sharedPlaceIds()
        // Explicit markers arrive as transcribed coordinates, but a place already pinned by a
        // search is registered under its mapboxId — resolve by place (as ClearMapMarkersAction
        // does) so re-pinning it keeps one store entry and its rich result instead of adding a
        // positional duplicate. candidatePins (silent nav_sdk search) and Current-context
        // search results (curated_search_tool / search_box_*) are included too. An unmatched
        // place id still becomes a minimal result so the UI can fetch details; raw coordinates
        // without an id fall back to a positional pin with no result.
        val registered = mapSessionStore.state.value.pins +
            mapSessionStore.state.value.candidatePins +
            contextHolder.state.value.search.results.toLookupPins()
        val pins = markers
            .map { marker -> marker.resolvePin(registered, preserveIdMatchedPoint = !args.replace, sharedIds) }
            .distinctBy { it.id }
        // Store before render on both paths: the render suspends through its framing animation,
        // and pins are tappable the moment they draw — writing the store afterwards left a window
        // where a tap resolved against stale pins and was swallowed. The trade is that a rare
        // render failure leaves the store holding pins the map never drew; the tap window happened
        // on every call.
        return if (args.replace) {
            dropRecentResultsReplacedBy(pins)
            mapSessionStore.setPins(pins)
            service.showSearchPins(pins).map {
                "Pinned ${pins.size} marker(s) on the map."
            }
        } else {
            pins.forEach(mapSessionStore::upsertPin)
            service.addSearchPins(pins).map { added ->
                val alreadyPinned = pins.size - added
                if (alreadyPinned > 0) {
                    "Added $added marker(s) to the map ($alreadyPinned already pinned)."
                } else {
                    "Added $added marker(s) to the map."
                }
            }
        }.also { actionLog.d { "$it" } }
    }

    /**
     * A hosted (MCP) search never writes Recent, so a stale local list would keep answering "the
     * second one". Recent survives only a re-pin of its own results.
     */
    private fun dropRecentResultsReplacedBy(pins: List<MapPin>) {
        val recent = contextHolder.state.value.search.results
        if (recent.isEmpty()) return
        val allListed = pins.all { pin ->
            val pinned = pin.result ?: pin.point.toSearchResult()
            recent.any { it.samePlaceAs(pinned) }
        }
        if (!allListed) contextHolder.updateSearchResults(emptyList())
    }
}

private fun MapMarkerArgs.resolvePin(
    registered: List<MapPin>,
    preserveIdMatchedPoint: Boolean,
    sharedIds: Set<String>,
): MapPin {
    val point = toViaPoint()
    val (matchedById, rejected) = checkId(point, registered, sharedIds)
    val placeId = mapboxId?.takeIf(String::isNotBlank).takeUnless { rejected }
    val matched = matchedById ?: registered.firstOrNull {
        val registeredId = it.result?.mapboxId?.takeIf(String::isNotBlank)
        (placeId == null || registeredId == null) && it.point.samePlaceAs(point)
    } ?: registered.takeIf { rejected }?.nearestResultTo(point)
    return matched?.copy(
        id = placeId ?: matched.id,
        point = matched.point.takeIf { preserveIdMatchedPoint && matchedById != null }
            ?: point.withRoutingHintsFrom(matched.point),
        result = matched.result.withMapboxId(placeId, point),
    ) ?: MapPin(
        id = placeId ?: pinIdFor(point),
        point = point,
        result = placeId?.let { minimalSearchResultFor(it, point) },
    )
}

private data class IdCheck(val matchedById: MapPin?, val rejected: Boolean)

private fun MapMarkerArgs.checkId(point: ViaPoint, registered: List<MapPin>, sharedIds: Set<String>): IdCheck {
    val suppliedId = mapboxId?.takeIf(String::isNotBlank)
    if (suppliedId == null || suppliedId in sharedIds) return IdCheck(null, rejected = suppliedId != null)
    val sameId = registered.filter { it.result?.mapboxId == suppliedId }
    val matchedById = sameId.firstOrNull { it.point.couldBeSamePlaceAs(point) }
    val registeredHere = registered.any { !it.result?.mapboxId.isNullOrBlank() && it.point.samePlaceAs(point) }
    return IdCheck(matchedById, rejected = matchedById == null && (sameId.isNotEmpty() || registeredHere))
}

private fun List<MapPin>.nearestResultTo(point: ViaPoint): MapPin? =
    filter { it.result != null && it.point.samePositionAs(point) }.minByOrNull { metersBetween(it.point, point) }

private fun ViaSearchResult?.withMapboxId(placeId: String?, point: ViaPoint): ViaSearchResult? =
    when {
        this == null && placeId != null -> minimalSearchResultFor(placeId, point)
        this != null && placeId != null && mapboxId.isNullOrBlank() -> copy(mapboxId = placeId)
        else -> this
    }
