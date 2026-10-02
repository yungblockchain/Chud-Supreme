package com.m3u.tv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.repository.programme.ProgrammeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * "Ask Claude": a chat that knows the person's own library. Claude gets tools to search the
 * catalogue, browse categories, see what's on live TV now, read favourites and recently watched,
 * and start playback, so answers are about things that are actually there to watch.
 */

enum class ClaudeRole { User, Assistant, Notice }

/** One line of the conversation, with the library items the reply talks about. */
@Immutable
data class ClaudeEntry(
    val role: ClaudeRole,
    val text: String,
    val items: List<Channel> = emptyList(),
)

@Immutable
data class ClaudeUiState(
    val configured: Boolean = false,
    val model: ClaudeModel = ClaudeModel.Sonnet,
    val entries: List<ClaudeEntry> = emptyList(),
    val input: String = "",
    val keyInput: String = "",
    val thinking: Boolean = false,
    val checkingKey: Boolean = false,
    val failure: ClaudeFailure? = null,
)

internal enum class ItemKind(val label: String) { Live("live channel"), Film("film"), Series("series") }

@HiltViewModel
class ClaudeViewModel @Inject constructor(
    private val settings: ClaudeSettingsStore,
    private val channelRepository: ChannelRepository,
    private val playlistRepository: PlaylistRepository,
    private val programmeRepository: ProgrammeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ClaudeUiState(configured = settings.hasApiKey(), model = settings.model)
    )
    val state: StateFlow<ClaudeUiState> = _state.asStateFlow()

    /** Items Claude asked to play; App opens them like a click in the Library. */
    private val _play = MutableSharedFlow<Channel>(extraBufferCapacity = 1)
    val play: SharedFlow<Channel> = _play.asSharedFlow()

    /** The conversation in Messages API form. */
    private val history = mutableListOf<JsonObject>()
    private var turnJob: Job? = null
    private val playlistCache = mutableMapOf<String, Playlist?>()
    private val epgRequests = Semaphore(4)

    fun updateInput(value: String) = _state.update { it.copy(input = value) }
    fun updateKeyInput(value: String) = _state.update { it.copy(keyInput = value, failure = null) }

    fun selectModel(model: ClaudeModel) {
        settings.model = model
        _state.update { it.copy(model = model) }
    }

    fun saveKey() {
        val key = _state.value.keyInput.trim()
        if (key.isEmpty() || _state.value.checkingKey) return
        _state.update { it.copy(checkingKey = true, failure = null) }
        viewModelScope.launch {
            try {
                ClaudeApi.checkKey(key)
                settings.saveApiKey(key)
                _state.update { it.copy(configured = true, checkingKey = false, keyInput = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClaudeException) {
                _state.update { it.copy(checkingKey = false, failure = e.failure) }
            } catch (e: Exception) {
                // The Android Keystore can refuse on some devices; don't crash over it.
                _state.update { it.copy(checkingKey = false, failure = ClaudeFailure.Other(null)) }
            }
        }
    }

    fun forgetKey() {
        turnJob?.cancel()
        settings.clearApiKey()
        history.clear()
        _state.update { ClaudeUiState(configured = false, model = it.model) }
    }

    fun clearConversation() {
        turnJob?.cancel()
        history.clear()
        _state.update { it.copy(entries = emptyList(), thinking = false, failure = null) }
    }

    fun send(prompt: String = _state.value.input) {
        val text = prompt.trim()
        if (text.isEmpty() || _state.value.thinking) return
        val apiKey = settings.readApiKey()
        if (apiKey == null) {
            _state.update { it.copy(configured = false, failure = ClaudeFailure.NoKey) }
            return
        }
        _state.update {
            it.copy(
                input = "",
                thinking = true,
                failure = null,
                entries = it.entries + ClaudeEntry(ClaudeRole.User, text),
            )
        }
        history += userText(text)
        turnJob = viewModelScope.launch {
            try {
                val (reply, items) = runTurn(apiKey)
                _state.update {
                    it.copy(
                        thinking = false,
                        entries = it.entries + ClaudeEntry(ClaudeRole.Assistant, reply, items),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClaudeException) {
                // Drop the unanswered question so the next one starts from a valid history.
                trimToLastAnswer()
                _state.update { it.copy(thinking = false, failure = e.failure) }
            } catch (e: Exception) {
                trimToLastAnswer()
                _state.update { it.copy(thinking = false, failure = ClaudeFailure.Other(null)) }
            }
        }
    }

    /** Runs one question to its final answer, executing tool calls along the way. */
    private suspend fun runTurn(apiKey: String): Pair<String, List<Channel>> {
        val seen = linkedMapOf<Int, Channel>()
        repeat(MAX_TOOL_ROUNDS) {
            val response = ClaudeApi.messages(
                apiKey = apiKey,
                model = settings.model,
                system = systemPrompt(),
                tools = TOOLS,
                messages = history.takeLast(MAX_HISTORY_MESSAGES).dropWhileNotUserText(),
            )
            val content = response["content"]?.jsonArray ?: JsonArray(emptyList())
            history += buildJsonObject {
                put("role", "assistant")
                put("content", content)
            }
            val toolUses = content.mapNotNull { block ->
                block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
            }
            val stop = response["stop_reason"]?.jsonPrimitive?.contentOrNull
            if (toolUses.isEmpty() || stop != "tool_use") {
                val text = content
                    .mapNotNull { block ->
                        block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                            ?.get("text")?.jsonPrimitive?.contentOrNull
                    }
                    .joinToString("\n")
                    .replace("**", "")
                    .trim()
                val mentioned = seen.values.filter { channel ->
                    text.contains(channel.title.trim(), ignoreCase = true)
                }.take(MAX_CARDS)
                return text to mentioned
            }
            val results = toolUses.map { use ->
                val id = use["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = use["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val input = use["input"] as? JsonObject ?: JsonObject(emptyMap())
                val (result, error) = try {
                    runTool(name, input, seen) to false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "The tool failed: ${e.message.orEmpty()}" to true
                }
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", id)
                    put("content", result)
                    if (error) put("is_error", true)
                }
            }
            history += buildJsonObject {
                put("role", "user")
                put("content", JsonArray(results))
            }
        }
        return "I got lost looking through your library. Try asking again, a bit more specifically." to
            emptyList()
    }

    /* ------------------------------------------------------------------ tools */

    private suspend fun runTool(name: String, input: JsonObject, seen: MutableMap<Int, Channel>): String =
        when (name) {
            "search_catalog" -> searchCatalog(
                query = input.string("query").orEmpty(),
                kind = input.string("kind"),
                seen = seen,
            )
            "list_categories" -> listCategories(input.string("kind"))
            "browse_category" -> browseCategory(
                kind = input.string("kind"),
                category = input.string("category").orEmpty(),
                offset = input.int("offset") ?: 0,
                seen = seen,
            )
            "whats_on" -> whatsOn(
                query = input.string("query"),
                ids = (input["channel_ids"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    .orEmpty(),
                seen = seen,
            )
            "my_lists" -> myLists(seen)
            "play" -> play(input.int("id"))
            else -> "Unknown tool: $name"
        }

    private suspend fun searchCatalog(query: String, kind: String?, seen: MutableMap<Int, Channel>): String {
        if (query.isBlank()) return "Give a title or part of one to search for."
        val wanted = kindFilter(kind)
        val found = channelRepository.searchUnhidden(query, SEARCH_LIMIT * 3)
            .filter { wanted == null || kindOf(it) == wanted }
            .take(SEARCH_LIMIT)
        found.forEach { seen[it.id] = it }
        return itemsJson(found, total = null)
    }

    private suspend fun listCategories(kind: String?): String {
        val wanted = kindFilter(kind) ?: ItemKind.Film
        val categories = playlistsOf(wanted).flatMap { playlist ->
            channelRepository.getCategoryCounts(playlist.url).take(MAX_CATEGORIES)
        }
        if (categories.isEmpty()) return "No ${wanted.label} playlists are loaded."
        return buildJsonArray {
            categories.forEach { category ->
                addJsonObject {
                    put("category", category.name)
                    put("count", category.count)
                }
            }
        }.toString()
    }

    private suspend fun browseCategory(
        kind: String?,
        category: String,
        offset: Int,
        seen: MutableMap<Int, Channel>,
    ): String {
        val wanted = kindFilter(kind) ?: ItemKind.Film
        for (playlist in playlistsOf(wanted)) {
            val match = channelRepository.getCategoryCounts(playlist.url)
                .firstOrNull { it.name.equals(category, ignoreCase = true) } ?: continue
            val items = channelRepository.getUnhidden(playlist.url, match.name, byTitle = false)
            val page = items.drop(offset.coerceAtLeast(0)).take(BROWSE_LIMIT)
            page.forEach { seen[it.id] = it }
            return itemsJson(page, total = items.size)
        }
        return "There is no ${wanted.label} category called \"$category\". Use list_categories."
    }

    private suspend fun whatsOn(query: String?, ids: List<Int>, seen: MutableMap<Int, Channel>): String {
        val channels = when {
            ids.isNotEmpty() -> ids.take(WHATS_ON_LIMIT).mapNotNull { channelRepository.get(it) }
            !query.isNullOrBlank() -> channelRepository.searchUnhidden(query, WHATS_ON_LIMIT * 4)
                .filter { kindOf(it) == ItemKind.Live }
                .take(WHATS_ON_LIMIT)
            else -> (channelRepository.observeAllFavorite().first().filter { kindOf(it) == ItemKind.Live } +
                listOfNotNull(channelRepository.getPlayedRecently()))
                .distinctBy { it.id }
                .take(WHATS_ON_LIMIT)
        }
        if (channels.isEmpty()) {
            return "No matching live channels. Search with a channel name, or ask about favourites."
        }
        channels.forEach { seen[it.id] = it }
        val now = System.currentTimeMillis()
        val clock = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
        val listings = coroutineScope {
            channels.map { channel ->
                async { channel to epgRequests.withPermit { nowAndNext(channel, now) } }
            }.awaitAll()
        }
        return buildJsonArray {
            listings.forEach { (channel, programmes) ->
                addJsonObject {
                    put("id", channel.id)
                    put("channel", channel.title)
                    putJsonArray("programmes") {
                        programmes.take(3).forEach { programme ->
                            addJsonObject {
                                put("title", programme.title)
                                put("start", clock.format(Date(programme.startMillis)))
                                put("end", clock.format(Date(programme.endMillis)))
                                if (programme.isOnAt(now)) put("on_now", true)
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    private suspend fun nowAndNext(channel: Channel, now: Long): List<GuideProgramme> = try {
        val playlist = playlistFor(channel.playlistUrl)
        val credentials = XtreamCatalog.credentialsFor(playlist)
        val streamId = XtreamCatalog.idFromUrl(channel.url)
        val relationId = channel.relationId
        when {
            credentials != null && streamId != null ->
                XtreamCatalog.shortEpg(credentials, streamId, limit = 3)
            relationId != null && playlist != null && playlist.epgUrls.isNotEmpty() ->
                programmeRepository.getProgrammesInRange(
                    channel.playlistUrl, relationId, now, now + 6 * HOUR_MS
                ).map {
                    GuideProgramme(it.title, it.description, it.start, it.end, null, false)
                }
            else -> emptyList()
        }.filterNot { it.hasEndedBy(now) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun myLists(seen: MutableMap<Int, Channel>): String {
        val favourites = channelRepository.observeAllFavorite().first().take(FAVOURITES_LIMIT)
        val recent = listOfNotNull(channelRepository.getPlayedRecently())
        (favourites + recent).forEach { seen[it.id] = it }
        val favouritesJson = JsonArray(favourites.map { itemJson(it) })
        val recentJson = JsonArray(recent.map { itemJson(it) })
        return buildJsonObject {
            put("favourites", favouritesJson)
            put("last_played", recentJson)
        }.toString()
    }

    private suspend fun play(id: Int?): String {
        val channel = id?.let { channelRepository.get(it) } ?: return "No item with that id."
        _play.tryEmit(channel)
        return when (kindOf(channel)) {
            ItemKind.Live -> "Now playing ${channel.title}."
            else -> "Opened the page for ${channel.title}; the viewer presses Play there."
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private suspend fun playlistFor(url: String): Playlist? {
        if (playlistCache.containsKey(url)) return playlistCache[url]
        val playlist = playlistRepository.get(url)
        playlistCache[url] = playlist
        return playlist
    }

    private suspend fun playlistsOf(kind: ItemKind): List<Playlist> =
        playlistRepository.getAll()
            .filter { it.source != DataSource.EPG }
            .filter { playlistKind(it) == kind }

    private fun playlistKind(playlist: Playlist?): ItemKind = when {
        playlist == null -> ItemKind.Live
        playlist.isVod -> ItemKind.Film
        playlist.isSeries -> ItemKind.Series
        else -> ItemKind.Live
    }

    private suspend fun kindOf(channel: Channel): ItemKind = playlistKind(playlistFor(channel.playlistUrl))

    private fun kindFilter(kind: String?): ItemKind? = when (kind?.lowercase()) {
        "live", "channel", "channels", "tv" -> ItemKind.Live
        "film", "films", "movie", "movies", "vod" -> ItemKind.Film
        "series", "show", "shows", "tv_series" -> ItemKind.Series
        else -> null
    }

    private suspend fun itemJson(channel: Channel): JsonObject {
        val kind = kindOf(channel)
        return buildJsonObject {
            put("id", channel.id)
            put("title", channel.title)
            put("kind", kind.label)
            if (channel.category.isNotBlank()) put("category", channel.category)
        }
    }

    private suspend fun itemsJson(items: List<Channel>, total: Int?): String {
        if (items.isEmpty()) return "Nothing found."
        val array = JsonArray(items.map { itemJson(it) })
        return if (total == null) {
            array.toString()
        } else {
            buildJsonObject {
                put("total_in_category", total)
                put("items", array)
            }.toString()
        }
    }

    private fun systemPrompt(): String {
        val now = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT, Locale.getDefault())
            .format(Date())
        return """
            You are the assistant inside Chud Supreme, an IPTV app on the viewer's Fire TV. It is $now.
            Help them find something to watch in their own library: live TV channels, films and series from their IPTV provider.
            Use the tools to check what's actually in the library and on air before you suggest anything, and use titles exactly as the tools return them.
            Film and series categories come from the provider, and titles often carry a year or tags; you can use your own knowledge of films and shows to pick good ones from what's there.
            When they ask to watch something, call play with its id.
            Replies are read on a TV from the sofa: keep them short (under 80 words), plain text, no markdown, no tables.
        """.trimIndent()
    }

    private fun userText(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", text)
    }

    /** Removes the last question and anything after it that never got a final answer. */
    private fun trimToLastAnswer() {
        val lastQuestion = history.indexOfLast { it.isUserText() }
        if (lastQuestion >= 0) {
            while (history.size > lastQuestion) history.removeAt(history.lastIndex)
        }
        _state.update { state ->
            val entries = state.entries
            val index = entries.indexOfLast { it.role == ClaudeRole.User }
            if (index >= 0 && index == entries.lastIndex) state.copy(entries = entries.dropLast(1)) else state
        }
    }

    private companion object {
        const val MAX_TOOL_ROUNDS = 6
        const val MAX_HISTORY_MESSAGES = 30
        const val MAX_CARDS = 8
        const val SEARCH_LIMIT = 25
        const val BROWSE_LIMIT = 40
        const val MAX_CATEGORIES = 150
        const val WHATS_ON_LIMIT = 12
        const val FAVOURITES_LIMIT = 30
        const val HOUR_MS = 60 * 60_000L

        val TOOLS: JsonArray = buildJsonArray {
            tool(
                name = "search_catalog",
                description = "Search the viewer's library by title. Returns matching live channels, films and series with ids.",
                properties = {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Words from the title, e.g. \"batman\" or \"sky sports\".")
                    }
                    kindProperty()
                },
                required = listOf("query"),
            )
            tool(
                name = "list_categories",
                description = "List the provider's categories (with how many entries each holds) for live TV, films or series.",
                properties = { kindProperty() },
                required = listOf("kind"),
            )
            tool(
                name = "browse_category",
                description = "List entries in one category, 40 at a time, in the provider's order (often newest first).",
                properties = {
                    kindProperty()
                    putJsonObject("category") {
                        put("type", "string")
                        put("description", "Category name exactly as list_categories returns it.")
                    }
                    putJsonObject("offset") {
                        put("type", "integer")
                        put("description", "How many entries to skip, for the next page.")
                    }
                },
                required = listOf("kind", "category"),
            )
            tool(
                name = "whats_on",
                description = "What's on now and next on live channels: found by name, by ids, or (with neither) the viewer's favourite channels.",
                properties = {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Channel name words, e.g. \"sports\" or \"bbc\".")
                    }
                    putJsonObject("channel_ids") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                    }
                },
                required = emptyList(),
            )
            tool(
                name = "my_lists",
                description = "The viewer's favourites and the last thing they played.",
                properties = {},
                required = emptyList(),
            )
            tool(
                name = "play",
                description = "Play a live channel, or open a film's or series' page so the viewer can press Play.",
                properties = {
                    putJsonObject("id") {
                        put("type", "integer")
                        put("description", "The id from another tool's result.")
                    }
                },
                required = listOf("id"),
            )
        }
    }
}

private fun JsonArrayBuilder.tool(
    name: String,
    description: String,
    properties: JsonObjectBuilder.() -> Unit,
    required: List<String>,
) {
    addJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties", properties)
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }
}

private fun JsonObjectBuilder.kindProperty() {
    putJsonObject("kind") {
        put("type", "string")
        putJsonArray("enum") {
            add("live")
            add("film")
            add("series")
        }
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.isUserText(): Boolean =
    this["role"]?.jsonPrimitive?.contentOrNull == "user" && this["content"] is JsonPrimitive

/**
 * The API needs the conversation to start with a plain user question (not a tool result), so a
 * trimmed history is cut back to the first one.
 */
private fun List<JsonObject>.dropWhileNotUserText(): List<JsonObject> {
    val first = indexOfFirst { it.isUserText() }
    return if (first <= 0) this else drop(first)
}
