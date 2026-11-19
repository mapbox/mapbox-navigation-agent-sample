package com.mapbox.via.ui.navsdk

import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.ui.ViaFrontendConstants
import com.mapbox.via.ui.mapsession.MapPin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.sin

data class FramingPadding(
    val top: Double,
    val left: Double,
    val bottom: Double,
    val right: Double,
) {
    companion object {
        fun uniform(all: Double) = FramingPadding(all, all, all, all)
    }

    /**
     * [SearchPinFraming] stores paddings in points (dp). Android's Mapbox camera wants pixels;
     * iOS already works in points, so only Android scales.
     */
    fun scaledBy(density: Double) = FramingPadding(
        top = top * density,
        left = left * density,
        bottom = bottom * density,
        right = right * density,
    )
}

sealed interface SearchPinCameraPlan {
    data class Single(
        val center: ViaPoint,
        val zoom: Double,
        val bearing: Double,
        val pitch: Double,
        val padding: FramingPadding,
    ) : SearchPinCameraPlan

    data class Overview(
        val coordinates: List<ViaPoint>,
        val bearing: Double,
        val pitch: Double,
        val maxZoom: Double,
        val padding: FramingPadding,
        val zoom: Double? = null,
        val center: ViaPoint? = null,
    ) : SearchPinCameraPlan

    data object None : SearchPinCameraPlan
}

/**
 * Instant camera for the chat mini-map. Null means "do not touch the MapView yet":
 * applying a fit against an unsized / placeholder map produces a world-zoom flash
 * before the pins land.
 */
data class InlineWidgetSnap(
    val latitudes: List<Double>,
    val longitudes: List<Double>,
    val zoom: Double?,
    val maxZoom: Double,
    val bearing: Double,
    val pitch: Double,
    val padding: FramingPadding,
)

/** How much of the map is actually visible while search pins are framed. */
enum class SearchPinViewport {
    Full,
    Inline,
}

/**
 * Per-call camera framing for a pin render: which surface is being framed and, for the inline
 * widget, its measured size (dp) so the fit never depends on the live MapView's own bounds.
 * Travels with each [NavSdkService.showSearchPins] / [NavSdkService.addSearchPins] call rather
 * than living as service state: a viewport published ahead of time could be replaced by a
 * presentation change between a search starting and its pins rendering, reframing the map with
 * the other surface's geometry.
 */
data class SearchPinFramingSpec(
    val viewport: SearchPinViewport = SearchPinViewport.Full,
    val fitWidthDp: Double = 0.0,
    val fitHeightDp: Double = 0.0,
) {
    companion object {
        val Full = SearchPinFramingSpec()
    }
}

/**
 * Camera policy for framing search pins after show/add.
 *
 * Intentionally frames **pins only** by default — does not include the user-location puck in
 * the fit bounds, matching the MapGPT apps: proximity only biases search ranking, not the
 * camera. To keep the puck on-screen, union the current location into the Overview
 * coordinates or Single framing in [plan] and in the iOS/Android apply sites.
 *
 * [plan]'s optional [routeGeometry] parameter unions the active route into the fit bounds for
 * along-route search results, so the camera shows both the route and every returned POI — but
 * only the stretch of route near those POIs: [routeGeometry] is clipped to a corridor around
 * [pins] first, so a search early in a long trip doesn't drag the fit out to the far end of the
 * remaining route. A route with no pins is [None], not a route-only Overview: an empty search
 * must never steal the camera by itself, matching every other search entry point's zero-result
 * behavior.
 */
object SearchPinFraming {
    const val MARKER_SELECT_ZOOM = 16.0
    const val OVERVIEW_MAX_ZOOM = 14.0
    const val BEARING_DEGREES = 0.0
    const val PITCH_DEGREES = 0.0
    const val OVERVIEW_PADDING = 80.0

    /**
     * Zoom to apply when snapping the camera to a selected marker, or null to keep the camera
     * where it is. [MARKER_SELECT_ZOOM] acts as a floor rather than a fallback: an overview
     * camera (or the SDK's default globe camera on a freshly mounted map) sits too far out to
     * read the selected POI's surroundings, while a camera already closer than the floor is the
     * driver's own and is left alone.
     */
    fun selectedMarkerZoom(currentZoom: Double): Double? =
        MARKER_SELECT_ZOOM.takeIf { currentZoom < it }

    /**
     * A pin whose place card is open takes camera precedence after an overview frame. A selection
     * without an open card (a carousel highlight, or one carried into a refined search) must not
     * undo the all-results frame, and one outside the frame's pins was never rendered.
     */
    fun selectedPinAfterFraming(pins: List<MapPin>, selectedPinId: String?, poiSheetOpen: Boolean): MapPin? =
        if (poiSheetOpen) pins.firstOrNull { it.id == selectedPinId } else null

