package com.m3u.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import java.util.Locale

/* -------------------------------------------------------------------------------------------------
 * The Files tab: the saved shares, a share's folders, and a form to add one. OK opens a folder or
 * plays a video; Back goes up a folder; holding OK on a share forgets it.
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun FilesScreen(
    onPlaying: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shares by viewModel.shares.collectAsStateWithLifecycle()
    BackHandler(enabled = state.adding || state.share != null) { viewModel.back() }
    when {
        state.adding -> AddShareForm(viewModel)
        state.share != null -> FolderPage(state, viewModel, onPlaying)
        else -> SharesPage(shares, state.focusPath, viewModel)
    }
}

@Composable
private fun SharesPage(shares: List<FileShare>, focusId: String?, viewModel: FilesViewModel) {
    val first = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val target = shares.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        if (shares.isNotEmpty()) listState.scrollToItem(target + 1)
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 32.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup(),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(R.string.dial_nav_files), color = TvColors.TextPrimary, fontFamily = TvFonts.Accent, fontSize = 28.sp)
                Text(
                    text = stringResource(R.string.dial_files_intro),
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 15.sp,
                    modifier = Modifier.widthIn(max = 860.dp),
                )
            }
        }
        itemsIndexed(shares, key = { _, share -> share.id }) { index, share ->
            EntryRow(
                icon = if (share.kind == ShareKind.Smb) Icons.Rounded.Lan else Icons.Rounded.Storage,
                title = share.name,
                subtitle = if (share.kind == ShareKind.Smb) "SMB · ${share.address}/${share.share}" else "WebDAV · ${share.address}",
                onClick = { viewModel.open(share) },
                onLongClick = { viewModel.remove(share) },
                focusRequester = first.takeIf { index == target },
            )
        }
        item {
            TvActionButton(
                text = stringResource(R.string.dial_files_add),
                icon = Icons.Rounded.Add,
                onClick = viewModel::startAdding,
                focusRequester = first.takeIf { shares.isEmpty() },
            )
        }
        if (shares.isNotEmpty()) {
            item {
                Text(text = stringResource(R.string.dial_files_forget_hint), color = TvColors.TextMuted, fontFamily = TvFonts.Body, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FolderPage(state: FilesState, viewModel: FilesViewModel, onPlaying: () -> Unit) {
    val share = state.share ?: return
    val first = remember(state.path) { FocusRequester() }
    val listState = rememberLazyListState()
    val target = state.entries.indexOfFirst { it.path == state.focusPath }.coerceAtLeast(0)
    // Once a folder has loaded (or failed): its first row, or the folder just stepped out of.
    LaunchedEffect(state.path, state.loading, state.error != null) {
        if (state.loading) return@LaunchedEffect
        if (state.entries.isNotEmpty()) listState.scrollToItem(target + 1)
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 32.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup(),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = share.name,
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                )
                Text(
                    text = when {
                        state.loading -> stringResource(R.string.dial_radio_loading)
                        state.error != null -> stringResource(R.string.dial_files_error, state.error)
                        state.entries.isEmpty() -> stringResource(R.string.dial_files_empty)
                        else -> "/" + state.path
                    },
                    color = if (state.error != null) TvColors.Danger else TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                )
            }
        }
        itemsIndexed(state.entries, key = { _, entry -> entry.path }) { index, entry ->
            EntryRow(
                icon = when {
                    entry.folder -> Icons.Rounded.Folder
                    entry.playable -> Icons.Rounded.Movie
                    else -> Icons.Rounded.Description
                },
                title = entry.name,
                subtitle = if (entry.folder) null else formatSize(entry.size),
                onClick = {
                    when {
                        entry.folder -> viewModel.open(share, entry.path)
                        entry.playable -> viewModel.play(entry, onPlaying)
                    }
                },
                focusRequester = first.takeIf { index == target && !state.loading },
                dimmed = !entry.folder && !entry.playable,
            )
        }
        if (state.error != null) {
            item {
                TvActionButton(
                    text = stringResource(R.string.dial_files_retry),
                    icon = Icons.Rounded.Check,
                    onClick = { viewModel.open(share, state.path) },
                    focusRequester = first.takeIf { state.entries.isEmpty() },
                )
            }
        }
    }
}

@Composable
private fun EntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    dimmed: Boolean = false,
) {
    FocusFrame(
        onClick = onClick,
        onLongClick = onLongClick,
        focusRequester = focusRequester,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.01f,
        semanticsLabel = title,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 1000.dp),
    ) { focused ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    focused -> TvColors.OnFocus
                    dimmed -> TvColors.TextMuted
                    else -> TvColors.Focus
                },
                modifier = Modifier.size(26.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (focused) TvColors.OnFocus else if (dimmed) TvColors.TextMuted else TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        color = if (focused) TvColors.OnFocus.copy(alpha = 0.75f) else TvColors.TextMuted,
                        fontFamily = TvFonts.Body,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddShareForm(viewModel: FilesViewModel) {
    var kind by rememberSaveable { mutableStateOf(ShareKind.Smb) }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var share by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 32.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup(),
    ) {
        item {
            Text(
                text = stringResource(R.string.dial_files_add),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShareKind.entries.forEachIndexed { index, option ->
                    TvActionButton(
                        text = if (option == ShareKind.Smb) stringResource(R.string.dial_files_kind_smb) else stringResource(R.string.dial_files_kind_webdav),
                        icon = if (option == ShareKind.Smb) Icons.Rounded.Lan else Icons.Rounded.Storage,
                        selected = kind == option,
                        onClick = { kind = option },
                        focusRequester = first.takeIf { index == 0 },
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(if (kind == ShareKind.Smb) R.string.dial_files_smb_hint else R.string.dial_files_webdav_hint),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 860.dp),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 720.dp)) {
                DialTextField(
                    label = stringResource(if (kind == ShareKind.Smb) R.string.dial_files_host else R.string.dial_files_url),
                    value = address,
                    onValueChange = { address = it },
                    placeholder = if (kind == ShareKind.Smb) "192.168.1.20" else "http://192.168.1.20:5005/video",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    readOnly = false,
                )
                if (kind == ShareKind.Smb) {
                    DialTextField(
                        label = stringResource(R.string.dial_files_share),
                        value = share,
                        onValueChange = { share = it },
                        placeholder = "Movies",
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        readOnly = false,
                    )
                }
                DialTextField(
                    label = stringResource(R.string.dial_files_user),
                    value = user,
                    onValueChange = { user = it },
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                    readOnly = false,
                )
                DialTextField(
                    label = stringResource(R.string.dial_field_password),
                    value = password,
                    onValueChange = { password = it },
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                    readOnly = false,
                    secret = true,
                )
                if (kind == ShareKind.Smb) {
                    DialTextField(
                        label = stringResource(R.string.dial_files_domain),
                        value = domain,
                        onValueChange = { domain = it },
                        placeholder = "WORKGROUP",
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        readOnly = false,
                    )
                }
                DialTextField(
                    label = stringResource(R.string.dial_files_name),
                    value = name,
                    onValueChange = { name = it },
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    readOnly = false,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    text = stringResource(R.string.dial_files_save),
                    icon = Icons.Rounded.Check,
                    enabled = address.isNotBlank() && (kind == ShareKind.WebDav || share.isNotBlank()),
                    focusableWhenDisabled = true,
                    onClick = { viewModel.add(kind, name, address, share, user, domain, password) },
                )
                TvActionButton(
                    text = stringResource(R.string.dial_channels_rename_cancel),
                    icon = Icons.Rounded.Close,
                    onClick = viewModel::cancelAdding,
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    bytes > 0 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> ""
}
