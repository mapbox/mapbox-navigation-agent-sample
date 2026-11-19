package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.navsdk.NavSdkService
import com.mapbox.via.ui.navsdk.NO_ACTIVE_ROUTE_ERROR_MESSAGE
import com.mapbox.via.ui.navsdk.distanceLabel

class GetNextManeuverAction(private val service: NavSdkService) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_get_next_maneuver"
    override val name: String = "Get next maneuver"
    override val description: String =
        "Returns a description of the next navigation maneuver, including the road name and distance remaining."
    override val examples: List<String> = listOf(
        "What's my next turn?",
        "What do I do next?",
        "Where do I turn?",
    )

    override suspend fun execute(): Result<String> =
        service.getNextManeuver().fold(
            onSuccess = { maneuver ->
                if (maneuver == null) {
                    Result.success("No upcoming maneuver information available.")
                } else {
                    val action = formatAction(maneuver.type, maneuver.modifier)
                    val road = maneuver.text.ifBlank { null }
                    val distance = maneuver.distanceRemaining?.let { distanceLabel(it) }
                    Result.success(buildString {
                        append("Your next maneuver is")
                        if (action != null) append(" $action")
                        if (road != null) append(" on $road")
                        if (distance != null) append(" in $distance")
                        append(".")
                    })
                }
            },
            onFailure = { error ->
                Result.success(
                    error.toNavigationActionMessage(
                        noActiveRouteMessage = NO_ACTIVE_ROUTE_ERROR_MESSAGE,
                        fallbackMessage = "I couldn't get maneuver details right now.",
                    ),
                )
            },
        ).also { actionLog.d { "$it" } }

    private fun formatAction(type: String?, modifier: String?): String? =
        when {
            type == null -> null
            modifier != null -> "$type $modifier"
            else -> type
        }

}
