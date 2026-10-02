package dev.oxyroid.parser.xtream

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject

class XtreamParserImpl @Inject constructor(
    okHttpClient: OkHttpClient,
) : XtreamParser {

    @OptIn(ExperimentalSerializationApi::class)
    private val json: Json
        get() = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
        }

    private val utils by lazy {
        XtreamParserUtils(
            json = json,
            okHttpClient = okHttpClient,
        )
    }

    override suspend fun getInfo(input: XtreamInput): XtreamInfo {
        val (basicUrl, username, password, _) = input
        val infoUrl = XtreamParser.createInfoUrl(basicUrl, username, password)
        return checkNotNull(utils.newCall<XtreamInfo>(infoUrl))
    }

    override fun parse(input: XtreamInput): Sequence<XtreamData> = sequence {
        val (basicUrl, username, password, type) = input
        val requiredLives = type == null || type == XtreamInput.TYPE_LIVE
        val requiredVods = type == null || type == XtreamInput.TYPE_VOD
        val requiredSeries = type == null || type == XtreamInput.TYPE_SERIES
        if (requiredLives) {
            val liveStreamsUrl = XtreamParser.createActionUrl(
                basicUrl,
                username,
                password,
                XtreamParser.Action.GET_LIVE_STREAMS,
            )
            yieldAll(utils.newSequenceCall<XtreamLive>(liveStreamsUrl))
        }
        if (requiredVods) {
            val vodStreamsUrl = XtreamParser.createActionUrl(
                basicUrl,
                username,
                password,
                XtreamParser.Action.GET_VOD_STREAMS,
            )
            yieldAll(utils.newSequenceCall<XtreamVod>(vodStreamsUrl))
        }
        if (requiredSeries) {
            val seriesStreamsUrl = XtreamParser.createActionUrl(
                basicUrl,
                username,
                password,
                XtreamParser.Action.GET_SERIES_STREAMS,
            )
            yieldAll(utils.newSequenceCall<XtreamSerial>(seriesStreamsUrl))
        }
    }

    override suspend fun getXtreamOutput(input: XtreamInput): XtreamOutput {
        val (basicUrl, username, password, type) = input
        val requiredLives = type == null || type == XtreamInput.TYPE_LIVE
        val requiredVods = type == null || type == XtreamInput.TYPE_VOD
        val requiredSeries = type == null || type == XtreamInput.TYPE_SERIES
        val infoUrl = XtreamParser.createInfoUrl(basicUrl, username, password)
        val liveCategoriesUrl = XtreamParser.createActionUrl(
            basicUrl,
            username,
            password,
            XtreamParser.Action.GET_LIVE_CATEGORIES,
        )
        val vodCategoriesUrl = XtreamParser.createActionUrl(
            basicUrl,
            username,
            password,
            XtreamParser.Action.GET_VOD_CATEGORIES,
        )
        val serialCategoriesUrl = XtreamParser.createActionUrl(
            basicUrl,
            username,
            password,
            XtreamParser.Action.GET_SERIES_CATEGORIES,
        )
        val info: XtreamInfo = utils.newCall(infoUrl) ?: return XtreamOutput()
        val allowedOutputFormats = info.userInfo.allowedOutputFormats
        val serverProtocol = info.serverInfo.serverProtocol ?: "http"
        val port = info.serverInfo.port?.toIntOrNull()
        val httpsPort = info.serverInfo.httpsPort?.toIntOrNull()

        val liveCategories: List<XtreamCategory> =
            if (requiredLives) utils.newCall(liveCategoriesUrl) ?: emptyList() else emptyList()
        val vodCategories: List<XtreamCategory> =
            if (requiredVods) utils.newCall(vodCategoriesUrl) ?: emptyList() else emptyList()
        val serialCategories: List<XtreamCategory> =
            if (requiredSeries) utils.newCall(serialCategoriesUrl) ?: emptyList() else emptyList()

        return XtreamOutput(
            liveCategories = liveCategories,
            vodCategories = vodCategories,
            serialCategories = serialCategories,
            allowedOutputFormats = allowedOutputFormats,
            serverProtocol = serverProtocol,
            port = if (serverProtocol == "http") port else httpsPort,
        )
    }

    override suspend fun getSeriesInfoOrThrow(
        input: XtreamInput,
        seriesId: Int,
    ): XtreamChannelInfo {
        val (basicUrl, username, password, type) = input
        check(type == XtreamInput.TYPE_SERIES) { "xtream input type must be `series`" }
        return utils.newCallOrThrow(
            XtreamParser.createActionUrl(
                basicUrl,
                username,
                password,
                XtreamParser.Action.GET_SERIES_INFO,
                XtreamParser.GET_SERIES_INFO_PARAM_ID to seriesId,
            ),
        )
    }
}
