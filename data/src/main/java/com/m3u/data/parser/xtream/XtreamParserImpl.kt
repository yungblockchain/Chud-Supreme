package com.m3u.data.parser.xtream

import com.m3u.data.api.OkhttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal class XtreamParserImpl @Inject constructor(
	@OkhttpClient(true) okHttpClient: OkHttpClient,
) : XtreamParser {
	// Big providers take a while to build their full catalogue (130k films can be a 50 MB JSON
	// answer that starts after several seconds of silence), so catalogue calls get far longer
	// timeouts than OkHttp's 10-second defaults.
	private val catalogueClient = okHttpClient.newBuilder()
		.connectTimeout(30, TimeUnit.SECONDS)
		.readTimeout(3, TimeUnit.MINUTES)
		.callTimeout(0, TimeUnit.SECONDS)
		.build()
	private val delegate = dev.oxyroid.parser.xtream.XtreamParserImpl(catalogueClient)

	override suspend fun getSeriesInfoOrThrow(
		input: XtreamInput,
		seriesId: Int,
	): XtreamChannelInfo = delegate.getSeriesInfoOrThrow(input, seriesId)

	override fun parse(input: XtreamInput): Flow<XtreamData> =
		delegate.parse(input)
			.asFlow()
			.flowOn(Dispatchers.Default)

	override suspend fun getInfo(input: XtreamInput): XtreamInfo = delegate.getInfo(input)

	override suspend fun getXtreamOutput(input: XtreamInput): XtreamOutput =
		delegate.getXtreamOutput(input)
}