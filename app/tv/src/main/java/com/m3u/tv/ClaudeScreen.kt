package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import kotlinx.coroutines.yield

/** The "Ask Claude" tab: set up an API key, then chat about what to watch. */
@Composable
fun ClaudeScreen(
    onPlay: (Channel) -> Unit,
    viewModel: ClaudeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnPlay by rememberUpdatedState(onPlay)
    LaunchedEffect(viewModel) {
        viewModel.play.collect { channel -> currentOnPlay(channel) }
    }
    if (state.configured) {
        ClaudeChat(state = state, viewModel = viewModel, onPlay = onPlay)
    } else {
        ClaudeSetup(state = state, viewModel = viewModel)
    }
}

@Composable
private fun ClaudeSetup(state: ClaudeUiState, viewModel: ClaudeViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
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
                text = stringResource(R.string.dial_claude_setup_title),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                lineHeight = 42.sp,
            )
            Text(
                text = stringResource(R.string.dial_claude_setup_body),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 18.sp,
                lineHeight = 28.sp,
            )
            Text(
                text = stringResource(R.string.dial_claude_setup_tip),
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .focusGroup()
        ) {
            DialTextField(
                label = stringResource(R.string.dial_claude_key_label),
                value = state.keyInput,
                onValueChange = viewModel::updateKeyInput,
                placeholder = stringResource(R.string.dial_claude_key_placeholder),
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                readOnly = state.checkingKey,
                secret = true,
                onDone = viewModel::saveKey,
            )
            ModelChips(selected = state.model, onSelect = viewModel::selectModel)
            TvActionButton(
                text = stringResource(R.string.dial_claude_key_save),
                icon = Icons.Rounded.Key,
                onClick = viewModel::saveKey,
                enabled = !state.checkingKey && state.keyInput.isNotBlank(),
                focusableWhenDisabled = true,
            )
            val message = when {
                state.checkingKey -> stringResource(R.string.dial_claude_key_checking)
                state.failure != null -> failureText(state.failure)
                else -> null
            }
            if (message != null) {
                Text(
                    text = message,
                    color = if (state.failure != null) TvColors.Danger else TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun ClaudeChat(state: ClaudeUiState, viewModel: ClaudeViewModel, onPlay: (Channel) -> Unit) {
    val listState = rememberLazyListState()
    val firstSuggestion = remember { FocusRequester() }
    val suggestions = listOf(
        stringResource(R.string.dial_claude_suggest_on_now),
        stringResource(R.string.dial_claude_suggest_film),
        stringResource(R.string.dial_claude_suggest_series),
        stringResource(R.string.dial_claude_suggest_sport),
    )
    LaunchedEffect(state.entries.size, state.thinking) {
        val last = state.entries.size + (if (state.thinking) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }
    LaunchedEffect(Unit) {
        if (state.entries.isEmpty()) {
            yield()
            runCatching { firstSuggestion.requestFocus() }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 32.dp, end = 64.dp, bottom = 24.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.focusGroup()
        ) {
            SectionTitle(
                title = stringResource(R.string.dial_claude_title),
                subtitle = stringResource(R.string.dial_claude_subtitle, modelLabel(state.model)),
                modifier = Modifier.weight(1f),
            )
            TvActionButton(
                text = modelLabel(state.model),
                icon = Icons.Rounded.Tune,
                onClick = { viewModel.selectModel(ClaudeModel.entries.nextAfter(state.model)) },
            )
            TvActionButton(
                text = stringResource(R.string.dial_claude_new_chat),
                icon = Icons.Rounded.Refresh,
                onClick = viewModel::clearConversation,
            )
            TvActionButton(
                text = stringResource(R.string.dial_claude_forget_key),
                icon = Icons.Rounded.Delete,
                onClick = viewModel::forgetKey,
            )
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusGroup()
        ) {
            if (state.entries.isEmpty()) {
                item(key = "intro") {
                    Text(
                        text = stringResource(R.string.dial_claude_intro),
                        color = TvColors.TextSecondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                    )
                }
            }
            itemsIndexed(state.entries, key = { index, _ -> "entry-$index" }) { _, entry ->
                ClaudeBubble(entry = entry, onPlay = onPlay)
            }
            if (state.thinking) {
                item(key = "thinking") {
                    Text(
                        text = stringResource(R.string.dial_claude_thinking),
                        color = TvColors.Accent,
                        fontFamily = TvFonts.Body,
                        fontSize = 16.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            state.failure?.let { failure ->
                item(key = "failure") {
                    Text(
                        text = failureText(failure),
                        color = TvColors.Danger,
                        fontFamily = TvFonts.Body,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.focusGroup()
        ) {
            itemsIndexed(suggestions, key = { index, _ -> "suggestion-$index" }) { index, suggestion ->
                TvActionButton(
                    text = suggestion,
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = { viewModel.send(suggestion) },
                    enabled = !state.thinking,
                    focusableWhenDisabled = true,
                    focusRequester = if (index == 0) firstSuggestion else null,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.focusGroup()
        ) {
            Box(Modifier.weight(1f)) {
                DialTextField(
                    label = stringResource(R.string.dial_claude_input_label),
                    value = state.input,
                    onValueChange = viewModel::updateInput,
                    placeholder = stringResource(R.string.dial_claude_input_placeholder),
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    readOnly = false,
                    onDone = { viewModel.send() },
                )
            }
            TvActionButton(
                text = stringResource(R.string.dial_claude_send),
                icon = Icons.AutoMirrored.Rounded.Send,
                onClick = { viewModel.send() },
                enabled = !state.thinking && state.input.isNotBlank(),
                focusableWhenDisabled = true,
            )
        }
    }
}

@Composable
private fun ClaudeBubble(entry: ClaudeEntry, onPlay: (Channel) -> Unit) {
    val fromUser = entry.role == ClaudeRole.User
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = entry.text,
            color = if (fromUser) TvColors.OnFocus else TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontSize = 18.sp,
            lineHeight = 27.sp,
            modifier = Modifier
                .widthIn(max = 900.dp)
                .background(
                    if (fromUser) TvColors.Focus.copy(alpha = 0.85f) else TvColors.Surface.copy(alpha = 0.86f),
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
        if (entry.items.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.focusGroup()
            ) {
                items(entry.items, key = { it.id }) { channel ->
                    TvActionButton(
                        text = channel.title,
                        icon = Icons.Rounded.PlayArrow,
                        onClick = { onPlay(channel) },
                    )
                }
            }
        }
    }
}

/** The three models stacked, so all of them are visible at once in the narrow setup column. */
@Composable
private fun ModelChips(selected: ClaudeModel, onSelect: (ClaudeModel) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.focusGroup()
    ) {
        ClaudeModel.entries.forEach { model ->
            TvActionButton(
                text = modelLabel(model),
                icon = Icons.Rounded.AutoAwesome,
                selected = model == selected,
                onClick = { onSelect(model) },
            )
        }
    }
}

@Composable
private fun modelLabel(model: ClaudeModel): String = stringResource(
    when (model) {
        ClaudeModel.Haiku -> R.string.dial_claude_model_haiku
        ClaudeModel.Sonnet -> R.string.dial_claude_model_sonnet
        ClaudeModel.Opus -> R.string.dial_claude_model_opus
    }
)

@Composable
private fun failureText(failure: ClaudeFailure): String = when (failure) {
    ClaudeFailure.NoKey -> stringResource(R.string.dial_claude_error_no_key)
    ClaudeFailure.Rejected -> stringResource(R.string.dial_claude_error_rejected)
    ClaudeFailure.NoCredit -> stringResource(R.string.dial_claude_error_credit)
    ClaudeFailure.RateLimited -> stringResource(R.string.dial_claude_error_rate)
    ClaudeFailure.Overloaded -> stringResource(R.string.dial_claude_error_busy)
    ClaudeFailure.Offline -> stringResource(R.string.dial_claude_error_offline)
    is ClaudeFailure.Other -> stringResource(
        R.string.dial_claude_error_other,
        failure.code?.toString() ?: stringResource(R.string.dial_claude_error_no_response),
    )
}
