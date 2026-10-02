package com.m3u.data.database.model

import androidx.room.ColumnInfo

/**
 * One category (channel group) of a playlist with how many visible entries it holds.
 * [firstId] is the lowest channel id in it, which keeps categories in the provider's order.
 */
data class ChannelCategoryCount(
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("count")
    val count: Int,
    @ColumnInfo("first_id")
    val firstId: Int,
)
