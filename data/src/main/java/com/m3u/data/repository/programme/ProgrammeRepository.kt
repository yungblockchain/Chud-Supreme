package com.m3u.data.repository.programme

import androidx.paging.PagingData
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Programme
import com.m3u.data.database.model.ProgrammeRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ProgrammeRepository {
    fun pagingProgrammes(
        playlistUrl: String,
        relationId: String
    ): Flow<PagingData<Programme>>

    fun observeProgrammeRange(
        playlistUrl: String,
        relationId: String
    ): Flow<ProgrammeRange>

    fun observeProgrammeRange(
        playlistUrl: String
    ): Flow<ProgrammeRange>

    val refreshingEpgUrls: StateFlow<List<String>>
    fun checkOrRefreshProgrammesOrThrow(
        vararg playlistUrls: String,
        ignoreCache: Boolean
    ): Flow<Int>

    suspend fun getById(id: Int): Programme?
    suspend fun getProgrammeCurrently(channelId: Int): Programme?
    suspend fun getProgrammesCurrently(playlistUrl: String): Map<String, Programme>

    /**
     * Programmes from the playlist's EPG (XMLTV) for one channel ([relationId], its tvg-id)
     * overlapping the time range [from, to) in epoch milliseconds.
     */
    /**
     * Programmes on the live playlists' guides whose title contains [query], airing between
     * [from] and [to], each with the (visible) channel that shows it; soonest first.
     */
    suspend fun searchAirings(query: String, from: Long, to: Long, limit: Int): List<Pair<Channel, Programme>>

    suspend fun getProgrammesInRange(
        playlistUrl: String,
        relationId: String,
        from: Long,
        to: Long,
    ): List<Programme>
}
