package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ClearMapMarkersArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.ui.mapsession.MapPin
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.pinIdFor
import com.mapbox.via.ui.mapsession.samePlaceAs
import com.mapbox.via.ui.navsdk.NavSdkService
import kotlinx.serialization.KSerializer

class ClearMapMarkersAction(
    private val service: NavSdkService,
    private val mapSessionStore: MapSessionStore,
) : ViaAction<ClearMapMarkersArgs> {
    override val id: String = "nav_sdk_clear_map_markers"
    override val name: String = "Clear markers"
    override val description: String =
        "Remove pins previously added to the map. Pass specific markers to remove just those; " +
            "pass no markers to clear all pins."
    override val argsSerializer: KSerializer<ClearMapMarkersArgs> = ClearMapMarkersArgs.serializer()
    override val examples: List<String> = listOf("Clear the pins.", "Remove that marker from the map.")

    override suspend fun execute(args: ClearMapMarkersArgs): Result<String> {
        return if (args.markers.isEmpty()) {
            service.clearSearchPins().map {
                mapSessionStore.setPins(emptyList())
                "Map markers cleared."
            }
        } else {
            // Explicit markers arrive as transcribed coordinates, but registered pins may be
            // keyed by mapboxId — resolve by place so removal reaches search pins too.
            val registered = mapSessionStore.state.value.pins
            val pins = args.markers.map { marker ->
                registered.firstOrNull { it.point.samePlaceAs(marker) }
                    ?: MapPin(id = pinIdFor(marker), point = marker)
            }
            service.removeSearchPins(pins).map { removed ->
                pins.forEach { pin -> mapSessionStore.removePin(pin.id) }
                "Removed $removed marker(s) from the map."
            }
        }.also { actionLog.d { "$it" } }
    }
}
