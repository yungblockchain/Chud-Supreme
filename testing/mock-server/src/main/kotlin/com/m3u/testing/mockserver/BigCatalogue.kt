package com.m3u.testing.mockserver

import java.io.Writer

/*
 * A second Xtream account ("big" / "big") the size of a large real provider: 50k live channels,
 * 130k films and 20k series across a few hundred categories. Lists are written straight to the
 * response as they're generated, so the server never holds them in memory. A few entries are
 * deliberately malformed, as real panels' are, and the film list starts after a delay longer
 * than OkHttp's default 10-second read timeout.
 */

internal const val BIG_USERNAME = "big"
internal const val BIG_PASSWORD = "big"

internal object BigCatalogue {
    const val LIVE = 50_000
    const val VOD = 130_000
    const val SERIES = 20_000
    const val LIVE_CATEGORIES = 60
    const val VOD_CATEGORIES = 80
    const val SERIES_CATEGORIES = 40
    const val LIVE_FIRST_STREAM_ID = 100_000
    const val VOD_FIRST_STREAM_ID = 500_000
    const val SERIES_FIRST_ID = 900_000
    const val VOD_FIRST_BYTE_DELAY_MS = 12_000L

    /** Every this-many films has an empty stream id, which a strict decoder rejects. */
    const val MALFORMED_EVERY = 10_000

    private const val LIVE_CATEGORY_FIRST_ID = 1_000
    private const val VOD_CATEGORY_FIRST_ID = 2_000
    private const val SERIES_CATEGORY_FIRST_ID = 3_000

    fun liveCategories(): String =
        categories(LIVE_CATEGORY_FIRST_ID, LIVE_CATEGORIES) { "Live ${it + 1}" }

    fun vodCategories(): String =
        categories(VOD_CATEGORY_FIRST_ID, VOD_CATEGORIES) { "Films ${it + 1}" }

    fun seriesCategories(): String =
        categories(SERIES_CATEGORY_FIRST_ID, SERIES_CATEGORIES) { "Series ${it + 1}" }

    fun isBigLiveStream(streamId: Int?): Boolean =
        streamId != null && streamId >= LIVE_FIRST_STREAM_ID && streamId < LIVE_FIRST_STREAM_ID + LIVE

    fun writeLive(writer: Writer, baseUrl: String) {
        writer.write("[")
        for (index in 0 until LIVE) {
            if (index > 0) writer.write(",")
            val category = LIVE_CATEGORY_FIRST_ID + index % LIVE_CATEGORIES
            val id = LIVE_FIRST_STREAM_ID + index
            writer.write(
                """{"num":${index + 1},"name":"Channel ${index + 1}","stream_type":"live",""" +
                    """"stream_id":$id,"stream_icon":"$baseUrl/images/ch-${index % 40}.png",""" +
                    """"epg_channel_id":"big.$id","category_id":"$category","tv_archive":1,""" +
                    """"tv_archive_duration":3}"""
            )
        }
        writer.write("]")
    }

    fun writeVod(writer: Writer, baseUrl: String) {
        writer.write("[")
        for (index in 0 until VOD) {
            if (index > 0) writer.write(",")
            val category = VOD_CATEGORY_FIRST_ID + index % VOD_CATEGORIES
            val id = if (index > 0 && index % MALFORMED_EVERY == 0) {
                "\"\""
            } else {
                (VOD_FIRST_STREAM_ID + index).toString()
            }
            val year = 1970 + index % 55
            writer.write(
                """{"num":${index + 1},"name":"Film ${index + 1} ($year)","stream_type":"movie",""" +
                    """"stream_id":$id,"stream_icon":"$baseUrl/images/film-${index % 50}.png",""" +
                    """"category_id":"$category","container_extension":"mp4","rating":"7.1"}"""
            )
        }
        writer.write("]")
    }

    fun writeSeries(writer: Writer, baseUrl: String) {
        writer.write("[")
        for (index in 0 until SERIES) {
            if (index > 0) writer.write(",")
            val category = SERIES_CATEGORY_FIRST_ID + index % SERIES_CATEGORIES
            writer.write(
                """{"num":${index + 1},"name":"Show ${index + 1}","series_id":${SERIES_FIRST_ID + index},""" +
                    """"cover":"$baseUrl/images/series-${index % 30}.png","category_id":"$category"}"""
            )
        }
        writer.write("]")
    }

    private fun categories(firstId: Int, count: Int, name: (Int) -> String): String =
        (0 until count).joinToString(prefix = "[", postfix = "]", separator = ",") { index ->
            """{"category_id":"${firstId + index}","category_name":"${name(index)}","parent_id":0}"""
        }
}
