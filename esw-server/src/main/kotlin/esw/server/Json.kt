package esw.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Turns plain Kotlin values into JSON, so messages can be written as `obj("t" to "lobby", "games" to list)`. */
fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Enum<*> -> JsonPrimitive(value.name)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> JsonArray(value.map { toJson(it) })
    else -> error("Cannot turn ${value::class} into JSON")
}

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to toJson(v) })

private val parser = Json { ignoreUnknownKeys = true }

fun parseMessage(text: String): JsonObject? = try {
    parser.parseToJsonElement(text).jsonObject
} catch (_: Exception) {
    null
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull

fun JsonElement.asBool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

fun JsonElement.asObject(): JsonObject? = this as? JsonObject
