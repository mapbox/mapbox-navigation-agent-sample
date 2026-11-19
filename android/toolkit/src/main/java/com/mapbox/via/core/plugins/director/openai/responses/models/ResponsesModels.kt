package com.mapbox.via.core.plugins.director.openai.responses.models

import com.mapbox.via.core.plugins.director.openai.live.decodeLive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.mapbox.via.core.plugins.director.openai.live.models.client.BackendTool
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Responses API input items Via sends: user/developer messages and function
 * results. Used both inside Live `response.item.create` frames and as the
 * `input` of a direct `/v1/responses` request. The `type` discriminator comes
 * from each subtype's [SerialName].
 */
@Serializable
sealed interface ResponsesInputItem {
    @Serializable
    @SerialName("message")
    data class Message(
        val role: String,
        val content: List<Content>,
    ) : ResponsesInputItem {
        @Serializable
        data class Content(
            val text: String,
            val type: String = "input_text",
        )

        companion object {
            const val ROLE_USER: String = "user"
            const val ROLE_DEVELOPER: String = "developer"
            const val ROLE_ASSISTANT: String = "assistant"

            fun user(text: String): Message = Message(ROLE_USER, listOf(Content(text)))
            fun developer(text: String): Message = Message(ROLE_DEVELOPER, listOf(Content(text)))

            /** Assistant history uses `output_text` parts (Live `session.input` and Responses alike). */
            fun assistant(text: String): Message = Message(ROLE_ASSISTANT, listOf(Content(text, type = "output_text")))
        }
    }

    @Serializable
    @SerialName("function_call_output")
    data class FunctionCallOutput(
        @SerialName("call_id") val callId: String,
        val output: String,
        @Transient val failed: Boolean = false,
    ) : ResponsesInputItem

    /**
     * Makes [tools] available to the backend from this point of the conversation
     * on (https://developers.openai.com/api/docs/guides/tools-tool-search#add-tools-at-a-specific-point-in-the-input).
     * The only documented [role] is `developer`. This is how hosted MCP servers
     * reach a Live session, since `delegation.responses.tools` rejects them.
     */
    @Serializable
    @SerialName("additional_tools")
    data class AdditionalTools(
        val tools: List<BackendTool>,
        val role: String = "developer",
    ) : ResponsesInputItem
}

/**
 * The subset of Responses streaming events Via reacts to. Nested inside Live's
 * `response.event` envelope for the voice director and streamed as SSE `data`
 * lines for the text director. Decode with [ResponsesStreamEvent.parse].
 */
sealed interface ResponsesStreamEvent {
    val type: String

    @Serializable
    data class Created(
        val response: ResponseSnapshot = ResponseSnapshot(),
        override val type: String = TYPE,
    ) : ResponsesStreamEvent {
        companion object {
            const val TYPE: String = "response.created"
        }
    }

    /** A new output item; for `mcp_call` items this is where the tool name first appears. */
    @Serializable
    data class OutputItemAdded(
        val item: OutputItem = OutputItem(),
        override val type: String = TYPE,
    ) : ResponsesStreamEvent {
        companion object {
            const val TYPE: String = "response.output_item.added"
        }
    }

    /**
     * A finished output item. Function calls are read from here: an
     * arguments-done event alone does not carry the call id and name, and the
     * terminal snapshot forwarded by Live has an empty `output` list. Hosted
     * `mcp_call` items arrive here already executed, with `output` or `error`.
     */
    @Serializable
    data class OutputItemDone(
        val item: OutputItem = OutputItem(),
        override val type: String = TYPE,
    ) : ResponsesStreamEvent {
        companion object {
            const val TYPE: String = "response.output_item.done"
        }
    }

    @Serializable
    data class OutputTextDelta(
        val delta: String = "",
        override val type: String = TYPE,
    ) : ResponsesStreamEvent {
        companion object {
            const val TYPE: String = "response.output_text.delta"
        }
    }

    /** `response.completed`, `response.failed` or `response.incomplete`. */
    @Serializable
    data class Terminal(
        val response: ResponseSnapshot = ResponseSnapshot(),
        override val type: String,
    ) : ResponsesStreamEvent {
        val isCompleted: Boolean get() = type == COMPLETED

        companion object {
            const val COMPLETED: String = "response.completed"
            const val FAILED: String = "response.failed"
            const val INCOMPLETE: String = "response.incomplete"
            val TYPES: Set<String> = setOf(COMPLETED, FAILED, INCOMPLETE)
        }
    }

