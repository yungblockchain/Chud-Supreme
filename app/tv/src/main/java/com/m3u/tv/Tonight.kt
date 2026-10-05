package com.m3u.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.m3u.data.database.model.Channel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/* -------------------------------------------------------------------------------------------------
 * "On tonight": what the guide says is coming up this evening on favourite channels, as a row on
 * Home. OK on a programme that's on plays the channel; on one still to come it sets a reminder,
 * and when the time comes a card offers to switch over. Reminders are kept on the stick and
 * forgotten once the programme has ended.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class TonightProgramme(val channel: Channel, val programme: GuideProgramme)

@Immutable
data class Reminder(
    val channelId: Int,
    val channelTitle: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    val key: String get() = reminderKey(channelId, startMs)
}

fun reminderKey(channelId: Int, startMs: Long): String = "$channelId@$startMs"

@Singleton
class ReminderStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val _reminders = MutableStateFlow(read().purged())
    /** Reminders still to come, soonest first. */
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    fun isSet(channelId: Int, startMs: Long): Boolean = _reminders.value.any { it.key == reminderKey(channelId, startMs) }

    /** Adds the reminder, or removes it when it was already set; returns true when now set. */
    fun toggle(channel: Channel, programme: GuideProgramme): Boolean {
        val key = reminderKey(channel.id, programme.startMillis)
        val current = _reminders.value.purged()
        val next = if (current.any { it.key == key }) {
            current.filterNot { it.key == key }
        } else {
            (current + Reminder(channel.id, channel.title, programme.title, programme.startMillis, programme.endMillis)).take(MAX)
        }
        save(next)
        return next.any { it.key == key }
    }

    fun remove(reminder: Reminder) = save(_reminders.value.filterNot { it.key == reminder.key })

    /** Drops reminders for programmes that have ended. */
    fun purge() {
        val next = _reminders.value.purged()
        if (next.size != _reminders.value.size) save(next)
    }

    fun reload() {
        _reminders.value = read().purged()
    }

    private fun save(reminders: List<Reminder>) {
        val sorted = reminders.sortedBy { it.startMs }
        _reminders.value = sorted
        prefs.edit().putString(KEY, JsonArray(sorted.map { it.toJson() }).toString()).apply()
    }

    private fun List<Reminder>.purged(): List<Reminder> {
        val now = System.currentTimeMillis()
        return filter { it.endMs > now }.sortedBy { it.startMs }
    }

    private fun read(): List<Reminder> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            Reminder(
                channelId = item["channel"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                channelTitle = item["channelTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                title = item["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                startMs = item["start"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                endMs = item["end"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
            )
        }
    }.getOrDefault(emptyList())

    private fun Reminder.toJson() = JsonObject(
        mapOf(
            "channel" to JsonPrimitive(channelId),
            "channelTitle" to JsonPrimitive(channelTitle),
            "title" to JsonPrimitive(title),
            "start" to JsonPrimitive(startMs),
            "end" to JsonPrimitive(endMs),
        )
    )

    private companion object {
        const val KEY = "reminders"
        const val MAX = 40
    }
}

/** The evening window the row covers: from now (or 6 pm, if earlier) to the end of the day. */
fun tonightWindow(now: Long = System.currentTimeMillis()): LongRange {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    val sixPm = (calendar.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val endOfDay = (calendar.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    // Late in the evening the row looks a little past midnight, so it never runs empty at 11 pm.
    val end = if (now >= endOfDay - 2 * 60 * 60_000L) endOfDay + 3 * 60 * 60_000L else endOfDay
    return maxOf(now, sixPm)..end
}

@Composable
fun TonightRow(
    items: List<TonightProgramme>,
    reminderKeys: Set<String>,
    onOpen: (TonightProgramme) -> Unit,
) {
    val now = System.currentTimeMillis()
    val shown = items.filter { !it.programme.hasEndedBy(now) }
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            title = stringResource(R.string.dial_tonight_title),
            subtitle = stringResource(R.string.dial_tonight_subtitle),
            modifier = Modifier.padding(start = 48.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            modifier = Modifier.focusGroup(),
        ) {
            items(shown, key = { "${it.channel.id}-${it.programme.startMillis}" }) { item ->
                val onNow = item.programme.isOnAt(now)
                val reminded = reminderKey(item.channel.id, item.programme.startMillis) in reminderKeys
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.programme.startMillis))
                FocusFrame(
                    onClick = { onOpen(item) },
                    shape = RoundedCornerShape(12.dp),
                    semanticsLabel = "${item.programme.title}, $time, ${item.channel.title}",
                    focusedScale = 1.04f,
                    modifier = Modifier
                        .width(300.dp)
                        .height(112.dp),
                ) { focused ->
                    val primary = if (focused) TvColors.OnFocus else TvColors.TextPrimary
                    val secondary = if (focused) TvColors.OnFocus.copy(alpha = 0.75f) else TvColors.TextSecondary
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TvColors.Surface),
                        ) {
                            AsyncImage(
                                model = item.channel.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(64.dp)
                                    .padding(6.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    onNow -> stringResource(R.string.dial_guide_now)
                                    reminded -> stringResource(R.string.dial_tonight_reminded, time)
                                    else -> time
                                },
                                color = if (onNow || reminded) (if (focused) TvColors.OnFocus else TvColors.Focus) else secondary,
                                fontFamily = TvFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                            Text(
                                text = item.programme.title,
                                color = primary,
                                fontFamily = TvFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.channel.title,
                                color = secondary,
                                fontFamily = TvFonts.Body,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (reminded && !onNow) {
                            Icon(
                                imageVector = Icons.Rounded.Notifications,
                                contentDescription = null,
                                tint = if (focused) TvColors.OnFocus else TvColors.Focus,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The card that pops up when a reminded programme is about to start. */
@Composable
fun ReminderCard(
    reminder: Reminder,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val watchFocus = remember { FocusRequester() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(reminder.key) {
        repeat(5) {
            yield()
            if (runCatching { watchFocus.requestFocus() }.isSuccess) return@repeat
            delay(120)
        }
        delay(REMINDER_CARD_MS)
        currentOnDismiss()
    }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(reminder.startMs))
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .width(460.dp)
            .background(TvColors.Background.copy(alpha = 0.96f), RoundedCornerShape(16.dp))
            .padding(24.dp)
            .focusGroup(),
    ) {
        Text(
            text = stringResource(R.string.dial_reminder_title, time),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
        )
        Text(
            text = reminder.title,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = reminder.channelTitle,
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                text = stringResource(R.string.dial_reminder_watch),
                icon = Icons.Rounded.PlayArrow,
                onClick = onWatch,
                focusRequester = watchFocus,
            )
            TvActionButton(
                text = stringResource(R.string.dial_reminder_dismiss),
                icon = Icons.Rounded.Close,
                onClick = onDismiss,
            )
        }
    }
}

private const val REMINDER_CARD_MS = 90_000L
