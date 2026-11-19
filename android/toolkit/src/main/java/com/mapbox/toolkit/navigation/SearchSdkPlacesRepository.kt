package com.mapbox.toolkit.navigation

import com.mapbox.annotation.MapboxExperimental
import com.mapbox.search.AttributeSet
import com.mapbox.search.details.DetailsApi
import com.mapbox.search.details.RetrieveDetailsOptions
import com.mapbox.search.common.metadata.OpenHours
import com.mapbox.search.common.metadata.OpenPeriod
import com.mapbox.search.common.metadata.WeekTimestamp
import com.mapbox.search.result.SearchResult
import com.mapbox.via.kit.place.PLACES_DETAILS_BATCH_LIMIT
import com.mapbox.via.kit.place.PlaceAddress
import com.mapbox.via.kit.place.PlaceAttributes
import com.mapbox.via.kit.place.PlaceCoordinates
import com.mapbox.via.kit.place.PlaceDetails
import com.mapbox.via.kit.place.PlacePhoto
import com.mapbox.via.kit.place.PlaceScore
import com.mapbox.via.kit.place.PlacesDetailsBatchResult
import com.mapbox.via.kit.place.PlacesRepository

/**
 * Place details through the Mapbox Search SDK's Details API. Covers name, address, location, categories, opening
 * hours, contact details, photos, and cuisine; brand and price level are left out.
 */
internal class SearchSdkPlacesRepository : PlacesRepository {
    @OptIn(MapboxExperimental::class)
    private val detailsApi by lazy { DetailsApi.create() }

    override suspend fun getPlaceDetails(mapboxId: String): Result<PlaceDetails> =
        retrieve(listOf(mapboxId)).mapCatching { results ->
            results.firstOrNull()?.toPlaceDetails() ?: throw NoSuchElementException("No place with id $mapboxId.")
        }

    override suspend fun getPlaceDetailsBatch(mapboxIds: List<String>): Result<PlacesDetailsBatchResult> {
        if (mapboxIds.isEmpty() || mapboxIds.size > PLACES_DETAILS_BATCH_LIMIT) {
            return Result.failure(
                IllegalArgumentException("Expected 1 to $PLACES_DETAILS_BATCH_LIMIT ids, got ${mapboxIds.size}."),
            )
        }

        return retrieve(mapboxIds).map { results ->
            val details = results.map { it.toPlaceDetails() }
            val found = details.map { it.mapboxId }.toSet()
            PlacesDetailsBatchResult(results = details, missing = mapboxIds.filterNot { it in found })
        }
    }

    @OptIn(MapboxExperimental::class)
    private suspend fun retrieve(mapboxIds: List<String>): Result<List<SearchResult>> =
        awaitSearch { detailsApi.retrieveDetails(mapboxIds, DETAILS_OPTIONS, it) }

    private companion object {
        /** Without attribute sets the API returns only basic data: no hours, contact details, cuisine, or photos. */
        @OptIn(MapboxExperimental::class)
        val DETAILS_OPTIONS = RetrieveDetailsOptions(
            attributeSets = listOf(AttributeSet.BASIC, AttributeSet.VISIT, AttributeSet.VENUE, AttributeSet.PHOTOS),
        )
    }
}

private fun SearchResult.toPlaceDetails() = PlaceDetails(
    mapboxId = mapboxId ?: id,
    name = name,
    fullAddress = fullAddress,
    primaryCategory = categoryIds?.firstOrNull(),
    categories = categoryIds.orEmpty(),
    openingHours = metadata?.openHours?.describe(),
    permanentlyClosed = (metadata?.openHours is OpenHours.PermanentlyClosed).takeIf { metadata?.openHours != null },
    phone = metadata?.phone,
    website = metadata?.website,
    photos = (metadata?.primaryPhotos.orEmpty() + metadata?.otherPhotos.orEmpty()).map {
        PlacePhoto(url = it.url, width = it.width, height = it.height)
    },
    score = metadata?.popularity?.let { PlaceScore(popularity = it.toDouble()) },
    attributes = metadata?.cuisines?.firstOrNull()?.let { PlaceAttributes(cuisine = it) },
    coordinates = PlaceCoordinates(latitude = coordinate.latitude(), longitude = coordinate.longitude()),
    address = address?.let {
        PlaceAddress(
            addressLine1 = listOfNotNull(it.houseNumber, it.street).joinToString(" ").ifBlank { null },
            streetName = it.street,
            locality = it.locality,
            city = it.place,
            postcode = it.postcode,
            region = it.region,
            country = it.country,
            countryCode = it.countryInfo?.isoCodeAlpha2,
        )
    },
)

/** Opening hours as text for the assistant, such as "Monday: 7:00 AM – 3:00 PM; Tuesday: …". */
private fun OpenHours.describe(): String? = when (this) {
    is OpenHours.AlwaysOpen -> "Open 24 hours"
    is OpenHours.PermanentlyClosed -> "Permanently closed"
    is OpenHours.TemporaryClosed -> "Temporarily closed"
    is OpenHours.Scheduled -> listOfNotNull(
        weekdayText?.joinToString("; ")?.ifBlank { null } ?: periods.joinToString("; ") { it.describe() }.ifBlank { null },
        note,
    ).joinToString(". ").ifBlank { null }
    else -> null
}

private fun OpenPeriod.describe(): String = "${open.describe()} to ${closed.describe()}"

private fun WeekTimestamp.describe(): String =
    "${day.name.lowercase().replaceFirstChar { it.uppercase() }} ${"%02d:%02d".format(hour, minute)}"
