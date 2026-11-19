package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.navsdk.NavSdkService

class ZoomInAction(private val service: NavSdkService) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_zoom_in"
    override val name: String = "Zoom in"
    override val description: String = "Zoom the navigation map camera in by one step."
    override val examples: List<String> = listOf("Zoom in.", "Get closer.")

    override suspend fun execute(): Result<String> =
        service.zoomIn().map { "Zoomed in." }
            .also { actionLog.d { "$it" } }
}
