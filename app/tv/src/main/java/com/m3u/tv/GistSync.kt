package com.m3u.tv

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/* -------------------------------------------------------------------------------------------------
 * Settings sync through a secret GitHub gist: "Save to GitHub" puts the backup (settings, skins,
 * favourites; never the API keys in the key store) in a gist on the person's own account;
 * "Restore from GitHub" on another TV finds it and puts it back. Uses the GitHub token saved in
 * Services (it needs the gist permission).
 *
 * A secret gist isn't listed, but anyone with its link can read it, and a backup still holds
 * things like provider addresses with logins in them. So the gist only ever holds the backup
 * encrypted (AES-GCM) with a key made from the GitHub token: without the token it's noise.
 * ---------------------------------------------------------------------------------------------- */

object GistSync {
    sealed interface Result {
        data class Done(val text: String?) : Result
        data object NoAccess : Result
        data object NotFound : Result
        /** Saved with another GitHub token: it can't be opened with this one. */
        data object Locked : Result
        data object Failed : Result
    }

    private const val API = "https://api.github.com"
    private const val DESCRIPTION = "Chud Supreme backup"
    private const val FILE = "chud-supreme-backup.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Saves [backup] to the backup gist (made the first time). */
    suspend fun save(token: String, backup: String): Result = withContext(Dispatchers.IO) {
        val sealed = runCatching { seal(token, backup) }.getOrNull() ?: return@withContext Result.Failed
        val files = JsonObject(mapOf(FILE to JsonObject(mapOf("content" to JsonPrimitive(sealed)))))
        val existing = when (val found = find(token)) {
            is Found.Id -> found.id
            Found.None -> null
            Found.NoAccess -> return@withContext Result.NoAccess
            Found.Failed -> return@withContext Result.Failed
        }
        fun send(method: String, url: String, body: JsonObject): Int = runCatching {
            client.newCall(request(token, url).method(method, body.toString().toRequestBody(JSON_TYPE)).build()).execute().use { response ->
                if (response.code == 403 && response.header("x-ratelimit-remaining") == "0") RATE_LIMITED else response.code
            }
        }.getOrDefault(-1)
        val create = JsonObject(mapOf("description" to JsonPrimitive(DESCRIPTION), "public" to JsonPrimitive(false), "files" to files))
        var code = if (existing != null) send("PATCH", "$API/gists/$existing", JsonObject(mapOf("files" to files))) else send("POST", "$API/gists", create)
        // The gist went away since it was found: a new one.
        if (existing != null && code == 404) code = send("POST", "$API/gists", create)
        when (code) {
            200, 201 -> Result.Done(null)
            401, 403, 404 -> Result.NoAccess
            else -> Result.Failed
        }
    }

    /** The saved backup's text. */
    suspend fun load(token: String): Result = withContext(Dispatchers.IO) {
        val id = when (val found = find(token)) {
            is Found.Id -> found.id
            Found.None -> return@withContext Result.NotFound
            Found.NoAccess -> return@withContext Result.NoAccess
            Found.Failed -> return@withContext Result.Failed
        }
        runCatching {
            val gist = client.newCall(request(token, "$API/gists/$id").get().build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext if (response.code in setOf(401, 403)) Result.NoAccess else Result.Failed
                json.parseToJsonElement(response.body.string()) as? JsonObject
            } ?: return@withContext Result.Failed
            val file = (gist["files"] as? JsonObject)?.get(FILE) as? JsonObject ?: return@withContext Result.NotFound
            val truncated = file["truncated"]?.jsonPrimitive?.booleanOrNull == true
            val text = if (!truncated) {
                file["content"]?.jsonPrimitive?.contentOrNull
            } else {
                // Big backups come by their raw address instead.
                file["raw_url"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                    client.newCall(request(token, raw).get().build()).execute().use { if (it.isSuccessful) it.body.string() else null }
                }
            }
            when {
                text.isNullOrBlank() -> Result.Failed
                else -> unseal(token, text)?.let { Result.Done(it) } ?: Result.Locked
            }
        }.getOrDefault(Result.Failed)
    }

    private sealed interface Found {
        data class Id(val id: String) : Found
        data object None : Found
        data object NoAccess : Found
        data object Failed : Found
    }

    /** The backup gist among the person's gists, by its description. */
    private fun find(token: String): Found = runCatching {
        var page = 1
        while (page <= MAX_PAGES) {
            val list = client.newCall(request(token, "$API/gists?per_page=100&page=$page").get().build()).execute().use { response ->
                if (response.code == 403 && response.header("x-ratelimit-remaining") == "0") return Found.Failed
                if (response.code == 401 || response.code == 403) return Found.NoAccess
                if (!response.isSuccessful) return Found.Failed
                json.parseToJsonElement(response.body.string()) as? JsonArray
            } ?: return Found.Failed
            list.firstNotNullOfOrNull { element ->
                val gist = element as? JsonObject ?: return@firstNotNullOfOrNull null
                val described = gist["description"]?.jsonPrimitive?.contentOrNull == DESCRIPTION
                val hasFile = (gist["files"] as? JsonObject)?.containsKey(FILE) == true
                if (described && hasFile) gist["id"]?.jsonPrimitive?.contentOrNull else null
            }?.let { return Found.Id(it) }
            if (list.size < 100) return Found.None
            page++
        }
        Found.None
    }.getOrDefault(Found.Failed)

    /* ---------------------------------------------------------------------- encryption */

    private fun keyFor(token: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(token.toCharArray(), salt, KEY_ROUNDS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun seal(token: String, plain: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(token, salt), GCMParameterSpec(TAG_BITS, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val base64 = Base64.getEncoder()
        return JsonObject(
            mapOf(
                "format" to JsonPrimitive(SEALED_FORMAT),
                "salt" to JsonPrimitive(base64.encodeToString(salt)),
                "iv" to JsonPrimitive(base64.encodeToString(iv)),
                "data" to JsonPrimitive(base64.encodeToString(data)),
            )
        ).toString()
    }

    /** The backup inside [text]; null when this token can't open it. */
    private fun unseal(token: String, text: String): String? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        if (root["format"]?.jsonPrimitive?.contentOrNull != SEALED_FORMAT) return null
        val base64 = Base64.getDecoder()
        fun field(name: String) = base64.decode(root[name]?.jsonPrimitive?.contentOrNull ?: error("missing $name"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyFor(token, field("salt")), GCMParameterSpec(TAG_BITS, field("iv")))
        String(cipher.doFinal(field("data")), Charsets.UTF_8)
    }.getOrNull()

    private fun request(token: String, url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "ChudSupreme")

    private val JSON_TYPE = "application/json".toMediaType()
    private const val MAX_PAGES = 10
    private const val RATE_LIMITED = 429
    private const val SEALED_FORMAT = "chud-sealed/1"
    private const val KEY_ROUNDS = 60_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
}
