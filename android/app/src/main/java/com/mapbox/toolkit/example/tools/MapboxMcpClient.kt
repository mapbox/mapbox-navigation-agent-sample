package com.mapbox.toolkit.example.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * Calls tools on the Mapbox MCP server, so an app can offer the server's tools one at a time as local actions: the
 * assistant sees a regular app tool, and only the action's `execute` talks to the server.
 *
 * Requests are authenticated with the token from [mapboxAccessToken], which is read for each call so a token changed
 * in Settings is used right away. Close the client when no action needs it to release its HTTP client.
 */
class MapboxMcpClient(
  private val mapboxAccessToken: () -> String,
  private val serverUrl: String = MAPBOX_MCP_SERVER_URL,
) : AutoCloseable {
  private val httpClient = HttpClient(Android) {
    install(SSE) // Required for MCP Streamable HTTP
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
    defaultRequest { header(HttpHeaders.Authorization, "Bearer ${mapboxAccessToken()}") }
  }

  /** Calls the server's [toolName] with [arguments] and returns the text it replies with, or the error it reports. */
  @Suppress("TooGenericExceptionCaught")
  suspend fun callTool(toolName: String, arguments: JsonObject): Result<String> = try {
    val client = httpClient.mcpStreamableHttp(serverUrl)
    try {
      val result = client.callTool(CallToolRequest(CallToolRequestParams(toolName, arguments)))
      val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
      if (result.isError == true) Result.failure(IllegalStateException(text)) else Result.success(text)
    } finally {
      client.close()
    }
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: Exception) {
    Result.failure(error)
  }

  override fun close() {
    httpClient.close()
  }

  private companion object {
    const val MAPBOX_MCP_SERVER_URL = "https://mcp.mapbox.com/mcp"
    const val REQUEST_TIMEOUT_MILLIS = 30_000L
  }
}
