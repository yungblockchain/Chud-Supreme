package com.m3u.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.ArrayDeque
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/* -------------------------------------------------------------------------------------------------
 * "What's going on?" from the player: Claude gets the title, the episode, how far in it is and
 * the last minute or two of subtitles, and explains the scene in a few lines without spoiling
 * anything later. Needs the person's Claude key (Claude tab).
 * ---------------------------------------------------------------------------------------------- */

@Immutable
sealed interface SceneAnswer {
    data object Idle : SceneAnswer
    data object Thinking : SceneAnswer
    data class Answer(val text: String) : SceneAnswer
    data class Failed(val reason: String) : SceneAnswer
}

/** The subtitles shown lately, by where they were in the video, so Claude can read what was said. */
class RecentDialogue(private val player: Player) : Player.Listener {
    private val lines = ArrayDeque<Pair<Long, String>>()

    override fun onCues(cueGroup: CueGroup) {
        val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        if (text.isEmpty()) return
        val at = player.currentPosition
        synchronized(lines) {
            if (lines.peekLast()?.second == text) return
            lines.addLast(at to text)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** What was said in the [windowMs] before the current position, in order; nothing from later on. */
    fun recent(windowMs: Long = WINDOW_MS): List<String> {
        val now = player.currentPosition
        return synchronized(lines) {
            lines.filter { it.first in (now - windowMs)..now }.sortedBy { it.first }.map { it.second }.distinct()
        }
    }

    private companion object {
        const val MAX_LINES = 200
        const val WINDOW_MS = 150_000L
    }
}

@HiltViewModel
class SceneExplainerViewModel @Inject constructor(
    private val settings: ClaudeSettingsStore,
) : ViewModel() {
    private val _answer = MutableStateFlow<SceneAnswer>(SceneAnswer.Idle)
    val answer: StateFlow<SceneAnswer> = _answer.asStateFlow()
    private var job: Job? = null

    val available: Boolean get() = settings.hasApiKey()

    fun explain(title: String, episode: String?, positionMs: Long, dialogue: List<String>, noKey: String, failed: String) {
        val key = settings.readApiKey()
        if (key == null) {
            _answer.value = SceneAnswer.Failed(noKey)
            return
        }
        job?.cancel()
        _answer.value = SceneAnswer.Thinking
        job = viewModelScope.launch {
            val minutes = positionMs / 60_000
            val seconds = (positionMs / 1000) % 60
            val prompt = buildString {
                append("I'm watching \"").append(title).append('"')
                episode?.let { append(" (").append(it).append(')') }
                append(", ").append(minutes).append(" min ").append(seconds).append(" s in.\n")
                if (dialogue.isNotEmpty()) {
                    append("The last lines of dialogue were:\n")
                    dialogue.takeLast(40).forEach { append("- ").append(it).append('\n') }
                } else {
                    append("There are no subtitles to go on.\n")
                }
                append("What's going on in this scene, and who are the people in it? ")
                append("Answer in under 90 words, plain text. Don't reveal anything that happens after this point.")
            }
            _answer.value = try {
                val response = ClaudeApi.messages(
                    apiKey = key,
                    model = settings.model,
                    system = "You explain scenes of films and TV shows to a viewer who just missed something. Be brief, warm and spoiler-free.",
                    tools = JsonArray(emptyList()),
                    messages = listOf(buildJsonObject { put("role", "user"); put("content", prompt) }),
                    maxTokens = 400,
                )
                val text = response["content"]?.jsonArray.orEmpty()
                    .mapNotNull { block -> block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.contentOrNull }
                    .joinToString("\n").trim()
                if (text.isEmpty()) SceneAnswer.Failed(failed) else SceneAnswer.Answer(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SceneAnswer.Failed(failed)
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        _answer.value = SceneAnswer.Idle
    }
}

/** The answer card over the video; Back closes it. */
@Composable
fun SceneAnswerCard(answer: SceneAnswer, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (answer == SceneAnswer.Idle) return
    BackHandler(onBack = onDismiss)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .width(560.dp)
            .background(TvColors.Background.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.dial_scene_title),
            color = TvColors.Focus,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Text(
            text = when (answer) {
                SceneAnswer.Thinking -> stringResource(R.string.dial_scene_thinking)
                is SceneAnswer.Answer -> answer.text
                is SceneAnswer.Failed -> answer.reason
                SceneAnswer.Idle -> ""
            },
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontSize = 18.sp,
            lineHeight = 26.sp,
        )
        Text(
            text = stringResource(R.string.dial_scene_back),
            color = TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 12.sp,
        )
    }
}
