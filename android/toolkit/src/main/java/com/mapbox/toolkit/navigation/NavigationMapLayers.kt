package com.mapbox.toolkit.navigation

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.plugin.annotation.AnnotationPlugin
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.ui.mapsession.RoutePreviewState
import com.mapbox.via.ui.mapsession.RoutePreviewStore
import com.mapbox.via.ui.navsdk.AndroidNavSdkMarkerManager
import com.mapbox.via.ui.navsdk.AndroidNavSdkService
import com.mapbox.via.ui.navsdk.DestinationMarkerPolicy
import com.mapbox.via.ui.navsdk.RouteLineStyle
import com.mapbox.via.ui.navsdk.RoutePreview
import com.mapbox.via.ui.navsdk.RoutePreviewMarkerPolicy
import com.mapbox.via.ui.navsdk.ViaPinDrawer
import com.mapbox.via.ui.navsdk.samePlace
import com.mapbox.via.ui.navsdk.searchPinSymbolOptions
import com.mapbox.via.ui.navsdk.searchPinsForRendering
import com.mapbox.via.ui.navsdk.searchPinSelectedOverlayOptions
import com.mapbox.via.ui.theme.ViaDarkColorPalette
import com.mapbox.via.ui.theme.ViaLightColorPalette
import com.mapbox.via.ui.theme.ViaDarkThemeExtras
import com.mapbox.via.ui.theme.ViaLightThemeExtras
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Draws what the Navigation SDK actions put on the map onto the UX Framework's map: agent markers, search pins, the
 * previewed destination, and the previewed route. It reuses the navigation assistant's pins, colors, and rules, drawn
 * with the annotation plugin instead of Compose map content, because the UX Framework's map is a [MapView].
 *
 * The route line and destination pin are drawn only outside guidance; during guidance the UX Framework draws the
 * active route itself.
 */
