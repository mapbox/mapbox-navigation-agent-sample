package com.mapbox.toolkit.navigation

import com.mapbox.via.kit.place.HomeWorkPlacesRepository
import com.mapbox.via.kit.models.HomeWorkPlace
import com.mapbox.via.kit.models.PlaceCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Keeps the saved home and work places in memory, so they last only as long as this instance. */
internal class InMemoryHomeWorkPlacesRepository : HomeWorkPlacesRepository {
    private val places = MutableStateFlow<Map<PlaceCategory, HomeWorkPlace>>(emptyMap())

    override fun getPlaceStream(category: PlaceCategory): Flow<HomeWorkPlace?> =
        places.map { it[category] }.distinctUntilChanged()

    override fun getPlacesStream(): Flow<List<HomeWorkPlace>> = places.map { it.values.toList() }

    override suspend fun save(place: HomeWorkPlace): Result<HomeWorkPlace> {
        places.update { it + (place.category to place) }
        return Result.success(place)
    }

    override suspend fun delete(category: PlaceCategory): Result<Unit> {
        places.update { it - category }
        return Result.success(Unit)
    }
}
