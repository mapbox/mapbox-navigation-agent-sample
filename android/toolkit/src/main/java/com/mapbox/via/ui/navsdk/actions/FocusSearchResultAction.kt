package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.mapsession.MapMarkerEventSource
import com.mapbox.via.kit.actions.FocusSearchResultArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.pinIdFor
import com.mapbox.via.ui.navsdk.SearchPinCategory
import com.mapbox.via.ui.navsdk.metersBetween
import com.mapbox.via.ui.navsdk.samePositionAs
import kotlinx.serialization.KSerializer

class FocusSearchResultAction(
    private val contextHolder: ViaContextHolder,
    private val mapSessionStore: MapSessionStore,
    private val mapMarkerEventSource: MapMarkerEventSource = MapMarkerEventSource.Empty,
) : ViaAction<FocusSearchResultArgs> {
    override val id: String = ID
    override val name: String = "Focus search result"
    override val description: String =
        "Show one existing Recent search result by its 1-based resultIndex in a new inline map and " +
            "select its pin on the shared map, which opens its place card on the phone and on a " +
            "connected car display, without setting a destination or starting navigation. Use this " +
            "after choosing or recommending one result on a later follow-up turn, never on the turn " +
            "that searches. Pass the same resultIndex to any later destination or navigation action."
    override val argsSerializer: KSerializer<FocusSearchResultArgs> = FocusSearchResultArgs.serializer()
    override val examples: List<String> = listOf(
        "Center the map on the closest result.",
        "Show the recommended place on the map.",
        "Show me that one.",
    )

    override suspend fun execute(args: FocusSearchResultArgs): Result<String> = runCatching {
        val index = args.resultIndex - 1
        val indexed = contextHolder.state.value.search.indexedResults
        val result = indexed.getOrNull(index)
        if (result != null) {
            mapMarkerEventSource.publishFocused(
                result.coordinate.copy(
                    name = result.name,
                    category = result.coordinate.category
                        ?: SearchPinCategory.preferredCategory(result.categoryIds),
                ),
                result.mapboxId,
            )
            renderedPinId(result)?.let(mapSessionStore::selectPin)
            "Focused the inline map on ${result.name}."
        } else {
            // A hosted (MCP) search leaves Recent empty and pins through nav_sdk_add_map_markers, so the
            // numbered pins are the list the user heard. With Recent present, a bad index stays an error.
            val pin = mapSessionStore.state.value.pins.takeIf { indexed.isEmpty() }?.getOrNull(index)
                ?: throw IllegalArgumentException("No recent search result at index ${args.resultIndex}.")
            mapMarkerEventSource.publishFocused(pin.point, pin.result?.mapboxId)
            mapSessionStore.selectPin(pin.id)
            "Focused the inline map on ${pin.point.name ?: "the selected place"}."
        }
    }.also { actionLog.d { "$it" } }

    /**
     * The pin rendered for [result], if any: its own id, else the nearest pin at the same place,
     * because a marker placed from agent-transcribed coordinates carries only the positional id.
     */
    private fun renderedPinId(result: ViaSearchResult): String? {
        val pins = mapSessionStore.state.value.pins
        val id = pinIdFor(result)
        if (pins.any { it.id == id }) return id
        return pins
            .filter { it.point.samePositionAs(result.coordinate) }
            .minByOrNull { metersBetween(it.point, result.coordinate) }
            ?.id
    }

    companion object {
        const val ID: String = "nav_sdk_focus_search_result"
    }
}
