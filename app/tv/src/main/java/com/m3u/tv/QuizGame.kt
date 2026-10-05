package com.m3u.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * Quiz night: ten multiple-choice questions from the Open Trivia Database (free, no account), on
 * films, TV, music, sport or anything. Up and Down pick an answer, OK locks it in; a run of right
 * answers is worth more. The best score is kept like the other games'.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class QuizQuestion(val text: String, val answers: List<String>, val correct: Int, val difficulty: String)

private enum class QuizTopic(val category: Int?, val label: Int, val icon: ImageVector) {
    Mixed(null, R.string.dial_quiz_topic_mixed, Icons.Rounded.Shuffle),
    Film(11, R.string.dial_quiz_topic_film, Icons.Rounded.Movie),
    Tv(14, R.string.dial_quiz_topic_tv, Icons.Rounded.Tv),
    Music(12, R.string.dial_quiz_topic_music, Icons.Rounded.MusicNote),
    Sport(21, R.string.dial_quiz_topic_sport, Icons.Rounded.SportsSoccer),
    General(9, R.string.dial_quiz_topic_general, Icons.Rounded.Lightbulb),
}

object OpenTrivia {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Ten questions; empty when the service can't be reached (or asks for a pause: 5 s between sets). */
    suspend fun questions(category: Int?): List<QuizQuestion> = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://opentdb.com/api.php?amount=$QUESTIONS&type=multiple&encode=url3986")
            category?.let { append("&category=").append(it) }
        }
        val root = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode != 200) null
                else json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return@withContext emptyList()
        if (root["response_code"]?.jsonPrimitive?.intOrNull != 0) return@withContext emptyList()
        (root["results"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun text(name: String) = item[name]?.jsonPrimitive?.contentOrNull?.let { URLDecoder.decode(it, "UTF-8") }
            val question = text("question") ?: return@mapNotNull null
            val right = text("correct_answer") ?: return@mapNotNull null
            val wrong = (item["incorrect_answers"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.let { answer -> URLDecoder.decode(answer, "UTF-8") } }
            val answers = (wrong + right).shuffled()
            QuizQuestion(question, answers, answers.indexOf(right), text("difficulty").orEmpty())
        }
    }

    const val QUESTIONS = 10
}

@Composable
internal fun QuizGame(best: Int, onGameOver: (Int) -> Unit) {
    var topic by remember { mutableStateOf<QuizTopic?>(null) }
    var questions by remember { mutableStateOf<List<QuizQuestion>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var streak by remember { mutableIntStateOf(0) }
    var right by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(topic, round) {
        val chosen = topic ?: return@LaunchedEffect
        loading = true
        failed = false
        questions = try {
            OpenTrivia.questions(chosen.category)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        failed = questions.isEmpty()
        loading = false
        index = 0
        score = 0
        streak = 0
        right = 0
        picked = null
    }
    // After an answer: a moment to see it, then the next question (or the end).
    LaunchedEffect(picked) {
        if (picked == null) return@LaunchedEffect
        delay(ANSWER_PAUSE_MS)
        if (index + 1 >= questions.size) onGameOver(score)
        index += 1
        picked = null
    }

    val question = questions.getOrNull(index)
    val finished = topic != null && !loading && !failed && questions.isNotEmpty() && question == null
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 32.dp, end = 64.dp, bottom = 32.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Text(text = stringResource(R.string.dial_game_quiz), color = TvColors.Focus, fontFamily = TvFonts.Accent, fontSize = 28.sp)
            Text(text = stringResource(R.string.dial_quiz_score, score), color = TvColors.TextPrimary, fontFamily = TvFonts.Body, fontSize = 20.sp)
            Text(text = stringResource(R.string.dial_games_best, best), color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 20.sp)
        }
        when {
            topic == null -> TopicPicker(onPick = { topic = it })
            loading -> Note(stringResource(R.string.dial_quiz_loading))
            failed -> {
                Note(stringResource(R.string.dial_quiz_failed))
                QuizButton(text = stringResource(R.string.dial_quiz_retry), icon = Icons.Rounded.Refresh, focus = true, onClick = { round++ })
            }
            finished -> {
                Note(stringResource(R.string.dial_quiz_done, right, questions.size, score))
                QuizButton(text = stringResource(R.string.dial_quiz_again), icon = Icons.Rounded.Refresh, focus = true, onClick = { round++ })
                QuizButton(text = stringResource(R.string.dial_quiz_other_topic), icon = Icons.Rounded.Shuffle, focus = false, onClick = { topic = null })
            }
            question != null -> {
                Text(
                    text = stringResource(R.string.dial_quiz_progress, index + 1, questions.size, question.difficulty),
                    color = TvColors.TextMuted,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                )
                Text(
                    text = question.text,
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                    modifier = Modifier.widthIn(max = 980.dp),
                )
                AnswerList(
                    question = question,
                    key = index,
                    picked = picked,
                    onPick = { answer ->
                        if (picked != null) return@AnswerList
                        picked = answer
                        if (answer == question.correct) {
                            streak += 1
                            right += 1
                            score += POINTS + (streak - 1) * STREAK_BONUS
                        } else {
                            streak = 0
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TopicPicker(onPick: (QuizTopic) -> Unit) {
    Note(stringResource(R.string.dial_quiz_pick_topic))
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        QuizTopic.entries.forEach { topic ->
            TvActionButton(
                text = stringResource(topic.label),
                icon = topic.icon,
                onClick = { onPick(topic) },
                focusRequester = first.takeIf { topic == QuizTopic.Mixed },
            )
        }
    }
}

@Composable
private fun AnswerList(question: QuizQuestion, key: Int, picked: Int?, onPick: (Int) -> Unit) {
    val first = remember(key) { FocusRequester() }
    LaunchedEffect(key) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 820.dp)) {
        question.answers.forEachIndexed { answer, text ->
            val shown = picked != null
            val mark = when {
                shown && answer == question.correct -> " ✓"
                shown && answer == picked -> " ✗"
                else -> ""
            }
            FocusFrame(
                onClick = { onPick(answer) },
                focusRequester = first.takeIf { answer == 0 },
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.02f,
                semanticsLabel = text,
                modifier = Modifier.fillMaxWidth(),
            ) { focused ->
                Text(
                    text = "${'A' + answer}.  $text$mark",
                    color = when {
                        shown && answer == question.correct -> TvColors.Positive
                        shown && answer == picked -> TvColors.Danger
                        focused -> TvColors.OnFocus
                        else -> TvColors.TextPrimary
                    },
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun QuizButton(text: String, icon: ImageVector, focus: Boolean, onClick: () -> Unit) {
    val requester = remember { FocusRequester() }
    if (focus) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    TvActionButton(text = text, icon = icon, onClick = onClick, focusRequester = requester)
}

@Composable
private fun Note(text: String) {
    Text(text = text, color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 18.sp, modifier = Modifier.widthIn(max = 900.dp))
}

private const val ANSWER_PAUSE_MS = 1_400L
private const val POINTS = 10
private const val STREAK_BONUS = 5
