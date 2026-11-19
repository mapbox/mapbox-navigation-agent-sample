package com.mapbox.toolkit.navigation

import com.mapbox.geojson.BoundingBox
import com.mapbox.geojson.Point
import com.mapbox.search.ApiType
import com.mapbox.search.CategorySearchOptions
import com.mapbox.search.ForwardSearchOptions
import com.mapbox.search.ReverseGeoOptions
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.common.IsoCountryCode
import com.mapbox.search.result.SearchResult
import com.mapbox.via.kit.models.GeoBbox
import com.mapbox.via.kit.models.ViaAddress
import com.mapbox.via.kit.search.SearchBoxCoordinates
import com.mapbox.via.kit.search.SearchBoxRepository
import com.mapbox.via.kit.search.SearchBoxResult

/** Search Box lookups through the Mapbox Search SDK. */
internal class SearchSdkSearchBoxRepository : SearchBoxRepository {
    private val engine by lazy {
        SearchEngine.createSearchEngine(ApiType.SEARCH_BOX, SearchEngineSettings())
    }

    override suspend fun forward(
        query: String,
        proximity: SearchBoxCoordinates?,
        limit: Int,
        country: List<String>?,
        types: List<String>?,
        bbox: GeoBbox?,
    ): Result<List<SearchBoxResult>> {
        val options = ForwardSearchOptions.Builder()
            .limit(limit)
            .apply {
                proximity?.let { proximity(it.toPoint()) }
                country?.let { countries(it.map(::IsoCountryCode)) }
                types?.let { newTypes(it) }
                bbox?.let { boundingBox(it.toBoundingBox()) }
            }
            .build()
        return awaitSearch { engine.forward(query, options, it) }.map { it.map(SearchResult::toSearchBoxResult) }
    }

    override suspend fun category(
        categoryId: String,
        proximity: SearchBoxCoordinates?,
        limit: Int,
        country: List<String>?,
    ): Result<List<SearchBoxResult>> {
        val options = CategorySearchOptions(
            proximity = proximity?.toPoint(),
            countries = country?.map(::IsoCountryCode),
            limit = limit,
        )
        return awaitSearch { engine.search(categoryId, options, it) }.map { it.map(SearchResult::toSearchBoxResult) }
    }

    override suspend fun reverse(
        coordinates: SearchBoxCoordinates,
        types: List<String>,
    ): Result<List<SearchBoxResult>> {
        val options = ReverseGeoOptions.Builder(coordinates.toPoint()).newTypes(types).build()
        return awaitSearch { engine.search(options, it) }.map { it.map(SearchResult::toSearchBoxResult) }
    }
}

private fun SearchBoxCoordinates.toPoint(): Point = Point.fromLngLat(longitude, latitude)

private fun Point.toSearchBoxCoordinates() = SearchBoxCoordinates(latitude = latitude(), longitude = longitude())

private fun GeoBbox.toBoundingBox(): BoundingBox =
    BoundingBox.fromLngLats(minLongitude, minLatitude, maxLongitude, maxLatitude)

private fun SearchResult.toSearchBoxResult() = SearchBoxResult(
    mapboxId = mapboxId ?: id,
    name = name,
    fullAddress = fullAddress,
    address = address?.let {
        ViaAddress(
            houseNumber = it.houseNumber,
            street = it.street,
            neighborhood = it.neighborhood,
            locality = it.locality,
            postcode = it.postcode,
            place = it.place,
            district = it.district,
            region = it.region,
            country = it.country,
            countryCodeIsoAlpha2 = it.countryInfo?.isoCodeAlpha2,
        )
    },
    coordinates = coordinate.toSearchBoxCoordinates(),
    routableCoordinates = routablePoints?.firstOrNull()?.point?.toSearchBoxCoordinates(),
    featureType = newTypes.firstOrNull()?.lowercase(),
    poiCategoryIds = categoryIds.orEmpty(),
    distanceMeters = distanceMeters,
    phone = metadata?.phone,
    website = metadata?.website,
    rating = metadata?.rating?.toDouble() ?: metadata?.averageRating,
    popularity = metadata?.popularity?.toDouble(),
)
