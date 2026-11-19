package com.mapbox.via.ui.place

import com.mapbox.via.kit.place.PlaceNotFoundException
import com.mapbox.via.kit.place.PlaceResolver
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.ui.navsdk.NavSdkService

class NavSdkPlaceResolver(
    private val service: NavSdkService,
) : PlaceResolver {
    /** The search already returns rich results, so resolution is a pick, not a rebuild.
     * cache=false: this is an internal resolution (e.g. resolving a home/work address), never
     * displayed, so it must not overwrite what nav_sdk_get_search_results answers. */
    override suspend fun resolve(query: String, proximity: ViaPoint?): Result<ViaSearchResult> =
        service.searchPlaces(query, proximity, cache = false).fold(
            onSuccess = { results ->
                results.firstOrNull()?.let { result ->
                    val name = result.name.ifBlank { query }
                    Result.success(result.copy(name = name, coordinate = result.coordinate.copy(name = name)))
                } ?: Result.failure(PlaceNotFoundException(query))
            },
            onFailure = { Result.failure(it) },
        )
}
