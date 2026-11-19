package com.mapbox.via.ui.navsdk

import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaRouteProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class AndroidRouteRequestCanceledException : Exception()

internal fun routeRequestFailureShouldNotifyUser(error: Throwable): Boolean {
    if (error is CancellationException) throw error
    return error !is AndroidRouteRequestCanceledException
}

internal fun interface AndroidRouteRequester {
    suspend fun request(
        origin: ViaPoint,
        destination: ViaPoint,
        profile: ViaRouteProfile,
        waypoints: List<ViaPoint>,
        avoidHighways: Boolean,
    ): Result<List<NavigationRoute>>
}

internal class DefaultAndroidRouteRequester(
    private val requestRoutes: (RouteOptions, NavigationRouterCallback) -> Long,
    private val cancelRouteRequest: (Long) -> Unit,
) : AndroidRouteRequester {
    constructor(mapboxNavigation: MapboxNavigation) : this(
        requestRoutes = mapboxNavigation::requestRoutes,
        cancelRouteRequest = mapboxNavigation::cancelRouteRequest,
    )

    override suspend fun request(
        origin: ViaPoint,
        destination: ViaPoint,
        profile: ViaRouteProfile,
        waypoints: List<ViaPoint>,
        avoidHighways: Boolean,
    ): Result<List<NavigationRoute>> = suspendCancellableCoroutine { continuation ->
        val options = androidRouteOptions(origin, destination, profile, waypoints, avoidHighways)
        val requestId = requestRoutes(
            options,
            object : NavigationRouterCallback {
                override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
                    if (continuation.isActive) continuation.resume(Result.success(routes))
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(IllegalStateException(reasons.joinToString())))
                    }
                }

                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(AndroidRouteRequestCanceledException()))
                    }
                }
            },
        )
        continuation.invokeOnCancellation { cancelRouteRequest(requestId) }
    }
}

internal fun androidRouteOptions(
    origin: ViaPoint,
    destination: ViaPoint,
    profile: ViaRouteProfile,
    waypoints: List<ViaPoint> = emptyList(),
    avoidHighways: Boolean = false,
): RouteOptions = RouteOptions.builder()
    .applyDefaultNavigationOptions(profile.toDirectionsProfile())
    .coordinatesList(
        (listOf(origin) + waypoints + destination).map {
            Point.fromLngLat(it.longitude, it.latitude)
        },
    )
    .alternatives(true)
    .apply { if (avoidHighways && profile == ViaRouteProfile.Driving) exclude(DirectionsCriteria.EXCLUDE_MOTORWAY) }
    .build()

private fun ViaRouteProfile.toDirectionsProfile(): String = when (this) {
    ViaRouteProfile.Driving -> DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
    ViaRouteProfile.Walking -> DirectionsCriteria.PROFILE_WALKING
    ViaRouteProfile.Cycling -> DirectionsCriteria.PROFILE_CYCLING
}
