package com.mapbox.via.ui.navsdk.actions

import com.mapbox.via.kit.logging.ViaLog
import com.mapbox.via.kit.actions.ViaNoArgsAction
import com.mapbox.via.kit.context.ViaLegTraffic
import com.mapbox.via.ui.navsdk.NavSdkService

class GetTrafficAheadAction(private val service: NavSdkService) : ViaNoArgsAction() {
    override val id: String = "nav_sdk_get_traffic_ahead"
    override val name: String = "Get traffic ahead"
    override val description: String =
        "Returns a concise summary of traffic conditions and meaningful congestion changes on the remaining route."

    override val examples: List<String> = listOf(
        "What's the traffic like ahead?",
        "Is there any traffic on my route?",
        "Should I expect delays?",
    )

    override suspend fun execute(): Result<String> =
        service.getTrafficAhead().fold(
            onSuccess = { traffic ->
                ViaLog.d { "Traffic ahead: $traffic" }
                if (traffic.legs.isEmpty()) {
                    Result.success("No route is active. Set a destination to see traffic information.")
                } else {
                    Result.success(buildTrafficSummary(traffic.legs))
                }
            },
            onFailure = { error ->
                ViaLog.w(error) { "Failed to get traffic ahead" }
                Result.success(
                    error.toNavigationActionMessage(
                        noActiveRouteMessage = "No route is active. Set a destination to see traffic information.",
                        fallbackMessage = "I couldn't check traffic right now.",
                    ),
                )
            },
        ).also { actionLog.d { "$it" } }

    private fun buildTrafficSummary(legs: List<ViaLegTraffic>): String {
        val traffic = analyzeTraffic(legs)
        val overall = traffic.overall
            ?: return "Traffic information is not available for this route."
        val exceptions = traffic.meaningfulExceptions(overall)
        val summary = when {
            exceptions.isNotEmpty() ->
                "Traffic is ${overall.spokenLabel} overall, with " +
                    "${exceptions.rangeLabel()} traffic in some sections ahead."
            overall == CongestionLevel.Low -> ALL_LIGHT_TRAFFIC
            else -> "${overall.spokenLabel.replaceFirstChar(Char::uppercase)} traffic ahead."
        }
        return when {
            !traffic.hasUnknownCoverage -> summary
            summary == ALL_LIGHT_TRAFFIC ->
                "$LIGHT_TRAFFIC_WHERE_AVAILABLE $PARTIAL_TRAFFIC_AVAILABILITY"
            else -> "$summary $PARTIAL_TRAFFIC_AVAILABILITY"
        }
    }

    private fun analyzeTraffic(legs: List<ViaLegTraffic>): TrafficAnalysis {
        val segmentCounts = mutableMapOf<CongestionLevel, Int>()
        var sdkOverall: CongestionLevel? = null
        var hasUnknownCoverage = false

        legs.forEach { leg ->
            val legOverall = leg.sdkOverallCongestionLevel.toKnownCongestionLevel()
            sdkOverall = listOfNotNull(sdkOverall, legOverall).maxByOrNull(CongestionLevel::severity)
            val knownSegments = leg.segmentCongestionLevels.mapNotNull { it.toKnownCongestionLevel() }
            val missingSegmentData = leg.segmentCongestionLevels.isEmpty() ||
                knownSegments.size != leg.segmentCongestionLevels.size
            val legHasUnknownCoverage = legOverall == null && missingSegmentData
            hasUnknownCoverage = hasUnknownCoverage || legHasUnknownCoverage
            knownSegments.forEach { level ->
                segmentCounts[level] = segmentCounts.getOrElse(level) { 0 } + 1
            }
        }

        val derivedOverall = segmentCounts.entries.maxWithOrNull(
            compareBy<Map.Entry<CongestionLevel, Int>> { it.value }
                .thenBy { it.key.severity },
        )?.key
        return TrafficAnalysis(
            overall = sdkOverall ?: derivedOverall,
            segmentCounts = segmentCounts,
            hasUnknownCoverage = hasUnknownCoverage,
        )
    }

    private fun TrafficAnalysis.meaningfulExceptions(
        overall: CongestionLevel,
    ): List<CongestionLevel> {
        val knownSegmentCount = segmentCounts.values.sum()
        if (knownSegmentCount == 0) return emptyList()
        return segmentCounts
            .filter { (level, count) ->
                level.severity > overall.severity &&
                    count >= MIN_EXCEPTION_SEGMENTS &&
                    count.toDouble() / knownSegmentCount >= MIN_EXCEPTION_SHARE
            }
            .keys
            .toList()
    }

    private fun String?.toKnownCongestionLevel(): CongestionLevel? =
        CongestionLevel.entries.firstOrNull { it.rawValue == this }

    private fun List<CongestionLevel>.rangeLabel(): String {
        val ordered = distinct().sortedBy(CongestionLevel::severity)
        return when (ordered.size) {
            0 -> error("A congestion range needs at least one level")
            1 -> ordered.single().rawValue
            else -> "${ordered.first().rawValue} to ${ordered.last().rawValue}"
        }
    }

    private companion object {
        const val MIN_EXCEPTION_SEGMENTS = 3
        const val MIN_EXCEPTION_SHARE = 0.05
        const val ALL_LIGHT_TRAFFIC = "Traffic is light for the rest of your route."
        const val LIGHT_TRAFFIC_WHERE_AVAILABLE = "Traffic is light where data is available."
        const val PARTIAL_TRAFFIC_AVAILABILITY =
            "Traffic information is unavailable for some parts of the route."
    }

    private data class TrafficAnalysis(
        val overall: CongestionLevel?,
        val segmentCounts: Map<CongestionLevel, Int>,
        val hasUnknownCoverage: Boolean,
    )

    private enum class CongestionLevel(val rawValue: String, val severity: Int, val spokenLabel: String) {
        Low("low", severity = 0, spokenLabel = "light"),
        Moderate("moderate", severity = 1, spokenLabel = "moderate"),
        Heavy("heavy", severity = 2, spokenLabel = "heavy"),
        Severe("severe", severity = 3, spokenLabel = "severe"),
    }
}
