package com.m3u.tv

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewModelScope
import com.m3u.tv.stremio.StremioAddonStore
import com.m3u.tv.stremio.StremioClient
import com.m3u.tv.stremio.TorrentioConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/* -------------------------------------------------------------------------------------------------
 * Settings > Services: the person's own keys for film info (TMDB, Trakt), markets (CoinMarketCap,
 * Telegram) and crash reports (GitHub). Each is typed here or sent from the phone page, stored
 * encrypted on the Fire TV, and only ever sent to its own service.
 * ---------------------------------------------------------------------------------------------- */

sealed interface ReportsUpload {
    data object Idle : ReportsUpload
    data object Sending : ReportsUpload
    data class Sent(val count: Int) : ReportsUpload
    data object NeedsSetup : ReportsUpload
    data object Failed : ReportsUpload
}

@HiltViewModel
class ServicesSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
    private val companion: PhoneCompanion,
    private val store: DialSettingsStore,
    private val addons: StremioAddonStore,
    private val trakt: TraktService,
) : ViewModel() {
    val saved: StateFlow<Set<SecretName>> = secrets.saved
    val traktAccount: StateFlow<TraktAccount?> = trakt.account
    val traktSignIn: StateFlow<TraktSignIn> = trakt.signIn

    fun traktSignIn() = trakt.startSignIn()
    fun traktCancel() = trakt.cancelSignIn()
    fun traktSignOut() = trakt.signOut()
    fun toggleScrobble() = store.update { it.copy(traktScrobble = !it.traktScrobble) }
    fun toggleExtraSubtitles() = store.update { it.copy(extraSubtitleSources = !it.extraSubtitleSources) }
    val phonePage: StateFlow<CompanionInfo?> = companion.info
    val phoneMessages: SharedFlow<PhoneMessage> = companion.messages
    val preferences: StateFlow<DialPreferences> = store.preferences

    private val _reports = MutableStateFlow<List<CrashReport>>(emptyList())
    val reports: StateFlow<List<CrashReport>> = _reports.asStateFlow()

    private val _upload = MutableStateFlow<ReportsUpload>(ReportsUpload.Idle)
    val upload: StateFlow<ReportsUpload> = _upload.asStateFlow()

    init {
        refreshReports()
    }

    fun save(name: SecretName, value: String) {
        secrets.put(name, value)
        if (name == SecretName.RealDebrid || name == SecretName.TorBox) syncTorrentio()
    }

    fun remove(name: SecretName) {
        secrets.remove(name)
        if (name == SecretName.RealDebrid || name == SecretName.TorBox) syncTorrentio()
    }

    private fun syncTorrentio() {
        val installed = addons.addons.value.any {
            it.manifestUrl.contains("torrentio.strem.fun") || it.id.contains("torrentio", ignoreCase = true)
        }
        if (!installed) return
        val url = TorrentioConfig.manifestUrl(secrets.get(SecretName.RealDebrid), secrets.get(SecretName.TorBox))
        viewModelScope.launch {
            runCatching { StremioClient.fetchManifest(url) }
                .onSuccess { addons.upsert(it) }
                .onFailure { addons.replaceTorrentio(url) }
        }
    }

    fun togglePhonePage() {
        if (companion.running) companion.stop() else companion.start()
    }

    fun toggleAutoSend() = store.update { it.copy(autoSendReports = !it.autoSendReports) }

    fun refreshReports() {
        viewModelScope.launch(Dispatchers.IO) { _reports.value = CrashReports.list(context) }
    }

    fun sendReports() {
        val token = secrets.get(SecretName.GitHubToken)
        val repo = secrets.get(SecretName.GitHubRepo)
        if (token == null || repo == null) {
            _upload.value = ReportsUpload.NeedsSetup
            return
        }
        viewModelScope.launch {
            _upload.value = ReportsUpload.Sending
            _upload.value = runCatching { CrashReports.upload(context, token, repo) }
                .onFailure { if (it is CancellationException) throw it }
                .fold({ ReportsUpload.Sent(it) }, { ReportsUpload.Failed })
            refreshReports()
        }
    }

    fun deleteReports() {
        viewModelScope.launch(Dispatchers.IO) {
            CrashReports.deleteAll(context)
            _reports.value = emptyList()
            _upload.value = ReportsUpload.Idle
        }
    }
}

