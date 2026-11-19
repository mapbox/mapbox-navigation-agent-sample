package com.mapbox.toolkit.navigation

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mapbox.dash.sdk.DashNavigationFragment
import com.mapbox.toolkit.live.LiveDirector
import com.mapbox.via.kit.conversation.ConversationContinuityWriter
import com.mapbox.via.kit.geomemories.GeoMemoryRecorder
import com.mapbox.via.kit.geomemories.SearchMemoryRecorder
import com.mapbox.via.kit.location.AndroidLocationProvider
import com.mapbox.via.core.location.SimulatedLocationSettings
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.actions.ViaActionRegistry
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.actions.GetHomeAddressAction
import com.mapbox.via.kit.actions.GetWorkAddressAction
import com.mapbox.via.kit.actions.SetHomeAddressAction
import com.mapbox.via.kit.actions.SetWorkAddressAction
import com.mapbox.via.kit.actions.PlaceDetailsBatchAction
import com.mapbox.via.kit.mapsession.MapMarkerEventSource
import com.mapbox.via.kit.search.DistantMatchCorroborator
import com.mapbox.via.ui.MapContentController
import com.mapbox.via.ui.location.LocationContextSync
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.RoutePreviewStore
import com.mapbox.via.ui.mapsession.TripContinuitySync
import com.mapbox.via.ui.navsdk.AndroidNavSdkMarkerManager
import com.mapbox.via.ui.navsdk.AndroidNavSdkService
import com.mapbox.via.ui.navsdk.NavigationBackendSelection
import com.mapbox.via.ui.navsdk.NavigationContextSync
import com.mapbox.via.ui.navsdk.actions.AddMapMarkersAction
import com.mapbox.via.ui.navsdk.actions.AddWaypointAction
import com.mapbox.via.ui.navsdk.actions.ClearMapMarkersAction
import com.mapbox.via.ui.navsdk.actions.DestinationCommitter
import com.mapbox.via.ui.navsdk.actions.DismissPreviewAction
import com.mapbox.via.ui.navsdk.actions.FocusSearchResultAction
import com.mapbox.via.ui.navsdk.actions.GetNextManeuverAction
import com.mapbox.via.ui.navsdk.actions.GetRouteSummaryAction
import com.mapbox.via.ui.navsdk.actions.GetSearchResultsAction
import com.mapbox.via.ui.navsdk.actions.GetTrafficAheadAction
import com.mapbox.via.ui.navsdk.actions.GetUserLocationAction
import com.mapbox.via.ui.navsdk.actions.NavigateToAction
import com.mapbox.via.ui.navsdk.actions.RecenterCameraAction
import com.mapbox.via.ui.navsdk.actions.RemoveWaypointAction
import com.mapbox.via.ui.navsdk.actions.RouteOverviewCameraAction
import com.mapbox.via.ui.navsdk.actions.SearchAlongRouteAction
import com.mapbox.via.ui.navsdk.actions.SearchCategoryAction
import com.mapbox.via.ui.navsdk.actions.SearchPlacesAction
import com.mapbox.via.ui.navsdk.actions.SetDestinationAction
import com.mapbox.via.ui.navsdk.actions.ShowDestinationAction
import com.mapbox.via.ui.navsdk.actions.ShowRouteAlternativesAction
import com.mapbox.via.ui.navsdk.actions.StartNavigationAction
import com.mapbox.via.ui.navsdk.actions.StopNavigationAction
import com.mapbox.via.ui.navsdk.actions.TripMemoryRecorder
import com.mapbox.via.ui.navsdk.actions.ZoomInAction
import com.mapbox.via.ui.navsdk.actions.ZoomOutAction
import com.mapbox.via.ui.place.NavSdkPlaceResolver
import com.mapbox.via.ui.screens.place.PlaceDetailsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest

/**
 * The Navigation SDK actions the assistant can take on the UX Framework's map. Create one per app, and [attach] it to
 * the [DashNavigationFragment] showing the map.
 *
 * Search and place details use the Mapbox Search SDK, drive times use the Mapbox Matrix API, and saved places are kept
 * in memory. Trip history isn't connected yet, so actions that need it report that the information isn't available.
 */
