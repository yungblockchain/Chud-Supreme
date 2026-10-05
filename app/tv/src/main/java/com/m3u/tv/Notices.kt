package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/* -------------------------------------------------------------------------------------------------
 * The notices strip on Home: a newer build on GitHub, new episodes waiting on Trakt, programmes
 * to catch up on. Short chips, no buttons: each says where to go.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
sealed interface Notice {
    data class Update(val build: Int) : Notice
    data class TraktUpNext(val count: Int) : Notice
    data class Missed(val count: Int) : Notice
    data class Party(val code: String) : Notice
}

object UpdateCheck {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The newest build number on GitHub, or null when it can't be read. */
    suspend fun latestBuild(): Int? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("https://api.github.com/repos/${SupremeKeys.GITHUB_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
            }
            if (connection.responseCode !in 200..299) return@withContext null
            val root = json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
            (root?.get("tag_name") as? JsonPrimitive)?.contentOrNull?.removePrefix("build-")?.toIntOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

@HiltViewModel
class NoticesViewModel @Inject constructor() : ViewModel() {
    private val _updateBuild = MutableStateFlow<Int?>(null)
    /** A newer build's number, once the check has run and found one. */
    val updateBuild: StateFlow<Int?> = _updateBuild.asStateFlow()

    init {
        viewModelScope.launch {
            val mine = BuildConfig.CHUD_BUILD
            val latest = UpdateCheck.latestBuild() ?: return@launch
            if (mine > 0 && latest > mine) _updateBuild.value = latest
        }
    }
}

@Composable
fun NoticeStrip(notices: List<Notice>, modifier: Modifier = Modifier) {
    if (notices.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        notices.forEach { notice ->
            Text(
                text = when (notice) {
                    is Notice.Update -> stringResource(R.string.dial_notice_update, notice.build)
                    is Notice.TraktUpNext -> stringResource(R.string.dial_notice_trakt_upnext, notice.count)
                    is Notice.Missed -> stringResource(R.string.dial_notice_missed, notice.count)
                    is Notice.Party -> stringResource(R.string.dial_notice_party, notice.code)
                },
                color = if (notice is Notice.Update) TvColors.OnFocus else TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (notice is Notice.Update) TvColors.Focus else TvColors.Background.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
