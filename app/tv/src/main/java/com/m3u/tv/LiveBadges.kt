package com.m3u.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.model.Channel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* -------------------------------------------------------------------------------------------------
 * Live-match badges: while a match is on, channel cards whose names carry both teams (event and
 * PPV channels usually do: "Arsenal vs Chelsea", "EPL: Liverpool v Everton") get the score in
 * the corner. The scores come from API-Sports' live feed, read every couple of minutes while a
 * live list is on screen.
 * ---------------------------------------------------------------------------------------------- */

@HiltViewModel
class LiveBadgesViewModel @Inject constructor(
    private val secrets: SecretStore,
) : ViewModel() {
    private val _matches = MutableStateFlow<List<LiveMatch>>(emptyList())
    /** Matches on right now, with their scores. */
    val matches: StateFlow<List<LiveMatch>> = _matches.asStateFlow()
    private var job: Job? = null

    data class LiveMatch(val home: String, val away: String, val score: String, val minute: String)

    /** Keeps the scores fresh while [on]; stops when it isn't. */
    fun watch(on: Boolean) {
        if (!on) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            val key = secrets.get(SecretName.ApiSports) ?: return@launch
            while (true) {
                val rows = runCatching { ApiSports.live(key) }.getOrDefault(emptyList())
                _matches.value = rows.mapNotNull { row ->
                    val home = row.homeScore ?: return@mapNotNull null
                    val away = row.awayScore ?: return@mapNotNull null
                    LiveMatch(row.home, row.away, "$home–$away", row.detail)
                }
                delay(REFRESH_MS)
            }
        }
    }

    /** Badge text for each of [channels] that names a live match's teams. */
    fun badgesFor(channels: List<Channel>): Map<Int, String> {
        val live = _matches.value
        if (live.isEmpty() || channels.isEmpty()) return emptyMap()
        val prepared = live.map { Triple(it, it.home.teamWords(), it.away.teamWords()) }
        val result = HashMap<Int, String>()
        for (channel in channels) {
            val title = channel.title.lowercase(Locale.ROOT)
            val hit = prepared.firstOrNull { (_, home, away) ->
                home.isNotEmpty() && away.isNotEmpty() && home.any { title.contains(it) } && away.any { title.contains(it) }
            } ?: continue
            result[channel.id] = "LIVE ${hit.first.score}"
        }
        return result
    }

    /** The distinctive words of a team name ("Manchester United" → manchester, united; "FC" and the like dropped). */
    private fun String.teamWords(): List<String> =
        lowercase(Locale.ROOT).split(' ', '-', '.').map { it.trim() }
            .filter { it.length >= 4 && it !in STOP_WORDS }

    private companion object {
        const val REFRESH_MS = 120_000L
        val STOP_WORDS = setOf("united", "city", "town", "club", "athletic", "real", "sporting", "football", "association", "rovers", "wanderers")
    }
}
