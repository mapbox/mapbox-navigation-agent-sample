package com.mapbox.via.ui.mapsession

import com.mapbox.via.kit.actions.MapMarkerArgs

internal fun List<MapMarkerArgs>.mergeMapMarkers(added: List<MapMarkerArgs>): List<MapMarkerArgs> {
    val merged = toMutableList()
    for (marker in added) {
        val index = merged.indexOfFirst { it.samePlaceAs(marker) }
        if (index == -1) {
            merged += marker
        } else {
            val existing = merged[index]
            merged[index] = if (existing.placeId == null && marker.placeId != null) {
                existing.copy(
                    name = marker.name ?: existing.name,
                    category = marker.category ?: existing.category,
                    mapboxId = marker.placeId,
                )
            } else {
                existing
            }
        }
    }
    return merged
}

internal fun List<MapMarkerArgs>.sharedPlaceIds(): Set<String> =
    mapNotNull { it.placeId }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

internal fun List<MapMarkerArgs>.withoutSharedPlaceIds(): List<MapMarkerArgs> {
    val shared = sharedPlaceIds()
    return map { if (it.placeId in shared) it.copy(mapboxId = null) else it }
}

private fun MapMarkerArgs.samePlaceAs(other: MapMarkerArgs): Boolean {
    val id = placeId
    val otherId = other.placeId
    if (id != null && otherId != null) return id == otherId && toViaPoint().couldBeSamePlaceAs(other.toViaPoint())
    return toViaPoint().samePlaceAs(other.toViaPoint())
}

private val MapMarkerArgs.placeId: String?
    get() = mapboxId?.takeIf(String::isNotBlank)
