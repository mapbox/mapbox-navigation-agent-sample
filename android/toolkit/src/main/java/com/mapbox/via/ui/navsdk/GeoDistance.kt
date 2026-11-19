package com.mapbox.via.ui.navsdk

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle metres. Straight-line, so use it only where the provider offers no routed distance —
 * the category endpoint ignores `origin`.
 */
internal fun metersBetween(from: ViaPoint, to: ViaPoint): Double {
    val dLat = (to.latitude - from.latitude).toRadians()
    val dLng = (to.longitude - from.longitude).toRadians()
    val a = sin(dLat / 2).pow(2) +
        cos(from.latitude.toRadians()) * cos(to.latitude.toRadians()) * sin(dLng / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
}

/**
 * Same real-world place within [toleranceMeters], ignoring name — unlike
 * [com.mapbox.via.ui.mapsession.samePlaceAs], an agent-supplied destination name and a search
 * provider's name for the identical POI routinely differ, and a re-geocoded destination can be
 * snapped a few meters from where it was first set.
 */
internal fun ViaPoint.samePositionAs(other: ViaPoint, toleranceMeters: Double = 50.0): Boolean =
    metersBetween(this, other) <= toleranceMeters

internal fun ViaPoint?.isSameFixAs(other: ViaPoint?): Boolean =
    this != null && other != null && samePositionAs(other, SAME_FIX_METERS)

internal fun List<ViaSearchResult>.withDistanceFromDriver(anchor: ViaPoint?, driver: ViaPoint?): List<ViaSearchResult> {
    if (anchor == null || driver == null || anchor.isSameFixAs(driver)) return this
    return map { it.copy(distanceMeters = metersBetween(driver, it.coordinate)) }
}

/**
 * Same real-world place as [destination], for excluding it from results that would otherwise
 * suggest it back as an add-stop candidate. When both this result and [destinationMapboxId] carry
 * a Search Box id, that id is authoritative — a match holds however far apart the two coordinates
 * land (a large POI's entrances can be well over [samePositionAs]'s tolerance apart), and a
 * mismatch never falls back to proximity, so a genuinely different place that merely sits nearby
 * is not excluded. Only when an id is missing on either side (an agent-supplied raw-coordinate
 * destination, or a result from a provider that doesn't return one) does this fall back to
 * [samePositionAs].
 */
internal fun ViaSearchResult.matchesDestination(destination: ViaPoint, destinationMapboxId: String?): Boolean {
    val resultMapboxId = mapboxId
    if (destinationMapboxId != null && resultMapboxId != null) return resultMapboxId == destinationMapboxId
    return coordinate.samePositionAs(destination)
}

private fun Double.toRadians(): Double = this * PI / HALF_TURN_DEGREES

private const val EARTH_RADIUS_METERS = 6_371_000.0

private const val SAME_FIX_METERS = 100.0

private const val HALF_TURN_DEGREES = 180.0
