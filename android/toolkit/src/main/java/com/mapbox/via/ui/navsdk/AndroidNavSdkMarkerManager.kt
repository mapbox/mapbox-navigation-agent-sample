@file:Suppress("FunctionNaming")

package com.mapbox.via.ui.navsdk

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.gson.JsonPrimitive
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.annotation.IconImage
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotationGroup
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotationGroupState
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotationState
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.via.kit.logging.ViaLog
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.ui.ViaFrontendConstants
import com.mapbox.via.ui.theme.LocalViaThemeExtras
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TEXT_OFFSET_Y = 1.4
private const val TEXT_SIZE = 14.0
private val searchPinIconOffset = listOf(
    0.0,
    ViaFrontendConstants.SEARCH_PIN_SHADOW_RADIUS_POINTS +
        ViaFrontendConstants.SEARCH_PIN_SHADOW_Y_POINTS,
)

/** Two [ViaPoint]s refer to the same place when their coordinates and name match, regardless of category. */
internal fun ViaPoint.samePlace(other: ViaPoint): Boolean =
    latitude == other.latitude && longitude == other.longitude && name == other.name

internal data class SearchPinRenderItem(
    val pin: ViaPoint,
    val resultIndex: Int,
)

internal fun searchPinsForRendering(
    agentMarkers: List<ViaPoint>,
    searchPins: List<ViaPoint>,
): List<SearchPinRenderItem> = searchPins.mapIndexedNotNull { index, pin ->
    pin.takeIf { candidate -> agentMarkers.none { it.samePlace(candidate) } }
        ?.let { SearchPinRenderItem(it, index) }
}

internal data class SearchPinSymbolItem(
    val id: String,
    val pin: ViaPoint,
    val resultIndex: Int,
    val selected: Boolean,
)

@Suppress("LongParameterList") // One argument per symbol-layer property the tests assert on.
internal fun searchPinSymbolOptions(
    id: String,
    pin: ViaPoint,
    icon: Bitmap,
    resultIndex: Int,
    selected: Boolean,
    textColor: String,
    textHaloColor: String,
): PointAnnotationOptions = PointAnnotationOptions()
    .withPoint(Point.fromLngLat(pin.longitude, pin.latitude))
    .withData(JsonPrimitive(id))
    .withIconImage(icon)
    .withIconAnchor(IconAnchor.BOTTOM)
    .withIconOffset(searchPinIconOffset)
    .withSymbolSortKey(SearchPinCollisionPriority.sortKey(resultIndex, selected))
    .apply {
        SearchPinLabel.normalized(pin.name)?.let { name ->
            withTextField(name)
            withTextSize(ViaFrontendConstants.SEARCH_PIN_TEXT_SIZE_POINTS)
            withTextMaxWidth(
                ViaFrontendConstants.SEARCH_PIN_LABEL_WIDTH_POINTS /
                    ViaFrontendConstants.SEARCH_PIN_TEXT_SIZE_POINTS,
            )
            withTextAnchor(TextAnchor.TOP)
            withTextOffset(
                listOf(
                    0.0,
                    ViaFrontendConstants.SEARCH_PIN_LABEL_SPACING_POINTS /
                        ViaFrontendConstants.SEARCH_PIN_TEXT_SIZE_POINTS,
                ),
            )
            withTextColor(textColor)
            withTextHaloColor(textHaloColor)
            withTextHaloWidth(ViaFrontendConstants.SEARCH_PIN_TEXT_HALO_WIDTH_POINTS)
        }
    }

internal fun searchPinSelectedOverlayOptions(
    id: String,
    pin: ViaPoint,
    icon: Bitmap,
): PointAnnotationOptions = PointAnnotationOptions()
    .withPoint(Point.fromLngLat(pin.longitude, pin.latitude))
    .withData(JsonPrimitive(id))
    .withIconImage(icon)
    .withIconAnchor(IconAnchor.BOTTOM)
    .withIconOffset(searchPinIconOffset)

internal fun searchPinGroupState() = PointAnnotationGroupState().apply {
    iconAllowOverlap = true
    textAllowOverlap = false
    iconIgnorePlacement = false
    textIgnorePlacement = false
    iconOptional = false
    textOptional = true
}

