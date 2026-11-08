package com.dc.andagent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A tool the agent can invoke. Parameters are described as a JSON Schema
 * (passed through to the LLM as-is).
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonElement
    /** true → agent must get explicit user confirmation before execution */
    val destructive: Boolean
        get() = false

    /** Execute and return a result string for the LLM. Throws on failure. */
    suspend fun execute(args: JsonObject): String
}

fun jsonSchema(props: Map<String, JsonElement> = emptyMap(), required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

fun schemaOf(vararg props: Pair<String, JsonElement>, required: List<String> = emptyList()): JsonObject =
    jsonSchema(props.toMap(), required)

fun strParam(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

fun intParam(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

fun boolParam(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

fun numParam(description: String): JsonObject = buildJsonObject {
    put("type", "number")
    put("description", description)
}

/* ------------------------- argument accessors ------------------------- */

/** String argument; null for missing/blank/json-null. */
fun JsonObject.strArg(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

fun JsonObject.intArg(key: String): Int? = strArg(key)?.toIntOrNull()

fun JsonObject.floatArg(key: String): Float? = strArg(key)?.toFloatOrNull()

fun JsonObject.longArg(key: String): Long? = strArg(key)?.toLongOrNull()

fun JsonObject.boolArg(key: String): Boolean? =
    strArg(key)?.let { it.equals("true", true) || it == "1" }
