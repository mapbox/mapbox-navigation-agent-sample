package com.mapbox.toolkit.example.tools

import com.mapbox.via.kit.actions.ViaAction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Sends the user's feedback through the Mapbox MCP server's `feedback_tool`. The feedback is filed under the Mapbox
 * account that owns the access token, where the app's developer reviews it in Feedback Explorer.
 *
 * This shows how an app can offer one tool from an MCP server, rather than all of them: the assistant sees a regular
 * local action, and only [execute] talks to the server through [mcp].
 *
 * Every call files a real report.
 */
class MapboxFeedbackAction(private val mcp: MapboxMcpClient) : ViaAction<MapboxFeedbackArgs> {
  override val id = FEEDBACK_TOOL_NAME

  override val name = "Send feedback"

  override val description =
    "Send the user's feedback to the developer of this app: a wrong, closed, or missing place, a bad route or " +
      "arrival time, wrong road or traffic data, or a problem with this app, including a wrong answer from you. " +
      "Call it as soon as the user complains, reports a problem, or asks to send feedback, without reading it back " +
      "or asking them to confirm, because they are driving. Only call it for a problem the user raised, never to " +
      "log your own work. Pass the user's words in feedback, naming the place or road and what it should be if " +
      "they said. Pick the closest category, and application_issue for anything about the app itself. Pass " +
      "latitude and longitude of the place from an earlier search or route result, or the user's current location " +
      "when the problem is where they are; leave them out when the feedback isn't about a place. If the user also " +
      "asked to change the trip, do that too: sending feedback doesn't change anything."

  override val examples = listOf(
    "That restaurant is closed.",
    "The arrival time is wrong.",
    "This road doesn't exist.",
  )

  override val argsSerializer: KSerializer<MapboxFeedbackArgs> = MapboxFeedbackArgs.serializer()

  override suspend fun execute(args: MapboxFeedbackArgs): Result<String> {
    // The server takes the location as an optional nested object; flat args keep the advertised schema simple.
    val arguments = buildJsonObject {
      put("feedback", args.feedback)
      put("category", Json.encodeToJsonElement(MapboxFeedbackCategory.serializer(), args.category))
      if (args.latitude != null && args.longitude != null) {
        putJsonObject("coordinates") {
          put("latitude", args.latitude)
          put("longitude", args.longitude)
        }
      }
    }
    return mcp.callTool(FEEDBACK_TOOL_NAME, arguments)
  }

  private companion object {
    const val FEEDBACK_TOOL_NAME = "feedback_tool"
  }
}

@Serializable
data class MapboxFeedbackArgs(
  val feedback: String,
  val category: MapboxFeedbackCategory,
  val latitude: Double? = null,
  val longitude: Double? = null,
)

/** The categories `feedback_tool` accepts. */
@Serializable
enum class MapboxFeedbackCategory {
  @SerialName("poi_closed") PoiClosed,
  @SerialName("poi_details") PoiDetails,
  @SerialName("poi_location") PoiLocation,
  @SerialName("poi_missing") PoiMissing,
  @SerialName("map_data_issue") MapDataIssue,
  @SerialName("map_rendering_issue") MapRenderingIssue,
  @SerialName("routing_issue") RoutingIssue,
  @SerialName("poor_routing_issue") PoorRoutingIssue,
  @SerialName("alternative_route_issue") AlternativeRouteIssue,
  @SerialName("no_route") NoRoute,
  @SerialName("illegal_turn") IllegalTurn,
  @SerialName("illegal_entrance") IllegalEntrance,
  @SerialName("missing_expected_maneuver") MissingExpectedManeuver,
  @SerialName("eta_issue") EtaIssue,
  @SerialName("road_closure_issue") RoadClosureIssue,
  @SerialName("road_incident_issue") RoadIncidentIssue,
  @SerialName("speed_limit_issue") SpeedLimitIssue,
  @SerialName("traffic_issue") TrafficIssue,
  @SerialName("false_negative_traffic_issue") FalseNegativeTrafficIssue,
  @SerialName("false_positive_traffic_issue") FalsePositiveTrafficIssue,
  @SerialName("application_issue") ApplicationIssue,
}
