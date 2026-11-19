package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.navsdk.NavSdkService

class RecenterCameraAction(
    private val service: NavSdkService,
    private val mapSessionStore: MapSessionStore,
) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_recenter_camera"
    override val name: String = "Recenter camera"
    override val description: String =
        "Recenter the navigation map camera to follow the current location. When navigation is " +
            "already active and the guidance view is missing, this restores that view and the " +
            "stops already on the trip."
    override val examples: List<String> = listOf(
        "Recenter the map.",
        "Follow my location.",
        "Show me the map.",
        "I can't see navigation.",
        "Bring the map back.",
    )

    override suspend fun execute(): Result<String> {
        if (mapSessionStore.state.value.activeGuidance) {
            service.clearSearchPins().onSuccess { mapSessionStore.setPins(emptyList()) }
        }
        return service.recenterCamera().map { "Camera recentered." }
            .also { actionLog.d { "$it" } }
    }
}
