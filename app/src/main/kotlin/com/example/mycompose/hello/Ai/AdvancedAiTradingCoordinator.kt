package com.example.mycompose.hello.Ai

import com.example.mycompose.hello.viewmodel.CryptoPrice
import com.example.mycompose.hello.viewmodel.IndicatorValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Background AI coordinator for the real trading pipeline.
 *
 * It keeps news/AI analysis out of the 5-second trading loop, refreshes each
 * coin on a TTL, limits concurrent network/LLM work, and exposes only cached
 * results to the order gate. Missing/stale AI analysis therefore means HOLD.
 */
class AdvancedAiTradingCoordinator(
    private val scope: CoroutineScope,
    private val configProvider: () -> AdvancedCryptoAiEngine.Config = { AdvancedCryptoAiEngine.Config() }
) {
    private data class Cached(
        val analysis: AdvancedCryptoAiEngine.Analysis,
        val expiresAt: Long
    )

    private val cache = ConcurrentHashMap<String, Cached>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val concurrency = Semaphore(3)

    private val ttlMs = 5 * 60 * 1000L

    fun schedule(market: CryptoPrice, indicators: IndicatorValues) {
        val symbol = market.symbol.trim().uppercase()
        if (symbol.isBlank() || market.lastPrice <= 0.0) return
        if (indicators.ema200 <= 0.0 || indicators.atr <= 0.0) return

        val now = System.currentTimeMillis()
        val current = cache[symbol]
        if (current != null && current.expiresAt > now) return
        if (!pending.add(symbol)) return

        scope.launch(Dispatchers.IO) {
            try {
                concurrency.withPermit {
                    val snapshot = AdvancedCryptoAiEngine.MarketSnapshot(
                        symbol = symbol,
                        price = market.lastPrice,
                        change24hPct = market.priceChangePercentage24h,
                        volume24h = market.volume24h,
                        rsi = indicators.rsi,
                        macd = indicators.macd,
                        macdSignal = indicators.macdSignal,
                        ema9 = indicators.ema9,
                        ema21 = indicators.ema21,
                        ema50 = indicators.ema50,
                        ema200 = indicators.ema200,
                        bollingerUpper = indicators.bollingerUpper,
                        bollingerLower = indicators.bollingerLower,
                        atr = indicators.atr
                    )
                    val analysis = AdvancedCryptoAiEngine.analyze(snapshot, configProvider())
                    cache[symbol] = Cached(analysis, System.currentTimeMillis() + ttlMs)
                }
            } finally {
                pending.remove(symbol)
            }
        }
    }

    fun cached(symbol: String): AdvancedCryptoAiEngine.Analysis? {
        val item = cache[symbol.trim().uppercase()] ?: return null
        return if (item.expiresAt > System.currentTimeMillis()) item.analysis else null
    }

    fun clear() {
        cache.clear()
        pending.clear()
    }
}
