package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.navsdk.NavSdkService

class ZoomOutAction(private val service: NavSdkService) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_zoom_out"
    override val name: String = "Zoom out"
    override val description: String = "Zoom the navigation map camera out by one step."
    override val examples: List<String> = listOf("Zoom out.", "Show more of the map.")

    override suspend fun execute(): Result<String> =
        service.zoomOut().map { "Zoomed out." }
            .also { actionLog.d { "$it" } }
}
