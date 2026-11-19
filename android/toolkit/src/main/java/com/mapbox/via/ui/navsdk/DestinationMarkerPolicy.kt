package com.mapbox.via.ui.navsdk

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.ui.ViaFrontendConstants

/**
 * Shared route-preview map chrome rules. Android Compose and iOS Swift both call this so the
 * dest-pin suppression and snap-connector gates cannot drift apart.
 *
 * Primitive lat/lng overloads exist because Kotlin/Native's ObjC exporter does not map
 * `List<ViaPoint>` cleanly; Swift call sites pass coordinates, not ViaPoint lists.
 */
object DestinationMarkerPolicy {
    fun isNear(
        latitude: Double,
        longitude: Double,
        otherLatitude: Double,
        otherLongitude: Double,
    ): Boolean = ViaPoint(latitude, longitude)
        .samePositionAs(ViaPoint(otherLatitude, otherLongitude), toleranceMeters = 15.0)

    fun shouldSuppressDestinationPin(
        destination: ViaPoint,
        searchPins: List<ViaPoint>,
    ): Boolean = searchPins.any {
        isNear(destination.latitude, destination.longitude, it.latitude, it.longitude)
    }

    fun worldMetersBetween(
        latitude: Double,
        longitude: Double,
        otherLatitude: Double,
        otherLongitude: Double,
    ): Double = metersBetween(ViaPoint(latitude, longitude), ViaPoint(otherLatitude, otherLongitude))

    fun shouldShowSnapConnector(worldMeters: Double, screenPoints: Double): Boolean =
        worldMeters <= ViaFrontendConstants.ROUTE_SNAP_CONNECTOR_MAX_OFFSET_METERS &&
            screenPoints > ViaFrontendConstants.ROUTE_SNAP_CONNECTOR_MIN_SCREEN_DISTANCE_POINTS
}
