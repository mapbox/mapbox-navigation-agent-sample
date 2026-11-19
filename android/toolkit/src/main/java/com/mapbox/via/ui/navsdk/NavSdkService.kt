package com.mapbox.via.ui.navsdk

import com.mapbox.via.kit.context.ViaManeuver
import com.mapbox.via.kit.models.GeoBbox
import com.mapbox.via.kit.models.PlaceLookup
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.models.ViaRouteSummary
import com.mapbox.via.kit.context.ViaTrafficAhead
import com.mapbox.via.kit.models.ViaRouteCandidate
import com.mapbox.via.kit.models.ViaRouteProfile
import com.mapbox.via.ui.mapsession.MapPin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val NO_ACTIVE_ROUTE_ERROR_MESSAGE = "Navigation is not active."

internal const val ROUTE_SWITCH_UNSUPPORTED_MESSAGE =
    "Switching to an alternative route is not supported on this device. The route was not changed."

internal class NoActiveRouteException :
    IllegalStateException(NO_ACTIVE_ROUTE_ERROR_MESSAGE)

private val idleGuidanceRefreshEpoch: StateFlow<Int> = MutableStateFlow(0)

@Suppress("TooManyFunctions")
interface NavSdkService : NavSdkMarkerService {
    val userMessages: Flow<String>

    /**
     * Live user position used by [getUserLocation]. Emits `null` until a
     * location is available. Orchestrators snapshot this into
     * [com.mapbox.via.kit.context.ViaContext] so the first LLM call already
     * has coordinates.
     */
    val userLocation: Flow<ViaPoint?>

    /**
     * Current route progress during active guidance mode.
     */
    val guidanceProgress: StateFlow<GuidanceProgress?>

    /** Phone-scene activations. The guidance ETA recomputes when this changes. */
    val guidanceRefreshEpoch: StateFlow<Int>
        get() = idleGuidanceRefreshEpoch

    /** Adds one or more waypoints, best-effort. Returns the count actually added. */
    suspend fun addWaypoints(points: List<ViaPoint>): Result<Int>
    suspend fun getNextManeuver(): Result<ViaManeuver?>
    suspend fun getRouteSummary(): Result<ViaRouteSummary>
    suspend fun getSearchResults(): Result<List<ViaSearchResult>>

    /**
     * Writes [results] into what [getSearchResults] answers, without searching. Used when a
     * silent retry's rows are adopted for the map so the cache matches the pins.
     */
    suspend fun rememberSearchResults(results: List<ViaSearchResult>) {}
    suspend fun getTrafficAhead(): Result<ViaTrafficAhead>
    suspend fun getUserLocation(): Result<ViaPoint?>
    /** Fly the map camera to the given point without changing navigation/destination state. */
    suspend fun flyCameraTo(point: ViaPoint, zoom: Double? = null): Result<Unit>
    suspend fun recenterCamera(): Result<Unit>
    /** Removes one or more waypoints, best-effort. Returns the count actually removed. */
    suspend fun removeWaypoints(points: List<ViaPoint>): Result<Int>
    suspend fun routeOverviewCamera(): Result<Unit>
    suspend fun showDestinationCamera(): Result<Unit>
    suspend fun rememberCameraBeforePreview(): Result<Unit>
    suspend fun restoreCameraAfterPreview(): Result<Unit>
    /**
     * Frames a search pin the way selecting it does, at the zoom [rememberCameraBeforePreview]
     * captured; the selection snap alone would keep the route overview zoom.
     */
    suspend fun snapToSearchPin(point: ViaPoint): Result<Unit>

    /**
     * Frames every current search pin on the full map, without re-rendering them. Always pins only
     * at [SearchPinFramingSpec.Full], not the spec or route geometry the search framed with.
     */
    suspend fun frameAllSearchPins(): Result<Unit>
    suspend fun searchCategory(
        categoryId: String,
        displayName: String,
        proximity: ViaPoint? = null,
        limit: Int = 3
    ): Result<List<ViaSearchResult>>

    /**
     * [cache] controls whether this call replaces [getSearchResults]. Pass false for a silent
     * lookup (`showOnMap: false`); the arguments cannot tell a probe from a displayed search.
     * [searchCategory] and [searchAlongRoute] leave the cache alone on Android and overwrite it on iOS.
     */
    @Suppress("LongParameterList")
    suspend fun searchPlaces(
        query: String,
        proximity: ViaPoint? = null,
        limit: Int = 3,
        lookup: PlaceLookup = PlaceLookup.ANY,
        cache: Boolean = true,
        bbox: GeoBbox? = null,
    ): Result<List<ViaSearchResult>>

