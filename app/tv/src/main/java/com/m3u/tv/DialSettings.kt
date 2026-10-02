package com.m3u.tv

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * Dial settings, modelled on TiviMate's player/startup options. Stored in plain SharedPreferences
 * inside the TV module so upstream's settings schema stays untouched.
 * ---------------------------------------------------------------------------------------------- */

enum class DialStartup { Home, LastChannel, Guide }

enum class DialAspect { Fit, Stretch, Zoom }

enum class DialGuideLayout { Grid, List }

/**
 * Which player opens streams: the built-in one (Media3 ExoPlayer, with the channel banner, zapping
 * and catch-up controls), VLC, or a pick from the installed video players each time.
 */
enum class DialPlayer { BuiltIn, Vlc, Ask }

@Immutable
data class DialPreferences(
    val startup: DialStartup = DialStartup.Home,
    val controlsTimeoutSeconds: Int = 5,
    val invertChannelKeys: Boolean = false,
    val showChannelNumbers: Boolean = true,
    val showChannelBanner: Boolean = true,
    val aspect: DialAspect = DialAspect.Fit,
    val skipBackSeconds: Int = 10,
    val skipAheadSeconds: Int = 30,
    val resumePlayback: Boolean = true,
    val guideLayout: DialGuideLayout = DialGuideLayout.Grid,
    val launchAnimation: Boolean = true,
    val matchFrameRate: Boolean = false,
    val player: DialPlayer = DialPlayer.BuiltIn,
    /** On the main menu, Back twice in a row closes the app completely (frees its memory). */
    val backTwiceToExit: Boolean = true,
    /** Subtitle text size, as a percentage of the normal size. */
    val subtitleSizePercent: Int = 100,
    /** Films and series: start the next episode a few seconds after one ends. */
    val autoplayNextEpisode: Boolean = true,
    /** Menus at the fastest refresh rate the Fire TV offers (120 Hz where it lists 120 Hz). */
    val fastMenus: Boolean = true,
    /** Live TV asks the TV for a 120 Hz mode when the Fire Stick lists one. */
    val live120: Boolean = false,
    /** Send crash and error reports to the person's GitHub repository when the app starts. */
    val autoSendReports: Boolean = false,
    /** Back in the player shrinks the video into a corner instead of closing it. */
    val backToMini: Boolean = true,
) {
    companion object {
        val SUBTITLE_SIZE_OPTIONS = listOf(75, 100, 125, 150, 200)
        val CONTROLS_TIMEOUT_OPTIONS = listOf(3, 5, 8, 12)
        val SKIP_BACK_OPTIONS = listOf(1, 5, 10, 30, 60, 120)
        val SKIP_AHEAD_OPTIONS = listOf(1, 5, 10, 30, 60, 120)
    }
}

/** A named list of favourite channels ("Sports", "Kids"), in the order they were added. */
@Immutable
data class FavouriteGroup(
    val id: String,
    val name: String,
    val channelIds: List<Int>,
)

/** How one playlist's categories are arranged: moved ones first, hidden ones left out. */
@Immutable
data class CategoryLayout(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
) {
    /** [names] in this layout's order (unmoved ones keep the provider's order), minus hidden. */
    fun <T> arrange(items: List<T>, name: (T) -> String): List<T> {
        val position = order.withIndex().associate { (index, value) -> value to index }
        return items
            .filterNot { name(it) in hidden }
            .withIndex()
            .sortedWith(
                compareBy(
                    { position[name(it.value)] ?: Int.MAX_VALUE },
                    { it.index },
                )
            )
            .map { it.value }
    }
}

/** The last episode opened for a series, so "Continue S2 E5" can pick up from there. */
@Immutable
data class SeriesProgress(
    val season: String,
    val episodeId: String,
    val episodeNum: String?,
    val title: String?,
    val containerExtension: String?,
)

