package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.RemoveWaypointArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.navsdk.NavSdkService
import kotlinx.serialization.KSerializer

class RemoveWaypointAction(
    private val service: NavSdkService,
    private val mapSessionStore: MapSessionStore,
) : ViaAction<RemoveWaypointArgs> {
    override val id: String = "nav_sdk_remove_waypoint"
    override val name: String = "Remove waypoint"
    override val description: String =
        "Remove one or more intermediate stops from the current navigation route. Accepts a " +
            "single stop or a list of stops in one call."
    override val argsSerializer: KSerializer<RemoveWaypointArgs> = RemoveWaypointArgs.serializer()
    override val examples: List<String> = listOf(
        "Remove that stop.",
        "Cancel that waypoint.",
        "Remove my first two stops.",
        "Skip the coffee stop.",
    )

    override suspend fun execute(args: RemoveWaypointArgs): Result<String> {
        val waypoints = args.waypoints.distinct()
        return service.removeWaypoints(waypoints).map { removed ->
            val total = waypoints.size
            if (removed == total) {
                mapSessionStore.forgetAddedStops(waypoints)
                "Removed $removed ${stopWord(removed)} from your route."
            } else {
                // Which of the stops came off is unknown (removeWaypoints reports a count only),
                // so clearing none beats clearing the "Added" mark on one still on the route.
                "Removed $removed of $total ${stopWord(total)} from your route."
            }
        }.also { actionLog.d { "$it" } }
    }
}
