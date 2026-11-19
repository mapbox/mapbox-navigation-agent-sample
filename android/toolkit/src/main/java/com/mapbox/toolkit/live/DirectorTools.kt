package com.mapbox.toolkit.live

import com.mapbox.via.kit.logging.ViaLog
import com.mapbox.via.core.plugins.director.openai.live.liveJson
import com.mapbox.via.core.plugins.director.openai.live.models.client.BackendTool
import com.mapbox.via.kit.actions.ViaAction
import com.mapbox.via.kit.actions.ViaActionRegistry
import com.mapbox.via.kit.actions.toJsonSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Local [ViaAction]s advertised to the Responses backend. MCP is hosted by OpenAI,
 * not executed here. [execute] never throws: failures become an [Outcome] the model can recover from.
 * Each call runs through [ViaActionRegistry.execute], so action telemetry is reported.
 */
class DirectorTools internal constructor(
    private val registry: ViaActionRegistry,
    private val extraTools: List<FunctionToolSpec>,
) {
    constructor(localActions: ViaActionRegistry = ViaActionRegistry()) : this(localActions, extraTools = emptyList())

    private val offeredActions: Map<String, ViaAction<*>> = registry.actions.associateBy { it.id }

    /** Adds director-owned tools (such as `end_conversation`) that the director dispatches itself. */
    fun withExtra(specs: List<FunctionToolSpec>): DirectorTools = DirectorTools(registry, extraTools + specs)

    val specs: List<FunctionToolSpec>
        get() = offeredActions.values.map { action ->
            FunctionToolSpec(action.id, action.description, action.toJsonSchema())
        } + extraTools

    /**
     * The advertised tools in the shape `delegation.responses.tools` accepts. Schemas are
     * passed through [withoutFractionalNumbers]: the Live service rejects a `session.start`
     * whose function schemas contain non-integer numbers with "Type is not JSON
     * serializable: decimal.Decimal", and those keywords only narrow validation the model
     * does not need.
     */
    fun toBackendTools(): List<BackendTool> =
        specs.map { BackendTool.Function(it.name, it.description, it.parameters.withoutFractionalNumbers()) }

    fun contains(name: String): Boolean = name in offeredActions || extraTools.any { it.name == name }

    /**
     * True when a batch of [names] may run concurrently: more than one call, and every one is a
     * local action that opted in via [ViaAction.supportsConcurrentExecution]. Director-owned
     * extras and unknown names never qualify, so a batch with any of them runs sequentially.
     */
    fun supportsConcurrentBatch(names: List<String>): Boolean =
        names.size > 1 && names.all { offeredActions[it]?.supportsConcurrentExecution == true }

    suspend fun execute(name: String, argumentsJson: String): Outcome =
        offeredActions[name]?.runFunctionCall(registry, argumentsJson)
            ?: Outcome.failure("Tool '$name' is not available")

    /**
     * Result of running a tool: [output] is always sent back as the
     * `function_call_output`; [error] is null on success or a short message
     * when the call failed.
     */
    data class Outcome(val output: String, val error: String? = null) {
        companion object {
            fun failure(message: String): Outcome = Outcome(output = message, error = message)
        }
    }
}

/** A function tool definition in the Responses function schema. */
data class FunctionToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
) {
    companion object {
        /** A tool that takes no arguments. */
        fun noArgs(name: String, description: String): FunctionToolSpec = FunctionToolSpec(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {})
                put("additionalProperties", JsonPrimitive(false))
            },
        )
    }
}

private fun JsonPrimitive.isFractionalNumber(): Boolean =
    !isString && content.toDoubleOrNull() != null && content.toLongOrNull() == null

/** Recursively drops object entries and array items whose value is a non-integer JSON number. */
internal fun JsonObject.withoutFractionalNumbers(): JsonObject = JsonObject(
    entries.mapNotNull { (key, value) -> value.withoutFractionalNumbers()?.let { key to it } }.toMap(),
)

private fun JsonElement.withoutFractionalNumbers(): JsonElement? = when (this) {
    is JsonObject -> withoutFractionalNumbers()
    is JsonArray -> JsonArray(mapNotNull { it.withoutFractionalNumbers() })
    is JsonPrimitive -> if (isFractionalNumber()) null else this
}

/**
 * Decodes [argumentsJson] with the action's [ViaAction.argsSerializer] and runs
 * it. Blank arguments (sent for no-arg tools) decode against an empty object.
 *
 * Cancellation is the one failure that must not become an [DirectorTools.Outcome]:
 * a tool job is cancelled when the session is closing, and an outcome would be
 * sent to a session that no longer accepts events. Actions that wrap their work
 * in `runCatching` surface the cancellation as a failed [Result], so both paths
 * rethrow it.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun <Args> ViaAction<Args>.runFunctionCall(
    registry: ViaActionRegistry,
    argumentsJson: String,
): DirectorTools.Outcome =
    try {
        val args = liveJson.decodeFromString(argsSerializer, argumentsJson.ifBlank { "{}" })
        registry.execute(this, args).fold(
            onSuccess = { DirectorTools.Outcome(output = it) },
            onFailure = { error ->
                if (error is CancellationException) throw error
                ViaLog.w(error) { "Tool '$id' failed" }
                DirectorTools.Outcome.failure("Tool '$id' failed: ${error.message ?: error::class.simpleName}")
            },
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        DirectorTools.Outcome.failure(
            "Tool '$id' failed to decode arguments: ${error.message ?: error::class.simpleName}",
        )
    }