internal class NavigationMapLayers(
    private val context: Context,
    private val service: AndroidNavSdkService,
    private val markers: AndroidNavSdkMarkerManager,
    private val routePreviewStore: RoutePreviewStore,
) {
    /** Draws on [mapView] until cancelled, then removes everything it drew. */
    suspend fun drawOn(mapView: MapView): Nothing = coroutineScope {
        val isDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val layers = Layers(mapView.annotations, ViaPinDrawer(context.applicationContext, isDark), isDark)
        try {
            launch {
                combine(service.routeGeometry, service.routeLineStyle, service.isNavigating) { points, style, guiding ->
                    if (guiding) emptyList<Point>() to style else points to style
                }.collect { (points, style) -> layers.drawRouteLine(points, style) }
            }
            launch {
                combine(
                    markers.markers,
                    markers.searchPins,
                    markers.selectedMarkerFlow,
                    routePreviewStore.state,
                ) { agentMarkers, searchPins, selected, preview ->
                    MarkerState(agentMarkers, searchPins, selected, preview)
                }.collect { layers.drawMarkers(it) }
            }
            launch {
                combine(service.routePreview, service.searchPins, service.isNavigating) { preview, searchPins, guiding ->
                    preview.takeUnless { guiding } to searchPins
                }.collect { (preview, searchPins) -> layers.drawDestinationPin(preview, searchPins) }
            }
            awaitCancellation()
        } finally {
            layers.remove()
        }
    }

    private data class MarkerState(
        val agentMarkers: List<ViaPoint>,
        val searchPins: List<ViaPoint>,
        val selected: ViaPoint?,
        val preview: RoutePreviewState,
    )

    private class Layers(
        private val annotations: AnnotationPlugin,
        private val drawer: ViaPinDrawer,
        isDark: Boolean,
    ) {
        private val extras = if (isDark) ViaDarkThemeExtras else ViaLightThemeExtras
        private val textColor = (if (isDark) ViaDarkColorPalette else ViaLightColorPalette).textPrimary

        private val solidRoute: PolylineAnnotationManager = annotations.createPolylineAnnotationManager().apply {
            lineEmissiveStrength = 1.0
        }

        private val dottedRoute: PolylineAnnotationManager = annotations.createPolylineAnnotationManager().apply {
            lineCap = LineCap.ROUND
            lineEmissiveStrength = 1.0
        }

        private val destination: PointAnnotationManager = annotations.createPointAnnotationManager().apply {
            iconAllowOverlap = true
            iconIgnorePlacement = true
        }

        private val agentMarkers: PointAnnotationManager = annotations.createPointAnnotationManager()

        private val searchPins: PointAnnotationManager = annotations.createPointAnnotationManager().apply {
            iconAllowOverlap = true
            textAllowOverlap = false
            iconIgnorePlacement = false
            textIgnorePlacement = false
            iconOptional = false
            textOptional = true
        }

        private val selectedSearchPin: PointAnnotationManager = annotations.createPointAnnotationManager().apply {
            iconAllowOverlap = true
            iconIgnorePlacement = true
        }

        fun drawRouteLine(points: List<Point>, style: RouteLineStyle) {
            solidRoute.deleteAll()
            dottedRoute.deleteAll()
            if (points.size < 2) return
            when (style) {
                is RouteLineStyle.Solid -> solidRoute.create(
                    PolylineAnnotationOptions()
                        .withPoints(points)
                        .withLineColor(extras.routeFill.toArgb())
                        .withLineBorderColor(extras.routeCasing.toArgb())
                        .withLineBorderWidth(style.casingWidth)
                        .withLineWidth(style.width)
                        .withLineJoin(LineJoin.ROUND),
                )
                is RouteLineStyle.Dotted -> {
                    dottedRoute.lineDasharray = listOf(0.0, style.spacing / style.diameter)
                    dottedRoute.create(
                        PolylineAnnotationOptions()
                            .withPoints(points)
                            .withLineColor(extras.routeFill.toArgb())
                            .withLineWidth(style.diameter),
                    )
                }
            }
        }

        fun drawDestinationPin(preview: RoutePreview?, searchPins: List<ViaPoint>) {
            destination.deleteAll()
            val point = preview?.destination ?: return
            if (DestinationMarkerPolicy.shouldSuppressDestinationPin(point, searchPins)) return
            destination.create(
                PointAnnotationOptions()
                    .withPoint(Point.fromLngLat(point.longitude, point.latitude))
                    .withIconImage(drawer.pinBitmap(point.category, selected = true))
                    .withIconAnchor(IconAnchor.CENTER),
            )
        }

        fun drawMarkers(state: MarkerState) {
            val previewed = RoutePreviewMarkerPolicy.previewedDestination(state.preview)
            val visibleAgentMarkers = RoutePreviewMarkerPolicy.visibleMarkers(state.agentMarkers, previewed) { it }
            val visibleSearchPins = RoutePreviewMarkerPolicy.visibleMarkers(state.searchPins, previewed) { it }
            agentMarkers.deleteAll()
            agentMarkers.create(
                visibleAgentMarkers.mapIndexed { index, marker ->
                    val selected = state.selected?.samePlace(marker) == true
                    PointAnnotationOptions()
                        .withPoint(Point.fromLngLat(marker.longitude, marker.latitude))
                        .withIconImage(drawer.pinBitmap(marker.category, selected))
                        .withIconAnchor(IconAnchor.CENTER)
                        .apply {
                            if (selected) {
                                withTextField(marker.name ?: "#${index + 1}")
                                withTextAnchor(TextAnchor.TOP)
                                withTextOffset(listOf(0.0, AGENT_MARKER_TEXT_OFFSET_Y))
                                withTextSize(AGENT_MARKER_TEXT_SIZE)
                                withTextColor(Color.Black.toArgb())
                                withTextHaloColor(Color.White.toArgb())
                                withTextHaloWidth(2.0)
                            }
                        }
                },
            )

            searchPins.deleteAll()
            selectedSearchPin.deleteAll()
            val items = searchPinsForRendering(visibleAgentMarkers, visibleSearchPins)
            searchPins.create(
                items.map { item ->
                    searchPinSymbolOptions(
                        id = item.resultIndex.toString(),
                        pin = item.pin,
                        icon = drawer.searchPinBitmap(item.pin.category, selected = false),
                        resultIndex = item.resultIndex,
                        selected = state.selected?.samePlace(item.pin) == true,
                        textColor = textColor.toMapboxColor(),
                        textHaloColor = extras.pinTextBorder.toMapboxColor(),
                    )
                },
            )

            val selected = items.firstOrNull { state.selected?.samePlace(it.pin) == true } ?: return
            selectedSearchPin.create(
                searchPinSelectedOverlayOptions(
                    id = selected.resultIndex.toString(),
                    pin = selected.pin,
                    icon = drawer.searchPinBitmap(selected.pin.category, selected = true),
                ),
            )
        }

        fun remove() {
            listOf(solidRoute, dottedRoute, destination, agentMarkers, searchPins, selectedSearchPin)
                .forEach(annotations::removeAnnotationManager)
        }

        private fun Color.toMapboxColor(): String = "#%06X".format(toArgb() and RGB_MASK)
    }

    private companion object {
        const val RGB_MASK = 0x00FFFFFF
        const val AGENT_MARKER_TEXT_SIZE = 14.0
        const val AGENT_MARKER_TEXT_OFFSET_Y = 1.4
    }
}
