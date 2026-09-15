import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

data class MarketSnapshot(
    val price: Double,
    val volume: Double,
    val averageVolume: Double,
    val closes: List<Double>
)

data class CoinState(
    val ticker: String,
    @Volatile var holdsAsset: Boolean = false,
    @Volatile var purchasePrice: Double = 0.0,
    @Volatile var currentPrice: Double = 0.0,
    @Volatile var lastBuyTimeMs: Long = 0L,
    @Volatile var lastSignal: String = "WAIT"
)

class MultiCryptoBot(
    private val tickers: List<String>,
    private val targetProfitPercent: Double = 2.0,
    private val maxLossPercent: Double = 2.0,
    private val minBuyScore: Int = 7,
    private val cooldownMs: Long = 60_000L,
    private val fetchMarketData: suspend (String) -> MarketSnapshot
) {
    private val portfolio = ConcurrentHashMap<String, CoinState>()

    init {
        tickers.forEach { portfolio[it] = CoinState(it) }
    }

    fun startTrading() = runBlocking {
        val jobs = tickers.map { ticker ->
            launch(Dispatchers.Default) { tradeLoop(ticker) }
        }
        jobs.joinAll()
    }

    private suspend fun tradeLoop(ticker: String) {
        while (currentCoroutineContext().isActive) {
            try {
                val state = portfolio[ticker] ?: continue
                val data = fetchMarketData(ticker)

                if (data.closes.size < 50) {
                    state.lastSignal = "WAIT: need 50 candles"
                    delay(5_000)
                    continue
                }

                state.currentPrice = data.price
                monitorAndExecute(state, data)
                delay(5_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Error for $ticker: ${e.message}")
                delay(10_000)
            }
        }
    }

    private fun monitorAndExecute(
        state: CoinState,
        data: MarketSnapshot
    ) {
        val now = System.currentTimeMillis()

        if (!state.holdsAsset) {
            if (now - state.lastBuyTimeMs < cooldownMs) return

            val signal = strongBuySignal(data)
            state.lastSignal = signal.reason

            // VOLUME ALONE NEVER TRIGGERS BUY.
            if (signal.score >= minBuyScore && signal.volumeConfirmed) {
                state.holdsAsset = true
                state.purchasePrice = data.price
                state.currentPrice = data.price
                state.lastBuyTimeMs = now

                println(
                    "[BUY] ${state.ticker} @ ${data.price} " +
                    "score=${signal.score}/10 ${signal.reason}"
                )
            }
        } else {
            val entry = state.purchasePrice
            if (entry <= 0.0) return

            val pnl = ((data.price - entry) / entry) * 100.0

            if (pnl >= targetProfitPercent) {
                closePosition(state, data.price, "TAKE_PROFIT")
            } else if (pnl <= -maxLossPercent) {
                closePosition(state, data.price, "RISK_EXIT")
            } else if (strongExitSignal(data)) {
                closePosition(state, data.price, "TREND_REVERSAL")
            }
        }
    }

    private fun closePosition(state: CoinState, price: Double, reason: String) {
        val pnl = if (state.purchasePrice > 0.0)
            ((price - state.purchasePrice) / state.purchasePrice) * 100.0
        else 0.0

        println("[SELL] ${state.ticker} @ $price P/L=${"%.2f".format(pnl)}% $reason")

        state.holdsAsset = false
        state.purchasePrice = 0.0
        state.currentPrice = price
        state.lastSignal = "SOLD: $reason"
    }

    private data class BuySignal(
        val score: Int,
        val volumeConfirmed: Boolean,
        val reason: String
    )

    private fun strongBuySignal(data: MarketSnapshot): BuySignal {
        val p = data.closes
        val price = p.last()

        val rsi = rsi(p, 14)
        val previousRsi = rsi(p.dropLast(1), 14)
        val ema9 = ema(p, 9)
        val ema21 = ema(p, 21)
        val ema50 = ema(p, 50)
        val macd = macd(p)
        val bb = bollinger(p, 20)

        var score = 0
        val reasons = mutableListOf<String>()

        // RSI recovery, not blind RSI<30 buying.
        if (previousRsi < 35.0 && rsi > previousRsi && rsi < 50.0) {
            score++
            reasons += "RSI recovery"
        }

        // MACD confirmation.
        if (macd.first > macd.second && macd.first - macd.second > 0.0) {
            score++
            reasons += "MACD bullish"
        }

        // Trend confirmation.
        if (ema9 > ema21 && ema21 > ema50) {
            score += 2
            reasons += "EMA uptrend"
        } else if (ema9 > ema21) {
            score++
            reasons += "EMA recovery"
        }

        // Bollinger confirmation.
        if (price > bb.first && price <= bb.second) {
            score++
            reasons += "BB confirmation"
        }

        // Real volume confirmation: 20% above average.
        // It contributes points but can NEVER buy by itself.
        val volumeConfirmed =
            data.averageVolume > 0.0 &&
            data.volume >= data.averageVolume * 1.20

        if (volumeConfirmed) {
            score += 2
            reasons += "volume confirmed"
        }

        // Short-term momentum.
        if (price > p[p.lastIndex - 1]) {
            score++
            reasons += "momentum"
        }

        // Price above EMA21.
        if (price > ema21) {
            score++
            reasons += "above EMA21"
        }

        // Reject late/overextended entries.
        if (price > bb.second || price > ema21 * 1.025) {
            score = min(score, minBuyScore - 1)
            reasons += "overextended"
        }

        return BuySignal(
            score = score,
            volumeConfirmed = volumeConfirmed,
            reason = reasons.joinToString(", ").ifEmpty { "no confirmation" }
        )
    }

    private fun strongExitSignal(data: MarketSnapshot): Boolean {
        val p = data.closes
        val ema9 = ema(p, 9)
        val ema21 = ema(p, 21)
        val m = macd(p)
        val r = rsi(p, 14)

        return (ema9 < ema21 && m.first < m.second && m.first - m.second < 0.0) ||
               r >= 75.0
    }

    private fun rsi(prices: List<Double>, period: Int): Double {
        if (prices.size <= period) return 50.0
        var gains = 0.0
        var losses = 0.0
        for (i in prices.size - period until prices.size) {
            val d = prices[i] - prices[i - 1]
            if (d >= 0) gains += d else losses -= d
        }
        if (losses == 0.0) return 100.0
        val rs = gains / losses
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun ema(prices: List<Double>, period: Int): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size < period) return prices.average()
        val k = 2.0 / (period + 1.0)
        var e = prices.take(period).average()
        for (i in period until prices.size) e += (prices[i] - e) * k
        return e
    }

    private fun macd(prices: List<Double>): Pair<Double, Double> {
        val macdValues = mutableListOf<Double>()
        for (i in 26..prices.lastIndex) {
            val s = prices.subList(0, i + 1)
            macdValues += ema(s, 12) - ema(s, 26)
        }
        val line = macdValues.lastOrNull() ?: 0.0
        val signal = ema(macdValues, 9)
        return line to signal
    }

    private fun bollinger(prices: List<Double>, period: Int): Triple<Double, Double, Double> {
        val s = prices.takeLast(period)
        val middle = s.average()
        var v = 0.0
        for (x in s) v += (x - middle) * (x - middle)
        val sd = kotlin.math.sqrt(v / s.size)
        return Triple(middle, middle + 2.0 * sd, middle - 2.0 * sd)
    }
}
