package com.mapbox.toolkit.navigation

import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.matrix.v1.MapboxMatrix
import com.mapbox.api.matrix.v1.models.MatrixResponse
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaRouteProfile
import com.mapbox.via.kit.models.profileName
import com.mapbox.via.kit.search.MatrixRepository
import com.mapbox.via.kit.search.MatrixTravelEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.coroutines.resume

/**
 * Travel times and distances through the Mapbox Matrix API, using the Mapbox Java client. Driving uses the
 * `mapbox/driving` profile, which allows an origin and 24 destinations per request (`mapbox/driving-traffic` allows
 * only 9). Walking and cycling allow the same.
 */
internal class MapboxJavaMatrixRepository : MatrixRepository {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun estimates(
        origin: ViaPoint,
        destinations: List<ViaPoint>,
        profile: ViaRouteProfile,
    ): Result<List<MatrixTravelEstimate>> = try {
        val accessToken = MapboxOptions.accessToken
        when {
            destinations.isEmpty() -> Result.success(emptyList())
            accessToken.isBlank() -> Result.failure(IllegalStateException("No Mapbox access token."))
            else -> request(accessToken, origin, destinations, profile).await().map { response ->
                val durations = response.durations()?.firstOrNull()
                val distances = response.distances()?.firstOrNull()
                destinations.indices.map { index ->
                    MatrixTravelEstimate(durations?.getOrNull(index), distances?.getOrNull(index))
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun request(
        accessToken: String,
        origin: ViaPoint,
        destinations: List<ViaPoint>,
        profile: ViaRouteProfile,
    ): MapboxMatrix =
        MapboxMatrix.builder()
            .accessToken(accessToken)
            .user(DirectionsCriteria.PROFILE_DEFAULT_USER)
            .profile(profile.profileName())
            .coordinates((listOf(origin) + destinations).map { Point.fromLngLat(it.longitude, it.latitude) })
            .sources(0)
            .destinations(*Array(destinations.size) { it + 1 })
            .addAnnotations(DirectionsCriteria.ANNOTATION_DURATION, DirectionsCriteria.ANNOTATION_DISTANCE)
            .build()
}

private suspend fun MapboxMatrix.await(): Result<MatrixResponse> = suspendCancellableCoroutine { continuation ->
    enqueueCall(
        object : Callback<MatrixResponse> {
            override fun onResponse(call: Call<MatrixResponse>, response: Response<MatrixResponse>) {
                val body = response.body()
                val result = if (response.isSuccessful && body != null) {
                    Result.success(body)
                } else {
                    Result.failure(IllegalStateException("Matrix API error: ${response.code()} ${response.message()}"))
                }
                if (continuation.isActive) continuation.resume(result)
            }

            override fun onFailure(call: Call<MatrixResponse>, t: Throwable) {
                if (continuation.isActive) continuation.resume(Result.failure(t))
            }
        },
    )
    continuation.invokeOnCancellation { cancelCall() }
}
