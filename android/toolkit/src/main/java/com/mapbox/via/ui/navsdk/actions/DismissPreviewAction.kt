package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.navsdk.NavSdkService

class DismissPreviewAction(
    private val service: NavSdkService,
    private val mapSessionStore: MapSessionStore,
) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_dismiss_preview"
    override val name: String = "Dismiss preview"
    override val description: String =
        "Dismiss a route preview before navigation has started and return to free drive."
    override val examples: List<String> = listOf("Dismiss the preview.", "Clear the destination.")

    override suspend fun execute(): Result<String> {
        if (mapSessionStore.state.value.activeGuidance) {
            return Result.success("Navigation is already active, so the route was left as it is.")
        }
        return service.dismissPreview().map { "Preview dismissed." }
            .also { actionLog.d { "$it" } }
    }
}
