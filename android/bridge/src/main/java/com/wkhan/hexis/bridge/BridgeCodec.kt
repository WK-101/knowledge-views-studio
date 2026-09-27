package com.wkhan.hexis.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The single JSON configuration for the whole bridge, used identically by the core and every addon.
 *
 * `ignoreUnknownKeys` is the append-only evolution rule in force: a newer peer may add fields, and an
 * older peer simply ignores them rather than failing. Envelopes travel the AIDL spine as UTF-8 bytes.
 */
object BridgeCodec {
    @OptIn(ExperimentalSerializationApi::class) // explicitNulls: omit null fields for a leaner wire form
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): ByteArray =
        json.encodeToString(value).encodeToByteArray()

    inline fun <reified T> decode(bytes: ByteArray): T =
        json.decodeFromString(bytes.decodeToString())

    inline fun <reified T> encodeString(value: T): String =
        json.encodeToString(value)

    inline fun <reified T> decodeString(text: String): T =
        json.decodeFromString(text)
}
