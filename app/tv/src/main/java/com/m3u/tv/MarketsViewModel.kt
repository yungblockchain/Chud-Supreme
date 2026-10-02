package com.m3u.tv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MarketSection { Trending, PumpFun, Gmgn, Photon, Robinhood, New, Hyperliquid, TopCoins, Watchlist, Search }

/** Sections that list every chain's pools (the chain chips apply to these). */
val MarketSection.filtersByChain: Boolean
    get() = this != MarketSection.Hyperliquid && this != MarketSection.TopCoins &&
        this != MarketSection.PumpFun && this != MarketSection.Gmgn && this != MarketSection.Photon

@Immutable
data class ChartState(
    val key: String,
    val range: ChartRange,
    val candles: List<Candle> = emptyList(),
    val loading: Boolean = true,
)

@Immutable
data class MarketsState(
    val section: MarketSection = MarketSection.Trending,
    /** Chain filter; null shows every chain. */
    val chain: String? = null,
    val query: String = "",
    val items: List<MarketPair> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val note: String? = null,
    val updatedAt: Long? = null,
)

@HiltViewModel
class MarketsViewModel @Inject constructor(
    private val store: DialSettingsStore,
    private val secrets: SecretStore,
) : ViewModel() {

    private val _state = MutableStateFlow(MarketsState())
    val state: StateFlow<MarketsState> = _state.asStateFlow()

    val watchlist: StateFlow<List<String>> = store.watchlist

    private var loadJob: Job? = null

    private val _chart = MutableStateFlow<ChartState?>(null)
    val chart: StateFlow<ChartState?> = _chart.asStateFlow()
    private val chartCache = mutableMapOf<Pair<String, ChartRange>, Pair<Long, List<Candle>>>()
    private var chartJob: Job? = null

    private val _range = MutableStateFlow(ChartRange.Day)
    val range: StateFlow<ChartRange> = _range.asStateFlow()

    /** One-line notices (string resources) for a toast. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    val tradingBot: String? get() = secrets.get(SecretName.TelegramTradingBot)

    fun selectRange(range: ChartRange) {
        _range.value = range
    }

    /** Candles for the token in the side panel; cached for a minute per token and range. */
    fun loadChart(pair: MarketPair, range: ChartRange = _range.value) {
        val cacheKey = pair.key to range
        val cached = chartCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < CHART_TTL_MS) {
            _chart.value = ChartState(pair.key, range, cached.second, loading = false)
            return
        }
        chartJob?.cancel()
        _chart.update { current ->
            // Keep the old line on screen while the new one loads.
            if (current?.key == pair.key && current.range == range) current.copy(loading = true)
            else ChartState(pair.key, range)
        }
        chartJob = viewModelScope.launch {
            // Let focus settle while scrolling the list before asking the server.
            delay(CHART_DEBOUNCE_MS)
            val candles = runCatching { candlesFor(pair, range) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
            if (candles.isNotEmpty()) chartCache[cacheKey] = System.currentTimeMillis() to candles
            _chart.update { current ->
                if (current?.key == pair.key && current.range == range) current.copy(candles = candles, loading = false)
                else current
            }
        }
    }

    /** Sends the token's summary and links to the person's own Telegram chat. */
    fun sendToTelegram(pair: MarketPair) {
        val token = secrets.get(SecretName.TelegramBotToken)
        val chat = secrets.get(SecretName.TelegramChatId)
        if (token == null || chat == null) {
            _messages.tryEmit(R.string.dial_markets_telegram_setup)
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                TelegramBot.send(token, chat, TelegramBot.summary(pair, tokenLinks(pair, tradingBot)))
            }.onFailure { if (it is CancellationException) throw it }
            _messages.tryEmit(
                if (result.isSuccess) R.string.dial_markets_telegram_sent else R.string.dial_markets_telegram_failed
            )
        }
    }

    fun selectSection(section: MarketSection) {
        if (_state.value.section == section) return
        loadJob?.cancel()
        _state.update {
            it.copy(section = section, items = emptyList(), loading = false, failed = false, updatedAt = null)
        }
    }

    fun selectChain(chain: String?) = _state.update { it.copy(chain = chain) }

    fun updateQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggleWatch(pair: MarketPair) = store.toggleWatch(pair.watchKey)

    /** Reloads the current section. The screen calls this on a 30-second timer while visible. */
    fun refresh() {
        val requested = _state.value
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val items = load(requested.section, requested.query)
                _state.update { current ->
                    if (current.section != requested.section) current
                    else current.copy(
                        items = items,
                        loading = false,
                        failed = false,
                        note = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing the last good data; the screen notes that the update failed.
                _state.update { current ->
                    if (current.section != requested.section) current
                    else current.copy(loading = false, failed = true, note = e.message)
                }
            }
        }
    }

    private suspend fun load(section: MarketSection, query: String): List<MarketPair> = when (section) {
        MarketSection.Trending -> DexScreener.tokens(DexScreener.topBoosted().take(MAX_TOKENS))
        MarketSection.New -> DexScreener.tokens(DexScreener.latestProfiles().take(MAX_TOKENS))
        MarketSection.PumpFun -> MarketBoards.pumpFun()
        MarketSection.Gmgn -> MarketBoards.gmgn()
        MarketSection.Photon -> MarketBoards.photon()
        MarketSection.Robinhood -> {
            val trending = runCatching { GeckoTerminal.trendingTokens("robinhood", ROBINHOOD) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
            val boosted = (DexScreener.topBoosted() + DexScreener.latestProfiles())
                .filter { it.chainId == ROBINHOOD }
            DexScreener.tokens((trending + boosted).distinctBy { it.watchKey.lowercase() }.take(MAX_TOKENS))
        }
        MarketSection.Hyperliquid -> Hyperliquid.perps().take(MAX_TOKENS)
        MarketSection.TopCoins -> topCoins()
        MarketSection.Watchlist -> {
            val refs = store.watchlist.value.mapNotNull { TokenRef.fromWatchKey(it) }
            val onChain = refs.filter { it.chainId != CHAIN_HYPERLIQUID && it.chainId != CHAIN_COIN }
            val perps = refs.filter { it.chainId == CHAIN_HYPERLIQUID }.map { it.tokenAddress }.toSet()
            val coins = refs.filter { it.chainId == CHAIN_COIN }.map { it.tokenAddress }.toSet()
            DexScreener.tokens(onChain) +
                (if (perps.isEmpty()) emptyList() else Hyperliquid.perps().filter { it.baseAddress in perps }) +
                (if (coins.isEmpty()) emptyList() else topCoins().filter { it.baseAddress in coins })
        }
        MarketSection.Search -> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) emptyList()
            else DexScreener.search(trimmed)
                // One row per token: its deepest pool.
                .groupBy { it.key }
                .map { (_, pairs) -> pairs.maxByOrNull { it.liquidityUsd ?: 0.0 }!! }
                .sortedByDescending { it.liquidityUsd ?: 0.0 }
                .take(MAX_TOKENS)
        }
    }

    /** CoinMarketCap with the person's key; CoinGecko (no key needed) otherwise. */
    private suspend fun topCoins(): List<MarketPair> =
        secrets.get(SecretName.CoinMarketCap)
            ?.let { key -> runCatching { TopCoins.coinMarketCap(key) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: TopCoins.coinGecko()

    private companion object {
        const val MAX_TOKENS = 60
        const val ROBINHOOD = "robinhood"
        const val CHART_TTL_MS = 60_000L
        const val CHART_DEBOUNCE_MS = 300L
    }
}
