package com.m3u.tv

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * The lights follow the film. Philips Hue: the app finds the bridge on the home network, pairs
 * with a press of the bridge's button, and dims the chosen room while something plays (back up
 * on pause or stop). Home Assistant: the person's own scenes or scripts, one for "playing" and one
 * for "stopped", run through its API with a long-lived token kept in the encrypted key store.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class SmartHomeSettings(
    val hueIp: String? = null,
    val hueUser: String? = null,
    val hueGroup: String? = null,
    val hueGroupName: String? = null,
    /** How bright the room goes while playing, in percent (0 = off). */
    val hueDimPercent: Int = 15,
    val haUrl: String = "",
    val haPlaying: String = "",
    val haStopped: String = "",
) {
    val huePaired: Boolean get() = hueIp != null && hueUser != null
    val hueReady: Boolean get() = huePaired && hueGroup != null
    val haReady: Boolean get() = haUrl.isNotBlank() && (haPlaying.isNotBlank() || haStopped.isNotBlank())
}

@Singleton
class SmartHomeStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("smart_home", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<SmartHomeSettings> = _settings.asStateFlow()

    fun update(transform: (SmartHomeSettings) -> SmartHomeSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString("hue_ip", next.hueIp)
            .putString("hue_user", next.hueUser)
            .putString("hue_group", next.hueGroup)
            .putString("hue_group_name", next.hueGroupName)
            .putInt("hue_dim", next.hueDimPercent)
            .putString("ha_url", next.haUrl)
            .putString("ha_playing", next.haPlaying)
            .putString("ha_stopped", next.haStopped)
            .apply()
    }

    fun reload() {
        _settings.value = read()
    }

    private fun read() = SmartHomeSettings(
        hueIp = prefs.getString("hue_ip", null),
        hueUser = prefs.getString("hue_user", null),
        hueGroup = prefs.getString("hue_group", null),
        hueGroupName = prefs.getString("hue_group_name", null),
        hueDimPercent = prefs.getInt("hue_dim", 15),
        haUrl = prefs.getString("ha_url", null).orEmpty(),
        haPlaying = prefs.getString("ha_playing", null).orEmpty(),
        haStopped = prefs.getString("ha_stopped", null).orEmpty(),
    )
}