class NavigationActions(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val contextHolder = ViaContextHolder()
    private val geoMemories = GeoMemoryRecorder(InMemoryGeoMemoryRepository(), scope)
    private val tripMemories = TripMemoryRecorder(geoMemories, contextHolder)

    // Records navigation starting and stopping, whether the assistant or the user started it.
    private val mapSessionStore = MapSessionStore(onGuidanceChanged = tripMemories::guidanceChanged)
    private val routePreviewStore = RoutePreviewStore()
    private val markerManager = AndroidNavSdkMarkerManager()
    private val searchBoxRepository = SearchSdkSearchBoxRepository()
    private val placesRepository = SearchSdkPlacesRepository()

    // Caches place details, and gives the search actions the opening hours that "open now" searches need.
    private val placeDetailsStore = PlaceDetailsStore(placesRepository)

    private val service = AndroidNavSdkService(
        mapContentController = MapContentController(),
        mapSessionStore = mapSessionStore,
        routePreviewStore = routePreviewStore,
        simulatedLocationSettings = SimulatedLocationSettings(
            dataStore = PreferenceDataStoreFactory.create {
                context.applicationContext.filesDir.resolve(DATASTORE_FILE_NAME)
            },
            defaultEnabled = false,
            scope = scope,
        ),
        searchBoxRepository = searchBoxRepository,
        markerManager = markerManager,
    )

    private val mapLayers = NavigationMapLayers(context.applicationContext, service, markerManager, routePreviewStore)

    init {
        // Keep the state the actions read in step with the map session and the user's location.
        NavigationContextSync(mapSessionStore, NavigationBackendSelection(), contextHolder, scope)
        LocationContextSync(AndroidLocationProvider(context.applicationContext), contextHolder, service, scope)
    }

    private val destinationCommitter = DestinationCommitter(
        mapSessionStore = mapSessionStore,
        placeDetailsStore = placeDetailsStore,
        searchBoxRepository = searchBoxRepository,
        backgroundScope = scope,
        continuity = TripContinuitySync(ConversationContinuityWriter.NoOp, scope),
    )

    private val searchMemories = SearchMemoryRecorder.NoOp
    private val homeWorkPlaces = InMemoryHomeWorkPlacesRepository()
    private val placeResolver = NavSdkPlaceResolver(service)
    private val mapMarkerEventSource = MapMarkerEventSource.Empty
    private val matrixRepository = MapboxJavaMatrixRepository()

    // Keeps a single far-off match when nothing closer was found. Checking that it plausibly answers the query takes a
    // language model, which the sample SDK doesn't provide.
    private val distantMatchCorroborator = DistantMatchCorroborator { _, _ -> true }
    private val continuity = ConversationContinuityWriter.NoOp

    /**
     * Every Navigation SDK action except switching routes, which the UX Framework service doesn't support yet, plus the
     * home and work address and place details actions. Pass the ones the assistant can take to
     * [liveDirector].
     */
    val actions: List<ViaAction<*>> = listOf(
        AddMapMarkersAction(service, mapSessionStore, contextHolder),
        AddWaypointAction(service, contextHolder, mapSessionStore, tripMemories),
        ClearMapMarkersAction(service, mapSessionStore),
        FocusSearchResultAction(contextHolder, mapSessionStore, mapMarkerEventSource),
        GetHomeAddressAction(homeWorkPlaces, contextHolder),
        GetNextManeuverAction(service),
        GetRouteSummaryAction(service),
        GetSearchResultsAction(contextHolder, mapSessionStore),
        GetTrafficAheadAction(service),
        GetUserLocationAction(service),
        GetWorkAddressAction(homeWorkPlaces, contextHolder),
        RecenterCameraAction(service, mapSessionStore),
        RemoveWaypointAction(service, mapSessionStore),
        RouteOverviewCameraAction(service),
        SearchAlongRouteAction(
            service,
            contextHolder,
            mapSessionStore,
            mapMarkerEventSource,
            searchMemories,
            continuity,
            placeDetailsStore,
        ),
        SearchCategoryAction(
            service,
            contextHolder,
            mapSessionStore,
            mapMarkerEventSource,
            matrixRepository,
            searchMemories,
            continuity,
            placeDetailsStore,
        ),
        SearchPlacesAction(
            service,
            contextHolder,
            mapSessionStore,
            mapMarkerEventSource,
            matrixRepository,
            searchMemories,
            continuity,
            searchBoxRepository = searchBoxRepository,
            distantMatchCorroborator = distantMatchCorroborator,
            openingHours = placeDetailsStore,
        ),
        PlaceDetailsBatchAction(placesRepository, contextHolder),
        SetDestinationAction(
            service,
            contextHolder,
            mapSessionStore,
            routePreviewStore,
            destinationCommitter,
            tripMemories,
            homeWorkPlaces,
        ),
        NavigateToAction(service, contextHolder, mapSessionStore, destinationCommitter, tripMemories, homeWorkPlaces),
        SetHomeAddressAction(homeWorkPlaces, geoMemories, placeResolver, contextHolder),
        SetWorkAddressAction(homeWorkPlaces, geoMemories, placeResolver, contextHolder),
        ShowDestinationAction(service),
        ShowRouteAlternativesAction(service),
        DismissPreviewAction(service, mapSessionStore),
        StartNavigationAction(service, mapSessionStore, routePreviewStore),
        StopNavigationAction(service, mapSessionStore),
        ZoomInAction(service),
        ZoomOutAction(service),
    )

    /** A [LiveDirector] that lets the assistant call exactly [actions], such as some of [NavigationActions.actions]. */
    fun liveDirector(actions: List<ViaAction<*>>): LiveDirector = LiveDirector(ViaActionRegistry(actions), contextHolder)

    /** Runs the actions against [fragment]'s map, and draws their results on it, until cancelled. */
    @SuppressLint("RestrictedApi")
    suspend fun attach(fragment: DashNavigationFragment) {
        service.setGuidanceFragmentAttached(true)
        try {
            fragment.mapControllerFlow.collectLatest { controller ->
                val mapView = controller?.mapView
                service.attachMap(mapView?.mapboxMap)
                mapView?.let { mapLayers.drawOn(it) }
            }
        } finally {
            service.setGuidanceFragmentAttached(false)
            service.attachMap(null)
        }
    }

    private companion object {
        const val DATASTORE_FILE_NAME = "mapbox_toolkit_navigation.preferences_pb"
    }
}
