package com.m3u.tv

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.set
import com.m3u.data.worker.ExtensionPluginBootstrapWorker
import com.m3u.data.worker.PersistedUriPermissionCleanupWorker
import com.m3u.data.worker.ProviderCredentialRecoveryWorker
import com.m3u.data.worker.ProviderSessionCleanupWorker
import com.m3u.data.worker.initializePersistedUriPermissionLeases
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class M3UApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settings: Settings

    @Inject
    lateinit var secrets: SecretStore

    @Inject
    lateinit var skins: SkinStore

    override fun onCreate() {
        super.onCreate()
        // Crash reports stay on the device until the person sends them (Settings > Services).
        CrashReports.install(this)
        runCatching { secrets.seed() }
        // The chosen skin, before the first screen draws.
        runCatching { TvTheme.apply(skins.current.value) }
        enableHdrTunnelingOnce()
        initializePersistedUriPermissionLeases(this)
        PersistedUriPermissionCleanupWorker.enqueueRecovery(
            WorkManager.getInstance(this)
        )
        ProviderCredentialRecoveryWorker.enqueue(WorkManager.getInstance(this))
        ProviderSessionCleanupWorker.enqueue(
            workManager = WorkManager.getInstance(this),
        )
        ExtensionPluginBootstrapWorker.enqueue(WorkManager.getInstance(this))
    }

    /** First launch of this build turns on tunneled HDR/Dolby playback. Later toggles are kept. */
    private fun enableHdrTunnelingOnce() {
        val flags = getSharedPreferences("maxxx_migrations", MODE_PRIVATE)
        if (flags.getBoolean(KEY_HDR_TUNNEL, false)) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { settings[PreferencesKeys.TUNNELING] = true }
            flags.edit().putBoolean(KEY_HDR_TUNNEL, true).apply()
        }
    }

    /**
     * Pictures: animated GIF/WebP channel logos play, pictures fade in, and the in-memory cache
     * stays small enough for a Fire TV Stick.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .crossfade(IMAGE_CROSSFADE_MS)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(IMAGE_MEMORY_FRACTION)
                .build()
        }
        .build()

    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }
}

private const val IMAGE_CROSSFADE_MS = 180
private const val IMAGE_MEMORY_FRACTION = 0.15
private const val KEY_HDR_TUNNEL = "hdr_tunnel_v1"
