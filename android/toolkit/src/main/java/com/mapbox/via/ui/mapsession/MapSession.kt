package com.mapbox.via.ui.mapsession

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.ui.navsdk.samePositionAs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

data class AddedStop(
    val point: ViaPoint,
    val mapboxId: String? = null,
)

data class MapSession(
    val pins: List<MapPin> = emptyList(),
    /**
     * Results from a silent (showOnMap: false) search — never rendered or "currently
     * displayed", kept only so a later nav_sdk_add_map_markers call can resolve an enriched
     * twin instead of falling back to a bare positional pin.
     */
    val candidatePins: List<MapPin> = emptyList(),
    /** The highlighted pin (carousel card, emphasised marker); [poiSheetOpen] says if its sheet is up. */
    val selectedPinId: String? = null,
    val poiSheetOpen: Boolean = false,
    /**
     * Bumped by every [MapSessionStore.selectPin], including a re-tap of the already selected pin,
     * so a tap is observable even when it changes nothing else — the camera re-centres on it.
     */
    val selectionTaps: Int = 0,
    val destination: ViaPoint? = null,
    /**
     * Search Box place id for [destination], when it was resolved from a real search result —
     * null for agent-supplied raw coordinates. Lets a destination-exclusion filter match by
     * identity instead of proximity: see [com.mapbox.via.ui.navsdk.matchesDestination].
     */
    val destinationMapboxId: String? = null,
    /** Street address for [destination]; null until the background lookup fills it in. */
    val destinationAddress: String? = null,
    val routePreviewActive: Boolean = false,
    val activeGuidance: Boolean = false,
    /** True once the driver has reached the final destination; drives the arrival-state UI. */
    val arrivedAtDestination: Boolean = false,
    val overlayMode: MapOverlayMode = MapOverlayMode.None,
    val poiCarouselDismissed: Boolean = false,
    /**
     * Stops added to the active route, whatever added them — the POI sheet,
     * voice via [AddWaypointAction][com.mapbox.via.ui.navsdk.actions.AddWaypointAction], or
     * CarPlay. Search-derived stops keep their Mapbox id; coordinate-only voice paths still work
     * through positional fallback because the stop may have no pin on the map at all.
     */
    val addedStops: List<AddedStop> = emptyList(),
    /** Replaced by every [MapSessionStore.setPins], so it never outlives the pins it describes. */
    val searchCaption: SearchCaption? = null,
) {
    /** Looked up from the live pin list so late-arriving enrichment reaches an open sheet. */
    val selectedPin: MapPin? get() = pins.firstOrNull { it.id == selectedPinId }

    /**
     * Which pins are already stops, for the "Added" affordance. Search Box identity is
     * authoritative when both sides have it; otherwise coordinates bridge agent-supplied stops
     * whose names may differ from the search provider's.
     */
    val addedStopPinIds: Set<String>
        get() = pins
            .filter { pin -> addedStops.any { it.matches(pin) } }
            .mapTo(mutableSetOf()) { it.id }
}

private fun AddedStop.matches(pin: MapPin): Boolean {
    val stopMapboxId = mapboxId?.takeIf { it.isNotBlank() }
    val pinMapboxId = pin.result?.mapboxId?.takeIf { it.isNotBlank() }
    if (stopMapboxId != null && pinMapboxId != null) return stopMapboxId == pinMapboxId
    return point.samePositionAs(pin.point)
}

