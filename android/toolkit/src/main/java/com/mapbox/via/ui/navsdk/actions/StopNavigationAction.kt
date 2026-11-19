package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.navsdk.NavSdkService

class StopNavigationAction(
    private val service: NavSdkService,
    private val mapSessionStore: MapSessionStore,
) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_stop_navigation"
    override val name: String = "Stop navigation"
    override val description: String = "Stop active navigation and return to free drive state."
    override val examples: List<String> = listOf("Stop navigation.", "Cancel navigation.", "End the route.")

    override suspend fun execute(): Result<String> {
        service.stopVoiceGuidance()
        return service.stopNavigation().map {
            mapSessionStore.stopNavigation()
            "Navigation stopped."
        }
            .also { actionLog.d { "$it" } }
    }
}