internal fun searchPinSelectedOverlayGroupState() = PointAnnotationGroupState().apply {
    iconAllowOverlap = true
    iconIgnorePlacement = true
}

@Composable
internal fun SearchPinAnnotations(
    items: List<SearchPinSymbolItem>,
    onClick: (SearchPinSymbolItem) -> Unit,
) {
    if (items.isEmpty()) return
    val drawer = rememberViaPinDrawer()
    val textColor = MaterialTheme.colorScheme.onSurface.toMapboxColor()
    val textHaloColor = LocalViaThemeExtras.current.pinTextBorder.toMapboxColor()
    val currentOnClick by rememberUpdatedState(onClick)
    val itemsById = remember(items) { items.associateBy(SearchPinSymbolItem::id) }
    val options = remember(items, drawer, textColor, textHaloColor) {
        items.map { item ->
            searchPinSymbolOptions(
                id = item.id,
                pin = item.pin,
                icon = drawer.searchPinBitmap(item.pin.category, item.selected),
                resultIndex = item.resultIndex,
                selected = item.selected,
                textColor = textColor,
                textHaloColor = textHaloColor,
            )
        }
    }
    PointAnnotationGroup(
        annotations = options,
        onClick = { annotation ->
            annotation.getData()?.asString
                ?.let(itemsById::get)
                ?.let { item ->
                    currentOnClick(item)
                    true
                }
                ?: false
        },
        pointAnnotationGroupState = remember { searchPinGroupState() },
    )
    val selected = items.firstOrNull(SearchPinSymbolItem::selected) ?: return
    PointAnnotationGroup(
        annotations = remember(selected, drawer) {
            listOf(
                searchPinSelectedOverlayOptions(
                    id = selected.id,
                    pin = selected.pin,
                    icon = drawer.searchPinBitmap(selected.pin.category, selected = true),
                ),
            )
        },
        onClick = {
            currentOnClick(selected)
            true
        },
        pointAnnotationGroupState = remember { searchPinSelectedOverlayGroupState() },
    )
}

private const val RGB_MASK = 0x00FFFFFF

private fun Color.toMapboxColor(): String =
    String.format(Locale.ROOT, "#%06X", toArgb() and RGB_MASK)

class AndroidNavSdkMarkerManager : NavSdkMarkerService {
    private val customMarkers = MutableStateFlow<List<ViaPoint>>(emptyList())
    private val selectedMarker = MutableStateFlow<ViaPoint?>(null)
    private val _markerClicks = MutableSharedFlow<ViaPoint>(extraBufferCapacity = 1)
    override val markerClicks: Flow<ViaPoint> = _markerClicks

    /** Currently selected marker, null when nothing is selected. Collected by AndroidNavSdkService. */
    val selectedMarkerFlow: StateFlow<ViaPoint?> = selectedMarker.asStateFlow()

    /** Custom debug/agent markers currently on the map. Exposed for tests. */
    val markers: StateFlow<List<ViaPoint>> = customMarkers.asStateFlow()

    private val _searchPins = MutableStateFlow<List<ViaPoint>>(emptyList())

    /** The current search's result pins. Kept apart from agent markers so searches never clear them. */
    val searchPins: StateFlow<List<ViaPoint>> = _searchPins.asStateFlow()

    /** Replaces the previous search's pins; clears the selection if its pin just disappeared. */
    fun setSearchPins(pins: List<ViaPoint>) {
        _searchPins.value = pins
        clearSelectionIfHidden()
    }

    /** Clears the selection when its pin is no longer rendered by either marker list. */
    private fun clearSelectionIfHidden() {
        val selected = selectedMarker.value ?: return
        val stillVisible = _searchPins.value.any { it.samePlace(selected) } ||
            customMarkers.value.any { it.samePlace(selected) }
        if (!stillVisible) selectedMarker.value = null
    }