/** Philips Hue's local API (v1): discovery, pairing, rooms and setting a room's brightness. */
object Hue {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Bridges on this network, by address (from Philips' discovery service). */
    suspend fun discover(): List<String> = withContext(Dispatchers.IO) {
        (getJson(json, "https://discovery.meethue.com") as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.get("internalipaddress")?.jsonPrimitive?.contentOrNull
        }
    }

    sealed interface PairResult {
        data class Paired(val user: String) : PairResult
        data object PressButton : PairResult
        data object Failed : PairResult
    }

    /** Asks the bridge for a user; it says yes within 30 s of its round button being pressed. */
    suspend fun pair(ip: String): PairResult = withContext(Dispatchers.IO) {
        val answer = request("http://$ip/api", "POST", """{"devicetype":"chud_supreme#firetv"}""") as? JsonArray
            ?: return@withContext PairResult.Failed
        val first = answer.firstOrNull() as? JsonObject ?: return@withContext PairResult.Failed
        val user = (first["success"] as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull
        when {
            user != null -> PairResult.Paired(user)
            (first["error"] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "101" -> PairResult.PressButton
            else -> PairResult.Failed
        }
    }

    /** The rooms and zones, id to name. */
    suspend fun groups(ip: String, user: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val root = request("http://$ip/api/$user/groups", "GET", null) as? JsonObject ?: return@withContext emptyList()
        root.mapNotNull { (id, value) ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val type = item["type"]?.jsonPrimitive?.contentOrNull
            if (type != "Room" && type != "Zone" && type != "Entertainment") return@mapNotNull null
            id to (item["name"]?.jsonPrimitive?.contentOrNull ?: id)
        }
    }

    /** Lights in [group] to [percent] (0 switches them off; 100 back to full). */
    suspend fun setGroup(ip: String, user: String, group: String, percent: Int) = withContext(Dispatchers.IO) {
        val body = if (percent <= 0) """{"on":false,"transitiontime":20}"""
        else """{"on":true,"bri":${(percent * 254 / 100).coerceIn(1, 254)},"transitiontime":20}"""
        request("http://$ip/api/$user/groups/$group/action", "PUT", body)
    }

    private fun request(url: String, method: String, body: String?) = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** Home Assistant: runs a scene, script or automation by its entity id. */
object HomeAssistant {
    /** True when Home Assistant accepted the call. */
    suspend fun run(baseUrl: String, token: String, entityId: String): Boolean = withContext(Dispatchers.IO) {
        val domain = entityId.substringBefore('.', "")
        val service = when (domain) {
            "scene", "script" -> "turn_on"
            "automation" -> "trigger"
            "light", "switch", "input_boolean" -> "toggle"
            else -> return@withContext false
        }
        runCatching {
            val connection = (URL("${baseUrl.trimEnd('/')}/api/services/$domain/$service").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { it.write("""{"entity_id":"$entityId"}""".toByteArray()) }
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}

@HiltViewModel
class SmartHomeViewModel @Inject constructor(
    private val store: SmartHomeStore,
    private val secrets: SecretStore,
) : ViewModel() {
    val settings: StateFlow<SmartHomeSettings> = store.settings

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _groups = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val groups: StateFlow<List<Pair<String, String>>> = _groups.asStateFlow()

    // Starts as "not playing", so opening the app never touches the lights.
    private var lastPlaying: Boolean? = false

    fun update(transform: (SmartHomeSettings) -> SmartHomeSettings) = store.update(transform)

    val hasHaToken: Boolean get() = secrets.has(SecretName.HomeAssistant)

    fun saveHaToken(token: String) {
        if (token.isNotBlank()) secrets.put(SecretName.HomeAssistant, token.trim())
    }

    /** Finds the bridge and asks to pair; the person presses the bridge's button and tries again. */
    fun pairHue(messages: HueMessages) {
        viewModelScope.launch {
            _status.value = messages.searching
            val ip = store.settings.value.hueIp ?: Hue.discover().firstOrNull()
            if (ip == null) {
                _status.value = messages.noBridge
                return@launch
            }
            when (val result = Hue.pair(ip)) {
                is Hue.PairResult.Paired -> {
                    store.update { it.copy(hueIp = ip, hueUser = result.user) }
                    loadGroups()
                    _status.value = messages.paired
                }
                Hue.PairResult.PressButton -> {
                    store.update { it.copy(hueIp = ip) }
                    _status.value = messages.pressButton
                }
                Hue.PairResult.Failed -> _status.value = messages.failed
            }
        }
    }

    fun loadGroups() {
        val current = store.settings.value
        val ip = current.hueIp ?: return
        val user = current.hueUser ?: return
        viewModelScope.launch { _groups.value = Hue.groups(ip, user) }
    }

    fun nextGroup() {
        val groups = _groups.value
        if (groups.isEmpty()) {
            loadGroups()
            return
        }
        val index = groups.indexOfFirst { it.first == store.settings.value.hueGroup }
        val next = groups[(index + 1).mod(groups.size)]
        store.update { it.copy(hueGroup = next.first, hueGroupName = next.second) }
    }

    fun forgetHue() = store.update { it.copy(hueIp = null, hueUser = null, hueGroup = null, hueGroupName = null) }

    /** Playback started or stopped (called by App, already debounced). */
    fun onPlayback(playing: Boolean) {
        if (lastPlaying == playing) return
        lastPlaying = playing
        val current = store.settings.value
        viewModelScope.launch {
            if (current.hueReady) {
                Hue.setGroup(current.hueIp!!, current.hueUser!!, current.hueGroup!!, if (playing) current.hueDimPercent else 100)
            }
            val token = secrets.get(SecretName.HomeAssistant)
            if (current.haUrl.isNotBlank() && token != null) {
                val entity = if (playing) current.haPlaying else current.haStopped
                if (entity.isNotBlank()) HomeAssistant.run(current.haUrl, token, entity)
            }
        }
    }

    fun testHomeAssistant(ok: String, failed: String) {
        val current = store.settings.value
        val token = secrets.get(SecretName.HomeAssistant)
        viewModelScope.launch {
            val entity = current.haPlaying.ifBlank { current.haStopped }
            val worked = token != null && entity.isNotBlank() && HomeAssistant.run(current.haUrl, token, entity)
            _status.value = if (worked) ok else failed
        }
    }
}

@Immutable
data class HueMessages(val searching: String, val noBridge: String, val pressButton: String, val paired: String, val failed: String)

/** The Smart home section of Settings › Services. */
@Composable
fun SmartHomeRows(viewModel: SmartHomeViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val messages = HueMessages(
        searching = stringResource(R.string.dial_hue_searching),
        noBridge = stringResource(R.string.dial_hue_no_bridge),
        pressButton = stringResource(R.string.dial_hue_press_button),
        paired = stringResource(R.string.dial_hue_paired),
        failed = stringResource(R.string.dial_hue_failed),
    )
    val haOk = stringResource(R.string.dial_ha_test_ok)
    val haFailed = stringResource(R.string.dial_ha_test_failed)
    var haUrl by remember(settings.haUrl) { mutableStateOf(settings.haUrl) }
    var haPlaying by remember(settings.haPlaying) { mutableStateOf(settings.haPlaying) }
    var haStopped by remember(settings.haStopped) { mutableStateOf(settings.haStopped) }
    var haToken by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 820.dp)) {
        Text(
            text = stringResource(R.string.dial_smart_home_hint),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
        )
        SettingRow(
            label = stringResource(R.string.dial_hue_bridge),
            value = if (settings.huePaired) stringResource(R.string.dial_hue_paired_value, settings.hueIp.orEmpty())
            else stringResource(R.string.dial_hue_pair),
            onClick = { if (settings.huePaired) viewModel.forgetHue() else viewModel.pairHue(messages) },
        )
        if (settings.huePaired) {
            SettingRow(
                label = stringResource(R.string.dial_hue_room),
                value = settings.hueGroupName ?: stringResource(R.string.dial_hue_room_pick),
                onClick = viewModel::nextGroup,
            )
            SettingRow(
                label = stringResource(R.string.dial_hue_dim),
                value = if (settings.hueDimPercent == 0) stringResource(R.string.dial_hue_off) else "${settings.hueDimPercent}%",
                onClick = { viewModel.update { it.copy(hueDimPercent = HUE_DIM_OPTIONS.nextAfter(it.hueDimPercent)) } },
            )
        }
        status?.let {
            Text(text = it, color = TvColors.Focus, fontFamily = TvFonts.Body, fontSize = 14.sp)
        }
        DialTextField(
            label = stringResource(R.string.dial_ha_url),
            value = haUrl,
            onValueChange = { haUrl = it },
            placeholder = "http://homeassistant.local:8123",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            readOnly = false,
            onDone = { viewModel.update { it.copy(haUrl = haUrl.trim()) } },
        )
        DialTextField(
            label = stringResource(if (viewModel.hasHaToken) R.string.dial_ha_token_saved else R.string.dial_ha_token),
            value = haToken,
            onValueChange = { haToken = it },
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            readOnly = false,
            secret = true,
            onDone = {
                viewModel.saveHaToken(haToken)
                haToken = ""
            },
        )
        DialTextField(
            label = stringResource(R.string.dial_ha_playing),
            value = haPlaying,
            onValueChange = { haPlaying = it },
            placeholder = "scene.movie_time",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
            readOnly = false,
            onDone = { viewModel.update { it.copy(haPlaying = haPlaying.trim()) } },
        )
        DialTextField(
            label = stringResource(R.string.dial_ha_stopped),
            value = haStopped,
            onValueChange = { haStopped = it },
            placeholder = "scene.lights_up",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
            readOnly = false,
            onDone = { viewModel.update { it.copy(haStopped = haStopped.trim()) } },
        )
        if (settings.haUrl.isNotBlank()) {
            SettingRow(
                label = stringResource(R.string.dial_ha_test),
                value = "",
                onClick = { viewModel.testHomeAssistant(haOk, haFailed) },
            )
        }
    }
}

private val HUE_DIM_OPTIONS = listOf(0, 5, 15, 30, 50)
