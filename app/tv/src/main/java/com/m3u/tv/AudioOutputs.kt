package com.m3u.tv

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.get
import com.m3u.core.foundation.architecture.preferences.set
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * Where the sound goes. The stick plays through the TV (HDMI), a soundbar, wired or USB
 * headphones, or Bluetooth headphones such as AirPods; each keeps its own audio delay (Bluetooth
 * often runs late), sound profile, virtual surround and volume boost, and they come back by
 * themselves when that device is used again.
 *
 * Head-tracked spatial audio on AirPods is Apple's and only works from Apple devices; on a Fire
 * TV the nearest thing is the system's virtual surround, offered here for headphones.
 * ---------------------------------------------------------------------------------------------- */

enum class OutputKind {
    Speakers, Hdmi, Bluetooth, Headphones, Usb;

    /** Worn on the head: the place for virtual surround. */
    val personal: Boolean get() = this == Bluetooth || this == Headphones || this == Usb
}

@Immutable
data class AudioOutput(val kind: OutputKind, val name: String) {
    /** Bluetooth and USB devices are told apart by name; the rest are one each. */
    val key: String
        get() = when (kind) {
            OutputKind.Bluetooth, OutputKind.Usb -> "${kind.name}:$name"
            else -> kind.name
        }
}

/** The equaliser bands' upper edges, in hertz. */
val EQ_CUTOFFS = floatArrayOf(80f, 200f, 500f, 1_200f, 2_500f, 5_000f, 10_000f, 20_000f)

/** Sound profiles: decibels for each band in [EQ_CUTOFFS]. */
enum class EqPreset(val id: String, val gains: List<Float>) {
    Flat("flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    Cinema("cinema", listOf(3f, 2f, 0f, 0f, 1f, 1f, 0f, -1f)),
    Bass("bass", listOf(6f, 4f, 1f, 0f, 0f, 0f, 0f, 0f)),
    Vocal("vocal", listOf(-2f, -1f, 0f, 2f, 3f, 3f, 1f, 0f)),
    Treble("treble", listOf(0f, 0f, 0f, 0f, 1f, 3f, 4f, 4f)),
    /** Fuller lows and highs for listening quietly late at night. */
    Loudness("loudness", listOf(5f, 3f, 0f, -1f, 0f, 2f, 3f, 2f)),
    /** A gentle tune for AirPods and similar earbuds: a little warmth and air, no harshness. */
    AirPods("airpods", listOf(2f, 1f, 0f, -1f, -1f, 1f, 2f, 1f));

    companion object {
        fun parse(id: String?): EqPreset = entries.firstOrNull { it.id == id } ?: Flat
    }
}

@Immutable
data class OutputProfile(
    val delayMs: Int = 0,
    val eq: EqPreset = EqPreset.Flat,
    val surround: Boolean = false,
    val boostDb: Int = 0,
) {
    companion object {
        val BOOST_OPTIONS = listOf(0, 3, 6, 9)
    }
}

/** Which sound effects this device's system offers at all. */
object AudioEffectsSupport {
    val virtualizer: Boolean by lazy { has(AudioEffect.EFFECT_TYPE_VIRTUALIZER) }

