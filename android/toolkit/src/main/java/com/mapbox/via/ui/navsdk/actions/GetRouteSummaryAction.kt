package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.logging.ViaLog
import com.mapbox.via.kit.models.ViaLegStep
import com.mapbox.via.kit.models.ViaRouteLeg
import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.ui.navsdk.NavSdkService
import com.mapbox.via.ui.navsdk.distanceLabel
import kotlin.math.abs
import kotlin.math.roundToInt

class GetRouteSummaryAction(private val service: NavSdkService) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_get_route_summary"
    override val name: String = "Get route summary"
    override val description: String =
        "Returns a spoken, high-level summary of the active route, including total and remaining distance and " +
            "time (compared to typical traffic conditions), and a narrative description of the roads ahead. " +
            "Use this to describe or tell the user about their route out loud, not to show it on the map."
    override val examples: List<String> = listOf(
        "Tell me about my route.",
        "What should I expect on the way?",
        "How long is the rest of my trip?",
        "Describe the rest of my trip.",
        "Is traffic worse than usual right now?",
    )

    override suspend fun execute(): Result<String> =
        service.getRouteSummary().fold(
            onSuccess = { summary ->
                Result.success(buildString {
                    appendLine("Route summary:")
                    val totalDist = distanceLabel(summary.totalDistanceMeters)
                    val totalDur = formatDurationVsTypical(
                        summary.totalDurationSeconds,
                        summary.totalDurationTypicalSeconds,
                    )
                    val remainDist = distanceLabel(summary.remainingDistanceMeters)
                    val remainDur = formatDurationVsTypical(
                        summary.remainingDurationSeconds,
                        summary.remainingDurationTypicalSeconds,
                    )
                    appendLine("  Total: $totalDist, $totalDur")
                    appendLine("  Remaining: $remainDist, $remainDur")
                    if (summary.remainingLegs.isNotEmpty()) {
                        appendLine("Roads ahead:")
                        summary.remainingLegs.forEach { leg ->
                            append("  - ${formatLeg(leg)}")
                            appendLine()
                        }
                    }
                }.trimEnd())
            },
            onFailure = { error ->
                ViaLog.w(error) { "Failed to get route summary" }
                Result.success(
                    error.toNavigationActionMessage(
                        noActiveRouteMessage = "No route is active. Set a destination to see route information.",
                        fallbackMessage = "I couldn't get route details right now.",
                    ),
                )
            },
        ).also { actionLog.d { "$it" } }

    private fun formatLeg(leg: ViaRouteLeg): String {
        val road = leg.summary?.takeIf { it.isNotBlank() } ?: "unnamed roads"
        val header = "$road (${distanceLabel(leg.distanceMeters)}, ${formatDuration(leg.durationSeconds)})"
        val roads = leg.steps.topRoads()
        if (roads.isEmpty()) return header
        val detail = roads.joinToString(", ") { (name, dist) -> "$name (${distanceLabel(dist)})" }
        return "$header: $detail"
    }

    // Merges consecutive steps with the same name, then returns top roads by distance (up to 3).
    private fun List<ViaLegStep>.topRoads(): List<Pair<String, Double>> {
        val merged = mutableListOf<Pair<String, Double>>()
        for (step in this) {
            val name = step.name ?: continue
            val last = merged.lastOrNull()
            if (last != null && last.first == name) {
                merged[merged.lastIndex] = name to (last.second + step.distanceMeters)
            } else {
                merged += name to step.distanceMeters
            }
        }
        return merged.sortedByDescending { it.second }.take(TOP_ROADS_COUNT)
    }

    private fun formatDuration(seconds: Double): String {
        val totalMinutes = (seconds / SECONDS_PER_MINUTE).roundToInt()
        return when {
            totalMinutes < 1 -> "less than a minute"
            totalMinutes < MINUTES_PER_HOUR -> "$totalMinutes min"
            else -> {
                val hours = totalMinutes / MINUTES_PER_HOUR
                val minutes = totalMinutes % MINUTES_PER_HOUR
                if (minutes == 0) "${hours}h" else "${hours}h ${minutes}min"
            }
        }
    }

    // Appends a comparison to typical (no-traffic) duration when the difference is meaningful.
    private fun formatDurationVsTypical(seconds: Double, typicalSeconds: Double): String {
        val duration = formatDuration(seconds)
        val deltaSeconds = seconds - typicalSeconds
        val delta = formatDuration(abs(deltaSeconds))
        return when {
            typicalSeconds <= 0.0 -> duration
            abs(deltaSeconds) < DELAY_THRESHOLD_SECONDS -> "$duration (typical)"
            deltaSeconds > 0 -> "$duration ($delta longer than typical)"
            else -> "$duration ($delta faster than typical)"
        }
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
        const val MINUTES_PER_HOUR = 60
        const val TOP_ROADS_COUNT = 3
        const val DELAY_THRESHOLD_SECONDS = 60.0
    }
}
