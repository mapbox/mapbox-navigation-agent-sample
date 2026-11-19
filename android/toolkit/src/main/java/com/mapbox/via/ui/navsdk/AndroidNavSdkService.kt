package com.mapbox.via.ui.navsdk

import android.annotation.SuppressLint
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mapbox.common.location.Location
import com.mapbox.dash.di.DashKoinContext
import com.mapbox.dash.models.DestinationSearchResult
import com.mapbox.dash.models.OriginalSearchModel
import com.mapbox.dash.models.isEqualOrCloseTo
import com.mapbox.dash.sdk.Dash
import com.mapbox.dash.sdk.base.event.DashEventBase
import com.mapbox.dash.sdk.coordination.PlaceDestination
import com.mapbox.dash.sdk.event.DashSearchRequestState
import com.mapbox.dash.sdk.event.NavigationEvent
import com.mapbox.dash.sdk.event.NavigationState
import com.mapbox.dash.sdk.event.RouteChangeEvent
import com.mapbox.dash.sdk.map.domain.usecase.BuildUpdatedWaypointsListUseCase
import com.mapbox.dash.sdk.map.domain.usecase.RemoveWaypointsUseCase
import com.mapbox.dash.sdk.search.api.DashCategorySearchOptions
import com.mapbox.dash.sdk.search.api.DashFavoriteSearchResult
import com.mapbox.dash.sdk.search.api.DashForwardOptions
import com.mapbox.dash.sdk.search.api.DashSearchResult
import com.mapbox.dash.sdk.search.api.DashSearchSuggestion
import com.mapbox.dash.sdk.search.api.SearchAlongTheRouteOptions
import com.mapbox.dash.sdk.search.domain.IsMaxWaypointsReachedUseCase
import com.mapbox.dash.sdk.search.domain.NavigateToUseCase
import com.mapbox.dash.sdk.search.domain.category.GetPointsAlongRouteUseCase
import com.mapbox.dash.sdk.search.domain.model.MaxWaypointReachedState
import com.mapbox.dash.sdk.search.domain.repository.SearchRepository
import com.mapbox.dash.state.AppStore
import com.mapbox.dash.state.Event
import com.mapbox.dash.state.NavigationState as AppNavigationState
import com.mapbox.dash.state.UiStore
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.coroutine.awaitCameraForCoordinates
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.animation.flyTo
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.api.directions.v5.models.RouteLeg
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.tripdata.maneuver.model.Maneuver
import com.mapbox.search.result.NewSearchResultType
import com.mapbox.via.core.location.SimulatedLocationSettings
import com.mapbox.via.kit.logging.ViaLog
import com.mapbox.via.kit.models.ViaAddress
import com.mapbox.via.kit.models.ViaLegStep
import com.mapbox.via.kit.context.ViaLegTraffic
import com.mapbox.via.kit.context.ViaManeuver
import com.mapbox.via.kit.actions.MAX_SEARCH_BOX_CATEGORY_LIMIT
import com.mapbox.via.kit.actions.MAX_SEARCH_BOX_FORWARD_LIMIT
import com.mapbox.via.kit.models.GeoBbox
import com.mapbox.via.kit.models.PlaceLookup
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.routingLatitude
import com.mapbox.via.kit.models.routingLongitude
import com.mapbox.via.kit.models.ViaRouteLeg
import com.mapbox.via.kit.models.ViaRouteSummary
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.models.validPopularity
import com.mapbox.via.kit.models.validRating
import com.mapbox.via.kit.context.ViaTrafficAhead
import com.mapbox.via.kit.models.ViaRouteCandidate
import com.mapbox.via.kit.models.ViaRouteProfile
import com.mapbox.via.kit.search.PARKING_CATEGORY_ID
import com.mapbox.via.kit.search.SearchBoxCoordinates
import com.mapbox.via.kit.search.SearchBoxRepository
import com.mapbox.via.kit.search.SearchBoxResult
import com.mapbox.via.kit.search.needsJapaneseSearch
import com.mapbox.via.ui.MapContentController
import com.mapbox.via.ui.mapsession.MapPin
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.selectionCameraTargets
import com.mapbox.via.ui.mapsession.RoutePreviewStore
import com.mapbox.via.ui.screens.guidance.markArrivedWhenNearDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/*
 * Headless Dash integration: the visible map is a plain Mapbox map; navigation/search/routing run
 * through Dash.controller and Dash Koin use cases initialized by Dash.init().
 */

private const val CONGESTION_LOW_THRESHOLD = 40
private const val CONGESTION_MODERATE_THRESHOLD = 60
private const val CONGESTION_HEAVY_THRESHOLD = 80
private const val MAP_READY_TIMEOUT_MS = 8_000L
private const val FLY_TO_ZOOM = 12.0
private const val FLY_TO_ANIMATION_DURATION_MS = 1_500L
private const val DESTINATION_CAMERA_ZOOM = 17.0
private const val CAMERA_ANIMATION_DURATION_MS = 300L
private const val ROUTE_OVERVIEW_PADDING_PX = 80.0
private const val GEOMETRY_PRECISION = 6

