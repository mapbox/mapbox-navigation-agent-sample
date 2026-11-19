package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.kit.models.isNullIsland
import com.mapbox.via.ui.navsdk.LOCATION_REQUIRED_MESSAGE
import com.mapbox.via.ui.navsdk.NavSdkService

class GetUserLocationAction(private val service: NavSdkService) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_get_user_location"
    override val name: String = "Get user location"
    override val description: String =
        "Returns the current coordinates and location name of the user. " +
            "Call this only when the user asks where they are, or when Current " +
            "context has no User location. Do not call this just to search or " +
            "set a destination — copy the User location from context instead."

    override val examples: List<String> = listOf(
        "Where am I?",
        "What's my current location?",
        "What street am I on?"
    )

    override suspend fun execute(): Result<String> {
        val point = service.getUserLocation().getOrNull()?.takeUnless { it.isNullIsland() }
            ?: return Result.failure(IllegalStateException(LOCATION_REQUIRED_MESSAGE))
        val coords = "lat=${point.latitude}, lng=${point.longitude}"
        val locationName = point.name ?: "Location name not available"
        return Result.success("$locationName ($coords)").also { actionLog.d { "$it" } }
    }
}
