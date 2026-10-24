package com.okusezu.hummm.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Small helpers for walking InnerTube's deeply nested JSON. Like ytmusicapi's
 * `nav`, every lookup is null-safe so a missing renderer just yields null.
 */

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)

fun JsonElement?.path(vararg keys: Any): JsonElement? {
    var cur = this
    for (k in keys) {
        cur = when (k) {
            is String -> cur[k]
            is Int -> cur[k]
            else -> null
        } ?: return null
    }
    return cur
}

val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.obj: JsonObject? get() = this as? JsonObject

/** Concatenated text of a `{runs: [...]}` or `{simpleText}` object. */
val JsonElement?.text: String
    get() = this["simpleText"].str ?: runs.joinToString("") { it["text"].str.orEmpty() }

val JsonElement?.runs: List<JsonElement> get() = this["runs"].arr

/** Every value stored under [key], anywhere in the tree, in document order. */
fun JsonElement?.findAll(key: String): List<JsonElement> {
    val out = mutableListOf<JsonElement>()
    fun walk(e: JsonElement?) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k == key) out += v
                walk(v)
            }
            is JsonArray -> e.forEach(::walk)
            else -> {}
        }
    }
    walk(this)
    return out
}

fun JsonElement?.findFirst(key: String): JsonElement? {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key) return v
            v.findFirst(key)?.let { return it }
        }
        is JsonArray -> for (v in this) v.findFirst(key)?.let { return it }
        else -> {}
    }
    return null
}
