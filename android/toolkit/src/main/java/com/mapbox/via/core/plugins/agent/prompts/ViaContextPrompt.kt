package com.mapbox.via.core.plugins.agent.prompts

import com.mapbox.via.kit.context.ViaContext
import com.mapbox.via.kit.context.ViaNavigationState
import com.mapbox.via.kit.models.ViaPoint
import com.mapbox.via.kit.models.ViaSearchResult
import com.mapbox.via.kit.models.isNullIsland
import com.mapbox.via.kit.models.roundedUpEtaMinutes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * Renders the live [ViaContext] (user location, recent search results,
 * active navigation destination, ...) as a plain-text "Current context:"
 * block that can be prepended to a user prompt or sent as its own
 * conversation item. Always includes navigation state. Idle free drive is
 * `Navigation: FreeDrive` plus `No active route.` so a missing line cannot
 * be read as an ongoing trip.
 *
 * Shared by the Koog agent (per-turn, in front of the user prompt) and the
 * OpenAI Live directors (as appended context, not session instructions).
 *
 * [replacesEarlier] is for appended context: earlier blocks cannot be retracted
 * from a running session, so the new one tells the model to ignore them.
 */
internal fun buildContextSection(context: ViaContext, replacesEarlier: Boolean = false): String? {
    val lines = mutableListOf<String>()
    appendUserLocation(lines, context)

    val searchResults = context.search.results
    if (searchResults.isNotEmpty()) {
        lines += "Recent search results (${searchResults.size}):"
        lines += SEARCH_RESULT_FOLLOW_UP_INSTRUCTIONS
        searchResults.forEachIndexed { i, result ->
            lines += "${i + 1}. ${result.toContextLine()}"
        }
    }

    appendNavigationContext(lines, context.navigation.state)

    if (lines.isEmpty()) return null
    val header = if (replacesEarlier) SUPERSEDE_CONTEXT_HEADER else CONTEXT_HEADER
    return joinBlock(header, lines)
}

/**
 * The same context as [buildContextSection] split into blocks that each fit
 * [maxChars], for transports with a hard per-message payload limit. The first
 * block carries the supersede header, location, navigation, and the result
 * count; each search result then gets its own block so every result reaches the
 * model instead of the tail being dropped. Idle free drive still produces a block.
 *
 * A result whose line is over budget has its name shortened first, since the
 * list number and coordinates are what tools act on. A head that does not fit
 * continues in further blocks rather than losing waypoints.
 */
internal fun buildContextAppends(context: ViaContext, maxChars: Int): List<String>? {
    val lines = mutableListOf<String>()
    appendUserLocation(lines, context)
    val searchResults = context.search.results
    if (searchResults.isNotEmpty()) {
        lines += "Recent search results: ${searchResults.size} (one per following context block)"
    }
    appendNavigationContext(lines, context.navigation.state)
    if (lines.isEmpty()) return null

    return buildList {
        var header = SUPERSEDE_CONTEXT_HEADER
        val block = mutableListOf<String>()
        for (line in lines) {
            if (block.isNotEmpty() && joinBlock(header, block + line).length > maxChars) {
                add(joinBlock(header, block).take(maxChars))
                header = CONTINUED_CONTEXT_HEADER
                block.clear()
            }
            block += line
        }
        add(joinBlock(header, block).take(maxChars))
        if (searchResults.isNotEmpty()) addAll(SEARCH_RESULT_FOLLOW_UP_INSTRUCTIONS)
        searchResults.forEachIndexed { i, result ->
            add(
                fitSearchResultBlock(result, maxChars) {
                    "Search result ${i + 1} of ${searchResults.size}: $it"
                },
            )
        }
    }
}

/**
 * Wraps [result]'s context line with [decorate], shortening the name until the
 * block fits [maxChars]. Coordinates are what directions tools act on; the
 * 1-based list index is what nav_sdk_* tools act on — both go last. A hard cut
 * only happens when the block is over budget with no name at all.
 */
private inline fun fitSearchResultBlock(
    result: ViaSearchResult,
    maxChars: Int,
    decorate: (line: String) -> String,
): String {
    val full = decorate(result.toContextLine())
    val room = maxChars - decorate(result.toContextLine(name = "")).length
    return when {
        full.length <= maxChars -> full
        room <= ELLIPSIS.length -> full.take(maxChars)
        else -> decorate(
            result.toContextLine(name = result.name.take(room - ELLIPSIS.length) + ELLIPSIS),
        )
    }
}

private fun ViaSearchResult.toContextLine(name: String = this.name): String {
    val metadata = buildList {
        rating?.let { add("rating=$it") }
        popularity?.let { add("popularity=$it") }
        etaSeconds?.let { add("eta_minutes=${roundedUpEtaMinutes(it)}") }
        val routed = routedDistanceMeters
        if (routed != null) {
            add("drive_distance_meters=${routed.roundToLong()}")
        } else {
            distanceMeters?.let { add("distance_meters=${it.roundToLong()}") }
        }
    }
    return "$name (${metadata.joinToString()}) at lat=${coordinate.latitude}, lon=${coordinate.longitude}"
}