    fun inlineSnap(
        pins: List<ViaPoint>,
        fitWidthDp: Double,
        fitHeightDp: Double,
        focusedPin: ViaPoint? = null,
    ): InlineWidgetSnap? {
        if (fitWidthDp <= 0.0 || fitHeightDp <= 0.0) return null
        return when (
            val plan = plan(
                focusedPin?.let(::listOf) ?: pins,
                viewport = SearchPinViewport.Inline,
                fitWidthDp = fitWidthDp,
                fitHeightDp = fitHeightDp,
            )
        ) {
            is SearchPinCameraPlan.None -> null
            is SearchPinCameraPlan.Single -> InlineWidgetSnap(
                latitudes = listOf(plan.center.latitude),
                longitudes = listOf(plan.center.longitude),
                zoom = plan.zoom,
                maxZoom = OVERVIEW_MAX_ZOOM,
                bearing = plan.bearing,
                pitch = plan.pitch,
                padding = plan.padding,
            )
            is SearchPinCameraPlan.Overview -> {
                val center = plan.center
                val zoom = plan.zoom
                if (center != null && zoom != null) {
                    InlineWidgetSnap(
                        latitudes = listOf(center.latitude),
                        longitudes = listOf(center.longitude),
                        zoom = zoom,
                        maxZoom = plan.maxZoom,
                        bearing = plan.bearing,
                        pitch = plan.pitch,
                        padding = plan.padding,
                    )
                } else {
                    InlineWidgetSnap(
                        latitudes = plan.coordinates.map { it.latitude },
                        longitudes = plan.coordinates.map { it.longitude },
                        zoom = null,
                        maxZoom = plan.maxZoom,
                        bearing = plan.bearing,
                        pitch = plan.pitch,
                        padding = plan.padding,
                    )
                }
            }
        }
    }

    // Bounds how much of routeGeometry crosses into the fit: an unclipped long route is tens of
    // thousands of points, all crossing the platform bridge (boxed doubles on iOS) and running on
    // the main thread for camera-bounds math. This only bounds *cost* — corridorClip below bounds
    // *relevance*.
    private const val MAX_ROUTE_POINTS = 50

    // How far beyond the pins' own bounding box a route point may still count as "near" them —
    // wide enough to keep a short connecting stretch of road in frame, narrow enough that the
    // rest of a long remaining route (which can run for hours past the searched POIs) drops out
    // of the fit instead of forcing a city-scale zoom-out.
    private const val ROUTE_CORRIDOR_PADDING_DEGREES = 0.2

    // Mapbox GL world size at zoom 0. Used only when the inline widget's size is known so the
    // initial camera does not depend on whatever bounds the live MapView happens to have.
    private const val MERCATOR_TILE_SIZE = 512.0
    private const val DEGREES_HALF_TURN = 180.0
    private const val DEGREES_FULL_TURN = 360.0
    private const val MERCATOR_ORIGIN = 0.5
    private const val MERCATOR_LN_SCALE = 4.0
    private const val MERCATOR_MAX_SIN = 0.9999

    /** Points (dp). Android converts with [FramingPadding.scaledBy] before EdgeInsets. */
    const val INLINE_EDGE_PADDING = 16.0

    val INLINE_TOP_PADDING: Double =
        ViaFrontendConstants.FloatingButtonSize.value.toDouble() +
            ViaFrontendConstants.PaddingCompact.value.toDouble()

    val INLINE_BOTTOM_PADDING: Double = ViaFrontendConstants.POI_CAROUSEL_BAND_POINTS

    fun plan(
        pins: List<ViaPoint>,
        routeGeometry: List<ViaPoint> = emptyList(),
        viewport: SearchPinViewport = SearchPinViewport.Full,
        fitWidthDp: Double = 0.0,
        fitHeightDp: Double = 0.0,
    ): SearchPinCameraPlan {
        val padding = paddingFor(viewport)
        return when {
            pins.isEmpty() -> SearchPinCameraPlan.None
            // A route in frame always needs an Overview fit, even for a single pin — Single's
            // fixed zoom/center would crop the route out.
            pins.size == 1 && routeGeometry.isEmpty() -> SearchPinCameraPlan.Single(
                center = pins.single(),
                zoom = MARKER_SELECT_ZOOM,
                bearing = BEARING_DEGREES,
                pitch = PITCH_DEGREES,
                padding = padding,
            )
            else -> {
                val coordinates = pins + downsample(routeGeometry.nearCorridorOf(pins))
                val fitted = inlineFit(viewport, coordinates, padding, fitWidthDp, fitHeightDp)
                SearchPinCameraPlan.Overview(
                    coordinates = coordinates,
                    bearing = BEARING_DEGREES,
                    pitch = PITCH_DEGREES,
                    maxZoom = OVERVIEW_MAX_ZOOM,
                    padding = padding,
                    zoom = fitted?.first,
                    center = fitted?.second,
                )
            }
        }
    }

