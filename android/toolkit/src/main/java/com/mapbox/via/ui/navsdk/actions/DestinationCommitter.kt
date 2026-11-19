package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.search.SearchBoxCoordinates
import com.mapbox.via.kit.search.SearchBoxRepository
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.models.formatted
import com.mapbox.via.kit.models.withRoutingHintsFrom
import com.mapbox.via.ui.mapsession.MapSession
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.TripContinuitySync
import com.mapbox.via.ui.navsdk.metersBetween
import com.mapbox.via.ui.navsdk.samePositionAs
import com.mapbox.via.ui.screens.place.PlaceDetailsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Single write path for a trip destination: name, Search Box id and street address for the arrival
 * banner. Without an inline address, Places (by id) or reverse geocoding runs on [backgroundScope]
 * so starting guidance is never delayed.
 */
class DestinationCommitter(
    private val mapSessionStore: MapSessionStore,
    private val placeDetailsStore: PlaceDetailsStore,
    private val searchBoxRepository: SearchBoxRepository,
    private val backgroundScope: CoroutineScope,
    private val continuity: TripContinuitySync,
) {
    suspend fun commit(destination: ViaSearchResult) {
        val enriched = destination.enrichFromKnownPlaces(mapSessionStore.state.value)
        val inlineAddress = enriched.address?.formatted() ?: enriched.coordinate.address?.takeIf { it.isNotBlank() }
        mapSessionStore.beginRoutePreview(
            destination = enriched.coordinate,
            destinationMapboxId = enriched.mapboxId,
            destinationAddress = inlineAddress,
        )
        continuity.recordDestination(enriched)
        if (inlineAddress != null) return
        backgroundScope.launch {
            val address = enriched.mapboxId?.let { placeDetailsStore.get(it)?.fullAddress }?.takeIf { it.isNotBlank() }
                ?: reverseGeocodedAddress(enriched.coordinate)
                ?: return@launch
            val stillCurrent = mapSessionStore.setDestinationAddressIfCurrent(
                enriched.coordinate,
                enriched.mapboxId,
                address,
            )
            if (stillCurrent) {
                continuity.recordDestination(enriched.copy(coordinate = enriched.coordinate.copy(address = address)))
            }
        }
    }

    private suspend fun reverseGeocodedAddress(point: ViaPoint): String? = searchBoxRepository
        .reverse(SearchBoxCoordinates(point.latitude, point.longitude), types = listOf("address"))
        .getOrNull()
        ?.firstOrNull()
        ?.let { it.address?.formatted() ?: it.fullAddress }
        ?.takeIf { it.isNotBlank() }
}

/** A pin at the same spot usually has the address and id that agent-supplied coordinates lack. */
internal fun ViaSearchResult.enrichFromKnownPlaces(session: MapSession): ViaSearchResult {
    if (address != null && mapboxId != null) return this
    // Selected pin first, then the nearest: two cafes 30 m apart both pass the proximity check.
    val match = session.selectedPin?.result?.takeIf { it.samePlaceAs(this) }
        ?: (session.pins + session.candidatePins)
            .mapNotNull { it.result }
            .filter { it.samePlaceAs(this) }
            .minByOrNull { metersBetween(it.coordinate, coordinate) }
    return if (match == null) {
        this
    } else {
        copy(
            coordinate = coordinate.withRoutingHintsFrom(match.coordinate),
            address = address ?: match.address,
            mapboxId = mapboxId ?: match.mapboxId,
        )
    }
}

internal fun ViaSearchResult.samePlaceAs(other: ViaSearchResult): Boolean {
    val id = mapboxId?.takeIf { it.isNotBlank() }
    val otherId = other.mapboxId?.takeIf { it.isNotBlank() }
    return if (id != null && otherId != null) id == otherId else coordinate.samePositionAs(other.coordinate)
}
