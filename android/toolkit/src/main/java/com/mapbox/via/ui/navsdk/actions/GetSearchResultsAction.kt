package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.navsdk.distanceLabel

class GetSearchResultsAction(
    private val contextHolder: ViaContextHolder,
    private val mapSessionStore: MapSessionStore,
) : ViaNoArgsAction() {
    override val id: String = ID
    override val name: String = "Get search results"
    override val description: String =
        "Returns the latest list of search results currently displayed on the map."

    override val examples: List<String> = listOf(
        "What places are shown on the map?",
        "What are the search results?",
        "Show me the current search results.",
    )

    override suspend fun execute(): Result<String> {
        val results = mapSessionStore.state.value.pins.map { pin ->
            pin.result ?: pin.point.toSearchResult(query = pin.point.name ?: "Marker")
        }
            .ifEmpty { contextHolder.state.value.search.results }
        val output = if (results.isEmpty()) {
            "No search results available. Try searching for a place first."
        } else {
            results.mapIndexed { i, r ->
                val distance = r.distanceMeters?.let { " (${distanceLabel(it)} away)" }.orEmpty()
                "${i + 1}. ${r.name} — ${r.type.lowercase()}$distance${r.metadataSuffix()}"
            }.joinToString("\n", prefix = "Found ${results.size} search result(s):\n")
        }
        return Result.success(output).also { actionLog.d { "$it" } }
    }

    companion object {
        const val ID: String = "nav_sdk_get_search_results"
    }
}
