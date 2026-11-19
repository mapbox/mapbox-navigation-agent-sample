package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.place.HomeWorkPlacesRepository
import com.mapbox.via.kit.geomemories.RouteRequestKind
import com.mapbox.via.kit.actions.DestinationArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaRouteCandidate
import com.mapbox.via.kit.models.ViaRouteProfile
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.mapsession.RoutePreviewState
import com.mapbox.via.ui.mapsession.RoutePreviewStore
import com.mapbox.via.ui.navsdk.NavSdkService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer

/**
 * Sets the destination and previews routes. Takes explicit coordinates from the
 * agent (e.g. copied from search results) or falls back to the first Via
 * search-context result (e.g. after
 * [com.mapbox.via.core.plugins.agent.actions.calendar.PrepareCalendarDestinationAction]).
 */
@Suppress("LongParameterList")
class SetDestinationAction(
    private val service: NavSdkService,
    private val contextHolder: ViaContextHolder,
    private val mapSessionStore: MapSessionStore,
    private val routePreviewStore: RoutePreviewStore,
    private val destinationCommitter: DestinationCommitter,
    private val tripMemories: TripMemoryRecorder,
    private val homeWorkPlaces: HomeWorkPlacesRepository,
) : ViaAction<DestinationArgs> {
    override val id: String = "nav_sdk_set_destination"
    override val name: String = "Set destination"
    override val argsSerializer: KSerializer<DestinationArgs> = DestinationArgs.serializer()
    override val description: String =
        "Plan a route to a destination and show the route preview without starting " +
            "turn-by-turn navigation. Pass savedPlace (HOME or WORK) for the user's saved " +
            "home or work address. Otherwise pass resultIndex — the 1-based position from \"Recent " +
            "search results\" in the current context — to pick a result of an earlier search, " +
            "or destination/name with explicit coordinates when the place came back as tool " +
            "text instead; omit both to use the first result already in the MapGPT search " +
            "context (e.g. after prepare_calendar_destination). Pass profile walking or cycling " +
            "when explicitly requested; driving is the traffic-aware default. " +
            "Pass avoidHighways true only when the user asks to avoid highways, motorways, or freeways; " +
            "it applies to driving routes and is off by default. Pass waypoints in visit " +
            "order to preview a trip with intermediate stops; starting that preview keeps the stops. " +
            "The result includes the route's travel time and distance when known; quote only that, " +
            "never an estimate or a drive time quoted earlier in the conversation."
    override val examples: List<String> = listOf(
        "Set that as the destination.",
        "Preview the route.",
        "Set the calendar event as my destination.",
    )

    override suspend fun execute(args: DestinationArgs): Result<String> {
        val session = mapSessionStore.state.value
        val destination = args.resolveDestination(contextHolder, homeWorkPlaces, session)
            .getOrElse { return Result.failure(it) }
        return when {
            session.activeGuidance &&
                args.waypoints.isEmpty() &&
                // A request to avoid highways changes the route even when the place is the same.
                !args.avoidsHighways &&
                session.destination.sameCoordinatesAs(destination.coordinate) ->
                Result.success("Already navigating to ${destination.name}. The route was not changed.")
            session.activeGuidance -> replaceActiveDestination(destination, args)
            else -> previewAndSetDestination(destination, args)
        }
    }

    private suspend fun replaceActiveDestination(destination: ViaSearchResult, args: DestinationArgs): Result<String> {
        val profile = args.profile
        val waypoints = args.waypoints
        // navigateTo carries no route options, so anything but a plain drive goes through candidates.
        val navigation = if (profile == ViaRouteProfile.Driving && !args.avoidsHighways) {
            service.navigateTo(destination.coordinate, waypoints)
        } else {
            service.startNavigationTo(destination.coordinate, profile, waypoints, args.avoidsHighways)
        }
        return navigation.mapSuspending { route ->
            destinationCommitter.commit(destination)
            tripMemories.routeRequested(RouteRequestKind.Navigate, destination, waypoints, profile)
            mapSessionStore.recordAddedStops(waypoints)
            mapSessionStore.setPins(emptyList())
            service.clearSearchPins()
            confirmation(destination, args, route)
        }.also { actionLog.d { "$it" } }
    }

    private suspend fun previewAndSetDestination(destination: ViaSearchResult, args: DestinationArgs): Result<String> {
        val profile = args.profile
        val waypoints = args.waypoints
        val previewResult = showRoutePreview(destination.coordinate, args)
        // The setDestination fallback is a plain drive: it would bring motorways back unannounced.
        val plainDrive = profile == ViaRouteProfile.Driving && waypoints.isEmpty() && !args.avoidsHighways
        val result = if (previewResult.isSuccess || !plainDrive) {
            previewResult
        } else {
            service.setDestination(destination.coordinate).map { null }
        }
        return result.mapSuspending { route ->
            destinationCommitter.commit(destination)
            mapSessionStore.recordAddedStops(waypoints)
            val kind = if (args.autoStartNavigation) RouteRequestKind.Navigate else RouteRequestKind.Preview
            tripMemories.routeRequested(kind, destination, waypoints, profile)
            confirmation(destination, args, route)
        }.also { actionLog.d { "$it" } }
    }

    private fun confirmation(
        destination: ViaSearchResult,
        args: DestinationArgs,
        route: ViaRouteCandidate?,
    ): String =
        destinationConfirmation("Destination set to", destination.name, args.waypoints, route, args.avoidsHighways)

    // Mirrors UnifiedShellViewModel.fetchRouteCandidates: requestRouteCandidates alone sets the
    // destination and draws routes, so calling setDestination too would recompute them twice.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun showRoutePreview(destination: ViaPoint, args: DestinationArgs): Result<ViaRouteCandidate> {
        val profile = args.profile
        val waypoints = args.waypoints
        val autoStartNavigation = args.autoStartNavigation
        val avoidHighways = args.avoidsHighways
        val previous = routePreviewStore.state.value as? RoutePreviewState.Ready
        val enteringPreview = routePreviewStore.state.value is RoutePreviewState.Idle
        val loading = RoutePreviewState.Loading(destination, profile, autoStartNavigation, waypoints, avoidHighways)
        routePreviewStore.beginLoading(destination, profile, autoStartNavigation, waypoints, avoidHighways)
        try {
            if (enteringPreview) service.rememberCameraBeforePreview()
            val candidates = service.requestRouteCandidates(destination, profile, waypoints, avoidHighways)
                .getOrThrow()
            if (candidates.isEmpty()) error("No route found to $destination.")
            when {
                // Navigate can land while this fetch is in flight. Publishing then yanks the camera
                // off the puck and leaves a stale Ready preview that resurfaces after the trip ends.
                mapSessionStore.state.value.activeGuidance -> restorePreviousIfStillLoading(loading, previous)
                // Only publish if nothing else (a concurrent VM fetch) replaced this Loading state.
                routePreviewStore.state.value == loading -> {
                    routePreviewStore.setCandidates(candidates)
                    if (!autoStartNavigation) {
                        service.routeOverviewCamera()
                    }
                }
            }
            return Result.success(candidates.first())
        } catch (e: CancellationException) {
            restorePreviousIfStillLoading(loading, previous)
            throw e
        } catch (e: Exception) {
            restorePreviousIfStillLoading(loading, previous)
            return Result.failure(e)
        }
    }

    private fun restorePreviousIfStillLoading(loading: RoutePreviewState.Loading, previous: RoutePreviewState.Ready?) {
        if (routePreviewStore.state.value != loading) return
        if (previous != null) routePreviewStore.restore(previous) else routePreviewStore.reset()
    }
}

private fun ViaPoint?.sameCoordinatesAs(other: ViaPoint): Boolean =
    this != null && latitude == other.latitude && longitude == other.longitude