    private fun has(type: UUID): Boolean = runCatching {
        AudioEffect.queryEffects()?.any { it.type == type } == true
    }.getOrDefault(false)
}

@Singleton
class AudioOutputMonitor @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: Settings,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences("audio_outputs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _current = MutableStateFlow(detect())
    val current: StateFlow<AudioOutput> = _current.asStateFlow()

    private val _profiles = MutableStateFlow(read())
    val profiles: StateFlow<Map<String, OutputProfile>> = _profiles.asStateFlow()

    /** The profile of the device playing now. */
    val profile: StateFlow<OutputProfile> = combine(_current, _profiles) { output, all -> all[output.key] ?: defaultFor(output) }
        .stateIn(scope, SharingStarted.Eagerly, profileFor(_current.value))

    private val _changes = MutableSharedFlow<AudioOutput>(extraBufferCapacity = 4)

    /** Each time the sound moves to another device (not at start-up). */
    val changes: SharedFlow<AudioOutput> = _changes.asSharedFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    init {
        runCatching { audio?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
        scope.launch {
            val output = _current.value
            val saved = _profiles.value[output.key]
            if (saved != null) {
                // The app may have closed while other headphones were in use: this device's own delay.
                runCatching { settings[PreferencesKeys.AUDIO_DELAY_MS] = saved.delayMs }
            } else {
                // The device in use the first time keeps the delay that was already set.
                val delay = runCatching { settings[PreferencesKeys.AUDIO_DELAY_MS] }.getOrDefault(0)
                if (output.key !in _profiles.value) save(output.key, defaultFor(output).copy(delayMs = delay))
            }
        }
    }

    /** Re-reads the profiles (after a restore wrote them directly). */
    fun reload() {
        _profiles.value = read()
    }

    fun profileFor(output: AudioOutput): OutputProfile = _profiles.value[output.key] ?: defaultFor(output)

    /** Changes the profile of the device playing now. */
    fun update(transform: (OutputProfile) -> OutputProfile) {
        val output = _current.value
        save(output.key, transform(profileFor(output)))
    }

    /** The audio delay was changed (in the player or Settings): it belongs to this device. */
    fun rememberDelay(ms: Int) = update { it.copy(delayMs = ms) }

    private fun refresh() {
        val next = detect()
        if (next == _current.value) return
        val moved = next.key != _current.value.key
        _current.value = next
        // Same device with a new name (an HDMI receiver renegotiating): nothing else changes.
        if (!moved) return
        val delay = profileFor(next).delayMs
        scope.launch { runCatching { settings[PreferencesKeys.AUDIO_DELAY_MS] = delay } }
        _changes.tryEmit(next)
    }

    /** Android sends media to Bluetooth when it's connected, then to headphones, then to HDMI. */
    @SuppressLint("InlinedApi")
    private fun detect(): AudioOutput {
        val devices = runCatching { audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList() }.getOrNull().orEmpty()
        fun AudioDeviceInfo.label(): String = productName?.toString()?.trim().orEmpty()
        val bluetooth = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
        val hdmi = buildSet {
            add(AudioDeviceInfo.TYPE_HDMI)
            add(AudioDeviceInfo.TYPE_HDMI_ARC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_HDMI_EARC)
        }
        devices.firstOrNull { it.type in bluetooth }?.let { return AudioOutput(OutputKind.Bluetooth, it.label().ifEmpty { "Bluetooth" }) }
        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?.let { return AudioOutput(OutputKind.Headphones, it.label()) }
        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            ?.let { return AudioOutput(OutputKind.Usb, it.label().ifEmpty { "USB" }) }
        devices.firstOrNull { it.type in hdmi }?.let { return AudioOutput(OutputKind.Hdmi, it.label()) }
        return AudioOutput(OutputKind.Speakers, "")
    }

    private fun defaultFor(output: AudioOutput) = OutputProfile(
        eq = if (output.kind == OutputKind.Bluetooth && output.name.contains("airpods", ignoreCase = true)) EqPreset.AirPods else EqPreset.Flat,
    )

    private fun save(key: String, profile: OutputProfile) {
        val next = _profiles.value + (key to profile)
        _profiles.value = next
        prefs.edit().putString(KEY, JsonObject(next.mapValues { (_, value) ->
            JsonObject(
                mapOf(
                    "delay" to JsonPrimitive(value.delayMs),
                    "eq" to JsonPrimitive(value.eq.id),
                    "surround" to JsonPrimitive(value.surround),
                    "boost" to JsonPrimitive(value.boostDb),
                )
            )
        }).toString()).apply()
    }

    private fun read(): Map<String, OutputProfile> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        Json.parseToJsonElement(raw).jsonObject.mapValues { (_, value) ->
            val item = value.jsonObject
            OutputProfile(
                delayMs = item["delay"]?.jsonPrimitive?.intOrNull ?: 0,
                eq = EqPreset.parse(item["eq"]?.jsonPrimitive?.contentOrNull),
                surround = item["surround"]?.jsonPrimitive?.booleanOrNull ?: false,
                boostDb = item["boost"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }.getOrDefault(emptyMap())

    private companion object {
        const val KEY = "profiles"
    }
}

@HiltViewModel
class AudioOutputViewModel @Inject constructor(
    private val monitor: AudioOutputMonitor,
    private val settings: Settings,
) : ViewModel() {
    val current: StateFlow<AudioOutput> = monitor.current
    val profile: StateFlow<OutputProfile> = monitor.profile
    val changes: SharedFlow<AudioOutput> = monitor.changes

    fun update(transform: (OutputProfile) -> OutputProfile) = monitor.update(transform)

    fun nudgeDelay(stepMs: Int) {
        val next = (profile.value.delayMs + stepMs).coerceIn(-PlaybackSettingsViewModel.MAX_DELAY_MS, PlaybackSettingsViewModel.MAX_DELAY_MS)
        setDelay(next)
    }

    fun setDelay(ms: Int) {
        monitor.rememberDelay(ms)
        viewModelScope.launch { settings[PreferencesKeys.AUDIO_DELAY_MS] = ms }
    }
}
