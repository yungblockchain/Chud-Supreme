package com.m3u.tv

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * Profiles: who's watching. Each profile has a name and a face; some have a PIN; a kids profile
 * sees only live TV, films, series, the guide and favourites, with adult categories hidden and a
 * timer that ends playback. With more than one profile the app opens on the picker. Favourites and
 * history stay shared between profiles; profiles decide what's reachable, not who owns what.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class Profile(
    val id: String,
    val name: String,
    /** One of [PROFILE_FACES]. */
    val face: String,
    val pinHash: String? = null,
    val kids: Boolean = false,
    /** Minutes of play before a kids profile stops for the day; 0 = no limit. */
    val kidsMinutes: Int = 0,
) {
    val hasPin: Boolean get() = pinHash != null
}

val PROFILE_FACES: List<String> = listOf("🙂", "😎", "🦊", "🐼", "🦁", "🐸", "🦄", "🤖", "👾", "🎃", "🐙", "🌟")

/** The tabs a kids profile keeps. */
val KIDS_DESTINATIONS: Set<TvDestination> = setOf(
    TvDestination.Search,
    TvDestination.Home,
    TvDestination.Live,
    TvDestination.Films,
    TvDestination.Series,
    TvDestination.Guide,
    TvDestination.Favorites,
    TvDestination.MyLibrary,
)

/** Category names a kids profile never sees. */
private val ADULT_PATTERN = Regex(
    """(?i)(\badult\b|\bxxx\b|\b18\s*\+|\bporn|\berotic|\bsex\b|\bhentai\b|\bplayboy\b|\bbrazzers\b|\bnsfw\b)""",
)

fun isAdultCategory(name: String): Boolean = ADULT_PATTERN.containsMatchIn(name)

@Singleton
class ProfileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val defaultName: String get() = context.getString(R.string.dial_profile_default_name)
    private val random = SecureRandom()

    private val _profiles = MutableStateFlow(read())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _active = MutableStateFlow<Profile?>(null)
    /** The profile in use, once one has been picked this session. */
    val active: StateFlow<Profile?> = _active.asStateFlow()

    /** Whether the picker should show: more than one profile, or the only one has a PIN. */
    val needsPicker: Boolean
        get() = _active.value == null && (_profiles.value.size > 1 || _profiles.value.any { it.hasPin })

    val kidsActive: Boolean get() = _active.value?.kids == true

    init {
        // A single, open profile is simply the person: no picker to get through.
        if (_profiles.value.size == 1 && !_profiles.value.first().hasPin) _active.value = _profiles.value.first()
    }

    /** Picks [profile]; false when the PIN is wrong. */
    fun select(profile: Profile, pin: String?): Boolean {
        if (profile.pinHash != null && (pin == null || hash(pin) != profile.pinHash)) return false
        _active.value = profile
        prefs.edit().putString(KEY_LAST, profile.id).apply()
        return true
    }

    /** Back to the picker (switching profiles). */
    fun lock() {
        _active.value = null
    }

    /** True when the PIN opens the way out of a kids profile (or no PIN is set on it). */
    fun unlock(pin: String): Boolean {
        val current = _active.value ?: return true
        return current.pinHash == null || hash(pin) == current.pinHash
    }

    fun add(name: String, face: String, pin: String?, kids: Boolean, kidsMinutes: Int): Profile {
        val profile = Profile(
            id = System.currentTimeMillis().toString(36),
            name = name.trim().take(MAX_NAME).ifBlank { defaultName },
            face = face.takeIf { it in PROFILE_FACES } ?: PROFILE_FACES.first(),
            pinHash = pin?.takeIf { it.length in PIN_LENGTH }?.let(::hash),
            kids = kids,
            kidsMinutes = kidsMinutes.coerceIn(0, MAX_KIDS_MINUTES),
        )
        save(_profiles.value + profile)
        return profile
    }

    fun update(id: String, transform: (Profile) -> Profile) = save(_profiles.value.map { if (it.id == id) transform(it) else it })

    fun setPin(id: String, pin: String?) = update(id) { it.copy(pinHash = pin?.takeIf { p -> p.length in PIN_LENGTH }?.let(::hash)) }

    fun remove(id: String) {
        if (_profiles.value.size <= 1) return
        save(_profiles.value.filterNot { it.id == id })
        if (_active.value?.id == id) _active.value = null
    }

    val lastUsedId: String? get() = prefs.getString(KEY_LAST, null)

    private fun save(profiles: List<Profile>) {
        _profiles.value = profiles
        val json = JsonArray(
            profiles.map { profile ->
                JsonObject(
                    buildMap {
                        put("id", JsonPrimitive(profile.id))
                        put("name", JsonPrimitive(profile.name))
                        put("face", JsonPrimitive(profile.face))
                        profile.pinHash?.let { put("pin", JsonPrimitive(it)) }
                        put("kids", JsonPrimitive(profile.kids))
                        put("minutes", JsonPrimitive(profile.kidsMinutes))
                    }
                )
            }
        )
        prefs.edit().putString(KEY_PROFILES, json.toString()).apply()
        _active.value?.let { current -> _active.value = profiles.firstOrNull { it.id == current.id } ?: current }
    }

    private fun read(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null)
        val parsed = runCatching {
            Json.parseToJsonElement(raw ?: "[]").jsonArray.mapNotNull { element ->
                val item = element.jsonObject
                Profile(
                    id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    name = item["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    face = item["face"]?.jsonPrimitive?.content ?: PROFILE_FACES.first(),
                    pinHash = item["pin"]?.jsonPrimitive?.content,
                    kids = item["kids"]?.jsonPrimitive?.booleanOrNull ?: false,
                    kidsMinutes = item["minutes"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }
        }.getOrDefault(emptyList())
        return parsed.ifEmpty { listOf(Profile(id = "main", name = defaultName, face = PROFILE_FACES.first())) }
    }

    /** PBKDF2 over the PIN with a per-device salt: a leaked file doesn't give the PINs away in a blink. */
    private fun hash(pin: String): String {
        val salt = prefs.getString(KEY_SALT, null) ?: ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString(KEY_SALT, it).apply() }
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(), PBKDF2_ROUNDS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return key.joinToString("") { "%02x".format(it) }
    }

    /** True when [pin] belongs to a grown-up profile (one that isn't a kids profile and has a PIN). */
    fun verifyAdultPin(pin: String): Boolean {
        val hashed = hash(pin)
        return _profiles.value.any { !it.kids && it.pinHash != null && MessageDigest.isEqual(it.pinHash.toByteArray(), hashed.toByteArray()) }
    }

    /** Whether edits should ask for a grown-up's PIN: one exists and the profile in use has none. */
    val editsNeedPin: Boolean
        get() = _profiles.value.any { !it.kids && it.hasPin } && _active.value?.hasPin != true

    /** Re-reads the profiles (after a restore wrote the file directly). */
    fun reload() {
        _profiles.value = read()
        _active.value = _active.value?.let { current -> _profiles.value.firstOrNull { it.id == current.id } }
    }

    companion object {
        val PIN_LENGTH = 4..8
        private const val PBKDF2_ROUNDS = 10_000
        const val MAX_KIDS_MINUTES = 600
        private const val MAX_NAME = 24
        private const val KEY_PROFILES = "profiles"
        private const val KEY_LAST = "last_profile"
        private const val KEY_SALT = "salt"
    }
}