    /** Searches for [query] along the active route rather than around a point. */
    suspend fun searchAlongRoute(query: String, limit: Int = 3): Result<List<ViaSearchResult>>

    /** Category counterpart to [searchAlongRoute]: constrains [searchCategory] to the route corridor. */
    suspend fun searchCategoryAlongRoute(
        categoryId: String,
        displayName: String,
        limit: Int = 3,
    ): Result<List<ViaSearchResult>>

    /**
     * Replaces the search pins and frames them per [framing]. Search calls do not render pins
     * themselves. [includeActiveRoute] unions the active route into that fit on Android;
     * iOS still frames pins only.
     */
    suspend fun showSearchPins(
        pins: List<MapPin>,
        includeActiveRoute: Boolean = false,
        framing: SearchPinFramingSpec = SearchPinFramingSpec.Full,
    ): Result<Unit>

    /**
     * Appends [pins] to the current search pins, skipping places already pinned
     * (same latitude, longitude, and name), then re-frames the camera on all
     * current search pins per [framing]. Returns the count actually added.
     */
    suspend fun addSearchPins(
        pins: List<MapPin>,
        framing: SearchPinFramingSpec = SearchPinFramingSpec.Full,
    ): Result<Int>

    /**
     * Removes the search pins matching [pins] (same latitude, longitude, and
     * name). Returns the count actually removed. No camera movement.
     */
    suspend fun removeSearchPins(pins: List<MapPin>): Result<Int>

    /**
     * Clears search pins and pin selection. iOS also resumes guidance camera following;
     * Android does not move the camera.
     */
    suspend fun clearSearchPins(): Result<Unit>

    suspend fun setDestination(point: ViaPoint): Result<Unit>
    /** Set destination (and optional stops) and start navigation; the route is null when unknown. */
    suspend fun navigateTo(point: ViaPoint, waypoints: List<ViaPoint> = emptyList()): Result<ViaRouteCandidate?>
    suspend fun showRoutesOverview(): Result<Unit>

    /**
     * Computes candidate routes to [destination] for [profile] without starting guidance, and
     * renders them on the map. The first candidate is the router's recommended route.
     *
     * Preview-only: committing to a route is [startNavigation], backing out is [dismissPreview].
     *
     * [avoidHighways] excludes motorways from driving routes; walking and cycling ignore it.
     */
    suspend fun requestRouteCandidates(
        destination: ViaPoint,
        profile: ViaRouteProfile = ViaRouteProfile.Driving,
        waypoints: List<ViaPoint> = emptyList(),
        avoidHighways: Boolean = false,
    ): Result<List<ViaRouteCandidate>>

    /**
     * Marks the candidate with [id] as the one [startNavigation] will use, and restyles the
     * rendered routes so it reads as selected. Ids come from [requestRouteCandidates].
     */
    suspend fun selectRouteCandidate(id: String): Result<Unit>
    suspend fun switchToAlternativeRoute(): Result<ViaRouteCandidate> =
        Result.failure(UnsupportedOperationException(ROUTE_SWITCH_UNSUPPORTED_MESSAGE))

    /** Dismiss the destination and route preview screens, clearing the map and returning to free drive. */
    suspend fun dismissPreview(): Result<Unit>
    suspend fun startNavigation(): Result<Unit>
    /** Immediate cutoff. No-op when nothing is speaking. */
    suspend fun stopVoiceGuidance(): Result<Unit> = Result.success(Unit)
    suspend fun stopNavigation(): Result<Unit>

    /**
     * Ends an in-progress trip if the SDK has one; succeeds without side effects when idle.
     * Back-from-map uses this so an orphaned trip (UI left GUIDANCE, native session still
     * running) can still be stopped — [stopNavigation] itself may error or toast when idle.
     *
     * Default is a no-op. Platforms that own a trip session must override and gate on
     * native active guidance — calling [stopNavigation] from here would tear down preview
     * / idle state on iOS and NoOp.
     */
    suspend fun stopActiveTrip(): Result<Unit> = Result.success(Unit)
    suspend fun zoomIn(): Result<Unit>
    suspend fun zoomOut(): Result<Unit>
}
