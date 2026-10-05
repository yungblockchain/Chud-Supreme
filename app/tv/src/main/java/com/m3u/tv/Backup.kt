package com.m3u.tv

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.text.DateFormat
import java.util.Date
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.dao.ChannelDao
import com.m3u.data.repository.channel.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/* -------------------------------------------------------------------------------------------------
 * Backup and restore: one JSON file with every setting (player, appearance, Infinity layout,
 * addons, profiles, media server, groups, markers, bookmarks), the custom skins, and the
 * favourites by address. Keys are included only when asked. The phone page downloads it and
 * takes it back; the stick also keeps its last backup for a one-press restore.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class BackupSummary(val settings: Int, val skins: Int, val favourites: Int, val keys: Int, val takenAt: Long)

sealed interface BackupNotice {
    data class Saved(val summary: BackupSummary) : BackupNotice
    data class Restored(val summary: BackupSummary) : BackupNotice
    data object NotABackup : BackupNotice
    data object Failed : BackupNotice
}

@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
    private val channelRepository: ChannelRepository,
    private val channelDao: ChannelDao,
    private val settings: DialSettingsStore,
    private val skins: SkinStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The last backup kept on the stick, if any. */
    val keptFile: File get() = File(context.filesDir, KEPT_NAME)

    suspend fun create(includeKeys: Boolean): String = withContext(Dispatchers.IO) {
        val prefs = JsonObject(
            PREF_FILES.associateWith { name -> prefsToJson(name) }.filterValues { it.isNotEmpty() },
        )
        val skinFiles = JsonArray(skins.custom.value.map { it.toJson() })
        val favourites = JsonArray(
            runCatching { channelRepository.observeAllFavorite().first() }.getOrDefault(emptyList()).map { channel ->
                JsonObject(mapOf("url" to JsonPrimitive(channel.url), "title" to JsonPrimitive(channel.title)))
            }
        )
        val keys = if (includeKeys) {
            JsonObject(
                SecretName.entries.mapNotNull { name -> secrets.get(name)?.let { name.key to JsonPrimitive(it) } }.toMap()
            )
        } else null
        val root = JsonObject(
            buildMap {
                put("format", JsonPrimitive(FORMAT))
                put("takenAt", JsonPrimitive(System.currentTimeMillis()))
                put("prefs", prefs)
                put("skins", skinFiles)
                put("favourites", favourites)
                keys?.let { put("keys", it) }
            }
        )
        val text = root.toString()
        runCatching { keptFile.writeText(text) }
        text
    }

    /** Puts a backup back; null when the text isn't one of ours. */
    suspend fun restore(text: String): BackupSummary? = withContext(Dispatchers.IO) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@withContext null
        if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) return@withContext null
        var settingsCount = 0
        (root["prefs"] as? JsonObject).orEmpty().forEach { (name, values) ->
            if (name !in PREF_FILES) return@forEach
            settingsCount += jsonToPrefs(name, values as? JsonObject ?: return@forEach)
        }
        val skinList = (root["skins"] as? JsonArray).orEmpty()
        skinList.forEach { element -> runCatching { skins.importSkin(element.toString()) } }
        val favourites = (root["favourites"] as? JsonArray).orEmpty()
        var restoredFavourites = 0
        favourites.forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val url = item["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val title = item["title"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            // The same stream, found by its title among the playlists this box has now.
            val channel = runCatching { channelRepository.searchUnhidden(title, FAVOURITE_MATCHES) }
                .getOrDefault(emptyList())
                .firstOrNull { it.url == url } ?: return@forEach
            if (!channel.favourite) runCatching { channelDao.favouriteOrUnfavourite(channel.id, true) }
            restoredFavourites++
        }
        var keyCount = 0
        (root["keys"] as? JsonObject).orEmpty().forEach { (key, value) ->
            val name = SecretName.entries.firstOrNull { it.key == key } ?: return@forEach
            val text = value.jsonPrimitive.contentOrNull ?: return@forEach
            secrets.put(name, text)
            keyCount++
        }
        // Settings read at start-up go live on the next launch; what has a store refreshes now.
        settings.reload()
        BackupSummary(
            settings = settingsCount,
            skins = skinList.size,
            favourites = restoredFavourites,
            keys = keyCount,
            takenAt = root["takenAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    fun summaryOfKept(): BackupSummary? {
        val text = runCatching { keptFile.takeIf { it.exists() }?.readText() }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return BackupSummary(
            settings = (root["prefs"] as? JsonObject).orEmpty().values.sumOf { (it as? JsonObject)?.size ?: 0 },
            skins = (root["skins"] as? JsonArray)?.size ?: 0,
            favourites = (root["favourites"] as? JsonArray)?.size ?: 0,
            keys = (root["keys"] as? JsonObject)?.size ?: 0,
            takenAt = root["takenAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    private fun prefsToJson(name: String): JsonObject {
        val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        return JsonObject(
            all.mapNotNull { (key, value) ->
                val encoded: JsonObject = when (value) {
                    is String -> typed("s", JsonPrimitive(value))
                    is Int -> typed("i", JsonPrimitive(value))
                    is Long -> typed("l", JsonPrimitive(value))
                    is Boolean -> typed("b", JsonPrimitive(value))
                    is Float -> typed("f", JsonPrimitive(value))
                    is Set<*> -> typed("set", JsonArray(value.filterIsInstance<String>().map(::JsonPrimitive)))
                    else -> return@mapNotNull null
                }
                key to encoded
            }.toMap()
        )
    }

    private fun typed(type: String, value: JsonElement) = JsonObject(mapOf("t" to JsonPrimitive(type), "v" to value))

    private fun jsonToPrefs(name: String, values: JsonObject): Int {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        var count = 0
        values.forEach { (key, element) ->
            val item = element as? JsonObject ?: return@forEach
            val value = item["v"] ?: return@forEach
            when (item["t"]?.jsonPrimitive?.contentOrNull) {
                "s" -> value.jsonPrimitive.contentOrNull?.let { editor.putString(key, it) }
                "i" -> value.jsonPrimitive.intOrNull?.let { editor.putInt(key, it) }
                "l" -> value.jsonPrimitive.longOrNull?.let { editor.putLong(key, it) }
                "b" -> value.jsonPrimitive.booleanOrNull?.let { editor.putBoolean(key, it) }
                "f" -> value.jsonPrimitive.floatOrNull?.let { editor.putFloat(key, it) }
                "set" -> editor.putStringSet(key, (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet())
                else -> return@forEach
            }
            count++
        }
        editor.commit()
        return count
    }

    companion object {
        const val FORMAT = "chud-backup/1"
        private const val FAVOURITE_MATCHES = 20
        private const val KEPT_NAME = "last-backup.json"
        val PREF_FILES = listOf(
            "dial_settings", "appearance", "infinity_layout", "stremio_addons", "profiles",
            "media_server", "infinity_shelf", "playback_settings",
        )
    }
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: BackupService,
) : ViewModel() {
    private val _notice = MutableStateFlow<BackupNotice?>(null)
    val notice: StateFlow<BackupNotice?> = _notice.asStateFlow()
    private val _kept = MutableStateFlow(backup.summaryOfKept())
    val kept: StateFlow<BackupSummary?> = _kept.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun saveToStick(includeKeys: Boolean) = run {
        _busy.value = true
        viewModelScope.launch {
            val result = runCatching { backup.create(includeKeys) }
            _kept.value = backup.summaryOfKept()
            _notice.value = result.fold({ _kept.value?.let { BackupNotice.Saved(it) } ?: BackupNotice.Failed }, {
                if (it is CancellationException) throw it
                BackupNotice.Failed
            })
            _busy.value = false
        }
    }

    fun restoreKept() = run {
        val text = runCatching { backup.keptFile.readText() }.getOrNull() ?: return
        restoreText(text)
    }

    fun restoreText(text: String) {
        _busy.value = true
        viewModelScope.launch {
            val result = runCatching { backup.restore(text) }
            _notice.value = result.fold(
                { summary -> if (summary == null) BackupNotice.NotABackup else BackupNotice.Restored(summary) },
                {
                    if (it is CancellationException) throw it
                    BackupNotice.Failed
                },
            )
            _busy.value = false
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}

/** The Backup and restore rows in Settings. */
@Composable
fun BackupRows() {
    val viewModel: BackupViewModel = hiltViewModel()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val kept by viewModel.kept.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var includeKeys by rememberSaveable { mutableStateOf(false) }
    val on = stringResource(R.string.dial_value_on)
    val off = stringResource(R.string.dial_value_off)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dial_backup_hint),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
            modifier = Modifier.widthIn(max = 820.dp),
        )
        SettingRow(
            label = stringResource(R.string.dial_backup_include_keys),
            value = if (includeKeys) on else off,
            onClick = { includeKeys = !includeKeys },
        )
        SettingRow(
            label = stringResource(R.string.dial_backup_save),
            value = if (busy) stringResource(R.string.dial_backup_working) else "",
            onClick = { if (!busy) viewModel.saveToStick(includeKeys) },
        )
        kept?.let { summary ->
            SettingRow(
                label = stringResource(R.string.dial_backup_restore_kept),
                value = stringResource(
                    R.string.dial_backup_kept_summary,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(summary.takenAt)),
                    summary.favourites,
                ),
                onClick = { if (!busy) viewModel.restoreKept() },
            )
        }
        notice?.let { current ->
            Text(
                text = when (current) {
                    is BackupNotice.Saved -> stringResource(R.string.dial_backup_saved, current.summary.settings, current.summary.skins, current.summary.favourites)
                    is BackupNotice.Restored -> stringResource(R.string.dial_backup_restored, current.summary.settings, current.summary.skins, current.summary.favourites)
                    BackupNotice.NotABackup -> stringResource(R.string.dial_backup_not_a_backup)
                    BackupNotice.Failed -> stringResource(R.string.dial_backup_failed)
                },
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
        }
    }
}