@Composable
fun ServicesSettingsScreen(
    viewModel: ServicesSettingsViewModel = hiltViewModel(),
) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val upload by viewModel.upload.collectAsStateWithLifecycle()
    val phonePage by viewModel.phonePage.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val traktAccount by viewModel.traktAccount.collectAsStateWithLifecycle()
    val traktSignIn by viewModel.traktSignIn.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<SecretName?>(null) }
    var showReports by rememberSaveable { mutableStateOf(false) }

    @Composable
    fun KeyRow(name: SecretName, @StringRes label: Int, @StringRes hint: Int, secret: Boolean = true) {
        SecretRow(
            name = name,
            label = stringResource(label),
            hint = stringResource(hint),
            saved = name in saved,
            editing = editing == name,
            onEdit = { editing = it },
            onSave = viewModel::save,
            onRemove = viewModel::remove,
            secret = secret,
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 24.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = stringResource(R.string.dial_services_intro),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
        }
        item { SettingsSection(stringResource(R.string.dial_services_section_film)) }
        item { KeyRow(SecretName.Tmdb, R.string.dial_services_tmdb, R.string.dial_services_tmdb_hint) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_services_extra_subtitles),
                value = stringResource(if (preferences.extraSubtitleSources) R.string.dial_value_on else R.string.dial_value_off),
                onClick = viewModel::toggleExtraSubtitles,
            )
        }
        item { KeyRow(SecretName.Subdl, R.string.dial_services_subdl, R.string.dial_services_subdl_hint) }
        item { KeyRow(SecretName.SubSource, R.string.dial_services_subsource, R.string.dial_services_subsource_hint) }
        item {
            KeyRow(SecretName.TraktClientId, R.string.dial_services_trakt, R.string.dial_services_trakt_hint)
        }
        item {
            KeyRow(SecretName.TraktClientSecret, R.string.dial_services_trakt_secret, R.string.dial_services_trakt_secret_hint)
        }
        // Signed in once here, Trakt works everywhere: Home rows, ratings, comments, scrobbling.
        item {
            val account = traktAccount
            val signIn = traktSignIn
            SettingRow(
                label = when {
                    account != null -> stringResource(R.string.dial_trakt_signed_in, account.username)
                    signIn is TraktSignIn.Code -> stringResource(R.string.dial_trakt_enter_code, signIn.url, signIn.userCode)
                    else -> stringResource(R.string.dial_trakt_sign_in)
                },
                value = when {
                    account != null -> stringResource(R.string.dial_trakt_sign_out)
                    signIn is TraktSignIn.Code -> stringResource(R.string.dial_action_cancel)
                    signIn is TraktSignIn.Failed -> stringResource(
                        when (signIn.message) {
                            "needs_keys" -> R.string.dial_trakt_needs_keys
                            "expired" -> R.string.dial_trakt_expired
                            else -> R.string.dial_trakt_failed
                        }
                    )
                    else -> stringResource(R.string.dial_trakt_sign_in_value)
                },
                onClick = {
                    when {
                        account != null -> viewModel.traktSignOut()
                        signIn is TraktSignIn.Code -> viewModel.traktCancel()
                        else -> viewModel.traktSignIn()
                    }
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_trakt_scrobble),
                value = stringResource(if (preferences.traktScrobble) R.string.dial_value_on else R.string.dial_value_off),
                onClick = viewModel::toggleScrobble,
            )
        }
        item { SettingsSection(stringResource(R.string.dial_services_section_smart_home)) }
        item { SmartHomeRows() }
        item { SettingsSection(stringResource(R.string.dial_services_section_ratings)) }
        item { KeyRow(SecretName.MdbList, R.string.dial_services_mdblist, R.string.dial_services_mdblist_hint) }
        item { KeyRow(SecretName.Omdb, R.string.dial_services_omdb, R.string.dial_services_omdb_hint) }
        item { KeyRow(SecretName.FanartTv, R.string.dial_services_fanart, R.string.dial_services_fanart_hint) }
        item { SettingsSection(stringResource(R.string.dial_services_section_debrid)) }
        item {
            KeyRow(SecretName.RealDebrid, R.string.dial_services_realdebrid, R.string.dial_services_realdebrid_hint)
        }
        item {
            KeyRow(SecretName.TorBox, R.string.dial_services_torbox, R.string.dial_services_torbox_hint)
        }

        item { SettingsSection(stringResource(R.string.dial_services_section_markets)) }
        item { SettingsSection(stringResource(R.string.dial_services_section_odds)) }
        item {
            KeyRow(SecretName.SportsGameOdds, R.string.dial_services_sgo, R.string.dial_services_sgo_hint)
        }
        item {
            KeyRow(SecretName.CoinMarketCap, R.string.dial_services_cmc, R.string.dial_services_cmc_hint)
        }
        item {
            KeyRow(
                SecretName.TelegramBotToken,
                R.string.dial_services_telegram_bot,
                R.string.dial_services_telegram_bot_hint,
            )
        }
        item {
            KeyRow(
                SecretName.TelegramChatId,
                R.string.dial_services_telegram_chat,
                R.string.dial_services_telegram_chat_hint,
                secret = false,
            )
        }
        item {
            KeyRow(
                SecretName.TelegramTradingBot,
                R.string.dial_services_trading_bot,
                R.string.dial_services_trading_bot_hint,
                secret = false,
            )
        }

        item { SettingsSection(stringResource(R.string.dial_services_section_reports)) }
        item {
            KeyRow(SecretName.GitHubRepo, R.string.dial_services_github_repo, R.string.dial_services_github_repo_hint, secret = false)
        }
        item {
            KeyRow(SecretName.GitHubToken, R.string.dial_services_github_token, R.string.dial_services_github_token_hint)
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_services_reports_saved),
                value = stringResource(R.string.dial_services_reports_count, reports.size, reports.count { !it.sent }),
                onClick = { showReports = !showReports },
            )
        }
        if (showReports) {
            items(reports.take(MAX_REPORTS_SHOWN), key = { it.file.name }) { report ->
                SettingRow(
                    label = report.title.ifBlank { report.kind },
                    value = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(report.time)) +
                        if (report.sent) " ✓" else "",
                    onClick = {},
                )
            }
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_services_reports_send),
                value = when (val state = upload) {
                    ReportsUpload.Idle -> ""
                    ReportsUpload.Sending -> stringResource(R.string.dial_services_reports_sending)
                    is ReportsUpload.Sent -> stringResource(R.string.dial_services_reports_sent, state.count)
                    ReportsUpload.NeedsSetup -> stringResource(R.string.dial_services_reports_setup)
                    ReportsUpload.Failed -> stringResource(R.string.dial_services_reports_failed)
                },
                onClick = viewModel::sendReports,
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_services_reports_auto),
                value = stringResource(if (preferences.autoSendReports) R.string.dial_value_on else R.string.dial_value_off),
                onClick = viewModel::toggleAutoSend,
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_services_reports_delete),
                value = "",
                onClick = viewModel::deleteReports,
            )
        }

        item { SettingsSection(stringResource(R.string.dial_services_section_phone)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_services_phone_page),
                value = stringResource(if (phonePage != null) R.string.dial_value_on else R.string.dial_value_off),
                onClick = viewModel::togglePhonePage,
            )
        }
        phonePage?.let { info ->
            item { PhonePageCard(info) }
        }
    }
}

/** Address, QR code and PIN for the phone page. */
@Composable
internal fun PhonePageCard(info: CompanionInfo) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(max = 820.dp),
    ) {
        QrCode(
            text = info.address,
            contentDescription = stringResource(R.string.dial_services_phone_qr),
            size = 140.dp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.dial_services_phone_scan),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
            )
            Text(
                text = info.address,
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Text(
                text = stringResource(R.string.dial_services_phone_pin, info.pin),
                color = TvColors.Focus,
                fontFamily = TvFonts.Accent,
                fontSize = 28.sp,
            )
        }
    }
}

private const val MAX_REPORTS_SHOWN = 10
