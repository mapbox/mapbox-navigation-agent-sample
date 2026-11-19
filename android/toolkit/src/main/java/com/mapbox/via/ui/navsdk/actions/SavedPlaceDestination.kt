package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.place.HomeWorkPlacesRepository
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.context.updateSearchResults
import com.mapbox.via.kit.models.PlaceCategory
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.models.toSearchResult
import kotlinx.coroutines.flow.first

internal fun noSavedPlaceMessage(category: PlaceCategory): String =
    "The user has no ${category.displayLabel.lowercase()} address saved. " +
        "Ask them for it and save it before navigating there."

/**
 * The stored place replaces the search context, and never borrows routing hints from a nearby
 * search result the way explicit coordinates do.
 */
internal suspend fun HomeWorkPlacesRepository.resolveSavedDestination(
    category: PlaceCategory,
    contextHolder: ViaContextHolder,
): Result<ViaSearchResult> {
    val place = getPlaceStream(category).first()
        ?: return Result.failure(IllegalStateException(noSavedPlaceMessage(category)))
    val result = place.toSearchResult()
    contextHolder.updateSearchResults(listOf(result))
    return Result.success(result)
}
