package com.m3u.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* -------------------------------------------------------------------------------------------------
 * The in-app updater: when GitHub has a newer build, one press downloads the APK to the stick
 * and hands it to Android's installer (the first time, Fire OS asks to allow installs from this
 * app). Accounts, favourites and settings stay, since it's the same signed app updating itself.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class Ready(val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@HiltViewModel
class UpdaterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    /** Downloads the newest release and opens the installer on it. */
    fun install() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val file = try {
                download()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
                return@launch
            }
            _state.value = UpdateState.Ready(file)
            open(file)
        }
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    private suspend fun download(): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "chud-supreme.apk")
        _state.value = UpdateState.Downloading(0)
        var url = APK_URL
        var connection: HttpURLConnection? = null
        try {
            // GitHub answers the download link with a redirect to its file store.
            repeat(5) {
                connection?.disconnect()
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
                }
                val code = connection!!.responseCode
                if (code in 300..399) {
                    url = connection!!.getHeaderField("Location") ?: error("Download moved with no address")
                    return@repeat
                }
                if (code !in 200..299) error("GitHub answered $code")
                val length = connection!!.contentLengthLong
                connection!!.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var total = 0L
                        while (input.read(buffer).also { read = it } >= 0) {
                            output.write(buffer, 0, read)
                            total += read
                            if (length > 0) _state.value = UpdateState.Downloading((total * 100 / length).toInt().coerceIn(0, 99))
                        }
                    }
                }
                if (target.length() < MIN_APK_BYTES) error("The download was cut short")
                return@withContext target
            }
            error("Too many redirects")
        } finally {
            connection?.disconnect()
        }
    }

    private fun open(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { _state.value = UpdateState.Failed(it.message ?: "No installer") }
    }

    private companion object {
        const val APK_URL = "https://github.com/${SupremeKeys.GITHUB_REPO}/releases/latest/download/chud-supreme.apk"
        const val MIN_APK_BYTES = 5L * 1024 * 1024
    }
}

/** For "install the APK from a download" from the phone page or Settings: the newest build's link. */
fun updateDownloadUri(): Uri = Uri.parse("https://github.com/${SupremeKeys.GITHUB_REPO}/releases/latest/download/chud-supreme.apk")
