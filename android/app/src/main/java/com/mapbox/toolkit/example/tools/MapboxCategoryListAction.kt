package com.mapbox.toolkit.example.tools

import com.mapbox.via.kit.actions.ViaNoArgsAction
import kotlinx.serialization.json.JsonObject

/**
 * Lists the canonical category IDs Mapbox search supports, through the Mapbox MCP server's `category_list_tool`.
 *
 * `nav_sdk_search_category` names only common category IDs in its description and tells the assistant to call
 * `category_list_tool` for any other one, so the assistant picks a real ID instead of inventing one. The list rarely
 * changes, so the first successful reply is kept for the life of the action.
 */
class MapboxCategoryListAction(private val mcp: MapboxMcpClient) : ViaNoArgsAction() {
  override val id = CATEGORY_LIST_TOOL_NAME

  override val name = "List search categories"

  override val description =
    "List every canonical Mapbox search category ID. Call it before a category search when you are not sure the " +
      "category ID you would pass is a real canonical ID, then pick the matching ID from the list and run the " +
      "search in the same turn. Don't answer the user from the list alone."

  override val examples = listOf("Find a place that sells kites.", "Is there a climbing gym nearby?")

  override val supportsConcurrentExecution = true

  @Volatile private var categories: String? = null

  override suspend fun execute(): Result<String> =
    categories?.let { Result.success(it) }
      ?: mcp.callTool(CATEGORY_LIST_TOOL_NAME, JsonObject(emptyMap())).onSuccess { categories = it }

  private companion object {
    const val CATEGORY_LIST_TOOL_NAME = "category_list_tool"
  }
}