// This class is the whole headless-Dash surface: ~25 NavSdkService methods plus the 14 Dash
// observers.
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
@SuppressLint("RestrictedApi")
internal class AndroidNavSdkService(
    private val mapContentController: MapContentController,
    private val mapSessionStore: MapSessionStore,
    private val routePreviewStore: RoutePreviewStore,
    private val simulatedLocationSettings: SimulatedLocationSettings,
    private val searchBoxRepository: SearchBoxRepository,
    private val markerManager: AndroidNavSdkMarkerManager = AndroidNavSdkMarkerManager(),
    private val routeRequesterProvider: () -> AndroidRouteRequester = {
        DefaultAndroidRouteRequester(DashKoinContext.getKoin().get())
    },
) : NavSdkService {
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventsJob: Job? = null

    private val uiStore: UiStore?
        get() = runCatching { DashKoinContext.getKoin().get<UiStore>() }.getOrNull()

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val userMessages: Flow<String> = _userMessages.asSharedFlow()

    private val _dashLocation = MutableStateFlow<ViaPoint?>(null)
    private val _userLocation = MutableStateFlow<ViaPoint?>(null)

    /** Effective user position (simulated when enabled, else last Dash/GPS fix). */
    override val userLocation: StateFlow<ViaPoint?> = _userLocation.asStateFlow()

    @Volatile
    private var destinationPoint: ViaPoint? = null
    private var routeWaypoints: List<ViaPoint> = emptyList()
    private val _maneuver = MutableStateFlow<ViaManeuver?>(null)

    /** Next turn while guidance is active, for the banner over the map. */
    val maneuver: StateFlow<ViaManeuver?> = _maneuver.asStateFlow()

    private val _guidanceProgress = MutableStateFlow<GuidanceProgress?>(null)

    /** Remaining time and distance, so the destination card counts down instead of going stale. */
    override val guidanceProgress: StateFlow<GuidanceProgress?> = _guidanceProgress.asStateFlow()

    private var nextManeuver: ViaManeuver?
        get() = _maneuver.value
        set(value) { _maneuver.value = value }
    // Written from observeSearchResults() (Dispatchers.Main) and searchPlaces() (the caller's
    // dispatcher) — @Volatile for visibility, same pattern as previewRoutes/selectedRouteIndex
    // below. Both writers replace wholesale (never a read-then-write), so a plain @Volatile is
    // enough; there's no lost-update to guard against.
    @Volatile
    private var searchResults: List<ViaSearchResult> = emptyList()
    private var routeProgress: RouteProgress? = null
    private val routeRequester: AndroidRouteRequester by lazy(routeRequesterProvider)

    @Volatile
    private var previewRoutes: List<NavigationRoute> = emptyList()

    /** Index into [previewRoutes] of the candidate the sheet has highlighted, or Navigate will start. */
    @Volatile
    private var selectedRouteIndex: Int = 0

    private val _routeGeometry = MutableStateFlow<List<Point>>(emptyList())
    private val _routeLineStyle = MutableStateFlow(RouteLineStyle.Driving)

    private val _isNavigating = MutableStateFlow(false)

    /** True while Dash reports ActiveGuidance, so the map can follow the puck. */
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _routePreview = MutableStateFlow<RoutePreview?>(null)

    /** Destination and travel estimates for the previewed or active route, for the shell to show. */
    val routePreview: StateFlow<RoutePreview?> = _routePreview.asStateFlow()

    /**
     * Points of the active or previewed route. With Dash running headless, the Compose map
     * renders it from here.
     */
    val routeGeometry: StateFlow<List<Point>> = _routeGeometry.asStateFlow()

    val routeLineStyle: StateFlow<RouteLineStyle> = _routeLineStyle.asStateFlow()

    val searchPins: StateFlow<List<ViaPoint>> = markerManager.searchPins

    private val _mapCameraPadding = MutableStateFlow(EdgeInsets(0.0, 0.0, 0.0, 0.0))

    /**
     * Padding for the part of the map the chat drawer covers. Typed as [EdgeInsets] so camera moves
     * and the map ornaments share one value rather than each rebuilding it, and keeps the Mapbox
     * logo and attribution above the drawer, which have to stay visible.
     */
    val mapCameraPadding: StateFlow<EdgeInsets> = _mapCameraPadding.asStateFlow()

    /**
     * Height in pixels of the chat drawer covering the bottom of the map, so camera framing keeps
     * content above it. Callers must update this only once the drawer settles — updating during a
     * drag makes the camera thrash.
     */
    fun setMapCameraPadding(padding: EdgeInsets) {
        _mapCameraPadding.value = padding
    }

    private val poiSheetCameraBand = MutableStateFlow(0.0)

    /**
     * Height in pixels of the POI sheet's Half-detent band. The selection fly pads by this rather
     * than the drawer padding: selecting a pin presents the sheet, and the sheet collapses the
     * drawer — so the drawer value is near zero exactly when the bottom half of the map is covered.
     * The shell reports it at layout time and clears it on dispose. When the sheet is not
     * mounted the value stays zero and the fly centers plainly.
     */
    fun setPoiSheetCameraBand(px: Double) {
        poiSheetCameraBand.value = px
    }

    private val poiCarouselCameraBand = MutableStateFlow(0.0)

    /**
     * Height in pixels of the POI card carousel band riding above the drawer. Search-pin framing
     * adds it to the drawer's own padding rather than taking the larger of the two: the band sits
     * *above* whatever the drawer covers, so together they hide more of the map than either does.
     */
    fun setPoiCarouselCameraBand(px: Double) {
        poiCarouselCameraBand.value = px
    }

    private val guidanceResultsCameraPadding = MutableStateFlow<EdgeInsets?>(null)

    /**
     * What the guidance search results cover, reported by the guidance screen for as long as it is
     * up rather than by the results themselves: a search frames its pins before they compose.
     * Replaces the drawer padding and carousel band for search framing while set.
     */
    fun setGuidanceResultsCameraPadding(padding: EdgeInsets?) {
        guidanceResultsCameraPadding.value = padding
    }

    private val routeSheetCameraBand = MutableStateFlow(0.0)

    /**
     * Height in pixels of the Routes sheet's band, reported only while it is actually on screen.
     * Unlike [poiSheetCameraBand], [fitRouteOnMap] can run with no sheet up at all (e.g. a preview
     * fetch that fails and falls back to a plain destination set), so this must track real
     * visibility rather than layout presence.
     */
    fun setRouteSheetCameraBand(px: Double) {
        routeSheetCameraBand.value = px
    }

    private val mapAttached = MutableStateFlow(false)
    private val guidanceFragmentAttached = MutableStateFlow(false)
    private val enhancedLocationSeen = MutableStateFlow(false)
    private val mapboxMap = MutableStateFlow<MapboxMap?>(null)

    // Either surface counts: the shell's Compose map during discovery and preview, or Dash's
    // fragment during guidance. Gating on the Compose map alone would fail every call mid-drive.
    private val routingReady = combine(
        mapAttached,
        guidanceFragmentAttached,
        enhancedLocationSeen,
    ) { mapUp, fragmentUp, located ->
        (mapUp || fragmentUp) && located
    }

    /** Dash's guidance fragment owns the screen during turn-by-turn. */
    fun setGuidanceFragmentAttached(attached: Boolean) {
        guidanceFragmentAttached.value = attached
    }

    @Suppress("FunctionNaming")
    @Composable
    fun MarkerAnnotations() {
        val routePreview by routePreviewStore.state.collectAsState()
        markerManager.MarkerAnnotations(
            previewedDestination = RoutePreviewMarkerPolicy.previewedDestination(routePreview),
        )
    }

    /**
     * The Compose map coming and going says nothing about whether a trip is running — handing the
     * screen to the guidance fragment disposes this map mid-drive. Nothing here tears the session
     * down: Compose disposes the outgoing subtree before the incoming one registers, so any guard
     * based on "is the fragment up yet" is false exactly when it matters, and stopping the session
     * there ends the trip Dash just started. Location keeps flowing regardless of which surface is
     * on screen, so [enhancedLocationSeen] stays set too.
     */
    fun attachMap(map: MapboxMap?) {
        ensureDashObserversStarted()
        mapboxMap.value = map
        mapAttached.value = map != null
        if (map != null) startTripSession()
    }

    /**
     * Dash normally starts its trip session from its own map view model, which only exists while
     * DashNavigationFragment is alive. Running headless there is nobody to do it, so no location
     * ever arrives and every call gated on [routingReady] times out. Start it ourselves, using the
     * same token-based API Dash uses internally.
     */
    /** Everything the map and sheet draw for a trip. Cleared whenever the trip ends. */
    private fun clearGuidanceState() {
        previewRoutes = emptyList()
        selectedRouteIndex = 0
        routeProgress = null
        publishRouteLine(null)
        _routePreview.value = null
        _guidanceProgress.value = null
        _maneuver.value = null
        destinationPoint = null
        routeWaypoints = emptyList()
    }

    /**
     * Ends the Dash trip session. Owned by the shell's lifetime rather than the map's: the Compose
     * map is disposed whenever guidance takes the screen, and stopping there ends the trip.
     */
    fun stopTripSession() {
        runCatching { Dash.controller.stopTripSession() }
            .onFailure { ViaLog.e(it) { "Could not stop Dash trip session" } }
    }

    private fun startTripSession() {
        Dash.controller.startTripSession()
            .onFailure { ViaLog.e(it) { "Could not start Dash trip session" } }
    }

    override suspend fun addMapMarker(point: ViaPoint): Result<Unit> =
        markerManager.addMapMarker(point).also { result ->
            if (result.isSuccess) mapContentController.markMapContentPresent()
        }

    override suspend fun removeMapMarker(point: ViaPoint): Result<Unit> =
        markerManager.removeMapMarker(point)

    override val markerClicks: Flow<ViaPoint> = markerManager.markerClicks

    @Volatile
    private var dashObserversStarted = false

    /** Attaches Dash.controller observers once; requires [Dash.init] to have completed. */
    private fun ensureDashObserversStarted() {
        if (dashObserversStarted) return
        synchronized(this) {
            if (dashObserversStarted) return
            startDashObservers()
            dashObserversStarted = true
        }
    }

    private fun startDashObservers() {
        eventsJob?.cancel()
        eventsJob = observeGenericEvents().launchIn(observerScope)
        observeRouteWaypoints().launchIn(observerScope)
        observeNavigationState().launchIn(observerScope)
        observeRoutes().launchIn(observerScope)
        observeRouteProgress().launchIn(observerScope)
        observeRawLocation().launchIn(observerScope)
        observeLocationMatcherResult().launchIn(observerScope)
        observeEffectiveUserLocation().launchIn(observerScope)
        observeNextManeuver().launchIn(observerScope)
        observeNavigationEvents().launchIn(observerScope)
        observeHistory().launchIn(observerScope)
        observeFavorites().launchIn(observerScope)
        observeSearchSuggestions().launchIn(observerScope)
        observeSearchResults().launchIn(observerScope)
        observeSearchRequestState().launchIn(observerScope)
        observerScope.launch {
            // Selection is owned by MapSessionStore (MarkerClickSelectionSync writes it from
            // markerClicks); this follower keeps the marker manager's rendered selection in sync.
            mapSessionStore.state
                .map { it.selectedPin?.point }
                .distinctUntilChanged()
                .collect { markerManager.setSelected(it) }
        }
        observerScope.launch {
            // collectLatest so a newer selection cancels an in-flight move.
            mapSessionStore.state.selectionCameraTargets().collectLatest { point ->
                snapToSelectedMarker(point, animated = true)
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun observeGenericEvents(): Flow<DashEventBase> =
        Dash.controller.observeGenericEvents()

    @SuppressLint("RestrictedApi")
    private fun observeRouteWaypoints(): Flow<List<DashSearchResult>> =
        Dash.controller.observeRouteWaypoints()

    @SuppressLint("RestrictedApi")
    private fun observeNavigationState(): Flow<NavigationState> =
        Dash.controller.observeNavigationState()
            .onEach { state ->
                ViaLog.d { "Dash navigation state: $state" }
                val nowNavigating = state == NavigationState.ActiveGuidance
                val wasNavigating = _isNavigating.value
                _isNavigating.value = nowNavigating
                when {
                    // Dash is the source of truth: guidance can start or end from its own UI, not
                    // just from our actions, and the shell has to follow either way.
                    nowNavigating -> mapSessionStore.startActiveGuidance()
                    wasNavigating -> {
                        mapSessionStore.stopNavigation()
                        clearGuidanceState()
                    }
                }
            }

    @SuppressLint("RestrictedApi")
    private fun observeRoutes(): Flow<RouteChangeEvent> =
        Dash.controller.observeRoutes().onEach { event ->
            // Keyed off the sheet's selection, not index 0: an event landing while an alternate is
            // selected (Dash repopulating its route set, a reroute) must not revert the drawn line
            // and camera to the recommended route out from under the user's pick.
            event.routes.getOrNull(selectedRouteIndex)?.let { publishRouteLine(it) }
        }

    @SuppressLint("RestrictedApi")
    private fun observeRouteProgress(): Flow<RouteProgress> =
        Dash.controller.observeRouteProgress().onEach { progress ->
            routeProgress = progress
            _guidanceProgress.value = GuidanceProgress(
                durationRemainingSeconds = progress.durationRemaining,
                distanceRemainingMeters = progress.distanceRemaining.toDouble(),
                observedAt = Clock.System.now(),
            )
            markArrivedWhenNearDestination(
                mapSessionStore = mapSessionStore,
                remainingDistanceMeters = progress.distanceRemaining.toDouble(),
            )
        }

    @SuppressLint("RestrictedApi")
    private fun observeNavigationEvents(): Flow<NavigationEvent> =
        Dash.controller.observeNavigationEvents().onEach { event ->
            ViaLog.d { "Dash navigation event: $event" }
        }

    @SuppressLint("RestrictedApi")
    private fun observeRawLocation(): Flow<Location> =
        Dash.controller.observeRawLocation().onEach {
            _dashLocation.value = ViaPoint(it.latitude, it.longitude)
        }

    @SuppressLint("RestrictedApi")
    private fun observeLocationMatcherResult(): Flow<LocationMatcherResult> =
        Dash.controller.observeLocationMatcherResult().onEach {
            _dashLocation.value = ViaPoint(it.enhancedLocation.latitude, it.enhancedLocation.longitude)
            enhancedLocationSeen.value = true
        }

    /**
     * Publishes [userLocation] from simulation when enabled, else Dash GPS. Simulation feeds only
     * the puck, camera, and [getUserLocation]; Dash still routes from its own real fix, so this
     * deliberately leaves [enhancedLocationSeen] alone. Forcing it here would open [routingReady]
     * for an origin routing never uses — [Dash.controller.fetchRoutes] would then fail opaquely
     * instead of surfacing the actionable "location is off" timeout — and it would latch on with
     * no path back to false when simulation is turned off.
     */
    private fun observeEffectiveUserLocation(): Flow<ViaPoint?> =
        combine(
            simulatedLocationSettings.enabled,
            simulatedLocationSettings.effectivePoint,
            _dashLocation,
        ) { enabled, simulated, dash ->
            effectiveUserLocation(enabled, simulated, dash)
        }.onEach { _userLocation.value = it }

    @SuppressLint("RestrictedApi")
    private fun observeNextManeuver(): Flow<Maneuver?> =
        Dash.controller.observeNextManeuver().onEach { maneuver ->
            nextManeuver = maneuver?.let {
                ViaManeuver(
                    type = it.primary.type,
                    modifier = it.primary.modifier,
                    text = it.primary.text,
                    distanceRemaining = it.stepDistance.distanceRemaining,
                )
            }
        }

    @SuppressLint("RestrictedApi")
    private fun observeHistory(): Flow<List<DashSearchResult>> =
        Dash.controller.observeHistory()

    @SuppressLint("RestrictedApi")
    private fun observeFavorites(): Flow<List<DashFavoriteSearchResult>> =
        Dash.controller.observeFavorites()

    @SuppressLint("RestrictedApi")
    private fun observeSearchSuggestions(): Flow<List<DashSearchSuggestion>> =
        Dash.controller.observeSearchSuggestions()

    @SuppressLint("RestrictedApi")
    private fun observeSearchResults(): Flow<List<DashSearchResult>> =
        Dash.controller.observeSearchResults().onEach { results ->
            searchResults = results.map { it.toViaSearchResult() }
        }

    @SuppressLint("RestrictedApi")
    private fun observeSearchRequestState(): Flow<DashSearchRequestState> =
        Dash.controller.observeSearchRequestState()

    private suspend fun <T> whenMapReady(block: suspend () -> Result<T>): Result<T> {
        ensureDashObserversStarted()
        mapContentController.markMapContentPresent()
        return try {
            withTimeout(MAP_READY_TIMEOUT_MS) { routingReady.first { it } }
            block()
        } catch (timeout: TimeoutCancellationException) {
            val message = "Couldn't get your current location. Make sure location is on and try again."
            _userMessages.tryEmit(message)
            Result.failure(IllegalStateException(message, timeout))
        }
    }

    private suspend fun withMap(block: suspend (MapboxMap) -> Unit): Result<Unit> {
        val map = mapboxMap.value
            ?: return Result.failure(IllegalStateException("Map not ready"))
        return runCatching { withContext(Dispatchers.Main) { block(map) } }
            .onFailure { emitUserMessage(it, "Navigation unavailable.") }
    }

    override suspend fun recenterCamera(): Result<Unit> = whenMapReady {
        withMap { map ->
            val location = _userLocation.value
            if (location == null) {
                error("No location fix yet.")
            }
            val cameraOptions = CameraOptions.Builder()
                .center(location.toPoint())
                .zoom(map.cameraState.zoom)
                .build()
            val animationOptions = MapAnimationOptions.Builder()
                .duration(CAMERA_ANIMATION_DURATION_MS)
                .build()
            map.easeTo(cameraOptions, animationOptions)
        }
    }

    override suspend fun routeOverviewCamera(): Result<Unit> = whenMapReady {
        fitRouteOnMap()
    }

    override suspend fun showDestinationCamera(): Result<Unit> = whenMapReady {
        withMap { map ->
            val destination = checkNotNull(destinationPoint) { "No route set. Set a destination first." }
            val cameraOptions = CameraOptions.Builder()
                .center(destination.toPoint())
                .zoom(DESTINATION_CAMERA_ZOOM)
                .padding(_mapCameraPadding.value)
                .build()
            val animationOptions = MapAnimationOptions.Builder()
                .duration(CAMERA_ANIMATION_DURATION_MS)
                .build()
            map.easeTo(cameraOptions, animationOptions)
        }
    }

    /** Framing follows rendering: the camera fits only the pins the map actually draws. */
    private fun visibleDuringPreview(points: List<ViaPoint>): List<ViaPoint> = RoutePreviewMarkerPolicy.visibleMarkers(
        points,
        RoutePreviewMarkerPolicy.previewedDestination(routePreviewStore.state.value),
    ) { it }

    private fun visiblePinsDuringPreview(pins: List<MapPin>): List<MapPin> = RoutePreviewMarkerPolicy.visibleMarkers(
        pins,
        RoutePreviewMarkerPolicy.previewedDestination(routePreviewStore.state.value),
    ) { it.point }

    private var cameraBeforePreview: CameraOptions? = null

    // Cleared first: a preview left without a restore must not feed its stale snapshot to a later dismiss.
    override suspend fun rememberCameraBeforePreview(): Result<Unit> {
        cameraBeforePreview = null
        return withMap { map ->
            val state = map.cameraState
            cameraBeforePreview = CameraOptions.Builder()
                .center(state.center)
                .zoom(state.zoom)
                .bearing(state.bearing)
                .pitch(state.pitch)
                .build()
        }
    }

    override suspend fun snapToSearchPin(point: ViaPoint): Result<Unit> {
        val zoom = cameraBeforePreview?.zoom
        cameraBeforePreview = null
        return runCatching { snapToSelectedMarker(point, zoom = zoom, animated = true) }
    }

    override suspend fun frameAllSearchPins(): Result<Unit> {
        val pins = visibleDuringPreview(markerManager.searchPins.value)
        if (pins.isNotEmpty()) frameSearchPins(pins, includeActiveRoute = false, SearchPinFramingSpec.Full)
        return Result.success(Unit)
    }

    /**
     * Fits the current guidance search again, route included, with the padding the results drawer
     * is reporting now. The first frame runs while the drawer is still collapsed.
     */
    internal suspend fun reframeGuidanceSearchPins() {
        val pins = visibleDuringPreview(markerManager.searchPins.value)
        if (pins.isNotEmpty()) frameSearchPins(pins, includeActiveRoute = true, SearchPinFramingSpec.Full)
    }

    /** Padding comes from the live drawer, not the snapshot, so it does not undo [resetRouteCameraPadding]. */
    override suspend fun restoreCameraAfterPreview(): Result<Unit> {
        val camera = cameraBeforePreview
            ?: return Result.failure(IllegalStateException("No remembered camera"))
        cameraBeforePreview = null
        return withMap { map ->
            val cameraOptions = camera.toBuilder().padding(_mapCameraPadding.value).build()
            val animationOptions = MapAnimationOptions.Builder()
                .duration(CAMERA_ANIMATION_DURATION_MS)
                .build()
            map.easeTo(cameraOptions, animationOptions)
        }
    }

    override suspend fun zoomIn(): Result<Unit> = whenMapReady {
        adjustZoom(delta = 1.0)
    }

    override suspend fun zoomOut(): Result<Unit> = whenMapReady {
        adjustZoom(delta = -1.0)
    }

    override suspend fun setDestination(point: ViaPoint): Result<Unit> = whenMapReady {
        fetchAndApplyRoutes(point, "Could not set destination.")
            .onSuccess { fitRouteOnMap() }
            .map { }
    }

    /** Fetches routes for [destination], commits them as the preview candidates, and reports readiness. */
    private suspend fun fetchAndApplyRoutes(
        destination: ViaPoint,
        failureMessage: String,
        profile: ViaRouteProfile = ViaRouteProfile.Driving,
        waypoints: List<ViaPoint> = emptyList(),
        avoidHighways: Boolean = false,
    ): Result<List<NavigationRoute>> =
        runCatching {
            // Dash's fetchRoutes takes no route options, so only a plain drive can use it.
            val excludesMotorways = avoidHighways && profile == ViaRouteProfile.Driving
            when (profile) {
                ViaRouteProfile.Driving if waypoints.isEmpty() && !excludesMotorways ->
                    Dash.controller.fetchRoutes(destination.toPlaceDestination()).getOrThrow()
                else -> {
                    val origin = _userLocation.value ?: error("Current location is unavailable.")
                    routeRequester.request(origin, destination, profile, waypoints, avoidHighways).getOrThrow()
                }
            }
        }
            .onSuccess { routes ->
                destinationPoint = destination
                routeWaypoints = waypoints
                applyRouteCandidates(routes)
                mapContentController.markMapContentPresent()
            }
            .onFailure { error ->
                if (routeRequestFailureShouldNotifyUser(error)) {
                    emitUserMessage(error, failureMessage)
                }
            }

    /** Replaces the candidate set and (re-)selects the first one, mirroring a fresh fetch or re-fetch. */
    private fun applyRouteCandidates(routes: List<NavigationRoute>) {
        previewRoutes = routes
        selectedRouteIndex = 0
        applySelectedRoutePreview()
    }

    private fun publishRouteLine(route: NavigationRoute?) {
        _routeGeometry.value = route.toGeometryPoints()
        _routeLineStyle.value = RouteLineStyle.forProfile(route?.directionsRoute?.routeOptions()?.profile())
    }

    /** Publishes the currently selected candidate's geometry and destination-card summary. */
    private fun applySelectedRoutePreview() {
        val route = previewRoutes.getOrNull(selectedRouteIndex)
        publishRouteLine(route)
        val destination = destinationPoint
        _routePreview.value = if (route != null && destination != null) {
            RoutePreview(
                destination = destination,
                durationSeconds = route.directionsRoute.duration(),
                distanceMeters = route.directionsRoute.distance(),
            )
        } else {
            null
        }
    }

    /**
     * Snap to the selected pin, padded for the POI sheet's Half detent. Pitch and bearing stay at
     * the search camera; zoom is floored at POI level by
     * [SearchPinFraming.selectedMarkerZoom] so a pin tapped from a search overview (or from the
     * default globe camera a history-restored conversation re-mounts the map at) doesn't strand
     * the POI in a neighborhood-wide view. Bottom padding is the taller of the drawer (the
     * guidance results while they report one) and the sheet band. A map-ready timeout is dropped:
     * selection came from a live map, so a snap that lost a race with detach has nothing to tell
     * the user.
     */
    private suspend fun snapToSelectedMarker(point: ViaPoint, zoom: Double? = null, animated: Boolean = false) {
        mapContentController.markMapContentPresent()
        val map = withTimeoutOrNull(MAP_READY_TIMEOUT_MS) { mapboxMap.onMapReady() } ?: return
        val chrome = guidanceResultsCameraPadding.value ?: _mapCameraPadding.value
        val padding = EdgeInsets(
            chrome.top,
            chrome.left,
            maxOf(chrome.bottom, poiSheetCameraBand.value),
            chrome.right,
        )
        val targetZoom = zoom ?: SearchPinFraming.selectedMarkerZoom(map.cameraState.zoom)
        val cameraOptions = CameraOptions.Builder()
            .center(point.toPoint())
            .padding(padding)
            .apply { targetZoom?.let { zoom(it) } }
            .build()
        withContext(Dispatchers.Main) {
            if (animated) {
                val animationOptions = MapAnimationOptions.Builder().duration(CAMERA_ANIMATION_DURATION_MS).build()
                map.easeTo(cameraOptions, animationOptions)
            } else {
                map.setCamera(cameraOptions)
            }
        }
    }

    override suspend fun flyCameraTo(point: ViaPoint, zoom: Double?): Result<Unit> {
        ensureDashObserversStarted()
        mapContentController.markMapContentPresent()
        return try {
            val map = withTimeout(MAP_READY_TIMEOUT_MS) { mapboxMap.onMapReady() }
            val cameraOptions = CameraOptions.Builder()
                .center(point.toPoint())
                .zoom(zoom ?: FLY_TO_ZOOM)
                .padding(_mapCameraPadding.value)
                .build()
            val animationOptions = MapAnimationOptions.Builder()
                .duration(FLY_TO_ANIMATION_DURATION_MS)
                .build()
            withContext(Dispatchers.Main) { map.flyTo(cameraOptions, animationOptions) }
            Result.success(Unit)
        } catch (timeout: TimeoutCancellationException) {
            val message = "Map is not ready yet."
            _userMessages.tryEmit(message)
            Result.failure(IllegalStateException(message, timeout))
        }
    }

    override suspend fun navigateTo(point: ViaPoint, waypoints: List<ViaPoint>): Result<ViaRouteCandidate?> {
        // fetchAndApplyRoutes skips the preview fit; setDestination would zoom out.
        return whenMapReady {
            fetchAndApplyRoutes(point, "Could not set destination.", waypoints = waypoints)
                .mapCatching { routes ->
                    startNavigation().getOrThrow()
                    routes.first().toViaRouteCandidate(0)
                }
        }
    }

    override suspend fun showRoutesOverview(): Result<Unit> {
        if (previewRoutes.isEmpty()) {
            val message = "No route set. Set a destination first."
            _userMessages.tryEmit(message)
            return Result.failure(IllegalStateException(message))
        }
        return whenMapReady { fitRouteOnMap() }
    }

    override suspend fun requestRouteCandidates(
        destination: ViaPoint,
        profile: ViaRouteProfile,
        waypoints: List<ViaPoint>,
        avoidHighways: Boolean,
    ): Result<List<ViaRouteCandidate>> {
        return whenMapReady {
            fetchAndApplyRoutes(destination, "Could not load routes.", profile, waypoints, avoidHighways)
                .map { routes -> routes.mapIndexed { index, route -> route.toViaRouteCandidate(index) } }
        }
    }

    override suspend fun selectRouteCandidate(id: String): Result<Unit> {
        val index = id.toIntOrNull()?.takeIf { it in previewRoutes.indices }
        if (index == null) {
            val message = "That route is no longer available."
            _userMessages.tryEmit(message)
            return Result.failure(IllegalStateException(message))
        }
        if (index != selectedRouteIndex) {
            selectedRouteIndex = index
            applySelectedRoutePreview()
            // Best-effort: two candidates can share an origin/destination but bow out along a
            // different street, so re-fit to the newly selected shape or the detour stays offscreen.
            // A fit failure (e.g. map torn down mid-selection) doesn't fail the selection's own
            // Result, though withMap still surfaces its own "Navigation unavailable." message.
            fitRouteOnMap()
        }
        return Result.success(Unit)
    }

    override suspend fun dismissPreview(): Result<Unit> {
        ensureDashObserversStarted()
        clearGuidanceState()
        resetRouteCameraPadding()
        return runCatching { Dash.controller.resetNavigationState().getOrThrow() }
            .onFailure { emitUserMessage(it, "Could not dismiss preview.") }
    }

    /**
     * [recenterCamera] and [adjustZoom] build [CameraOptions] with no padding of their own, so they
     * inherit whatever inset [fitRouteOnMap] last committed. Ease back to the drawer's own padding
     * so that inset doesn't outlive the preview it was framing.
     */
    private suspend fun resetRouteCameraPadding() {
        val map = mapboxMap.value ?: return
        val cameraOptions = CameraOptions.Builder().padding(_mapCameraPadding.value).build()
        val animationOptions = MapAnimationOptions.Builder()
            .duration(CAMERA_ANIMATION_DURATION_MS)
            .build()
        withContext(Dispatchers.Main) { map.easeTo(cameraOptions, animationOptions) }
    }

    override suspend fun startNavigation(): Result<Unit> = whenMapReady {
        val destination = destinationPoint
        val route = previewRoutes.getOrNull(selectedRouteIndex)
        if (route == null || destination == null) {
            val message = "No route set. Set a destination first."
            _userMessages.tryEmit(message)
            return@whenMapReady Result.failure(IllegalStateException(message))
        }
        runCatching { Dash.controller.startNavigation(route).getOrThrow() }
            .onSuccess {
                // Dash.controller.startNavigation(route) sets routes but never records the
                // destination in the app-scoped AppStore, and DashNavigationFragment's maneuver
                // banner renders only when a destination waypoint is there. Mirror the SDK's own
                // flows (ActiveRoutesManager.process), which emit OnRoutesFound right after
                // routes are set. NavigateToUseCase would handle this, but it needs the UiStore,
                // which is scoped to the guidance fragment — and that fragment mounts only after
                // navigation starts.
                runCatching {
                    appStore.onEvent(
                        Event.AppEvent.OnRoutesFound(
                            waypoints = (routeWaypoints + destination).map { it.toDestinationSearchResult() },
                            routes = listOf(route),
                            stateOfCharges = emptyList(),
                        ),
                    )
                }.onFailure { error -> ViaLog.e(error) { "Could not register destination in Dash's AppStore" } }
                mapContentController.markMapContentPresent()
                ViaLog.d {
                    "startNavigation ok; appStore navigationState=" +
                        "${runCatching { appStore.currentData.navigationState }.getOrNull()}"
                }
            }
            .onFailure { emitUserMessage(it, "Could not start navigation.") }
    }

    override suspend fun stopNavigation(): Result<Unit> {
        ensureDashObserversStarted()
        if (appStore.currentData.navigationState == AppNavigationState.FreeDrive) {
            val message = "Navigation is not active."
            _userMessages.tryEmit(message)
            return Result.failure(IllegalStateException(message))
        }

        return runCatching { Dash.controller.stopNavigation().getOrThrow() }
            .onSuccess { clearGuidanceState() }
            .onFailure { emitUserMessage(it, "Could not stop navigation.") }
    }

    override suspend fun stopActiveTrip(): Result<Unit> {
        ensureDashObserversStarted()
        if (!_isNavigating.value) return Result.success(Unit)
        return stopNavigation()
    }

    private val appStore: AppStore by lazy { DashKoinContext.getKoin().get() }
    private val isMaxWaypointsReached: IsMaxWaypointsReachedUseCase by lazy { DashKoinContext.getKoin().get() }
    private val removeWaypointsUseCase: RemoveWaypointsUseCase by lazy { DashKoinContext.getKoin().get() }
    private val buildUpdatedWaypointsList: BuildUpdatedWaypointsListUseCase by lazy { DashKoinContext.getKoin().get() }
    private val navigateToUseCase: NavigateToUseCase by lazy { DashKoinContext.getKoin().get() }
    private val searchRepository: SearchRepository by lazy { DashKoinContext.getKoin().get() }

    // Stateless wrapper around the Nav SDK's own SearchAlongRouteUtils.selectPoints — no Dash
    // dependencies to inject, so no Koin lookup needed.
    private val getPointsAlongRoute = GetPointsAlongRouteUseCase()

    override suspend fun addWaypoints(points: List<ViaPoint>): Result<Int> = whenMapReady {
        val store = uiStore
        when {
            points.isEmpty() -> Result.success(0)
            store == null -> runCatching { error("Navigation unavailable.") }
                .onFailure { emitUserMessage(it, "Could not add waypoint(s).") }
            appStore.currentData.navigationState == AppNavigationState.FreeDrive -> {
                val message = "To add waypoints start navigation first."
                _userMessages.tryEmit(message)
                Result.failure(IllegalStateException(message))
            }
            else -> addWaypointsActiveGuidance(store, points)
        }
    }

    private suspend fun addWaypointsActiveGuidance(store: UiStore, points: List<ViaPoint>): Result<Int> {
        val results = points.map { addWaypointActiveGuidance(store, it) }
        val added = results.count { it.isSuccess }
        return if (added > 0) {
            Result.success(added)
        } else {
            val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: IllegalStateException("Could not add waypoint(s).")
            Result.failure<Int>(failure).also { emitUserMessage(failure, "Could not add waypoint(s).") }
        }
    }

    private suspend fun addWaypointActiveGuidance(store: UiStore, point: ViaPoint): Result<Unit> {
        val waypoint = point.toDestinationSearchResult()
        val existingWaypoints = store.data.value.tripState.waypoints
        return runCatching {
            check(!existingWaypoints.any { it.isEqualOrCloseTo(waypoint) }) {
                "That location is already on your route."
            }

            val maxWaypointState = isMaxWaypointsReached(store).value
            if (maxWaypointState is MaxWaypointReachedState.Reached) {
                error("Waypoint limit reached (max ${maxWaypointState.maxWaypointCount}).")
            }

            val newWaypoints = buildUpdatedWaypointsList(uiStore = store, newWaypoint = waypoint)
            check(newWaypoints.isNotEmpty()) { "Could not add waypoint to the current route." }
            navigateToUseCase(
                uiStore = store,
                destination = newWaypoints.last(),
                waypoints = newWaypoints.dropLast(1),
            ).getOrThrow()
        }
    }

    override suspend fun searchPlaces(
        query: String,
        proximity: ViaPoint?,
        limit: Int,
        // Dash.controller.forward exposes no feature-type filter, so the hint is dropped here
        // unless the search goes to Search Box HTTP, which can send types.
        lookup: PlaceLookup,
        cache: Boolean,
        bbox: GeoBbox?,
    ): Result<List<ViaSearchResult>> {
        // Dash sends the phone's language and no country, so Search Box's Japan index never answers it.
        // Only that case falls back to the user's location; a bbox retry keeps its own anchor.
        val anchor = proximity ?: _userLocation.value
        val routeToJapanIndex = bbox == null && needsJapaneseSearch(query, anchor)
        if (bbox != null || routeToJapanIndex) {
            return searchBoxForward(query, if (routeToJapanIndex) anchor else proximity, limit, lookup, cache, bbox)
                // A fixed message: Search Box's error text can carry the HTTP body or the token URL.
                .onFailure { error ->
                    ViaLog.e(error) { "Search Box forward failed for '$query'" }
                    if (routeToJapanIndex) _userMessages.tryEmit("Search failed.")
                }
        }
        ensureDashObserversStarted()
        val proximityPoint = proximity?.toPoint()
        ViaLog.i { "Search API forward: query='$query' limit=$limit" }
        // Coordinates and resolved places are payload data; keep off info.
        ViaLog.d { "Search API forward: proximity=${proximity?.let { "${it.latitude},${it.longitude}" }}" }
        return runCatching {
            // Dash.controller.forward exposes no limit (its internal default is 10);
            // results come back rank-ordered, so a client-side cap returns exactly
            // what a server-side limit would.
            Dash.controller.forward(query, proximity = proximityPoint)
                .take(limit.coerceAtLeast(1))
                .map { it.toViaSearchResult() }
        }.onSuccess { results ->
            // A silent lookup (cache=false) must not pollute nav_sdk_get_search_results — same
            // contract searchCategory/searchAlongRoute already have; see NavSdkService.kt's kdoc.
            if (cache) searchResults = results
            ViaLog.i { "Search API forward: ${results.size} result(s) for '$query'" }
            ViaLog.d {
                "Search API forward results: " +
                    results.joinToString {
                        "${it.name} (${it.coordinate.latitude},${it.coordinate.longitude}) " +
                            "category=${it.coordinate.category}"
                    }
            }
        }.onFailure { emitUserMessage(it, "Search failed.") }
    }

    @Suppress("LongParameterList")
    private suspend fun searchBoxForward(
        query: String,
        proximity: ViaPoint?,
        limit: Int,
        lookup: PlaceLookup,
        cache: Boolean,
        bbox: GeoBbox?,
    ): Result<List<ViaSearchResult>> {
        ViaLog.i { "Search Box forward: query='$query' limit=$limit bbox=${bbox != null}" }
        return searchBoxRepository.forward(
            query = query,
            proximity = proximity?.let { SearchBoxCoordinates(it.latitude, it.longitude) },
            limit = limit.coerceIn(1, MAX_SEARCH_BOX_FORWARD_LIMIT),
            types = if (lookup == PlaceLookup.ADDRESS) listOf("address") else null,
            bbox = bbox,
        ).map { hits ->
            hits.map { it.toViaSearchResult(lookup) }.also { results ->
                if (cache) searchResults = results
                ViaLog.i { "Search Box forward: ${results.size} result(s) for '$query'" }
            }
        }
    }

    override suspend fun rememberSearchResults(results: List<ViaSearchResult>) {
        searchResults = results
    }

    override suspend fun searchCategory(
        categoryId: String,
        displayName: String,
        proximity: ViaPoint?,
        limit: Int,
    ): Result<List<ViaSearchResult>> {
        ensureDashObserversStarted()
        val proximityPoint = proximity?.toPoint()
        val session = mapSessionStore.state.value
        val destination = session.destination
        // Requests one extra when a destination is active so filtering it back out below can't
        // shrink the reply below the caller's requested count.
        val fetchLimit = if (destination != null) limit + 1 else limit
        // Origin stays on the driver even when proximity anchors ranking elsewhere, or the drive
        // time is measured from the area rather than from the car.
        val options = DashCategorySearchOptions(
            proximity = proximityPoint,
            origin = _userLocation.value?.toPoint() ?: proximityPoint,
            limit = fetchLimit,
        )
        ViaLog.i { "Search API category: id='$categoryId' displayName='$displayName'" }
        // Coordinates and resolved places are payload data; keep off info.
        ViaLog.d { "Search API category: proximity=${proximity?.let { "${it.latitude},${it.longitude}" }}" }
        return runCatching {
            categorySearch(categoryId, displayName, options)
                // The active destination can legitimately match the category (e.g. an airport
                // category search while navigating to one), but suggesting it back as an
                // add-stop candidate for the trip already going there is nonsensical.
                .filterNot { destination != null && it.matchesDestination(destination, session.destinationMapboxId) }
                .take(limit)
        }.onSuccess { results ->
            ViaLog.i { "Search API category: ${results.size} result(s) for '$displayName'" }
            ViaLog.d {
                "Search API category results: " +
                    results.joinToString { "${it.name} (${it.coordinate.latitude},${it.coordinate.longitude})" }
            }
        }.onFailure { emitUserMessage(it, "Category search failed.") }
    }

    private suspend fun categorySearch(
        categoryId: String,
        displayName: String,
        options: DashCategorySearchOptions,
    ): List<ViaSearchResult> = if (categoryId == PARKING_CATEGORY_ID) {
        searchBoxCategory(categoryId, options)
    } else {
        searchRepository.categorySearch(categoryId, displayName, options).getOrThrow()
            .map { it.toViaSearchResult() }
            .ifEmpty {
                ViaLog.i { "Search API category: Dash returned no '$categoryId' results; retrying on Search Box" }
                searchBoxCategory(categoryId, options)
            }
    }

    private suspend fun searchBoxCategory(
        categoryId: String,
        options: DashCategorySearchOptions,
    ): List<ViaSearchResult> = searchBoxRepository.category(
        categoryId = categoryId,
        proximity = (options.proximity ?: options.origin)
            ?.let { SearchBoxCoordinates(it.latitude(), it.longitude()) },
        limit = (options.limit ?: MAX_SEARCH_BOX_CATEGORY_LIMIT).coerceIn(1, MAX_SEARCH_BOX_CATEGORY_LIMIT),
    ).getOrThrow().map { it.toViaSearchResult(PlaceLookup.ANY) }.withDistanceFromDriver(
        anchor = options.proximity?.toViaPoint(),
        driver = options.origin?.toViaPoint(),
    )

    // DashCategorySearchOptions.searchAlongTheRouteOptions runs the category search against a
    // corridor of points sampled from the route, the category counterpart to searchAlongRoute's
    // DashForwardOptions.searchAlongTheRouteOptions below.
    override suspend fun searchCategoryAlongRoute(
        categoryId: String,
        displayName: String,
        limit: Int,
    ): Result<List<ViaSearchResult>> {
        ensureDashObserversStarted()
        ViaLog.i { "Search API category along-route: id='$categoryId' displayName='$displayName' limit=$limit" }
        return runCatching {
            val routePoints = pointsAlongActiveRoute()
            check(routePoints.isNotEmpty()) {
                "No active route to search along. Start navigation or preview a route first."
            }
            val session = mapSessionStore.state.value
            val destination = session.destination
            val fetchLimit = if (destination != null) limit + 1 else limit
            val options = DashCategorySearchOptions(
                origin = _userLocation.value?.toPoint(),
                limit = fetchLimit,
                searchAlongTheRouteOptions = SearchAlongTheRouteOptions(routePoints = routePoints),
            )
            searchRepository.categorySearch(categoryId, displayName, options).getOrThrow()
                .map { it.toViaSearchResult() }
                .filterNot { destination != null && it.matchesDestination(destination, session.destinationMapboxId) }
                .take(limit)
        }.onSuccess { results ->
            ViaLog.i { "Search API category along-route: ${results.size} result(s) for '$displayName'" }
            ViaLog.d {
                "Search API category along-route results: " +
                    results.joinToString { "${it.name} (${it.coordinate.latitude},${it.coordinate.longitude})" }
            }
        }.onFailure { emitUserMessage(it, "Category search along the route failed.") }
    }

    // DashForwardOptions.searchAlongTheRouteOptions runs the query against a corridor of points
    // sampled from the route (one server-side search, not one per sampled point).
    override suspend fun searchAlongRoute(query: String, limit: Int): Result<List<ViaSearchResult>> {
        ensureDashObserversStarted()
        ViaLog.i { "Search API along-route forward: query='$query' limit=$limit" }
        return runCatching {
            val routePoints = pointsAlongActiveRoute()
            check(routePoints.isNotEmpty()) {
                "No active route to search along. Start navigation or preview a route first."
            }
            // The destination is trivially "along the route" (it's the last point on it), but
            // suggesting it as an add-stop candidate for a trip already going there is
            // nonsensical — fetch one extra so a match can be dropped without shrinking the reply.
            val destination = mapSessionStore.state.value.destination
            val fetchLimit = if (destination != null) limit + 1 else limit
            val options = DashForwardOptions(
                origin = _userLocation.value?.toPoint(),
                limit = fetchLimit,
                searchAlongTheRouteOptions = SearchAlongTheRouteOptions(routePoints = routePoints),
            )
            searchRepository.forward(query, options).getOrThrow().map { it.toViaSearchResult() }
                .filterNot { destination != null && it.coordinate.samePositionAs(destination) }
                .take(limit)
        }.onSuccess { results ->
            // showOnMap: false is an action-layer decision this service method never sees, so
            // never assign searchResults here — matching searchCategory — or a silent probe
            // could overwrite what nav_sdk_get_search_results/"the second one" resolve against.
            ViaLog.i { "Search API along-route forward: ${results.size} result(s) for '$query'" }
            ViaLog.d {
                "Search API along-route forward results: " +
                    results.joinToString { "${it.name} (${it.coordinate.latitude},${it.coordinate.longitude})" }
            }
        }.onFailure { emitUserMessage(it, "Search along the route failed.") }
    }

    /** Prefers the remaining route ahead of the driver; falls back to a previewed, unstarted route. */
    private fun pointsAlongActiveRoute(): List<Point> {
        val progress = routeProgress
        val previewRoute = previewRoutes.getOrNull(selectedRouteIndex)?.directionsRoute
        return when {
            progress != null -> getPointsAlongRoute(progress)
            previewRoute != null -> getPointsAlongRoute(previewRoute)
            else -> emptyList()
        }
    }

    /**
     * Best-effort: the given search results render as search-pin chips on the headless map,
     * replacing the previous search's pins. Never fails the caller. The pins are exactly the
     * results the caller passes (typically what was returned to the LLM), so no second search
     * runs.
     *
     * [includeActiveRoute] keeps the active/previewed route in the camera fit alongside [pins].
     */
    override suspend fun showSearchPins(
        pins: List<MapPin>,
        includeActiveRoute: Boolean,
        framing: SearchPinFramingSpec,
    ): Result<Unit> {
        ensureDashObserversStarted()
        val points = pins.map { it.point }
        markerManager.setSearchPins(points)
        if (points.isEmpty()) return Result.success(Unit)
        requestMapForSearchPins()
        val visiblePins = visiblePinsDuringPreview(pins)
        frameSearchPins(visiblePins.map { it.point }, includeActiveRoute, framing)
        // Selection framing must be last: opening details from an inline card selects before this
        // asynchronous overview frame, which would otherwise zoom back out.
        val session = mapSessionStore.state.value
        SearchPinFraming.selectedPinAfterFraming(visiblePins, session.selectedPinId, session.poiSheetOpen)
            ?.let { snapToSelectedMarker(it.point) }
        return Result.success(Unit)
    }

    /**
     * Snaps the camera to frame [pins] per [SearchPinFraming], merging live drawer padding unless
     * the inline widget is showing — that viewport is too small to also reserve the chat drawer.
     * Pins only — does not include the user puck; see [SearchPinFraming].
     * [includeActiveRoute] unions [pointsAlongActiveRoute] into the fit bounds — the same
     * remaining-route-ahead-of-the-driver geometry the along-route search itself queried against,
     * not the full origin-to-destination polyline, so the two halves of the feature agree.
     */
    private suspend fun frameSearchPins(
        pins: List<ViaPoint>,
        includeActiveRoute: Boolean,
        framing: SearchPinFramingSpec,
    ) {
        val routeGeometry = if (includeActiveRoute) pointsAlongActiveRoute().map { it.toViaPoint() } else emptyList()
        val plan = SearchPinFraming.plan(
            pins,
            routeGeometry,
            viewport = framing.viewport,
            fitWidthDp = framing.fitWidthDp,
            fitHeightDp = framing.fitHeightDp,
        )
        if (plan is SearchPinCameraPlan.None) return
        runCatching {
            withContext(Dispatchers.Main) {
                val map = withTimeout(MAP_READY_TIMEOUT_MS) { mapboxMap.onMapReady() }
                val drawer = _mapCameraPadding.value
                when (plan) {
                    is SearchPinCameraPlan.None -> return@withContext
                    is SearchPinCameraPlan.Single -> {
                        val camera = CameraOptions.Builder()
                            .center(plan.center.toPoint())
                            .zoom(plan.zoom)
                            .bearing(plan.bearing)
                            .pitch(plan.pitch)
                            .padding(plan.padding.forCamera(drawer, framing.viewport))
                            .build()
                        map.setCamera(camera)
                    }
                    is SearchPinCameraPlan.Overview -> {
                        val padding = plan.padding.forCamera(drawer, framing.viewport)
                        if (plan.zoom != null && plan.center != null) {
                            val camera = CameraOptions.Builder()
                                .center(plan.center.toPoint())
                                .zoom(plan.zoom)
                                .bearing(plan.bearing)
                                .pitch(plan.pitch)
                                .padding(padding)
                                .build()
                            map.setCamera(camera)
                        } else {
                            val camera = map.awaitCameraForCoordinates(
                                coordinates = plan.coordinates.map { it.toPoint() },
                                camera = CameraOptions.Builder()
                                    .padding(padding)
                                    .bearing(plan.bearing)
                                    .pitch(plan.pitch)
                                    .build(),
                                coordinatesPadding = null,
                                maxZoom = plan.maxZoom,
                                offset = null,
                            )
                            if (camera.center != null) map.setCamera(camera)
                        }
                    }
                }
            }
        }.onFailure { ViaLog.e(it) { "Failed to frame search pins" } }
    }

    /**
     * The policy's padding keeps the pins off the map's edges; this keeps them out from behind the
     * chat chrome: the drawer, plus the carousel band floating above it. Matches the same sum iOS
     * applies in ViaMapViewController.frameSearchPins. The inline widget has no drawer, so its
     * [SearchPinFraming] padding is used as-is.
     */
    private fun FramingPadding.forCamera(drawer: EdgeInsets, viewport: SearchPinViewport): EdgeInsets =
        if (viewport == SearchPinViewport.Inline) {
            val pixels = scaledBy(Resources.getSystem().displayMetrics.density.toDouble())
            EdgeInsets(pixels.top, pixels.left, pixels.bottom, pixels.right)
        } else {
            mergedWithChrome(drawer)
        }

    private fun FramingPadding.mergedWithChrome(drawer: EdgeInsets): EdgeInsets {
        val guidance = guidanceResultsCameraPadding.value
        val chrome = guidance ?: drawer
        val bottomChrome = guidance?.bottom ?: (drawer.bottom + poiCarouselCameraBand.value)
        return EdgeInsets(top + chrome.top, left + chrome.left, bottom + bottomChrome, right + chrome.right)
    }

    override suspend fun addSearchPins(pins: List<MapPin>, framing: SearchPinFramingSpec): Result<Int> {
        ensureDashObserversStarted()
        val added = markerManager.addSearchPins(pins.map { it.point })
        if (added > 0) {
            requestMapForSearchPins()
            frameSearchPins(visibleDuringPreview(markerManager.searchPins.value), includeActiveRoute = false, framing)
        }
        return Result.success(added)
    }

    /** Mounts the map out of sight so pins and camera framing are ready when the chat map expands. */
    private fun requestMapForSearchPins() {
        mapContentController.requestMapMount()
    }

    override suspend fun removeSearchPins(pins: List<MapPin>): Result<Int> {
        val removed = markerManager.removeSearchPins(pins.map { it.point })
        clearMapContentIfEmpty()
        return Result.success(removed)
    }

    override suspend fun clearSearchPins(): Result<Unit> {
        markerManager.clearSearchPins()
        clearMapContentIfEmpty()
        return Result.success(Unit)
    }

    /** Returns the shell to chat-only once nothing map-bearing remains: no pins, markers, route, or preview. */
    private fun clearMapContentIfEmpty() {
        val hasContent = markerManager.searchPins.value.isNotEmpty() ||
            markerManager.markers.value.isNotEmpty() ||
            _routeGeometry.value.isNotEmpty() ||
            _routePreview.value != null
        if (!hasContent) mapContentController.clearMapContent()
    }

    override suspend fun getUserLocation(): Result<ViaPoint?> = Result.success(_userLocation.value)

    override suspend fun getNextManeuver(): Result<ViaManeuver?> {
        ensureDashObserversStarted()
        if (appStore.currentData.navigationState == AppNavigationState.FreeDrive) {
            return Result.failure(NoActiveRouteException())
        }
        return Result.success(nextManeuver)
    }

    override suspend fun getSearchResults(): Result<List<ViaSearchResult>> = Result.success(searchResults)

    override suspend fun getTrafficAhead(): Result<ViaTrafficAhead> {
        ensureDashObserversStarted()
        if (appStore.currentData.navigationState == AppNavigationState.FreeDrive) {
            return Result.failure(NoActiveRouteException())
        }
        return Result.success(computeTrafficAhead())
    }

    private fun computeTrafficAhead(): ViaTrafficAhead {
        val progress = routeProgress ?: return ViaTrafficAhead(emptyList())
        return progress.route.legs()?.let { legs ->
            val legProgress = progress.currentLegProgress
            val legIndex = legProgress?.legIndex ?: 0
            val geometryIndex = legProgress?.geometryIndex ?: 0
            val legTraffic = legs.mapIndexedNotNull { index, leg ->
                if (index < legIndex) return@mapIndexedNotNull null
                val congestion = leg.annotation()?.congestion()?.takeIf { it.isNotEmpty() }
                    ?: leg.annotation()?.congestionNumeric()?.map { it.toCongestionLevel() }.orEmpty()
                val remaining = if (index == legIndex) congestion.drop(geometryIndex) else congestion
                ViaLegTraffic(
                    sdkOverallCongestionLevel = null,
                    segmentCongestionLevels = remaining,
                )
            }
            ViaTrafficAhead(legTraffic)
        } ?: ViaTrafficAhead(emptyList())
    }

    override suspend fun getRouteSummary(): Result<ViaRouteSummary> {
        ensureDashObserversStarted()
        if (appStore.currentData.navigationState == AppNavigationState.FreeDrive) {
            return Result.failure(NoActiveRouteException())
        }
        return routeProgress?.let { progress ->
            val route = progress.route
            val legs = route.legs().orEmpty()
            val legProgress = progress.currentLegProgress
            val legIndex = legProgress?.legIndex ?: 0
            val remainingLegs = legs.drop(legIndex)
            Result.success(
                ViaRouteSummary(
                    totalDistanceMeters = route.distance(),
                    totalDurationSeconds = route.duration(),
                    totalDurationTypicalSeconds = route.durationTypical() ?: 0.0,
                    remainingDistanceMeters = progress.distanceRemaining.toDouble(),
                    remainingDurationSeconds = progress.durationRemaining,
                    remainingDurationTypicalSeconds = remainingTypicalSeconds(remainingLegs, legProgress),
                    remainingLegs = remainingLegs.map { it.toViaRouteLeg() },
                )
            )
        } ?: Result.failure(IllegalStateException("Route progress not available yet."))
    }

    /**
     * Typical (uncongested) time left over [remainingLegs], scaling the current leg by how much
     * of it is still ahead. 0.0 when any leg lacks the typical-duration annotation, so callers
     * can tell "unknown" from "no delay".
     */
    private fun remainingTypicalSeconds(remainingLegs: List<RouteLeg>, legProgress: RouteLegProgress?): Double {
        var sum = 0.0
        remainingLegs.forEachIndexed { offset, leg ->
            val legTypical = leg.durationTypical() ?: return 0.0
            val legDuration = leg.duration()
            val isCurrentLeg = offset == 0 && legProgress != null && legDuration != null && legDuration > 0.0
            sum += if (isCurrentLeg) legTypical * (legProgress.durationRemaining / legDuration) else legTypical
        }
        return sum
    }

    private fun RouteLeg.toViaRouteLeg() = ViaRouteLeg(
        summary = summary(),
        distanceMeters = distance() ?: 0.0,
        durationSeconds = duration() ?: 0.0,
        durationTypicalSeconds = durationTypical() ?: 0.0,
        steps = steps().orEmpty().map { step ->
            ViaLegStep(
                name = step.name()?.takeIf { it.isNotBlank() },
                distanceMeters = step.distance(),
            )
        },
    )

    override suspend fun removeWaypoints(points: List<ViaPoint>): Result<Int> = whenMapReady {
        val store = uiStore
        when {
            points.isEmpty() -> Result.success(0)
            store == null -> runCatching { error("Navigation unavailable.") }
                .onFailure { emitUserMessage(it, "Could not remove waypoint(s).") }
            appStore.currentData.navigationState == AppNavigationState.FreeDrive -> {
                val message = "Removing waypoints only work during a navigation session."
                _userMessages.tryEmit(message)
                Result.failure(IllegalStateException(message))
            }
            else -> {
                val indices = points.mapNotNull { point ->
                    val waypoint = point.toDestinationSearchResult()
                    store.data.value.tripState.indexOf(waypoint).takeIf { it >= 0 }
                }.toSet()
                runCatching {
                    check(indices.isNotEmpty()) { "None of those waypoints are on your route." }
                    check(removeWaypointsUseCase(store, indices)) { "Could not remove waypoint(s)." }
                    indices.size
                }.onFailure { emitUserMessage(it, "Could not remove waypoint(s).") }
            }
        }
    }

    private suspend fun adjustZoom(delta: Double): Result<Unit> = withMap { map ->
        val targetZoom = (map.cameraState.zoom + delta).coerceIn(0.0, 22.0)
        val cameraOptions = CameraOptions.Builder()
            .zoom(targetZoom)
            .center(map.cameraState.center)
            .build()
        val animationOptions = MapAnimationOptions.Builder()
            .duration(CAMERA_ANIMATION_DURATION_MS)
            .build()
        map.easeTo(cameraOptions, animationOptions)
    }

    private suspend fun fitRouteOnMap(): Result<Unit> = withMap { map ->
        val coordinates = routeOverviewCoordinates()
        check(coordinates.isNotEmpty()) { "No route set. Set a destination first." }
        val drawer = _mapCameraPadding.value
        // The Routes sheet collapses the drawer to its ~113dp collapsed height, so drawer.bottom
        // alone understates how much of the map it actually covers; merge in the sheet's own band,
        // reported only while the sheet is really up (see routeSheetCameraBand).
        val bottomInset = maxOf(drawer.bottom, routeSheetCameraBand.value)
        val padding = EdgeInsets(
            drawer.top + ROUTE_OVERVIEW_PADDING_PX,
            drawer.left + ROUTE_OVERVIEW_PADDING_PX,
            bottomInset + ROUTE_OVERVIEW_PADDING_PX,
            drawer.right + ROUTE_OVERVIEW_PADDING_PX,
        )
        val camera = map.cameraFor(coordinates = coordinates, coordinatesPadding = padding)
        val animationOptions = MapAnimationOptions.Builder()
            .duration(CAMERA_ANIMATION_DURATION_MS)
            .build()
        map.easeTo(camera, animationOptions)
    }

    /**
     * Prefers [pointsAlongActiveRoute] (the same remaining-route-ahead-of-the-driver geometry
     * along-route search queries against, already bounded to a few hundred points rather than
     * a long route's tens of thousands): reusing it keeps a mid-drive "show me the route" fit
     * on the remaining leg instead of the whole origin-to-destination trip, and keeps this off
     * the main thread's back for `cameraForCoordinates`. Falls back to the full decoded polyline,
     * then to the straight-line pair, for the brief window before either has landed.
     */
    private fun routeOverviewCoordinates(): List<Point> {
        return pointsAlongActiveRoute().ifEmpty { _routeGeometry.value }.ifEmpty {
            listOfNotNull(_userLocation.value?.toPoint(), destinationPoint?.toPoint())
        }
    }

    private fun ViaPoint.toPoint() = Point.fromLngLat(longitude, latitude)

    private fun Point.toViaPoint() = ViaPoint(latitude = latitude(), longitude = longitude())

    private fun DashSearchResult.routablePoint(): Point? {
        val points = routablePoints.orEmpty()
        return (points.firstOrNull { it.name == "default" } ?: points.firstOrNull())?.point
    }

    private fun DashSearchResult.toViaSearchResult() = ViaSearchResult(
        // Prefer the canonical Places id so context ids can feed
        // place_details_batch_tool; Dash's record id only identifies the row.
        id = mapboxId ?: id,
        coordinate = ViaPoint(
            latitude = coordinate.latitude(),
            longitude = coordinate.longitude(),
            name = name,
            category = ViaPinDrawer.preferredCategory(categoryIds),
            routableLatitude = routablePoint()?.latitude(),
            routableLongitude = routablePoint()?.longitude(),
        ),
        address = address?.let { a ->
            ViaAddress(
                houseNumber = a.houseNumber,
                street = a.street,
                neighborhood = a.neighborhood,
                locality = a.locality,
                postcode = a.postcode,
                place = a.place,
                district = a.district,
                region = a.region,
                country = a.country,
                countryCodeIsoAlpha2 = null,
            )
        },
        name = name,
        type = type,
        categoryIds = categoryIds,
        description = description,
        distanceMeters = distanceMeters,
        isOffline = false,
        isTransient = false,
        mapboxId = mapboxId,
        // Best-effort: whether Dash's flattened metadata map ever carries these keys for a
        // plain list result (vs. a resolved destination, below) is unverified.
        rating = validRating(metadata?.get("rating")?.toDoubleOrNull()),
        popularity = validPopularity(metadata?.get("popularity")?.toDoubleOrNull()),
    )

    private fun DestinationSearchResult.toViaSearchResult() = ViaSearchResult(
        // Same Places-id preference as the DashSearchResult mapper above.
        id = mapboxId ?: id,
        coordinate = ViaPoint(
            coordinate.latitude(),
            coordinate.longitude(),
            name,
            category = ViaPinDrawer.preferredCategory(categoryIds),
        ),
        address = address?.run {
            ViaAddress(
                houseNumber = houseNumber,
                street = street,
                neighborhood = neighborhood,
                locality = locality,
                postcode = postcode,
                place = place,
                district = district,
                region = region,
                country = country,
                countryCodeIsoAlpha2 = null,
            )
        },
        name = name,
        type = type,
        categoryIds = categoryIds,
        description = descriptionText,
        distanceMeters = distance,
        isOffline = false,
        isTransient = true,
        etaSeconds = etaMinutes?.minutes?.inWholeSeconds,
        externalIDs = externalIDs,
        mapboxId = mapboxId,
        // Round-trip through toString() rather than a bare toDouble() widening: Float
        // 4.4f.toDouble() is 4.400000095367432, which the model would otherwise read verbatim.
        rating = validRating(metadata?.rating?.toString()?.toDoubleOrNull()),
        popularity = validPopularity(metadata?.searchResultMetadata?.popularity?.toString()?.toDoubleOrNull()),
    )

    private fun SearchBoxResult.toViaSearchResult(lookup: PlaceLookup) = ViaSearchResult(
        id = mapboxId,
        coordinate = ViaPoint(
            latitude = coordinates.latitude,
            longitude = coordinates.longitude,
            name = name,
            routableLatitude = routableCoordinates?.latitude,
            routableLongitude = routableCoordinates?.longitude,
            category = ViaPinDrawer.preferredCategory(poiCategoryIds),
        ),
        address = address,
        name = name,
        // featureType carries the real "poi"/"street"/etc. from Search Box; SearchQueryRelevance's
        // distant-match corroboration now branches on a POI specifically, so a POI mislabeled
        // "place" here would wrongly keep corroborating on its city/region/country.
        type = if (lookup == PlaceLookup.ADDRESS) "ADDRESS" else featureType ?: "place",
        categoryIds = poiCategoryIds,
        description = fullAddress,
        distanceMeters = distanceMeters,
        isOffline = false,
        isTransient = true,
        mapboxId = mapboxId,
        rating = validRating(rating),
        popularity = validPopularity(popularity),
    )

    private fun NavigationRoute?.toGeometryPoints(): List<Point> {
        val encoded = this?.directionsRoute?.geometry() ?: return emptyList()
        return runCatching { LineString.fromPolyline(encoded, GEOMETRY_PRECISION).coordinates() }
            .getOrElse { emptyList() }
    }

    // id is positional (see ViaRouteCandidate's kdoc): meaningful only until the next fetch.
    // summary() is Directions' short "major roads traversed" string, null when there's none to show.
    private fun NavigationRoute.toViaRouteCandidate(index: Int) = ViaRouteCandidate(
        id = index.toString(),
        durationSeconds = directionsRoute.duration(),
        distanceMeters = directionsRoute.distance(),
        viaName = directionsRoute.legs()?.firstOrNull()?.summary(),
    )

    private fun ViaPoint.toPlaceDestination() = PlaceDestination(
        point = Point.fromLngLat(routingLongitude, routingLatitude),
        name = name ?: "$latitude,$longitude",
    )

    private fun ViaPoint.toDestinationSearchResult() = DestinationSearchResult(
        originalSearchModel = OriginalSearchModel.Custom,
        coordinate = toPoint(),
        id = "$longitude,$latitude",
        name = name ?: "$latitude,$longitude",
        type = NewSearchResultType.POI,
    )

    private fun emitUserMessage(throwable: Throwable, fallback: String) {
        val message = throwable.message ?: fallback
        ViaLog.e(throwable) { message }
        _userMessages.tryEmit(message)
    }

    private fun Int?.toCongestionLevel(): String = when {
        this == null -> "unknown"
        this < CONGESTION_LOW_THRESHOLD -> "low"
        this < CONGESTION_MODERATE_THRESHOLD -> "moderate"
        this < CONGESTION_HEAVY_THRESHOLD -> "heavy"
        else -> "severe"
    }
}

/** Suspends until the platform map view attaches; callers bound the wait with a timeout. */
private suspend fun MutableStateFlow<MapboxMap?>.onMapReady(): MapboxMap = filterNotNull().first()

/**
 * Origin published on [AndroidNavSdkService.userLocation]: simulation when enabled, else Dash.
 * (0, 0) is "no fix", not the Gulf of Guinea — search/camera must not use it as origin.
 */
internal fun effectiveUserLocation(
    simulationEnabled: Boolean,
    simulated: ViaPoint?,
    dash: ViaPoint?,
): ViaPoint? = preferredUserLocation(
    simulationEnabled = simulationEnabled,
    simulationAwareLocation = if (simulationEnabled) simulated else dash,
    liveLocation = dash,
)

internal suspend fun MapboxMap.cameraFor(
    coordinates: List<Point>,
    camera: CameraOptions = CameraOptions.Builder().build(),
    coordinatesPadding: EdgeInsets? = null,
    maxZoom: Double? = null,
    offset: ScreenCoordinate? = null,
): CameraOptions = suspendCancellableCoroutine { continuation ->
    cameraForCoordinates(
        coordinates,
        camera,
        coordinatesPadding,
        maxZoom,
        offset
    ) {
        if (continuation.isActive) {
            continuation.resume(it)
        }
    }
}
