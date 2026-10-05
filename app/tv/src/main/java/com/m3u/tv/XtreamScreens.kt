package com.m3u.tv

import java.util.Locale
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.m3u.data.database.model.Playlist
import com.m3u.data.worker.SubscriptionWorker
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/* ---------------------------------------------------------------------------------------------
 * Sign in
 * ------------------------------------------------------------------------------------------- */

@Composable
fun XtreamSignInScreen(
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false,
    onCancel: (() -> Unit)? = null,
    viewModel: XtreamAccountViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val services: ServicesSettingsViewModel = hiltViewModel()
    val phonePage by services.phonePage.collectAsStateWithLifecycle()
    val serverFocus = remember { FocusRequester() }
    // The form is taller than the screen with the Xtream/M3U switch on top; whenever there's
    // news (checking, progress, done, a problem), scroll down so the message under the button
    // is on screen.
    val formScroll = rememberScrollState()
    LaunchedEffect(form.phase) {
        if (form.phase != XtreamSignInPhase.Idle) formScroll.animateScrollTo(formScroll.maxValue)
    }
    val signInFocus = remember { FocusRequester() }

    // Wait until nothing covers the screen (e.g. the launch animation) before taking focus.
    val focusAllowed = LocalTvFocusEnabled.current
    LaunchedEffect(requestInitialFocus, focusAllowed) {
        if (requestInitialFocus && focusAllowed) {
            yield()
            runCatching { serverFocus.requestFocus() }
        }
    }

    val m3u = form.mode == SignInMode.M3u
    Row(
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 32.dp, end = 64.dp, bottom = 32.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(0.9f)
                .widthIn(max = 520.dp)
        ) {
            Text(
                text = stringResource(if (m3u) R.string.dial_m3u_title else R.string.dial_signin_title),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                lineHeight = 42.sp,
            )
            Text(
                text = stringResource(if (m3u) R.string.dial_m3u_body else R.string.dial_signin_body),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 18.sp,
                lineHeight = 28.sp,
            )
            Text(
                text = stringResource(R.string.dial_signin_keyboard_hint),
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .verticalScroll(formScroll)
                .focusGroup()
        ) {
            // Xtream login or a plain M3U link.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    text = stringResource(R.string.dial_signin_mode_xtream),
                    icon = Icons.Rounded.Dns,
                    selected = !m3u,
                    onClick = { viewModel.setMode(SignInMode.Xtream) },
                )
                TvActionButton(
                    text = stringResource(R.string.dial_signin_mode_m3u),
                    icon = Icons.Rounded.Link,
                    selected = m3u,
                    onClick = { viewModel.setMode(SignInMode.M3u) },
                )
            }
            if (m3u) {
                DialTextField(
                    label = stringResource(R.string.dial_field_playlist_url),
                    value = form.playlistUrl,
                    onValueChange = viewModel::updatePlaylistUrl,
                    placeholder = stringResource(R.string.dial_field_playlist_url_placeholder),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    readOnly = form.busy,
                    focusRequester = serverFocus,
                )
                DialTextField(
                    label = stringResource(R.string.dial_field_epg_url),
                    value = form.epgUrl,
                    onValueChange = viewModel::updateEpgUrl,
                    placeholder = stringResource(R.string.dial_field_epg_url_placeholder),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    readOnly = form.busy,
                )
                // Free-to-air channels from iptv-org's public list, for the country the TV is set to.
                TvActionButton(
                    text = stringResource(R.string.dial_signin_free_channels),
                    icon = Icons.Rounded.Link,
                    supportingText = stringResource(R.string.dial_signin_free_channels_hint),
                    onClick = {
                        val country = Locale.getDefault().country.ifBlank { "GB" }.lowercase(Locale.ROOT)
                        viewModel.updatePlaylistUrl("https://iptv-org.github.io/iptv/countries/$country.m3u")
                        viewModel.updateEpgUrl("")
                    },
                )
            } else {
            DialTextField(
                label = stringResource(R.string.dial_field_server),
                value = form.server,
                onValueChange = viewModel::updateServer,
                placeholder = stringResource(R.string.dial_field_server_placeholder),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                readOnly = form.busy,
                focusRequester = serverFocus,
            )
            DialTextField(
                label = stringResource(R.string.dial_field_username),
                value = form.username,
                onValueChange = viewModel::updateUsername,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
                readOnly = form.busy,
            )
            DialTextField(
                label = stringResource(R.string.dial_field_password),
                value = form.password,
                onValueChange = viewModel::updatePassword,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
                readOnly = form.busy,
                secret = true,
            )
            }
            DialTextField(
                label = stringResource(R.string.dial_field_name),
                value = form.name,
                onValueChange = viewModel::updateName,
                placeholder = stringResource(R.string.dial_field_name_placeholder),
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                readOnly = form.busy,
                onDone = {
                    runCatching { signInFocus.requestFocus() }
                    viewModel.submit()
                },
                // Straight down to Sign in, not the phone button beside it.
                downFocus = signInFocus,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                TvActionButton(
                    text = stringResource(
                        if (m3u) R.string.dial_action_add_playlist else R.string.dial_action_sign_in
                    ),
                    icon = Icons.Rounded.CheckCircle,
                    onClick = viewModel::submit,
                    enabled = !form.busy,
                    focusableWhenDisabled = true,
                    focusRequester = signInFocus,
                )
                // Typing a long server address and password with a remote is slow.
                TvActionButton(
                    text = stringResource(R.string.dial_signin_use_phone),
                    icon = Icons.Rounded.PhoneAndroid,
                    selected = phonePage != null,
                    onClick = services::togglePhonePage,
                )
                if (onCancel != null) {
                    TvActionButton(
                        text = stringResource(R.string.dial_action_cancel),
                        icon = Icons.Rounded.Close,
                        onClick = onCancel,
                    )
                }
            }
            phonePage?.let { PhonePageCard(it) }
            SignInMessage(form.phase, m3u)
        }
    }
}

