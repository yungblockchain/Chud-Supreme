package com.m3u.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * Claude, with the person's own Anthropic API key. Requests go straight from the Fire TV to
 * api.anthropic.com; the key is kept on the device, encrypted with a key from the Android
 * Keystore, and never leaves it for anywhere else.
 */

/** Models offered in the app, cheapest and fastest first. */
enum class ClaudeModel(val id: String) {
    Haiku("claude-haiku-4-5-20251001"),
    Sonnet("claude-sonnet-5-5"),
    Opus("claude-opus-5-5"),
}

sealed interface ClaudeFailure {
    data object NoKey : ClaudeFailure
    data object Rejected : ClaudeFailure
    data object NoCredit : ClaudeFailure
    data object RateLimited : ClaudeFailure
    data object Overloaded : ClaudeFailure
    data object Offline : ClaudeFailure
    data class Other(val code: Int?) : ClaudeFailure
}

class ClaudeException(val failure: ClaudeFailure, message: String? = null) : IOException(message)

/** The API key (encrypted at rest) and the chosen model. */
@Singleton
class ClaudeSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("claude_settings", Context.MODE_PRIVATE)

    var model: ClaudeModel
        get() = prefs.getString(KEY_MODEL, null)
            ?.let { name -> ClaudeModel.entries.firstOrNull { it.name == name } }
            ?: ClaudeModel.Sonnet
        set(value) {
            prefs.edit().putString(KEY_MODEL, value.name).apply()
        }

    fun hasApiKey(): Boolean = prefs.contains(KEY_CIPHER)

    fun readApiKey(): String? {
        val stored = prefs.getString(KEY_CIPHER, null) ?: return null
        return runCatching {
            val (iv, data) = stored.split(":").let { it[0] to it[1] }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun saveApiKey(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(data, Base64.NO_WRAP)
        prefs.edit().putString(KEY_CIPHER, stored).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_CIPHER).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_CIPHER = "api_key"
        const val KEY_ALIAS = "chud_streams_claude_api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

/** Minimal Messages API client: one request, answer returned as JSON. */
internal object ClaudeApi {
    private const val BASE = "https://api.anthropic.com/v1"
    private const val VERSION = "2023-06-01"
    private val json = Json { ignoreUnknownKeys = true }

    /** Checks a key by listing models. Throws [ClaudeException] if it doesn't work. */
    suspend fun checkKey(apiKey: String) {
        request(apiKey, "GET", "$BASE/models?limit=1", body = null)
    }

    suspend fun messages(
        apiKey: String,
        model: ClaudeModel,
        system: String,
        tools: JsonArray,
        messages: List<JsonObject>,
        maxTokens: Int = 1024,
    ): JsonObject {
        val body = buildJsonObject {
            put("model", model.id)
            put("max_tokens", maxTokens)
            put("system", system)
            if (tools.isNotEmpty()) put("tools", tools)
            put("messages", JsonArray(messages))
        }
        return request(apiKey, "POST", "$BASE/messages", body.toString()).jsonObject
    }

    private suspend fun request(
        apiKey: String,
        method: String,
        url: String,
        body: String?,
    ): JsonElement = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 90_000
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", VERSION)
                setRequestProperty("content-type", "application/json")
                setRequestProperty("accept", "application/json")
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                val type = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]
                        ?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                val failure = when {
                    code == 401 || code == 403 -> ClaudeFailure.Rejected
                    code == 402 || type == "billing_error" -> ClaudeFailure.NoCredit
                    code == 429 -> ClaudeFailure.RateLimited
                    code == 529 || type == "overloaded_error" -> ClaudeFailure.Overloaded
                    code == 400 && text.contains("credit balance", ignoreCase = true) ->
                        ClaudeFailure.NoCredit
                    else -> ClaudeFailure.Other(code)
                }
                throw ClaudeException(failure, "HTTP $code")
            }
            json.parseToJsonElement(text)
        } catch (e: ClaudeException) {
            throw e
        } catch (e: UnknownHostException) {
            throw ClaudeException(ClaudeFailure.Offline, e.message)
        } catch (e: SocketTimeoutException) {
            throw ClaudeException(ClaudeFailure.Offline, e.message)
        } catch (e: IOException) {
            throw ClaudeException(ClaudeFailure.Other(null), e.message)
        } finally {
            connection?.disconnect()
        }
    }
}
