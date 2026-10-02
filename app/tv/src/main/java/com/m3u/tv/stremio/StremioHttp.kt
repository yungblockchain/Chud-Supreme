package com.m3u.tv.stremio

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal class StremioHttpException(val code: Int?, message: String) : IOException(message)

internal object StremioHttp {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    suspend fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = request(url, "GET", headers, null)

    suspend fun postForm(
        url: String,
        headers: Map<String, String> = emptyMap(),
        fields: Map<String, String>,
    ): JsonElement {
        val body = fields.entries.joinToString("&") { (name, value) ->
            "${enc(name)}=${enc(value)}"
        }
        return request(
            url,
            "POST",
            headers + ("Content-Type" to "application/x-www-form-urlencoded"),
            body.toByteArray(Charsets.UTF_8),
        )
    }

    suspend fun postJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
    ): JsonElement = request(
        url,
        "POST",
        headers + ("Content-Type" to "application/json"),
        body.toByteArray(Charsets.UTF_8),
    )

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            val connection = connect(url, "GET", headers, null)
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.readBytes() ?: ByteArray(0)
                if (code !in 200..299) {
                    throw StremioHttpException(code, bytes.decodeToString().take(240).ifBlank { "HTTP $code" })
                }
                bytes
            } finally {
                connection.disconnect()
            }
        }

    suspend fun ping(url: String): Boolean = withContext(Dispatchers.IO) {
        val connection = connect(url, "GET", emptyMap(), null)
        try {
            connection.responseCode in 200..399
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): JsonElement = withContext(Dispatchers.IO) {
        val connection = connect(url, method, headers, body)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw StremioHttpException(code, text.take(240).ifBlank { "HTTP $code" })
            }
            if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StremioHttpException) {
            throw e
        } catch (e: Exception) {
            throw StremioHttpException(null, e.message ?: "network")
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ChudMAXXX/1.0 (Android TV; Stremio)")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }
        return connection
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

internal fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonElement.asObject(): JsonObject? = runCatching { jsonObject }.getOrNull()
