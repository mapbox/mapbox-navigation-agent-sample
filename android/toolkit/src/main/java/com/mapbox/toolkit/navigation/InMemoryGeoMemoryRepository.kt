package com.mapbox.toolkit.navigation

import com.mapbox.via.kit.geomemories.GeoMemory
import com.mapbox.via.kit.geomemories.GeoMemoryRepository
import com.mapbox.via.kit.geomemories.key
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Keeps the trip and search history in memory, so it lasts only as long as this instance. */
internal class InMemoryGeoMemoryRepository : GeoMemoryRepository {
    private val memories = MutableStateFlow<Map<String, GeoMemory>>(emptyMap())

    override suspend fun record(memory: GeoMemory) {
        memories.update { it + (memory.key to memory) }
    }

    override fun getMemoriesStream(): Flow<List<GeoMemory>> =
        memories.map { it.values.sortedByDescending(GeoMemory::startedAt) }
}