@Composable
private fun SignInMessage(phase: XtreamSignInPhase, m3u: Boolean) {
    val (text, color) = when (phase) {
        XtreamSignInPhase.Idle -> return
        XtreamSignInPhase.Checking ->
            stringResource(R.string.dial_signin_checking) to TvColors.TextSecondary
        is XtreamSignInPhase.Importing -> when {
            m3u && phase.count > 0 -> stringResource(R.string.dial_m3u_importing_count, phase.count)
            m3u -> stringResource(R.string.dial_m3u_importing)
            phase.count > 0 -> stringResource(R.string.dial_signin_importing_count, phase.count)
            else -> stringResource(R.string.dial_signin_importing)
        } to TvColors.TextSecondary
        XtreamSignInPhase.Done ->
            stringResource(if (m3u) R.string.dial_m3u_done else R.string.dial_signin_done) to TvColors.Positive
        is XtreamSignInPhase.Failed -> when {
            m3u && phase.error == XtreamSignInError.ImportFailed &&
                phase.detail != SubscriptionWorker.FAILURE_STORAGE &&
                phase.detail != SubscriptionWorker.FAILURE_TOO_LARGE ->
                stringResource(R.string.dial_error_m3u_import)
            else -> signInErrorText(phase)
        } to TvColors.Danger
    }
    Text(
        text = text,
        color = color,
        fontFamily = TvFonts.Body,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** Says what actually went wrong reaching the server, not just that it failed. */
@Composable
private fun unreachableText(status: XtreamAccountStatus.Unreachable?): String {
    val host = status?.host?.takeIf { it.isNotBlank() }
    if (status == null || host == null) return stringResource(R.string.dial_error_unreachable)
    return when (status.problem) {
        XtreamProblem.HostNotFound -> stringResource(R.string.dial_error_host_not_found, host)
        XtreamProblem.Refused -> stringResource(R.string.dial_error_refused, host)
        XtreamProblem.Timeout -> stringResource(R.string.dial_error_timeout, host)
        XtreamProblem.Secure -> stringResource(R.string.dial_error_secure, host)
        XtreamProblem.Forbidden -> stringResource(R.string.dial_error_forbidden, host)
        XtreamProblem.HttpStatus -> stringResource(R.string.dial_error_http, host, status.httpCode ?: 0)
        XtreamProblem.NotXtream -> stringResource(R.string.dial_error_not_xtream, host)
        XtreamProblem.Generic -> stringResource(R.string.dial_error_unreachable)
    }
}

@Composable
private fun signInErrorText(failure: XtreamSignInPhase.Failed): String = when (failure.error) {
    XtreamSignInError.MissingPlaylistUrl -> stringResource(R.string.dial_error_missing_playlist_url)
    XtreamSignInError.MissingServer -> stringResource(R.string.dial_error_missing_server)
    XtreamSignInError.MissingCredentials -> stringResource(R.string.dial_error_missing_credentials)
    XtreamSignInError.Unreachable -> unreachableText(failure.unreachable)
    XtreamSignInError.Rejected -> stringResource(R.string.dial_error_rejected)
    XtreamSignInError.AccountInactive -> failure.detail
        ?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.dial_error_inactive, it) }
        ?: stringResource(R.string.dial_error_inactive_generic)
    XtreamSignInError.ImportFailed -> when (failure.detail) {
        SubscriptionWorker.FAILURE_TIMEOUT -> stringResource(R.string.dial_error_import_timeout)
        SubscriptionWorker.FAILURE_NETWORK -> stringResource(R.string.dial_error_import_network)
        SubscriptionWorker.FAILURE_STORAGE -> stringResource(R.string.dial_error_import_storage)
        SubscriptionWorker.FAILURE_TOO_LARGE -> stringResource(R.string.dial_error_import_too_large)
        else -> stringResource(R.string.dial_error_import)
    }
}

