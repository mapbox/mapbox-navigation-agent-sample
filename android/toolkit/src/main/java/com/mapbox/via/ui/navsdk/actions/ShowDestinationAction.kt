package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.navsdk.NavSdkService

class ShowDestinationAction(private val service: NavSdkService) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_show_destination"
    override val name: String = "Show destination"
    override val description: String = """
        Frame the map camera on just the destination point, not the whole route. Use for
        destination-only requests (e.g. "show me the destination", "where do we arrive?").
        """.trimIndent()
    override val examples: List<String> = listOf(
        "Show me the destination.",
        "Show the destination on the map.",
        "Where do we arrive?",
        "Zoom in on the destination.",
    )

    override suspend fun execute(): Result<String> =
        service.showDestinationCamera().map { "Destination camera set." }
            .also { actionLog.d { "$it" } }
}
