package com.mapbox.via.ui.mapsession

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.ui.navsdk.samePositionAs

fun pinIdFor(point: ViaPoint): String =
    listOfNotNull(point.name, point.latitude.toString(), point.longitude.toString()).joinToString("@")

/**
 * A pin's stable identity: the place id when the backend gave us one, otherwise
 * the positional fallback. Keeping this in one place stops the store and the
 * platform marker layers from deriving different ids for the same pin.
 */
fun pinIdFor(result: ViaSearchResult): String =
    result.mapboxId?.takeIf { it.isNotBlank() } ?: pinIdFor(result.coordinate)

/**
 * Positional place equality (latitude, longitude, name) — the same fields
 * AndroidNavSdkMarkerManager.samePlace matches on. Lives here so actions and
 * fakes don't re-derive the definition per platform.
 */
internal fun ViaPoint.samePlaceAs(other: ViaPoint): Boolean =
    latitude == other.latitude && longitude == other.longitude && name == other.name

internal fun ViaPoint.couldBeSamePlaceAs(other: ViaPoint): Boolean =
    samePositionAs(other) || (name != null && name.equals(other.name, ignoreCase = true))