    /** Anything else; logged and otherwise ignored. */
    data class Other(override val type: String) : ResponsesStreamEvent
}

/** Decodes one Responses streaming event; unknown types become [ResponsesStreamEvent.Other]. */
internal fun JsonObject.toResponsesStreamEvent(): ResponsesStreamEvent {
    val type = this["type"]?.jsonPrimitive?.content ?: return ResponsesStreamEvent.Other("?")
    return when (type) {
        ResponsesStreamEvent.Created.TYPE -> decodeLive<ResponsesStreamEvent.Created>()
        ResponsesStreamEvent.OutputItemAdded.TYPE -> decodeLive<ResponsesStreamEvent.OutputItemAdded>()
        ResponsesStreamEvent.OutputItemDone.TYPE -> decodeLive<ResponsesStreamEvent.OutputItemDone>()
        ResponsesStreamEvent.OutputTextDelta.TYPE -> decodeLive<ResponsesStreamEvent.OutputTextDelta>()
        in ResponsesStreamEvent.Terminal.TYPES -> decodeLive<ResponsesStreamEvent.Terminal>()
        else -> ResponsesStreamEvent.Other(type)
    }
}

@Serializable
data class ResponseSnapshot(
    val id: String? = null,
    val status: String? = null,
    val usage: Usage? = null,
    val error: Error? = null,
    @SerialName("incomplete_details") val incompleteDetails: IncompleteDetails? = null,
) {
    @Serializable
    data class Usage(
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
        @SerialName("total_tokens") val totalTokens: Int? = null,
        @SerialName("input_tokens_details") val inputTokensDetails: InputTokensDetails? = null,
    ) {
        @Serializable
        data class InputTokensDetails(@SerialName("cached_tokens") val cachedTokens: Int? = null)
    }

    @Serializable
    data class Error(val code: String? = null, val message: String? = null)

    @Serializable
    data class IncompleteDetails(val reason: String? = null)

    /** Human-readable explanation of a non-completed response, or null when it completed. */
    fun failureMessage(): String? = when (status) {
        STATUS_COMPLETED, null -> null
        STATUS_INCOMPLETE -> "OpenAI response incomplete" + (incompleteDetails?.reason?.let { ": $it" }.orEmpty())
        else -> error?.let { "${it.message} (${it.code})" } ?: "OpenAI response $status"
    }

    fun toLog(): String = buildString {
        append("id=").append(id).append(" status=").append(status)
        usage?.let {
            append(" input=").append(it.inputTokens)
            append(" cached=").append(it.inputTokensDetails?.cachedTokens)
            append(" output=").append(it.outputTokens)
        }
    }

    companion object {
        const val STATUS_COMPLETED: String = "completed"
        const val STATUS_INCOMPLETE: String = "incomplete"
        const val STATUS_FAILED: String = "failed"
    }
}

@Serializable
data class OutputItem(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val arguments: String? = null,
    /** `mcp_call` result text; `mcp_list_tools` has none. */
    val output: String? = null,
    /** `mcp_call`/`mcp_list_tools` failure; the server sends either a string or an object. */
    val error: JsonElement? = null,
    @SerialName("server_label") val serverLabel: String? = null,
    /** `mcp_list_tools`: the tools the server exposed. */
    val tools: List<McpListedTool> = emptyList(),
) {
    /** This item as a [FunctionCall], or null when it is not a `function_call`. */
    fun asFunctionCall(): FunctionCall? =
        if (type == FUNCTION_CALL && name != null && callId != null) {
            FunctionCall(name = name, callId = callId, arguments = arguments.orEmpty())
        } else {
            null
        }

    val isMessage: Boolean get() = type == MESSAGE
    val isMcpCall: Boolean get() = type == MCP_CALL
    val isMcpListTools: Boolean get() = type == MCP_LIST_TOOLS

    val mcpToolLabel: String get() = listOfNotNull(serverLabel, name).joinToString("/").ifEmpty { "?" }

    /** The failure as text, or null when the item succeeded. */
    val errorMessage: String?
        get() = error?.let { if (it is JsonNull) null else if (it is JsonPrimitive) it.content else it.toString() }

    companion object {
        const val MESSAGE: String = "message"
        const val FUNCTION_CALL: String = "function_call"
        const val MCP_CALL: String = "mcp_call"
        const val MCP_LIST_TOOLS: String = "mcp_list_tools"
    }
}

@Serializable
data class McpListedTool(val name: String)

/** A backend `function_call` ready for Via to execute. */
data class FunctionCall(
    val name: String,
    val callId: String,
    val arguments: String,
)
