package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.navsdk.NavSdkService

class RouteOverviewCameraAction(private val service: NavSdkService) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_route_overview_camera"
    override val name: String = "Route overview camera"
    override val description: String = """
        Set the navigation map camera to show a full overview of the route, from current location to
        destination. Use for whole-route/trip requests, not destination-only ones (use
        nav_sdk_show_destination for those).
        """.trimIndent()
    override val examples: List<String> = listOf(
        "Show the full route.",
        "Show route overview.",
        "Route overview.",
        "Show me the whole route on the map.",
        "Zoom out to see the whole route.",
    )

    override suspend fun execute(): Result<String> =
        service.routeOverviewCamera().map { "Route overview camera set." }
            .also { actionLog.d { "$it" } }
}
