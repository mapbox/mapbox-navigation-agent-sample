package com.mapbox.via.ui.mapsession

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.ui.navsdk.SearchPinCategory

data class MapPin(
    val id: String,
    val point: ViaPoint,
    /** Readable category label shown beneath the place name. Build it with [formatMapPinSubtitle]. */
    val subtitle: String? = null,
    /**
     * Rich search payload backing the place card. Null for pins the device never
     * saw a payload or place id for — for example, raw coordinates supplied by the agent.
     */
    val result: ViaSearchResult? = null,
)

/** Search-context results as pins, so bare markers can be re-identified against them. */
internal fun List<ViaSearchResult>.toLookupPins(): List<MapPin> = map { result ->
    MapPin(
        id = pinIdFor(result),
        point = result.coordinate.copy(
            category = result.coordinate.category ?: result.categoryIds?.firstOrNull(),
        ),
        subtitle = formatMapPinSubtitle(result.categoryIds?.firstOrNull() ?: result.coordinate.category),
        result = result,
    )
}

internal fun minimalSearchResultFor(mapboxId: String, point: ViaPoint) =
    ViaSearchResult(
        id = mapboxId,
        coordinate = point,
        address = null,
        name = point.name.orEmpty(),
        type = "poi",
        categoryIds = null,
        description = null,
        distanceMeters = null,
        isOffline = false,
        isTransient = true,
        mapboxId = mapboxId,
    )

/**
 * Search providers hand back category ids like `coffee_shop`; show them as readable labels.
 * [SearchPinCategory.MARKER] is an icon fallback, not a place type — omit it.
 */
internal fun formatMapPinSubtitle(category: String?): String? {
    val raw = category?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.takeUnless { it.equals(SearchPinCategory.MARKER, ignoreCase = true) }
        ?: return null
    return raw.split('_', '-', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.titlecase() }
        }
}
