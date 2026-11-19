package com.mapbox.via.ui.screens.place

import com.mapbox.via.kit.place.OpeningHoursLookup
import com.mapbox.via.kit.place.PLACES_DETAILS_BATCH_LIMIT
import com.mapbox.via.kit.place.PlaceDetails
import com.mapbox.via.kit.place.PlacesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache over [PlacesRepository], keyed by mapboxId. No LLM in this path — the sheet
 * calls it directly on selection. Failures are cached as absent (not retried-never): a bad id
 * degrades the sheet to its thin fallback rather than surfacing an error.
 */
class PlaceDetailsStore(private val repository: PlacesRepository) : OpeningHoursLookup {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, PlaceDetails>()

    suspend fun get(mapboxId: String): PlaceDetails? {
        mutex.withLock { cache[mapboxId] }?.let { return it }
        // Only a hit is cached, so a failure degrades this call alone and the next one retries.
        val details = repository.getPlaceDetails(mapboxId).getOrNull()
        if (details != null) mutex.withLock { cache[mapboxId] = details }
        return details
    }

    /**
     * Same cache, one request for whatever isn't already in it. An id missing from the result
     * (repository failure, or absent from the batch response's `missing`/`unprocessed`) is simply
     * absent from the returned map, and — like [get] — not cached as absent, so the next call
     * retries it.
     */
    suspend fun getBatch(mapboxIds: List<String>): Map<String, PlaceDetails> {
        if (mapboxIds.isEmpty()) return emptyMap()
        val distinctIds = mapboxIds.distinct()
        val cached = mutex.withLock { distinctIds.mapNotNull { id -> cache[id]?.let { id to it } } }.toMap()
        val missingIds = distinctIds - cached.keys
        val fetched = missingIds.chunked(PLACES_DETAILS_BATCH_LIMIT).flatMap { chunk ->
            repository.getPlaceDetailsBatch(chunk).getOrNull()?.results.orEmpty()
        }
        if (fetched.isNotEmpty()) {
            mutex.withLock { fetched.forEach { cache[it.mapboxId] = it } }
        }
        return cached + fetched.associateBy { it.mapboxId }
    }

    override suspend fun hoursByMapboxId(mapboxIds: List<String>): Map<String, String> =
        getBatch(mapboxIds).mapNotNull { (id, details) -> details.openingHours?.let { id to it } }.toMap()
}

internal data class PlaceDetailsLoad(val details: PlaceDetails?, val settled: Boolean)