@Singleton
class DialSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("dial_settings", Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(readPreferences())
    val preferences: StateFlow<DialPreferences> = _preferences.asStateFlow()

    private val _history = MutableStateFlow(readHistory())

    private val _watchlist = MutableStateFlow(
        prefs.getString(KEY_WATCHLIST, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            .orEmpty()
    )

    /** Markets tab watchlist, as "chainId:tokenAddress" entries in the order they were added. */
    val watchlist: StateFlow<List<String>> = _watchlist.asStateFlow()

    fun toggleWatch(key: String) {
        val current = _watchlist.value
        val next = if (key in current) current - key else current + key
        _watchlist.value = next
        prefs.edit().putString(KEY_WATCHLIST, next.joinToString("\n")).apply()
    }

    private val _favouriteGroups = MutableStateFlow(readGroups())

    /** Favourite groups, in the order they're shown. */
    val favouriteGroups: StateFlow<List<FavouriteGroup>> = _favouriteGroups.asStateFlow()

    fun createGroup(name: String, firstChannelId: Int? = null): FavouriteGroup {
        val group = FavouriteGroup(
            id = System.currentTimeMillis().toString(36),
            name = name.trim().take(MAX_GROUP_NAME),
            channelIds = listOfNotNull(firstChannelId),
        )
        saveGroups(_favouriteGroups.value + group)
        return group
    }

    fun toggleInGroup(groupId: String, channelId: Int) = saveGroups(
        _favouriteGroups.value.map { group ->
            if (group.id != groupId) group
            else if (channelId in group.channelIds) group.copy(channelIds = group.channelIds - channelId)
            else group.copy(channelIds = group.channelIds + channelId)
        }
    )

    fun moveInGroup(groupId: String, channelId: Int, delta: Int) = saveGroups(
        _favouriteGroups.value.map { group ->
            if (group.id != groupId) return@map group
            val ids = group.channelIds.toMutableList()
            val from = ids.indexOf(channelId)
            if (from < 0) return@map group
            val to = (from + delta).coerceIn(0, ids.lastIndex)
            ids.add(to, ids.removeAt(from))
            group.copy(channelIds = ids)
        }
    )

    fun moveGroup(groupId: String, delta: Int) {
        val groups = _favouriteGroups.value.toMutableList()
        val from = groups.indexOfFirst { it.id == groupId }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, groups.lastIndex)
        groups.add(to, groups.removeAt(from))
        saveGroups(groups)
    }

    fun deleteGroup(groupId: String) = saveGroups(_favouriteGroups.value.filterNot { it.id == groupId })

    private fun saveGroups(groups: List<FavouriteGroup>) {
        _favouriteGroups.value = groups
        val json = JsonArray(groups.map { group ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(group.id),
                    "name" to JsonPrimitive(group.name),
                    "channels" to JsonArray(group.channelIds.map(::JsonPrimitive)),
                )
            )
        })
        prefs.edit().putString(KEY_FAVOURITE_GROUPS, json.toString()).apply()
    }

    private fun readGroups(): List<FavouriteGroup> = runCatching {
        val raw = prefs.getString(KEY_FAVOURITE_GROUPS, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            FavouriteGroup(
                id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = item["name"]?.jsonPrimitive?.content.orEmpty(),
                channelIds = item["channels"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
                    .orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    private val _categoryLayouts = MutableStateFlow(readCategoryLayouts())

    /** Category order and hidden categories, by playlist URL. */
    val categoryLayouts: StateFlow<Map<String, CategoryLayout>> = _categoryLayouts.asStateFlow()

    /** Moves [name] by [delta] places among [shown] (the categories as currently displayed). */
    fun moveCategory(playlistUrl: String, shown: List<String>, name: String, delta: Int) {
        val order = shown.toMutableList()
        val from = order.indexOf(name)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, order.lastIndex)
        order.add(to, order.removeAt(from))
        updateLayout(playlistUrl) { it.copy(order = order) }
    }

    fun moveCategoryToFront(playlistUrl: String, shown: List<String>, name: String) =
        moveCategory(playlistUrl, shown, name, -shown.size)

    fun hideCategory(playlistUrl: String, name: String) =
        updateLayout(playlistUrl) { it.copy(hidden = it.hidden + name) }

    fun showAllCategories(playlistUrl: String) =
        updateLayout(playlistUrl) { it.copy(hidden = emptySet()) }

    fun resetCategories(playlistUrl: String) = updateLayout(playlistUrl) { CategoryLayout() }

    private fun updateLayout(playlistUrl: String, transform: (CategoryLayout) -> CategoryLayout) {
        val next = _categoryLayouts.value.toMutableMap()
        next[playlistUrl] = transform(next[playlistUrl] ?: CategoryLayout())
        _categoryLayouts.value = next
        val json = JsonObject(next.mapValues { (_, layout) ->
            JsonObject(
                mapOf(
                    "order" to JsonArray(layout.order.map(::JsonPrimitive)),
                    "hidden" to JsonArray(layout.hidden.map(::JsonPrimitive)),
                )
            )
        })
        prefs.edit().putString(KEY_CATEGORY_LAYOUTS, json.toString()).apply()
    }

    private fun readCategoryLayouts(): Map<String, CategoryLayout> = runCatching {
        val raw = prefs.getString(KEY_CATEGORY_LAYOUTS, null) ?: return emptyMap()
        Json.parseToJsonElement(raw).jsonObject.mapValues { (_, value) ->
            val item = value.jsonObject
            CategoryLayout(
                order = item["order"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                hidden = item["hidden"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
            )
        }
    }.getOrDefault(emptyMap())

    /** Films and series opened most recently first, as channel ids. */
    val history: StateFlow<List<Int>> = _history.asStateFlow()

    fun update(transform: (DialPreferences) -> DialPreferences) {
        val next = transform(_preferences.value)
        _preferences.value = next
        prefs.edit()
            .putString(KEY_STARTUP, next.startup.name)
            .putInt(KEY_CONTROLS_TIMEOUT, next.controlsTimeoutSeconds)
            .putBoolean(KEY_INVERT_CHANNEL_KEYS, next.invertChannelKeys)
            .putBoolean(KEY_SHOW_CHANNEL_NUMBERS, next.showChannelNumbers)
            .putBoolean(KEY_SHOW_CHANNEL_BANNER, next.showChannelBanner)
            .putString(KEY_ASPECT, next.aspect.name)
            .putInt(KEY_SKIP_BACK, next.skipBackSeconds)
            .putInt(KEY_SKIP_AHEAD, next.skipAheadSeconds)
            .putBoolean(KEY_RESUME, next.resumePlayback)
            .putString(KEY_GUIDE_LAYOUT, next.guideLayout.name)
            .putBoolean(KEY_LAUNCH_ANIMATION, next.launchAnimation)
            .putBoolean(KEY_MATCH_FRAME_RATE, next.matchFrameRate)
            .putString(KEY_PLAYER, next.player.name)
            .putBoolean(KEY_BACK_TWICE_TO_EXIT, next.backTwiceToExit)
            .putInt(KEY_SUBTITLE_SIZE, next.subtitleSizePercent)
            .putBoolean(KEY_AUTOPLAY_NEXT, next.autoplayNextEpisode)
            .putBoolean(KEY_FAST_MENUS, next.fastMenus)
            .putBoolean(KEY_LIVE_120, next.live120)
            .putBoolean(KEY_AUTO_SEND_REPORTS, next.autoSendReports)
            .putBoolean(KEY_BACK_TO_MINI, next.backToMini)
            .apply()
    }

    var lastChannelId: Int?
        get() = prefs.getInt(KEY_LAST_CHANNEL, -1).takeIf { it >= 0 }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_CHANNEL) else putInt(KEY_LAST_CHANNEL, value)
            }.apply()
        }

    fun recordOnDemand(channelId: Int) {
        val next = (listOf(channelId) + _history.value.filter { it != channelId }).take(MAX_HISTORY)
        _history.value = next
        prefs.edit().putString(KEY_HISTORY, next.joinToString(",")).apply()
    }

    fun forget(channelId: Int) {
        val next = _history.value.filter { it != channelId }
        _history.value = next
        prefs.edit().putString(KEY_HISTORY, next.joinToString(",")).apply()
    }

    fun clearHistory() {
        _history.value = emptyList()
        val editor = prefs.edit().remove(KEY_HISTORY)
        prefs.all.keys.filter { it.startsWith(KEY_SERIES_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    fun seriesProgress(seriesChannelId: Int): SeriesProgress? {
        val raw = prefs.getString("$KEY_SERIES_PREFIX$seriesChannelId", null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size < 5) return null
        return SeriesProgress(
            season = parts[0],
            episodeId = parts[1],
            episodeNum = parts[2].ifEmpty { null },
            title = parts[3].ifEmpty { null },
            containerExtension = parts[4].ifEmpty { null },
        )
    }

    fun saveSeriesProgress(seriesChannelId: Int, progress: SeriesProgress) {
        val raw = listOf(
            progress.season,
            progress.episodeId,
            progress.episodeNum.orEmpty(),
            progress.title.orEmpty().replace(SEPARATOR, " "),
            progress.containerExtension.orEmpty(),
        ).joinToString(SEPARATOR)
        prefs.edit().putString("$KEY_SERIES_PREFIX$seriesChannelId", raw).apply()
    }

    fun highScore(game: String): Int = prefs.getInt("$KEY_HIGH_SCORE_PREFIX$game", 0)

    fun saveHighScore(game: String, score: Int) {
        prefs.edit().putInt("$KEY_HIGH_SCORE_PREFIX$game", score).apply()
    }

    private fun readPreferences(): DialPreferences {
        val defaults = DialPreferences()
        return DialPreferences(
            startup = prefs.getString(KEY_STARTUP, null)
                ?.let { name -> DialStartup.entries.firstOrNull { it.name == name } }
                ?: defaults.startup,
            controlsTimeoutSeconds = prefs.getInt(KEY_CONTROLS_TIMEOUT, defaults.controlsTimeoutSeconds),
            invertChannelKeys = prefs.getBoolean(KEY_INVERT_CHANNEL_KEYS, defaults.invertChannelKeys),
            showChannelNumbers = prefs.getBoolean(KEY_SHOW_CHANNEL_NUMBERS, defaults.showChannelNumbers),
            showChannelBanner = prefs.getBoolean(KEY_SHOW_CHANNEL_BANNER, defaults.showChannelBanner),
            aspect = prefs.getString(KEY_ASPECT, null)
                ?.let { name -> DialAspect.entries.firstOrNull { it.name == name } }
                ?: defaults.aspect,
            skipBackSeconds = prefs.getInt(KEY_SKIP_BACK, defaults.skipBackSeconds),
            skipAheadSeconds = prefs.getInt(KEY_SKIP_AHEAD, defaults.skipAheadSeconds),
            resumePlayback = prefs.getBoolean(KEY_RESUME, defaults.resumePlayback),
            guideLayout = prefs.getString(KEY_GUIDE_LAYOUT, null)
                ?.let { name -> DialGuideLayout.entries.firstOrNull { it.name == name } }
                ?: defaults.guideLayout,
            launchAnimation = prefs.getBoolean(KEY_LAUNCH_ANIMATION, defaults.launchAnimation),
            matchFrameRate = prefs.getBoolean(KEY_MATCH_FRAME_RATE, defaults.matchFrameRate),
            player = prefs.getString(KEY_PLAYER, null)
                ?.let { name -> DialPlayer.entries.firstOrNull { it.name == name } }
                ?: defaults.player,
            backTwiceToExit = prefs.getBoolean(KEY_BACK_TWICE_TO_EXIT, defaults.backTwiceToExit),
            subtitleSizePercent = prefs.getInt(KEY_SUBTITLE_SIZE, defaults.subtitleSizePercent),
            autoplayNextEpisode = prefs.getBoolean(KEY_AUTOPLAY_NEXT, defaults.autoplayNextEpisode),
            fastMenus = prefs.getBoolean(KEY_FAST_MENUS, defaults.fastMenus),
            live120 = prefs.getBoolean(KEY_LIVE_120, defaults.live120),
            autoSendReports = prefs.getBoolean(KEY_AUTO_SEND_REPORTS, defaults.autoSendReports),
            backToMini = prefs.getBoolean(KEY_BACK_TO_MINI, defaults.backToMini),
        )
    }

    private fun readHistory(): List<Int> =
        prefs.getString(KEY_HISTORY, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()

    private companion object {
        const val KEY_STARTUP = "startup"
        const val KEY_CONTROLS_TIMEOUT = "controls_timeout"
        const val KEY_INVERT_CHANNEL_KEYS = "invert_channel_keys"
        const val KEY_SHOW_CHANNEL_NUMBERS = "show_channel_numbers"
        const val KEY_SHOW_CHANNEL_BANNER = "show_channel_banner"
        const val KEY_ASPECT = "aspect"
        const val KEY_SKIP_BACK = "skip_back"
        const val KEY_SKIP_AHEAD = "skip_ahead"
        const val KEY_RESUME = "resume"
        const val KEY_GUIDE_LAYOUT = "guide_layout"
        const val KEY_LAUNCH_ANIMATION = "launch_animation"
        const val KEY_MATCH_FRAME_RATE = "match_frame_rate"
        const val KEY_PLAYER = "player"
        const val KEY_BACK_TWICE_TO_EXIT = "back_twice_to_exit"
        const val KEY_SUBTITLE_SIZE = "subtitle_size"
        const val KEY_AUTOPLAY_NEXT = "autoplay_next_episode"
        const val KEY_FAST_MENUS = "fast_menus"
        const val KEY_LIVE_120 = "live_120"
        const val KEY_AUTO_SEND_REPORTS = "auto_send_reports"
        const val KEY_BACK_TO_MINI = "back_to_mini"
        const val KEY_LAST_CHANNEL = "last_channel"
        const val KEY_HISTORY = "on_demand_history"
        const val KEY_FAVOURITE_GROUPS = "favourite_groups"
        const val KEY_CATEGORY_LAYOUTS = "category_layouts"
        const val MAX_GROUP_NAME = 40
        const val KEY_WATCHLIST = "markets_watchlist"
        const val KEY_HIGH_SCORE_PREFIX = "high_score_"
        const val KEY_SERIES_PREFIX = "series_progress_"
        const val SEPARATOR = "\u001F"
        const val MAX_HISTORY = 24
    }
}

/** Cycles to the next value in [options], starting over after the last one. */
internal fun <T> List<T>.nextAfter(current: T): T {
    val index = indexOf(current)
    return if (index < 0 || index == lastIndex) first() else get(index + 1)
}
