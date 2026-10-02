package com.m3u.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The keys and tokens people add on the Fire TV (or from their phone). None ship with the app. */
enum class SecretName(val key: String) {
    OpenSubtitles("opensubtitles_api_key"),
    Tmdb("tmdb_api_key"),
    RealDebrid("real_debrid_token"),
    TorBox("torbox_api_key"),
    TraktClientId("trakt_client_id"),
    TraktClientSecret("trakt_client_secret"),
    TraktAccess("trakt_access_token"),
    TraktRefresh("trakt_refresh_token"),
    GitHubToken("github_token"),
    GitHubRepo("github_repo"),
    TelegramBotToken("telegram_bot_token"),
    TelegramChatId("telegram_chat_id"),
    TelegramTradingBot("telegram_trading_bot"),
    CoinMarketCap("coinmarketcap_api_key"),
    SportsGameOdds("sportsgameodds_api_key"),
    ApiSports("api_sports_key"),
}

/**
 * API keys and tokens, encrypted with a key that never leaves the Android Keystore, in the
 * app's private storage. Nothing here is ever built into the app or sent anywhere except the
 * service each key belongs to.
 */
@Singleton
class SecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private val _saved = MutableStateFlow(savedNames())

    /** Which secrets are set, to show "Added" / "Not set" without decrypting anything. */
    val saved: StateFlow<Set<SecretName>> = _saved.asStateFlow()

    fun has(name: SecretName): Boolean = prefs.contains(name.key)

    fun get(name: SecretName): String? {
        val stored = prefs.getString(name.key, null) ?: return null
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

    fun put(name: SecretName, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            remove(name)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(data, Base64.NO_WRAP)
        prefs.edit().putString(name.key, stored).apply()
        _saved.value = savedNames() + name
    }

    fun remove(name: SecretName) {
        prefs.edit().remove(name.key).apply()
        _saved.value = savedNames() - name
    }

    /** First launch of this build fills the owner's keys. A later edit in Settings is kept. */
    fun seed() {
        fun fill(name: SecretName, value: String) {
            if (!has(name)) put(name, value)
        }
        fill(SecretName.Tmdb, SupremeKeys.TMDB)
        fill(SecretName.CoinMarketCap, SupremeKeys.COINMARKETCAP)
        fill(SecretName.ApiSports, SupremeKeys.API_SPORTS)
        fill(SecretName.RealDebrid, SupremeKeys.REAL_DEBRID)
        fill(SecretName.TorBox, SupremeKeys.TORBOX)
    }

    private fun savedNames(): Set<SecretName> =
        SecretName.entries.filterTo(mutableSetOf()) { prefs.contains(it.key) }

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
        const val KEY_ALIAS = "chud_streams_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
