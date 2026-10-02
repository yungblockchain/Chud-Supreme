package com.m3u.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.m3u.data.database.model.AdjacentChannels
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.ChannelCategoryCount
import com.m3u.data.database.model.ChannelMetadataBase
import com.m3u.data.database.model.ExtensionChannelMetadataOverlay
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAllRaw(vararg channels: Channel): List<Long>

    @Transaction
    suspend fun insertOrReplace(channel: Channel): Long {
        val id = insertOrReplaceAllRaw(channel).single()
        synchronizeMetadataBases(listOf(channel))
        return id
    }

    @Transaction
    suspend fun insertOrReplaceAll(vararg channels: Channel) {
        insertOrReplaceAllRaw(*channels)
        synchronizeMetadataBases(channels.asList())
    }

    @Transaction
    suspend fun insertOrReplaceAllAndReturnIds(vararg channels: Channel): List<Long> {
        val ids = insertOrReplaceAllRaw(*channels)
        synchronizeMetadataBases(channels.asList())
        return ids
    }

    @Upsert
    suspend fun upsertMetadataBases(vararg bases: ChannelMetadataBase)

    @Upsert
    suspend fun upsertMetadataOverlays(vararg overlays: ExtensionChannelMetadataOverlay)

    @Query(
        """
        SELECT * FROM channel_metadata_bases
        WHERE playlist_url = :playlistUrl
        """
    )
    suspend fun getMetadataBases(playlistUrl: String): List<ChannelMetadataBase>

    @Query(
        """
        SELECT * FROM extension_channel_metadata_overlays
        WHERE playlist_url = :playlistUrl AND extension_id = :extensionId
        """
    )
    suspend fun getMetadataOverlays(
        playlistUrl: String,
        extensionId: String,
    ): List<ExtensionChannelMetadataOverlay>

    @Query(
        """
        SELECT * FROM extension_channel_metadata_overlays
        WHERE extension_id = :extensionId
        """
    )
    suspend fun getMetadataOverlays(
        extensionId: String,
    ): List<ExtensionChannelMetadataOverlay>

    @Query(
        """
        DELETE FROM extension_channel_metadata_overlays
        WHERE playlist_url = :playlistUrl AND extension_id = :extensionId
        """
    )
    suspend fun deleteMetadataOverlays(
        playlistUrl: String,
        extensionId: String,
    ): Int

    @Query(
        """
        DELETE FROM extension_channel_metadata_overlays
        WHERE extension_id = :extensionId
        """
    )
    suspend fun deleteMetadataOverlays(extensionId: String): Int

    @Query(
        """
        UPDATE streams
        SET title = COALESCE(
                (
                    SELECT overlay.title
                    FROM extension_channel_metadata_overlays AS overlay
                    WHERE overlay.playlist_url = :playlistUrl
                    AND overlay.channel_reference = :channelReference
                    AND overlay.title IS NOT NULL
                    ORDER BY overlay.extension_id ASC
                    LIMIT 1
                ),
                (
                    SELECT base.title
                    FROM channel_metadata_bases AS base
                    WHERE base.playlist_url = :playlistUrl
                    AND base.channel_reference = :channelReference
                ),
                title
            ),
            `group` = COALESCE(
                (
                    SELECT overlay.category
                    FROM extension_channel_metadata_overlays AS overlay
                    WHERE overlay.playlist_url = :playlistUrl
                    AND overlay.channel_reference = :channelReference
                    AND overlay.category IS NOT NULL
                    ORDER BY overlay.extension_id ASC
                    LIMIT 1
                ),
                (
                    SELECT base.category
                    FROM channel_metadata_bases AS base
                    WHERE base.playlist_url = :playlistUrl
                    AND base.channel_reference = :channelReference
                ),
                `group`
            )
        WHERE playlist_url = :playlistUrl
        AND relation_id = :channelReference
        """
    )
    suspend fun recomputeEffectiveMetadata(
        playlistUrl: String,
        channelReference: String,
    ): Int

    // No table alias on the DELETE target: Android 9 and 10 (Fire OS 7) ship SQLite 3.22, which
    // rejects "DELETE FROM table AS alias" as a syntax error, and that made every playlist
    // import fail there.
    @Query(
        """
        DELETE FROM channel_metadata_bases
        WHERE playlist_url = :playlistUrl
        AND channel_reference NOT IN (
            SELECT streams.relation_id
            FROM streams
            WHERE streams.playlist_url = :playlistUrl
            AND streams.relation_id IS NOT NULL
        )
        """
    )
    suspend fun deleteOrphanedMetadata(playlistUrl: String): Int

    @Transaction
    suspend fun synchronizeMetadataBases(channels: List<Channel>) {
        val bases = channels
            .asSequence()
            .mapNotNull { channel ->
                val reference = channel.relationId?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                ChannelMetadataBase(
                    playlistUrl = channel.playlistUrl,
                    channelReference = reference,
                    title = channel.title,
                    category = channel.category,
                )
            }
            .associateBy { base -> base.playlistUrl to base.channelReference }
            .values
            .toTypedArray()
        if (bases.isEmpty()) return
        upsertMetadataBases(*bases)
        // Only references an extension has an overlay for can end up different from the row
        // just written, so only those are recomputed. Recomputing every reference made big
        // imports quadratic: each UPDATE scans the playlist's rows, so 200k entries never
        // finished on a Fire TV.
        bases.groupBy { it.playlistUrl }.forEach { (playlistUrl, playlistBases) ->
            val overlaid = getOverlayReferences(playlistUrl).toHashSet()
            if (overlaid.isEmpty()) return@forEach
            playlistBases
                .filter { base -> base.channelReference in overlaid }
                .forEach { base -> recomputeEffectiveMetadata(base.playlistUrl, base.channelReference) }
        }
    }

    @Query(
        """
        SELECT DISTINCT channel_reference FROM extension_channel_metadata_overlays
        WHERE playlist_url = :playlistUrl
        """
    )
    suspend fun getOverlayReferences(playlistUrl: String): List<String>

    @Query(
        """
            SELECT DISTINCT `group`
            FROM streams
            WHERE playlist_url = :playlistUrl
            AND title LIKE '%'||:query||'%'
        """
    )
    suspend fun getCategoriesByPlaylistUrl(
        playlistUrl: String,
        query: String
    ): List<String>

    @Query(
        """
            SELECT DISTINCT `group`
            FROM streams
            WHERE playlist_url = :playlistUrl
            AND title LIKE '%'||:query||'%'
        """
    )
    fun observeCategoriesByPlaylistUrl(
        playlistUrl: String,
        query: String
    ): Flow<List<String>>

    @Delete
    suspend fun delete(channel: Channel)

    @Query("DELETE FROM streams WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM streams WHERE playlist_url = :playlistUrl")
    suspend fun deleteByPlaylistUrl(playlistUrl: String)

    @Query("DELETE FROM streams WHERE playlist_url = :playlistUrl AND (favourite = 0 AND hidden = 0)")
    suspend fun deleteByPlaylistUrlIgnoreFavOrHidden(playlistUrl: String)

    @Query("SELECT relation_id FROM streams WHERE relation_id IS NOT NULL AND playlist_url IN (:playlistUrls) AND (favourite = 1 OR hidden = 1)")
    suspend fun getFavOrHiddenRelationIdsByPlaylistUrl(vararg playlistUrls: String): List<String>

    @Query("SELECT url FROM streams WHERE relation_id IS NULL AND playlist_url IN (:playlistUrls) AND (favourite = 1 OR hidden = 1)")
    suspend fun getFavOrHiddenUrlsByPlaylistUrlNotContainsRelationId(vararg playlistUrls: String): List<String>

    @Query("SELECT * FROM streams WHERE seen != 0 ORDER BY seen DESC LIMIT 1")
    suspend fun getPlayedRecently(): Channel?

    @Query("SELECT * FROM streams WHERE seen != 0 ORDER BY seen DESC LIMIT 1")
    fun observePlayedRecently(): Flow<Channel?>

    @Query("SELECT * FROM streams WHERE seen != 0 ORDER BY seen DESC LIMIT :limit")
    suspend fun getPlayedRecently(limit: Int): List<Channel>

    @Query("SELECT * FROM streams WHERE id = :id")
    suspend fun get(id: Int): Channel?

    @Query(
        """
        SELECT * FROM streams
        WHERE hidden = 0
        AND playlist_url NOT IN (:seriesPlaylistUrls)
        ORDER BY RANDOM()
        LIMIT 1
    """
    )
    suspend fun randomIgnoreSeriesAndHidden(vararg seriesPlaylistUrls: String): Channel?

    @Query(
        """
        SELECT * FROM streams
        WHERE favourite = 1
        AND playlist_url NOT IN (:seriesPlaylistUrls)
        ORDER BY RANDOM()
        LIMIT 1
    """
    )
    suspend fun randomIgnoreSeriesInFavorite(vararg seriesPlaylistUrls: String): Channel?

    @Query("SELECT * FROM streams WHERE url = :url AND playlist_url = :playlistUrl")
    suspend fun getByPlaylistUrlAndUrl(playlistUrl: String, url: String): Channel?

    @Query("SELECT * FROM streams WHERE title = :title AND playlist_url = :playlistUrl")
    suspend fun getByPlaylistUrlAndTitle(playlistUrl: String, title: String): Channel?

    @Query("SELECT * FROM streams WHERE playlist_url = :playlistUrl")
    suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel>

    // Category-at-a-time reads, so a provider with 50k channels or 130k films never has to be
    // held in memory all at once.
    @Query(
        """
        SELECT `group` AS name, COUNT(*) AS count, MIN(id) AS first_id
        FROM streams
        WHERE playlist_url = :playlistUrl AND hidden = 0
        GROUP BY `group`
        ORDER BY first_id
        """
    )
    suspend fun getCategoryCounts(playlistUrl: String): List<ChannelCategoryCount>

    @Query("SELECT * FROM streams WHERE playlist_url = :playlistUrl AND hidden = 0 ORDER BY id")
    suspend fun getUnhiddenInProviderOrder(playlistUrl: String): List<Channel>

    @Query(
        "SELECT * FROM streams WHERE playlist_url = :playlistUrl AND hidden = 0 " +
            "ORDER BY title COLLATE NOCASE, id"
    )
    suspend fun getUnhiddenByTitle(playlistUrl: String): List<Channel>

    @Query(
        "SELECT * FROM streams WHERE playlist_url = :playlistUrl AND hidden = 0 " +
            "AND `group` = :category ORDER BY id"
    )
    suspend fun getUnhiddenInCategoryInProviderOrder(
        playlistUrl: String,
        category: String,
    ): List<Channel>

    @Query(
        "SELECT * FROM streams WHERE playlist_url = :playlistUrl AND hidden = 0 " +
            "AND `group` = :category ORDER BY title COLLATE NOCASE, id"
    )
    suspend fun getUnhiddenInCategoryByTitle(
        playlistUrl: String,
        category: String,
    ): List<Channel>

    /** Titles containing [query], the ones that start with it first, at most [limit]. */
    @Query(
        """
        SELECT * FROM streams
        WHERE hidden = 0 AND title LIKE '%' || :query || '%'
        ORDER BY CASE WHEN title LIKE :query || '%' THEN 0 ELSE 1 END, length(title), id
        LIMIT :limit
        """
    )
    suspend fun searchUnhidden(query: String, limit: Int): List<Channel>

    @Query(
        """
        SELECT stream.url AS url,
            COALESCE(base.category, stream.`group`) AS `group`,
            COALESCE(base.title, stream.title) AS title,
            stream.cover AS cover,
            stream.playlist_url AS playlist_url,
            stream.license_type AS license_type,
            stream.license_key AS license_key,
            stream.id AS id,
            stream.favourite AS favourite,
            stream.hidden AS hidden,
            stream.seen AS seen,
            stream.relation_id AS relation_id
        FROM streams AS stream
        LEFT JOIN channel_metadata_bases AS base
            ON base.playlist_url = stream.playlist_url
            AND base.channel_reference = stream.relation_id
        """
    )
    suspend fun getAllWithSourceMetadata(): List<Channel>

    @Query("SELECT * FROM streams WHERE playlist_url = :playlistUrl AND relation_id = :relationId")
    suspend fun getByPlaylistUrlAndRelationId(playlistUrl: String, relationId: String): Channel?

    @Query("SELECT * FROM streams WHERE relation_id IN (:relationIds) AND hidden = 0")
    suspend fun getByRelationIds(relationIds: List<String>): List<Channel>

    @Query("SELECT * FROM streams WHERE id = :id")
    fun observeById(id: Int): Flow<Channel?>

    @Query("SELECT * FROM streams WHERE playlist_url = :playlistUrl")
    fun observeAllByPlaylistUrl(playlistUrl: String): Flow<List<Channel>>

    @Query("SELECT DISTINCT relation_id FROM streams WHERE playlist_url = :playlistUrl AND relation_id IS NOT NULL")
    fun observeRelationIdsByPlaylistUrl(playlistUrl: String): Flow<List<String>>

    @Query("SELECT * FROM streams WHERE hidden = 0")
    fun observeAllUnhidden(): Flow<List<Channel>>

    @Query("SELECT * FROM streams WHERE favourite = 1")
    fun observeAllFavorite(): Flow<List<Channel>>

    @Query("SELECT * FROM streams WHERE hidden = 1")
    fun observeAllHidden(): Flow<List<Channel>>

    @Query(
        """
            SELECT * FROM streams 
            WHERE playlist_url = :url
            AND title LIKE '%'||:query||'%'
            AND `group` = :category
        """
    )
    fun pagingAllByPlaylistUrl(
        url: String,
        category: String,
        query: String
    ): PagingSource<Int, Channel>

    @Query(
        """
            SELECT * FROM streams 
            WHERE playlist_url = :url
            AND title LIKE '%'||:query||'%'
            AND `group` = :category
            ORDER BY title ASC
        """
    )
    fun pagingAllByPlaylistUrlAsc(
        url: String,
        category: String,
        query: String
    ): PagingSource<Int, Channel>

    @Query(
        """
            SELECT * FROM streams 
            WHERE playlist_url = :url
            AND title LIKE '%'||:query||'%'
            AND `group` = :category
            ORDER BY title DESC
        """
    )
    fun pagingAllByPlaylistUrlDesc(
        url: String,
        category: String,
        query: String
    ): PagingSource<Int, Channel>

    @Query(
        """
            SELECT * FROM streams 
            WHERE playlist_url = :url
            AND title LIKE '%'||:query||'%'
            AND `group` = :category
            ORDER BY seen DESC
        """
    )
    fun pagingAllByPlaylistUrlRecently(
        url: String,
        category: String,
        query: String
    ): PagingSource<Int, Channel>

    @Query(
        """
            SELECT * FROM streams 
            WHERE playlist_url = :url
            AND title LIKE '%'||:query||'%'
        """
    )
    fun pagingAllByPlaylistUrlMixed(
        url: String,
        query: String
    ): PagingSource<Int, Channel>

    @Query("SELECT COUNT(playlist_url) FROM streams WHERE playlist_url = :playlistUrl")
    fun observeCountByPlaylistUrl(playlistUrl: String): Flow<Int>

    @Query("SELECT COUNT(playlist_url) FROM streams WHERE playlist_url = :playlistUrl")
    suspend fun getCountByPlaylistUrl(playlistUrl: String): Int

    @Query("SELECT * FROM streams WHERE favourite = 1 AND seen + :limit < :current ORDER BY seen")
    fun observeAllUnseenFavorites(
        limit: Long,
        current: Long
    ): Flow<List<Channel>>

    @Query("UPDATE streams SET favourite = :target WHERE id = :id")
    suspend fun favouriteOrUnfavourite(id: Int, target: Boolean)

    @Query("UPDATE streams SET hidden = :target WHERE id = :id")
    suspend fun hide(id: Int, target: Boolean)

    @Query("UPDATE streams SET seen = :target WHERE id = :id")
    suspend fun updateSeen(id: Int, target: Long)

    @Query(
        """
            WITH TargetChannel AS (
                SELECT *
                FROM streams
                WHERE id = :channelId
                AND playlist_url = :playlistUrl
                AND `group` = :category
            )
            SELECT
                (SELECT id FROM streams
                 WHERE playlist_url = :playlistUrl
                   AND `group` = :category
                   AND title < (SELECT title FROM TargetChannel)
                   ORDER BY title DESC LIMIT 1) AS next_id,
                (SELECT id FROM streams
                 WHERE playlist_url = :playlistUrl
                   AND `group` = :category
                   AND title > (SELECT title FROM TargetChannel)
                   ORDER BY title ASC LIMIT 1) AS prev_id
        """
    )
    fun observeAdjacentChannels(
        channelId: Int,
        playlistUrl: String,
        category: String,
    ): Flow<AdjacentChannels>


    @Query(
        """
            SELECT * FROM streams WHERE 1
            AND title LIKE '%'||:query||'%'
        """
    )
    fun query(
        query: String
    ): PagingSource<Int, Channel>

    @Query(
        """
            SELECT * FROM streams WHERE 1
            AND favourite = 1
        """
    )
    fun pagingAllFavorite(): PagingSource<Int, Channel>
    @Query(
        """
            SELECT * FROM streams WHERE 1
            AND favourite = 1
            ORDER BY title ASC
            
        """
    )
    fun pagingAllFavoriteAsc(): PagingSource<Int, Channel>
    @Query(
        """
            SELECT * FROM streams WHERE 1
            AND favourite = 1
            ORDER BY title DESC
        """
    )
    fun pagingAllFavoriteDesc(): PagingSource<Int, Channel>
    @Query(
        """
            SELECT * FROM streams WHERE 1
            AND favourite = 1
            ORDER BY seen DESC
        """
    )
    fun pagingAllFavoriteRecently(): PagingSource<Int, Channel>
    @Query(
        """
            SELECT * FROM streams WHERE 1
            AND title LIKE '%'||:query||'%'
        """
    )
    fun pagingAll(query: String): PagingSource<Int, Channel>
}
