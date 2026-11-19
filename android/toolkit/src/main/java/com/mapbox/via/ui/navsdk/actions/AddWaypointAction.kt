package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.AddWaypointArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.ui.mapsession.MapPin
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.pinIdFor
import com.mapbox.via.ui.mapsession.samePlaceAs
import com.mapbox.via.ui.navsdk.NavSdkService
import com.mapbox.via.ui.navsdk.samePositionAs
import kotlinx.serialization.KSerializer

class AddWaypointAction(
    private val service: NavSdkService,
    private val contextHolder: ViaContextHolder,
    private val mapSessionStore: MapSessionStore,
    private val tripMemories: TripMemoryRecorder,
) : ViaAction<AddWaypointArgs> {
    override val id: String = "nav_sdk_add_waypoint"
    override val name: String = "Add waypoint"
    override val description: String =
        "Add one or more intermediate stops to the current navigation route. Pass " +
            "resultIndices — the 1-based positions from \"Recent search results\" in the " +
            "current context — to pick results of an earlier search (\"add the second one\"), " +
            "or waypoints with explicit coordinates when the place came back as tool text " +
            "instead (e.g. from a search backend that runs off-device). Named cities, " +
            "addresses, and places are valid stops; do not invent an unrelated POI. " +
            "Accepts a single stop or a list of stops in one call, mixing both fields if needed."
    override val argsSerializer: KSerializer<AddWaypointArgs> = AddWaypointArgs.serializer()
    // Keep examples generic: they feed on-device intent matching, not the LLM.
    // Named-place routing guidance lives in description (and the assistant prompt).
    override val examples: List<String> = listOf(
        "Stop here on the way.",
        "Add this as a waypoint.",
        "Add a stop on the way.",
    )

    override suspend fun execute(args: AddWaypointArgs): Result<String> {
        val resolvedWaypoints = args.resolveWaypoints(contextHolder)
        val waypoints = resolvedWaypoints.map { it.point }.distinctBy { pinIdFor(it) }
        // Both platforms treat an empty list as a trivial Result.success(0) — without this
        // check, an all-out-of-range resultIndices call (or an empty payload the args'
        // defaults now allow) would report "Added 0 stops to your route." as if it worked.
        if (waypoints.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "No stops to add. Pass waypoint coordinates, or resultIndices that exist " +
                        "in the current search results.",
                ),
            )
        }
        return service.addWaypoints(waypoints).map { added ->
            if (added > 0) {
                // Before pinning, and whatever the stop came from: the stop's pin would otherwise
                // open the guidance results, unmounting the guidance chat under the reply.
                if (added == waypoints.size) mapSessionStore.dismissPoiCarousel()
                pinAddedWaypoints(resolvedWaypoints)
            }
            val total = waypoints.size
            when {
                // addWaypoints reports a count, not which points succeeded, so naming would be
                // misleading here — keep the count-only message when some failed. For the same
                // reason a partial add records nothing: marking the wrong stop "Added" in the UI
                // is worse than leaving the spoken count as the only confirmation.
                added != total -> "Added $added of $total ${stopWord(total)} to your route."
                else -> {
                    val stops = waypoints.map { point ->
                        val mapboxId = resolvedWaypoints.firstOrNull {
                            it.point.samePlaceAs(point) && it.mapboxId != null
                        }?.mapboxId
                        ResolvedWaypoint(point, mapboxId)
                    }
                    stops.forEach { mapSessionStore.recordAddedStop(it.point, it.mapboxId) }
                    tripMemories.stopsAdded(stops)
                    namedConfirmation(waypoints) ?: "Added $added ${stopWord(added)} to your route."
                }
            }
        }.also { actionLog.d { "$it" } }
    }

    private suspend fun pinAddedWaypoints(resolvedWaypoints: List<ResolvedWaypoint>) {
        val registered = mapSessionStore.state.value.pins + mapSessionStore.state.value.candidatePins
        val pins = resolvedWaypoints.map { waypoint ->
            registered.firstOrNull { it.matches(waypoint) }
                ?: MapPin(id = pinIdFor(waypoint.point), point = waypoint.point)
        }
        pins.forEach(mapSessionStore::upsertPin)
        service.addSearchPins(pins)
    }

    // Mirrors AddedStop.matches: Search Box identity is authoritative when both sides have it,
    // otherwise position (not name, which the agent and the search provider routinely disagree
    // on) bridges an agent-supplied stop to its registered pin.
    private fun MapPin.matches(waypoint: ResolvedWaypoint): Boolean {
        val waypointMapboxId = waypoint.mapboxId?.takeIf { it.isNotBlank() }
        val pinMapboxId = result?.mapboxId?.takeIf { it.isNotBlank() }
        if (waypointMapboxId != null && pinMapboxId != null) return waypointMapboxId == pinMapboxId
        return point.samePositionAs(waypoint.point)
    }

    /** Null when any waypoint has no name (raw coordinates from the model) — count-only then. */
    private fun namedConfirmation(waypoints: List<ViaPoint>): String? {
        val names = waypoints.map { it.name?.takeIf { name -> name.isNotBlank() } ?: return null }
        val joined = when (names.size) {
            1 -> names.single()
            2 -> "${names[0]} and ${names[1]}"
            else -> "${names.dropLast(1).joinToString(", ")}, and ${names.last()}"
        }
        val stops = if (names.size == 1) "a stop" else "stops"
        return "Added $joined as $stops to your route."
    }

    private fun stopWord(count: Int): String = if (count == 1) "stop" else "stops"
}