/**
 * Single-line field that behaves on a remote: up/down leave the field instead of moving the
 * caret, and the centre button opens the on-screen keyboard.
 *
 * Moving onto the field only highlights it. Until OK is pressed the field is read-only, so no
 * typing session exists and nothing can bring the keyboard up (Fire OS opens it for any focused
 * text box that's ready for typing, whatever the app asks). Leaving the field, or Done on the
 * keyboard, ends typing again.
 */
@Composable
internal fun DialTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    readOnly: Boolean,
    placeholder: String = "",
    secret: Boolean = false,
    focusRequester: FocusRequester? = null,
    onDone: () -> Unit = {},
    /** Where Down goes from this field, when the nearest thing below isn't the right one. */
    downFocus: FocusRequester? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    // Down (or "Next" on the keyboard): to [downFocus] when it's on screen, else the next thing
    // below. A target that isn't on screen yet must never throw.
    val moveDown: () -> Boolean = {
        val target = downFocus
        if (target != null && runCatching { target.requestFocus() }.isSuccess) {
            true
        } else {
            focusManager.moveFocus(FocusDirection.Down)
        }
    }
    // The typing session starts a frame after the field stops being read-only; ask for the
    // keyboard once it exists.
    LaunchedEffect(typing) {
        if (typing) {
            withFrameNanos { }
            keyboard?.show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = if (focused) TvColors.Focus else TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly || !typing,
            singleLine = true,
            textStyle = TextStyle(
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontSize = 18.sp,
            ),
            cursorBrush = SolidColor(TvColors.Focus),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
                // Moving onto the field with the remote only highlights it; OK opens the keyboard.
                showKeyboardOnFocus = false,
            ),
            keyboardActions = KeyboardActions(
                onNext = {
                    // "Next" on the keyboard carries on typing in the field below.
                    KeyboardHandoff.pass()
                    moveDown()
                },
                onDone = {
                    keyboard?.hide()
                    typing = false
                    onDone()
                },
            ),
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    typing = it.isFocused && !readOnly && KeyboardHandoff.take()
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            Key.DirectionDown -> moveDown()
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
                                readOnly -> true
                                !typing -> {
                                    typing = true
                                    true
                                }
                                event.key == Key.DirectionCenter -> {
                                    keyboard?.show()
                                    true
                                }
                                // Enter from a real keyboard: the field's own action (Next, Done).
                                else -> false
                            }
                            // Back closes the keyboard (the keyboard handles that itself); with it
                            // already closed, it ends typing and goes on to the screen's Back.
                            Key.Back -> {
                                typing = false
                                false
                            }
                            else -> false
                        }
                    }
                }
                .semantics {
                    contentDescription = label
                    if (secret) password()
                }
                .background(
                    if (focused) TvColors.SurfaceRaised else TvColors.Surface.copy(alpha = 0.72f),
                    HudShape
                )
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) TvColors.Focus else TvColors.Focus.copy(alpha = 0.25f),
                    shape = HudShape,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = TvColors.TextMuted,
                            fontFamily = TvFonts.Body,
                            fontSize = 18.sp,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/**
 * "Next" on the on-screen keyboard: the field that takes focus straight after carries on typing,
 * where a field reached with the remote would only be highlighted. Main thread only.
 */
