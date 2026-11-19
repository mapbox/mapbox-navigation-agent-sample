package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.actions.DestinationArgs
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaRouteCandidate
import com.mapbox.via.kit.models.ViaRouteProfile
import com.mapbox.via.ui.navsdk.NavSdkService
import com.mapbox.via.ui.navsdk.routeSummaryLabel

internal suspend fun NavSdkService.startNavigationTo(
    destination: ViaPoint,
    profile: ViaRouteProfile,
    waypoints: List<ViaPoint> = emptyList(),
    avoidHighways: Boolean = false,
): Result<ViaRouteCandidate> = requestRouteCandidates(
    destination,
    profile,
    waypoints,
    avoidHighways,
).mapCatching { candidates ->
    val route = checkNotNull(candidates.firstOrNull()) { "No ${profile.name.lowercase()} route found to $destination." }
    startNavigation().getOrThrow()
    route
}

internal val DestinationArgs.avoidsHighways: Boolean
    get() = avoidHighways && profile == ViaRouteProfile.Driving

internal fun destinationConfirmation(
    lead: String,
    name: String,
    waypoints: List<ViaPoint>,
    route: ViaRouteCandidate?,
    avoidsHighways: Boolean = false,
): String {
    val stops = if (waypoints.isEmpty()) "" else " with ${waypoints.size} ${stopWord(waypoints.size)}"
    val avoiding = if (avoidsHighways) ", avoiding highways" else ""
    val routeSentence = route?.let { " Route: ${routeSummaryLabel(it.durationSeconds, it.distanceMeters)}." }.orEmpty()
    return "$lead $name$stops$avoiding.$routeSentence"
}