private fun appendUserLocation(lines: MutableList<String>, context: ViaContext) {
    context.user?.location?.takeUnless { it.isNullIsland() }?.let { location ->
        lines += "User location: lat=${location.latitude}, lon=${location.longitude}"
    }
}

private fun joinBlock(header: String, lines: List<String>): String = buildString {
    appendLine(header)
    lines.forEach { appendLine(it) }
}.trimEnd()

private fun appendNavigationContext(lines: MutableList<String>, state: ViaNavigationState) {
    val waypoints = state.waypoints
    val mode = when (state) {
        is ViaNavigationState.ActiveGuidance -> "ActiveGuidance"
        is ViaNavigationState.FreeDrive -> "FreeDrive"
    }
    lines += "Navigation: $mode"
    if (waypoints.isEmpty()) {
        // ActiveGuidance with no known destination is still a trip (see IosNavigationContextSync).
        if (state is ViaNavigationState.FreeDrive) lines += "No active route."
        return
    }
    val line = { stop: ViaSearchResult ->
        if (stop.coordinate.isNullIsland()) {
            stop.name
        } else {
            "${stop.name} at lat=${stop.coordinate.latitude}, lon=${stop.coordinate.longitude}"
        }
    }
    lines += "Active destination: ${line(waypoints.last())}"
    // Tail is the destination (NavigationContextSync). Listing it again reads as an extra stop.
    val stops = waypoints.dropLast(1)
    if (stops.isNotEmpty()) {
        lines += "Waypoints:"
        stops.forEach { lines += "- ${line(it)}" }
    }
}

/**
 * Identity of the rendered context block after location hysteresis. Null when
 * [buildContextSection] would return null. Pass [lastEmittedLocation] so GPS
 * jitter inside [LOCATION_REFRESH_METERS] does not look like a new snapshot.
 */
internal fun contextSignature(
    context: ViaContext,
    lastEmittedLocation: ViaPoint? = null,
): String? = buildContextSection(stabilizeContextLocation(context, lastEmittedLocation))

/**
 * Reuses [lastEmittedLocation] until the user has moved at least
 * [LOCATION_REFRESH_METERS], so a parked car straddling a grid line does not
 * churn context items.
 */
internal fun stabilizeContextLocation(context: ViaContext, lastEmittedLocation: ViaPoint?): ViaContext {
    val user = context.user
    val current = user?.location
    if (user == null || current == null) return context
    val stable = when {
        lastEmittedLocation == null -> current
        distanceMeters(current, lastEmittedLocation) < LOCATION_REFRESH_METERS -> lastEmittedLocation
        else -> current
    }
    return if (stable == current) context else context.copy(user = user.copy(location = stable))
}

internal fun distanceMeters(a: ViaPoint, b: ViaPoint): Double {
    val dLat = (a.latitude - b.latitude) * METERS_PER_DEGREE_LATITUDE
    val meanLatRad = (a.latitude + b.latitude) / 2.0 * PI / 180.0
    val dLon = (a.longitude - b.longitude) * METERS_PER_DEGREE_LATITUDE * cos(meanLatRad)
    return hypot(dLat, dLon)
}

/** Prepends [buildContextSection] to a user prompt, Koog-style. */
internal fun buildPromptWithContext(context: ViaContext, userPrompt: String): String {
    val section = buildContextSection(context) ?: return userPrompt
    return buildString {
        appendLine(section)
        appendLine()
        appendLine("User request:")
        append(userPrompt)
    }
}

internal const val CONTEXT_HEADER: String = "Current context:"
internal const val SUPERSEDE_CONTEXT_HEADER: String =
    "Current context (replaces all earlier Current context blocks; ignore them):"
internal const val CONTINUED_CONTEXT_HEADER: String = "Current context (continued):"
internal const val CLEARED_CONTEXT_SECTION: String = "$SUPERSEDE_CONTEXT_HEADER\n(none)"

/** Re-emit user location only after this much real movement. */
const val LOCATION_REFRESH_METERS: Double = 100.0

private const val ELLIPSIS: String = "…"

private const val METERS_PER_DEGREE_LATITUDE: Double = 111_320.0

private val SEARCH_RESULT_FOLLOW_UP_INSTRUCTIONS: List<String> = listOf(
    "Each numbered result above maps to the same 1-based index on the client; pass that index to nav_sdk_* " +
        "tools — never opaque place ids.",
    "For ordinal or name destination, call nav_sdk_set_destination with 1-based resultIndex; only when " +
        "nav_sdk_set_destination is unavailable, use directions_tool.",
    "For that ordinal or name selection, use the retained result without search or place details.",
    "To enrich Recent search results with place_details_batch_tool, pass resultIndices (1-based list " +
        "positions) instead of mapbox_id.",
    "For destination replacement, use the retained result to replace the active destination; " +
        "never add it as a waypoint.",
    "When the user asks to show, pin, or put these results on the map, call nav_sdk_add_map_markers " +
        "once with each result's name, latitude, and longitude from its numbered line.",
)