internal object KeyboardHandoff {
    private var passedAt = 0L

    fun pass() {
        passedAt = SystemClock.uptimeMillis()
    }

    /** True (once) if typing was passed on a moment ago. */
    fun take(): Boolean {
        val passed = SystemClock.uptimeMillis() - passedAt < HANDOFF_WINDOW_MS
        passedAt = 0L
        return passed
    }

    private const val HANDOFF_WINDOW_MS = 600L
}

/* ---------------------------------------------------------------------------------------------
 * Accounts
 * ------------------------------------------------------------------------------------------- */

@Composable
fun XtreamAccountScreen(
    viewModel: XtreamAccountViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val m3uPlaylists by viewModel.m3uPlaylists.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    val nothingAdded = accounts.isEmpty() && m3uPlaylists.isEmpty()

    LaunchedEffect(form.phase) {
        if (adding && form.phase == XtreamSignInPhase.Done) adding = false
    }

    if (nothingAdded || adding) {
        XtreamSignInScreen(
            viewModel = viewModel,
            onCancel = if (nothingAdded) null else {
                {
                    viewModel.resetForm()
                    adding = false
                }
            },
        )
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 48.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
    ) {
        item {
            SectionTitle(
                title = stringResource(R.string.dial_accounts_title),
                subtitle = stringResource(R.string.dial_accounts_subtitle),
            )
        }
        items(accounts, key = { it.key }) { account ->
            AccountCard(
                account = account,
                status = statuses[account.key] ?: XtreamAccountStatus.Loading,
                onRefresh = { viewModel.refreshStatus(account) },
                onRemove = { viewModel.remove(account) },
            )
        }
        items(m3uPlaylists, key = { "m3u-${it.url}" }) { playlist ->
            M3uPlaylistCard(
                playlist = playlist,
                onRefresh = { viewModel.refreshPlaylist(playlist) },
                onRemove = { viewModel.removePlaylist(playlist) },
            )
        }
        item {
            TvActionButton(
                text = stringResource(R.string.dial_action_add_account),
                icon = Icons.Rounded.Add,
                onClick = {
                    viewModel.resetForm()
                    adding = true
                },
            )
        }
    }
}

@Composable
private fun AccountCard(
    account: XtreamAccount,
    status: XtreamAccountStatus,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmingRemove by remember(account.key) { mutableStateOf(false) }
    LaunchedEffect(confirmingRemove) {
        if (confirmingRemove) {
            delay(4_000)
            confirmingRemove = false
        }
    }
    val host = account.credentials.server
        .substringAfter("://")

    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 880.dp)
            .background(TvColors.Surface.copy(alpha = 0.86f), RoundedCornerShape(16.dp))
            .padding(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = account.title,
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.dial_account_login, account.credentials.username, host),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            val (statusText, statusColor) = statusLine(status)
            AccountFact(stringResource(R.string.dial_account_status_label), statusText, statusColor)
            if (status is XtreamAccountStatus.Ready) {
                AccountFact(
                    label = stringResource(R.string.dial_account_expires_label),
                    value = expiryText(status.expiresAtMillis),
                    valueColor = expiryColor(status.expiresAtMillis),
                )
                AccountFact(
                    label = stringResource(R.string.dial_account_connections_label),
                    value = connectionsText(status),
                    valueColor = TvColors.TextPrimary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvActionButton(
                text = stringResource(R.string.dial_action_check_again),
                icon = Icons.Rounded.Refresh,
                onClick = onRefresh,
                enabled = status != XtreamAccountStatus.Loading,
                focusableWhenDisabled = true,
            )
            TvActionButton(
                text = stringResource(
                    if (confirmingRemove) R.string.dial_action_remove_confirm
                    else R.string.dial_action_remove
                ),
                icon = Icons.Rounded.Delete,
                onClick = {
                    if (confirmingRemove) onRemove() else confirmingRemove = true
                },
            )
        }
    }
}