    private fun List<ViaPoint>.nearCorridorOf(pins: List<ViaPoint>): List<ViaPoint> {
        if (isEmpty() || pins.isEmpty()) return this
        val minLat = pins.minOf { it.latitude } - ROUTE_CORRIDOR_PADDING_DEGREES
        val maxLat = pins.maxOf { it.latitude } + ROUTE_CORRIDOR_PADDING_DEGREES
        val minLon = pins.minOf { it.longitude } - ROUTE_CORRIDOR_PADDING_DEGREES
        val maxLon = pins.maxOf { it.longitude } + ROUTE_CORRIDOR_PADDING_DEGREES
        return filter { it.latitude in minLat..maxLat && it.longitude in minLon..maxLon }
    }

    // Uniform striding does not preserve the route's extent — it keeps arbitrary interior
    // points, and the actual min/max latitude/longitude only survive by luck, which can crop
    // part of the route out of the fit. Since only the bounding box matters here, keep the
    // four extreme points instead.
    private fun downsample(points: List<ViaPoint>): List<ViaPoint> {
        if (points.size <= MAX_ROUTE_POINTS) return points
        return listOfNotNull(
            points.minByOrNull { it.latitude },
            points.maxByOrNull { it.latitude },
            points.minByOrNull { it.longitude },
            points.maxByOrNull { it.longitude },
        ).distinct()
    }

    private fun paddingFor(viewport: SearchPinViewport): FramingPadding = when (viewport) {
        SearchPinViewport.Full -> FramingPadding.uniform(OVERVIEW_PADDING)
        SearchPinViewport.Inline -> FramingPadding(
            top = INLINE_TOP_PADDING,
            left = INLINE_EDGE_PADDING,
            bottom = INLINE_BOTTOM_PADDING,
            right = INLINE_EDGE_PADDING,
        )
    }

    private fun inlineFit(
        viewport: SearchPinViewport,
        coordinates: List<ViaPoint>,
        padding: FramingPadding,
        fitWidthDp: Double,
        fitHeightDp: Double,
    ): Pair<Double, ViaPoint>? {
        val innerW = fitWidthDp - padding.left - padding.right
        val innerH = fitHeightDp - padding.top - padding.bottom
        val fittable = viewport == SearchPinViewport.Inline &&
            fitWidthDp > 0.0 && fitHeightDp > 0.0 && innerW > 0.0 && innerH > 0.0 &&
            coordinates.isNotEmpty()
        if (!fittable) return null
        val bounds = LatLngBounds.of(coordinates)
        return if (abs(bounds.maxLng - bounds.minLng) > DEGREES_HALF_TURN) {
            null
        } else {
            zoomToFit(bounds, innerW, innerH).takeIf { it >= 0.0 }?.let { zoom -> zoom to bounds.center }
        }
    }

    private data class LatLngBounds(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
        val center: ViaPoint get() = ViaPoint((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0, name = null)

        companion object {
            fun of(coordinates: List<ViaPoint>) = LatLngBounds(
                minLat = coordinates.minOf { it.latitude },
                maxLat = coordinates.maxOf { it.latitude },
                minLng = coordinates.minOf { it.longitude },
                maxLng = coordinates.maxOf { it.longitude },
            )
        }
    }

    private fun zoomToFit(bounds: LatLngBounds, innerW: Double, innerH: Double): Double {
        val dx = abs(mercatorX(bounds.maxLng) - mercatorX(bounds.minLng)).coerceAtLeast(1e-12)
        val dy = abs(mercatorY(bounds.maxLat) - mercatorY(bounds.minLat)).coerceAtLeast(1e-12)
        val zoomX = log2(innerW / (dx * MERCATOR_TILE_SIZE))
        val zoomY = log2(innerH / (dy * MERCATOR_TILE_SIZE))
        return min(min(zoomX, zoomY), OVERVIEW_MAX_ZOOM)
    }

    private fun mercatorX(lng: Double): Double = (lng + DEGREES_HALF_TURN) / DEGREES_FULL_TURN

    private fun mercatorY(lat: Double): Double {
        val sinLat = sin(lat * PI / DEGREES_HALF_TURN).coerceIn(-MERCATOR_MAX_SIN, MERCATOR_MAX_SIN)
        return MERCATOR_ORIGIN - ln((1.0 + sinLat) / (1.0 - sinLat)) / (MERCATOR_LN_SCALE * PI)
    }
}