// Small, single-purpose mutators over one piece of session state each; splitting them across
// classes would separate cohesive pins/selection/guidance operations for no real benefit.
@Suppress("TooManyFunctions")
class MapSessionStore(
    private val onTripChanged: ((session: MapSession, routeCleared: Boolean) -> Unit)? = null,
    private val onGuidanceChanged: ((active: Boolean) -> Unit)? = null,
) {
    private val _state = MutableStateFlow(MapSession())
    val state: StateFlow<MapSession> = _state.asStateFlow()

    /**
     * After [beginRoutePreview] or [startActiveGuidance] clears [MapSession.arrivedAtDestination],
     * ignore near-zero remaining distance until the trip reports en-route progress — stale
     * route-summary from the previous leg would otherwise re-latch arrival immediately.
     */
    private var blockArrivalRelatch = false

    /** @see blockArrivalRelatch */
    internal fun armArrivalRelatchGuard() {
        blockArrivalRelatch = true
    }

    /** Clears [blockArrivalRelatch] once [remainingDistanceMeters] reflects the new leg. */
    internal fun clearArrivalRelatchGuardIfEnRoute(remainingDistanceMeters: Double, thresholdMeters: Double) {
        if (blockArrivalRelatch && remainingDistanceMeters > thresholdMeters) {
            blockArrivalRelatch = false
        }
    }

    internal fun isArrivalRelatchBlocked(): Boolean = blockArrivalRelatch

    /** [selectPinId] selects in the same update; a mounted carousel settles into any gap between the two. */
    fun setPins(pins: List<MapPin>, selectPinId: String? = null, caption: SearchCaption? = null) {
        // A repeated id survives into keyed Compose lists as "Key was already used", and it shifts
        // the ordinal FocusSearchResultAction reads. The first pin wins, matching cacheCandidates.
        val uniquePins = pins.distinctBy(MapPin::id)
        _state.update { current ->
            val explicit = selectPinId?.takeIf { id -> uniquePins.any { it.id == id } }
            if (explicit != null) {
                return@update current.copy(
                    pins = uniquePins,
                    selectedPinId = explicit,
                    poiSheetOpen = true,
                    poiCarouselDismissed = false,
                    selectionTaps = current.selectionTaps + 1,
                    searchCaption = caption,
                )
            }
            val selectedPinId = current.selectedPin?.let { selected ->
                val exact = uniquePins.firstOrNull { it.id == selected.id }
                if (selected.result?.mapboxId != null) {
                    exact?.id
                } else {
                    uniquePins.firstOrNull {
                        it.result != null && it.point.samePlaceAs(selected.point)
                    }?.id ?: exact?.id
                        ?: uniquePins.firstOrNull { it.point.samePlaceAs(selected.point) }?.id
                }
            }
            current.copy(
                pins = uniquePins,
                selectedPinId = selectedPinId,
                poiSheetOpen = current.poiSheetOpen && selectedPinId != null,
                poiCarouselDismissed = false,
                searchCaption = caption,
            )
        }
    }

    /**
     * Merges into the silent-search cache, new entries first — see [MapSession.candidatePins].
     * Must merge rather than replace: verifying multiple candidates, or searching multiple
     * categories, before a single combined pin call means this is called more than once per
     * conversation, and a later call must not evict an earlier candidate's enrichment. New
     * entries go first, not last, because [AddMapMarkersAction][com.mapbox.via.ui.navsdk.actions.AddMapMarkersAction]
     * resolves by place (lat/lng), not id — the same place can be cached twice under two
     * different ids (no mapboxId yet, so keyed positionally, then a later search returns one),
     * and [distinctBy] keeps the first occurrence per id, so putting new pins first is what
     * makes the fresher, more-enriched entry win that place-based lookup, not just an id match.
     * Not evicted at any turn boundary, so a candidate can resurface many turns later with
     * distance/ETA computed from a stale origin. That is an accepted limitation: it is the same
     * place with stale metadata, never a wrong place.
     */
    fun cacheCandidates(pins: List<MapPin>) {
        _state.update { current ->
            current.copy(candidatePins = (pins + current.candidatePins).distinctBy { it.id })
        }
    }

    fun upsertPin(pin: MapPin) {
        _state.update { current ->
            val indexById = current.pins.indexOfFirst { it.id == pin.id }
            val indexByPlace = current.pins.indexOfFirst { it.point.samePlaceAs(pin.point) }
            val existing = current.pins.getOrNull(indexByPlace)
            val existingMapboxId = existing?.result?.mapboxId?.takeIf(String::isNotBlank)
            val incomingMapboxId = pin.result?.mapboxId?.takeIf(String::isNotBlank)
            val pins = when {
                indexById >= 0 -> current.pins.toMutableList().apply {
                    this[indexById] = pin.copy(result = pin.result ?: this[indexById].result)
                }
                existing == null -> current.pins + pin
                existingMapboxId != null &&
                    incomingMapboxId != null &&
                    existingMapboxId != incomingMapboxId -> current.pins + pin
                incomingMapboxId != null ->
                    current.pins.toMutableList().apply {
                        // Append rendering keeps the native marker's original key.
                        this[indexByPlace] = pin.copy(id = existing.id)
                    }
                else -> current.pins
            }
            current.copy(pins = pins)
        }
    }

    fun removePin(id: String) {
        _state.update { current ->
            val pins = current.pins.filterNot { it.id == id }
            val selectedPinId = current.selectedPinId.takeIf { sel -> pins.any { it.id == sel } }
            current.copy(
                pins = pins,
                selectedPinId = selectedPinId,
                poiSheetOpen = current.poiSheetOpen && selectedPinId != null,
            )
        }
    }

    /** A tap: highlights the pin and opens its sheet. */
    fun selectPin(id: String) {
        _state.update { current ->
            if (current.pins.none { it.id == id }) return@update current
            current.copy(selectedPinId = id, poiSheetOpen = true, selectionTaps = current.selectionTaps + 1)
        }
    }

    /** A carousel settle: highlights the pin, sheet untouched. Ignored while a sheet is open. */
    fun focusPin(id: String) {
        _state.update { current ->
            if (current.poiSheetOpen || current.pins.none { it.id == id }) return@update current
            current.copy(selectedPinId = id)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedPinId = null, poiSheetOpen = false) }
    }

    /** Pin-scoped clear: a dismissal that settles late must not wipe a newer selection. */
    fun clearSelection(ifSelectedId: String) {
        _state.update {
            if (it.selectedPinId == ifSelectedId) it.copy(selectedPinId = null, poiSheetOpen = false) else it
        }
    }

    fun closePoiSheet(ifSelectedId: String) {
        _state.update {
            if (it.selectedPinId == ifSelectedId) it.copy(poiSheetOpen = false) else it
        }
    }

    fun setOverlayMode(mode: MapOverlayMode) {
        _state.update { it.copy(overlayMode = mode) }
    }

    /**
     * Every add-stop path funnels here — the shell ViewModel's buttons, the agent's
     * add-waypoint tool, and CarPlay — because the "Added" affordance is the only confirmation
     * a voice-added stop landed at all.
     */
    fun recordAddedStop(point: ViaPoint, mapboxId: String? = null) {
        _state.update { current ->
            // Positional identity: routing hints on the point must not split one stop into two.
            current.copy(addedStops = current.addedStops.plusDistinct(AddedStop(point, mapboxId)))
        }
        notifyTrip()
    }

    fun recordAddedStops(points: List<ViaPoint>) {
        if (points.isEmpty()) return
        _state.update { current ->
            current.copy(
                addedStops = points.fold(current.addedStops) { stops, point ->
                    stops.plusDistinct(AddedStop(point))
                },
            )
        }
        notifyTrip()
    }

    fun forgetAddedStops(points: List<ViaPoint>) {
        if (points.isEmpty()) return
        _state.update { current ->
            current.copy(
                addedStops = current.addedStops.filterNot { added ->
                    points.any { it.samePositionAs(added.point) }
                },
            )
        }
        notifyTrip()
    }

    fun dismissPoiCarousel() {
        _state.update { it.copy(poiCarouselDismissed = true) }
    }

    fun beginRoutePreview(
        destination: ViaPoint,
        destinationMapboxId: String? = null,
        destinationAddress: String? = null,
    ) {
        // Do not force activeGuidance off: a late set_destination after Navigate would drop the
        // shell out of GUIDANCE while the SDK trip kept running.
        _state.update { current ->
            val sameDestination = when {
                current.destination == null -> false
                current.destinationMapboxId != null && destinationMapboxId != null ->
                    current.destinationMapboxId == destinationMapboxId
                else -> current.destination.samePositionAs(destination)
            }
            // Stops survive a re-geocoded destination a few metres away; identity does not. Go re-commits
            // the previewed point without an id, so exact coordinates keep what set_destination knew.
            val sameIdentity = when {
                current.destination == null -> false
                destinationMapboxId != null -> current.destinationMapboxId == destinationMapboxId
                else -> current.destination.latitude == destination.latitude &&
                    current.destination.longitude == destination.longitude
            }
            if (current.arrivedAtDestination) armArrivalRelatchGuard()
            current.copy(
                destination = destination,
                destinationMapboxId = destinationMapboxId ?: current.destinationMapboxId.takeIf { sameIdentity },
                destinationAddress = destinationAddress ?: current.destinationAddress.takeIf { sameIdentity },
                routePreviewActive = true,
                addedStops = current.addedStops.takeIf { sameDestination }.orEmpty(),
                arrivedAtDestination = false,
            )
        }
        notifyTrip()
    }

    /**
     * Fills a missing address from a lookup, unless [destination] has been replaced meanwhile.
     * Returns whether this lookup is still the current destination, so a late result is not
     * written anywhere else.
     */
    fun setDestinationAddressIfCurrent(destination: ViaPoint, destinationMapboxId: String?, address: String): Boolean {
        val updated = _state.updateAndGet { current ->
            val currentDestination = current.destination ?: return@updateAndGet current
            if (current.destinationAddress != null) return@updateAndGet current
            val stillCurrent = if (destinationMapboxId != null || current.destinationMapboxId != null) {
                current.destinationMapboxId == destinationMapboxId
            } else {
                currentDestination.samePositionAs(destination)
            }
            if (stillCurrent) current.copy(destinationAddress = address) else current
        }
        val currentDestination = updated.destination
        val addressLanded = updated.destinationAddress == address
        val stillThisPlace = when {
            currentDestination == null -> false
            destinationMapboxId != null || updated.destinationMapboxId != null ->
                updated.destinationMapboxId == destinationMapboxId
            else -> currentDestination.samePositionAs(destination)
        }
        return addressLanded && stillThisPlace
    }

    fun startActiveGuidance() {
        // Search pins belong to the browse that led here. Leaving them up would put the
        // results carousel back over an in-progress trip once mode left GUIDANCE.
        // Only drop them on the transition in — Android syncs this on every ActiveGuidance
        // emission, and a re-emit must not wipe pins added mid-trip (along-route search).
        val previous = _state.getAndUpdate { current ->
            if (current.arrivedAtDestination) armArrivalRelatchGuard()
            if (current.activeGuidance) {
                current.copy(routePreviewActive = true, arrivedAtDestination = false)
            } else {
                current.copy(
                    activeGuidance = true,
                    routePreviewActive = true,
                    arrivedAtDestination = false,
                    pins = emptyList(),
                    searchCaption = null,
                    selectedPinId = null,
                    poiSheetOpen = false,
                    // Stops recorded for this preview belong to the trip guidance is starting.
                    addedStops = current.addedStops.takeIf { current.routePreviewActive }.orEmpty(),
                )
            }
        }
        if (!previous.activeGuidance) onGuidanceChanged?.invoke(true)
        notifyTrip()
    }

    fun markArrivedAtDestination() {
        _state.update { current ->
            if (!current.activeGuidance || current.arrivedAtDestination) current
            else current.copy(arrivedAtDestination = true)
        }
    }

    fun clearArrivedAtDestination() {
        _state.update { current ->
            if (!current.arrivedAtDestination) current else {
                armArrivalRelatchGuard()
                current.copy(arrivedAtDestination = false)
            }
        }
    }

    fun stopNavigation() {
        blockArrivalRelatch = false
        val previous = _state.getAndUpdate {
            it.copy(
                activeGuidance = false,
                routePreviewActive = false,
                destination = null,
                destinationMapboxId = null,
                destinationAddress = null,
                addedStops = emptyList(),
                arrivedAtDestination = false,
            )
        }
        if (previous.activeGuidance) onGuidanceChanged?.invoke(false)
        notifyTrip(routeCleared = true)
    }

    private fun notifyTrip(routeCleared: Boolean = false) {
        onTripChanged?.invoke(_state.value, routeCleared)
    }

    /**
     * Drops conversation-derived UI (pins, selection, route preview). Active guidance is
     * preserved: a real, in-progress trip outlives the conversation that started it.
     */
    fun reset() {
        _state.update { current ->
            MapSession(
                destination = current.destination,
                destinationMapboxId = current.destinationMapboxId,
                destinationAddress = current.destinationAddress,
                activeGuidance = current.activeGuidance,
                arrivedAtDestination = current.arrivedAtDestination,
            )
        }
    }
}

private fun List<AddedStop>.plusDistinct(stop: AddedStop): List<AddedStop> =
    (this + stop).distinctBy { pinIdFor(it.point) to it.mapboxId }
