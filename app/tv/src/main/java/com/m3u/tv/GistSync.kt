package com.m3u.tv

import java.util.concurrent.TimeUnit
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
 * favourites, never API keys) in a gist on the person's own account; "Restore from GitHub" on
 * another TV finds it and puts it back. Uses the GitHub token saved in Services (it needs the
 * gist permission). A secret gist isn't listed anywhere, but anyone with its link can read it,
 * which is why keys stay out.
 * ---------------------------------------------------------------------------------------------- */

object GistSync {
    sealed interface Result {
        data class Done(val text: String?) : Result
        data object NoAccess : Result
        data object NotFound : Result
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
        val files = JsonObject(mapOf(FILE to JsonObject(mapOf("content" to JsonPrimitive(backup)))))
        val existing = when (val found = find(token)) {
            is Found.Id -> found.id
            Found.None -> null
            Found.NoAccess -> return@withContext Result.NoAccess
            Found.Failed -> return@withContext Result.Failed
        }
        val (method, url, body) = if (existing != null) {
            Triple("PATCH", "$API/gists/$existing", JsonObject(mapOf("files" to files)))
        } else {
            Triple(
                "POST", "$API/gists",
                JsonObject(mapOf("description" to JsonPrimitive(DESCRIPTION), "public" to JsonPrimitive(false), "files" to files)),
            )
        }
        val code = runCatching {
            client.newCall(request(token, url).method(method, body.toString().toRequestBody(JSON_TYPE)).build()).execute().use { it.code }
        }.getOrDefault(-1)
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
            if (text.isNullOrBlank()) Result.Failed else Result.Done(text)
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

    private fun request(token: String, url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "ChudSupreme")

    private val JSON_TYPE = "application/json".toMediaType()
    private const val MAX_PAGES = 5
}
