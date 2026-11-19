package com.mapbox.via.core.plugins.director.openai.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON for GPT-Live frames and Responses traffic. [encodeDefaults] keeps each
 * event `type` on the wire; [explicitNulls] is off so unset optionals are omitted
 * (`delegation_id` is a [kotlinx.serialization.json.JsonElement] with [kotlinx.serialization.json.JsonNull]
 * when it must be sent as null).
 */
internal val liveJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    coerceInputValues = true
}

internal inline fun <reified T> String.decodeLive(): T = liveJson.decodeFromString<T>(this)

internal inline fun <reified T> JsonObject.decodeLive(): T = liveJson.decodeFromJsonElement<T>(this)

internal inline fun <reified T> T.encodeLive(): String = liveJson.encodeToString(this)

/** Reads the `type` discriminator of a frame, or null if absent. */
internal fun JsonObject.eventType(): String? = this["type"]?.jsonPrimitive?.content

internal fun String.parseLiveFrame(): JsonObject = liveJson.parseToJsonElement(this).jsonObject