    /**
     * Appends [pins] to the current search pins, skipping places already pinned
     * ([samePlace]). Returns the count actually added.
     */
    fun addSearchPins(pins: List<ViaPoint>): Int {
        var added = 0
        _searchPins.update { current ->
            val incoming = mutableListOf<ViaPoint>()
            pins.forEach { pin ->
                val alreadyPinned = current.any { it.samePlace(pin) } || incoming.any { it.samePlace(pin) }
                if (!alreadyPinned) incoming += pin
            }
            added = incoming.size
            current + incoming
        }
        return added
    }

    /**
     * Removes the search pins matching [pins] ([samePlace]); the selection is
     * cleared if its pin disappears. Returns the count actually removed.
     */
    fun removeSearchPins(pins: List<ViaPoint>): Int {
        var removed = 0
        _searchPins.update { current ->
            val remaining = current.filterNot { existing -> pins.any { it.samePlace(existing) } }
            removed = current.size - remaining.size
            remaining
        }
        clearSelectionIfHidden()
        return removed
    }

    /** Removes every search pin; the selection is cleared if its pin disappears. */
    fun clearSearchPins() {
        setSearchPins(emptyList())
    }

    /** Selection is owned by MapSessionStore; AndroidNavSdkService pushes it here. */
    fun setSelected(marker: ViaPoint?) {
        selectedMarker.value = marker
    }

    /** Renders agent markers as circular pins and search pins as chips. */
    @Composable
    fun MarkerAnnotations(previewedDestination: ViaPoint? = null) {
        val allAgentMarkers by customMarkers.collectAsState()
        val allSearchPins by _searchPins.collectAsState()
        val selected by selectedMarker.collectAsState()
        val agentMarkers = RoutePreviewMarkerPolicy.visibleMarkers(allAgentMarkers, previewedDestination) { it }
        val currentSearchPins = RoutePreviewMarkerPolicy.visibleMarkers(allSearchPins, previewedDestination) { it }
        val drawer = rememberViaPinDrawer()
        agentMarkers.forEachIndexed { index, marker ->
            key(marker.latitude, marker.longitude, marker.name) {
                AgentMarkerAnnotation(marker, index, selected, drawer)
            }
        }
        val renderedSearchPins = searchPinsForRendering(agentMarkers, currentSearchPins)
        val searchPinSymbols = renderedSearchPins.map { item ->
            SearchPinSymbolItem(
                id = item.resultIndex.toString(),
                pin = item.pin,
                resultIndex = item.resultIndex,
                selected = selected?.samePlace(item.pin) == true,
            )
        }
        SearchPinAnnotations(searchPinSymbols) { _markerClicks.tryEmit(it.pin) }
    }

    @Composable
    private fun AgentMarkerAnnotation(
        marker: ViaPoint,
        index: Int,
        selected: ViaPoint?,
        drawer: ViaPinDrawer,
    ) {
        val label = marker.name ?: "#${index + 1}"
        val isSelected = selected?.samePlace(marker) == true
        val iconImage = remember(marker.category, isSelected, drawer) {
            IconImage(drawer.pinBitmap(marker.category, isSelected))
        }
        PointAnnotation(
            point = Point.fromLngLat(marker.longitude, marker.latitude),
            pointAnnotationState = remember(marker, index) {
                PointAnnotationState().apply {
                    iconAnchor = IconAnchor.CENTER
                    textAnchor = TextAnchor.TOP
                    textOffset = listOf(0.0, TEXT_OFFSET_Y)
                    textSize = TEXT_SIZE
                    textColor = Color.Black
                    textHaloColor = Color.White
                    textHaloWidth = 2.0
                    interactionsState.onClicked {
                        _markerClicks.tryEmit(marker)
                        true
                    }
                }
            }.also { state ->
                state.iconImage = iconImage
                state.textField = if (isSelected) label else null
            },
        )
    }

    override suspend fun addMapMarker(point: ViaPoint): Result<Unit> = runCatching {
        if (customMarkers.value.any { it.samePlace(point) }) {
            ViaLog.d { "addMapMarker: $point already present, skipping" }
            return@runCatching
        }
        customMarkers.update { it + point }
    }

    override suspend fun removeMapMarker(point: ViaPoint): Result<Unit> = runCatching {
        customMarkers.update { it.filterNot { existing -> existing.samePlace(point) } }
        clearSelectionIfHidden()
    }
}
