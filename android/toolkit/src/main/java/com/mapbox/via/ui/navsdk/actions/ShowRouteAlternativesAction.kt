package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.navsdk.NavSdkService

internal const val SHOW_ROUTE_ALTERNATIVES_RESULT =
    "Showing the route and its alternatives on the map. The active route has not changed."

class ShowRouteAlternativesAction(private val service: NavSdkService) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_show_route_alternatives"
    override val name: String = "Route alternatives"
    override val description: String =
        "Frame the route and its alternatives on the navigation map. Camera only: this never changes " +
            "the active route. Use it when the user asks about or wants to compare the other route."
    override val examples: List<String> = listOf("Show route alternatives.", "Show me all routes.")

    override suspend fun execute(): Result<String> =
        service.showRoutesOverview().map { SHOW_ROUTE_ALTERNATIVES_RESULT }
            .also { actionLog.d { "$it" } }
}
