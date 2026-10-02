package com.m3u.data.repository.channel

import androidx.paging.PagingData
import androidx.paging.PagingSource
import com.m3u.core.foundation.wrapper.Sort
import com.m3u.data.database.model.AdjacentChannels
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.ChannelCategoryCount
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

interface ChannelRepository {
    fun observe(id: Int): Flow<Channel?>

    fun observeAllByPlaylistUrl(playlistUrl: String): Flow<List<Channel>>
    fun observeRelationIdsByPlaylistUrl(playlistUrl: String): Flow<List<String>>
    fun pagingAll(query: String): PagingSource<Int, Channel>
    fun pagingAllByPlaylistUrl(
        url: String,
        category: String,
        query: String,
        sort: Sort
    ): PagingSource<Int, Channel>

    suspend fun get(id: Int): Channel?
    fun observeAdjacentChannels(
        channelId: Int,
        playlistUrl: String,
        category: String,
    ): Flow<AdjacentChannels>

    suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel>

    /** Categories of a playlist with their visible entry counts, in the provider's order. */
    suspend fun getCategoryCounts(playlistUrl: String): List<ChannelCategoryCount>

    /**
     * Visible entries of a playlist, or of one [category] of it (null for all), in the provider's
     * order or alphabetically by title.
     */
    suspend fun getUnhidden(
        playlistUrl: String,
        category: String?,
        byTitle: Boolean,
    ): List<Channel>

    /** Visible entries across all playlists whose title contains [query]. */
    suspend fun searchUnhidden(query: String, limit: Int): List<Channel>
    suspend fun getByRelationIds(relationIds: List<String>): List<Channel>
    suspend fun favouriteOrUnfavourite(id: Int)
    suspend fun hide(id: Int, target: Boolean)
    suspend fun reportPlayed(id: Int)
    suspend fun getPlayedRecently(): Channel?

    /** The last [limit] channels, films or episodes played, most recent first. */
    suspend fun getPlayedRecently(limit: Int): List<Channel>
    fun observePlayedRecently(): Flow<Channel?>
    fun observeAllUnseenFavorites(limit: Duration): Flow<List<Channel>>
    fun observeAllFavorite(): Flow<List<Channel>>
    fun pagingAllFavorite(sort: Sort): PagingSource<Int, Channel>
    fun observeAllHidden(): Flow<List<Channel>>
    fun search(query: String): PagingSource<Int, Channel>
}