/** A plain M3U playlist on the Accounts page: its name and link, reload and remove. */
@Composable
private fun M3uPlaylistCard(
    playlist: Playlist,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmingRemove by remember(playlist.url) { mutableStateOf(false) }
    LaunchedEffect(confirmingRemove) {
        if (confirmingRemove) {
            delay(4_000)
            confirmingRemove = false
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 880.dp)
            .background(TvColors.Surface.copy(alpha = 0.86f), RoundedCornerShape(16.dp))
            .padding(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = playlist.title,
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (playlist.epgUrls.isEmpty()) R.string.dial_m3u_card_no_guide
                    else R.string.dial_m3u_card_with_guide
                ),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvActionButton(
                text = stringResource(R.string.dial_action_reload),
                icon = Icons.Rounded.Refresh,
                onClick = onRefresh,
            )
            TvActionButton(
                text = stringResource(
                    if (confirmingRemove) R.string.dial_action_remove_confirm
                    else R.string.dial_action_remove
                ),
                icon = Icons.Rounded.Delete,
                onClick = {
                    if (confirmingRemove) onRemove() else confirmingRemove = true
                },
            )
        }
    }
}

@Composable
private fun AccountFact(label: String, value: String, valueColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 13.sp,
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun statusLine(status: XtreamAccountStatus): Pair<String, Color> = when (status) {
    XtreamAccountStatus.Loading ->
        stringResource(R.string.dial_account_checking) to TvColors.TextSecondary
    is XtreamAccountStatus.Unreachable -> when (status.problem) {
        XtreamProblem.Forbidden -> stringResource(R.string.dial_account_blocked)
        XtreamProblem.HttpStatus -> stringResource(R.string.dial_account_http_error, status.httpCode ?: 0)
        XtreamProblem.NotXtream -> stringResource(R.string.dial_account_not_xtream)
        else -> stringResource(R.string.dial_account_unreachable)
    } to TvColors.Danger
    XtreamAccountStatus.Rejected ->
        stringResource(R.string.dial_account_rejected) to TvColors.Danger
    is XtreamAccountStatus.Ready -> when {
        !status.active -> (status.status ?: "") to TvColors.Danger
        status.trial -> stringResource(R.string.dial_account_status_trial) to TvColors.Positive
        else -> stringResource(R.string.dial_account_status_active) to TvColors.Positive
    }
}

@Composable
private fun expiryText(expiresAtMillis: Long?): String {
    if (expiresAtMillis == null) return stringResource(R.string.dial_account_no_expiry)
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expiresAtMillis))
    val remaining = expiresAtMillis - System.currentTimeMillis()
    val detail = if (remaining <= 0) {
        stringResource(R.string.dial_account_expired)
    } else {
        val days = TimeUnit.MILLISECONDS.toDays(remaining).toInt()
        pluralStringResource(R.plurals.dial_account_days_left, days, days)
    }
    return stringResource(R.string.dial_account_expires_value, date, detail)
}

private fun expiryColor(expiresAtMillis: Long?): Color {
    if (expiresAtMillis == null) return TvColors.TextPrimary
    val days = TimeUnit.MILLISECONDS.toDays(expiresAtMillis - System.currentTimeMillis())
    return when {
        days < 0 -> TvColors.Danger
        days <= 7 -> TvColors.Focus
        else -> TvColors.TextPrimary
    }
}

@Composable
private fun connectionsText(status: XtreamAccountStatus.Ready): String {
    val active = status.activeConnections
    val max = status.maxConnections
    return if (active != null && max != null) {
        stringResource(R.string.dial_account_connections_value, active, max)
    } else {
        stringResource(R.string.dial_account_not_reported)
    }
}
