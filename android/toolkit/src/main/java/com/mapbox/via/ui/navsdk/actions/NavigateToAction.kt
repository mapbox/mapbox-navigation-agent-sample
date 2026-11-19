package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.place.HomeWorkPlacesRepository
import com.mapbox.via.kit.geomemories.RouteRequestKind
import com.mapbox.via.kit.actions.DestinationArgs
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.context.ViaContextHolder
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaRouteCandidate
import com.mapbox.via.ui.mapsession.MapSessionStore
import com.mapbox.via.ui.navsdk.NavSdkService
import kotlinx.serialization.KSerializer

/**
 * Sets the destination and starts turn-by-turn navigation in one step. Takes
 * explicit coordinates from the agent (e.g. copied from search results) or falls
 * back to the first Via search-context result (e.g. after a prepared calendar
 * destination).
 */
class NavigateToAction(
    private val service: NavSdkService,
    private val contextHolder: ViaContextHolder,
    private val mapSessionStore: MapSessionStore,
    private val destinationCommitter: DestinationCommitter,
    private val tripMemories: TripMemoryRecorder,
    private val homeWorkPlaces: HomeWorkPlacesRepository,
) : ViaAction<DestinationArgs> {
    override val id: String = ID
    override val name: String = "Navigate to"
    override val argsSerializer: KSerializer<DestinationArgs> = DestinationArgs.serializer()
    override val description: String =
        "Plan a route to a destination and immediately start turn-by-turn navigation. " +
            "Pass savedPlace (HOME or WORK) for the user's saved home or work address. " +
            "Otherwise pass resultIndex — the 1-based position from \"Recent search results\" in the " +
            "current context — to pick a result of an earlier search, or destination/name " +
            "with explicit coordinates when the place came back as tool text instead; omit " +
            "both to use the first result already in the MapGPT search context. Pass profile " +
            "walking or cycling when explicitly requested; driving is the traffic-aware default. " +
            "Pass avoidHighways true only when the user asks to avoid highways, motorways, or freeways; " +
            "it applies to driving routes and is off by default. Keep it true when starting a route " +
            "that was previewed that way. For a " +
            "destination plus intermediate stops in one request, resolve places first then " +
            "pass them in waypoints in visit order (do not ask which gas station or coffee " +
            "shop to pick). " +
            "Prefer this after the user confirms (e.g. \"let's go\") following " +
            "prepare_calendar_destination, except when the user asks for destination and " +
            "stops together — then call this once with waypoints filled. " +
            "The result includes the route's travel time and distance when known; quote only that, " +
            "never an estimate or a drive time quoted earlier in the conversation."
    override val examples: List<String> = listOf(
        "Let's go.",
        "Start navigating.",
        "Navigate to that destination.",
        "Take me there.",
        "Take me to the office, but stop at a gas station and a coffee shop on the way.",
    )

    override suspend fun execute(args: DestinationArgs): Result<String> {
        val destination = args.resolveDestination(contextHolder, homeWorkPlaces, mapSessionStore.state.value)
            .getOrElse { return Result.failure(it) }
        return navigate(destination.coordinate, args).mapSuspending { route ->
            destinationCommitter.commit(destination)
            tripMemories.routeRequested(RouteRequestKind.Navigate, destination, args.waypoints, args.profile)
            mapSessionStore.startActiveGuidance()
            mapSessionStore.recordAddedStops(args.waypoints)
            // startActiveGuidance keeps browse pins during an already-active trip so along-route
            // search stays usable; starting navigation to a new destination must still drop them.
            service.clearSearchPins().onSuccess { mapSessionStore.setPins(emptyList()) }
            destinationConfirmation("Navigating to", destination.name, args.waypoints, route, args.avoidsHighways)
        }.also { actionLog.d { "$it" } }
    }

    private suspend fun navigate(
        destination: ViaPoint,
        args: DestinationArgs,
    ): Result<ViaRouteCandidate> =
        service.startNavigationTo(destination, args.profile, args.waypoints, args.avoidsHighways)

    companion object {
        const val ID: String = "nav_sdk_navigate_to"
    }
}
