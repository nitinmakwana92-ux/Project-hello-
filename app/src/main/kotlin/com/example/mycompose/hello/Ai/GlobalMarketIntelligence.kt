package com.example.mycompose.hello.Ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Lightweight market-intelligence layer used before a real order decision.
 *
 * Candle flow is an ESTIMATE when only OHLC is available. Exact buyer/seller
 * aggression requires the selected exchange's public trade tape/order book.
 * This class deliberately never claims 100%/101% accuracy or guaranteed profit.
 */
object GlobalMarketIntelligence {
    data class CandleFlow(
        val buyerPressurePct: Double,
        val sellerPressurePct: Double,
        val volumeTrendPct: Double,
        val bullishVolume: Boolean,
        val bearishVolume: Boolean,
        val reversalCandidate: Boolean,
        val score: Double,
        val reason: String
    )

    data class NewsSnapshot(
        val summary: String,
        val fetchedAt: Long
    )

    private val client = OkHttpClient.Builder().build()
    private val cachedAt = AtomicLong(0L)
    @Volatile private var cachedNews = ""
    private val newsFetchLock = Mutex()

    fun analyzeCandleFlow(
        closes: List<Double>,
        highs: List<Double>,
        lows: List<Double>,
        volume24h: Double
    ): CandleFlow {
        val n = minOf(closes.size, highs.size, lows.size)
        if (n < 20) {
            return CandleFlow(50.0, 50.0, 0.0, false, false, false, 0.0,
                "FLOW WAIT • candle history insufficient")
        }

        val start = maxOf(1, n - 20)
        var buyWeight = 0.0
        var sellWeight = 0.0
        val volumes = ArrayList<Double>()

        for (i in start until n) {
            val h = highs[i]
            val l = lows[i]
            val c = closes[i]
            val prev = closes[i - 1]
            if (h <= 0.0 || l <= 0.0 || c <= 0.0 || prev <= 0.0 || h < l) continue

            val range = (h - l).coerceAtLeast(c * 1e-9)
            val location = ((c - l) / range).coerceIn(0.0, 1.0)
            val returnPct = ((c - prev) / prev) * 100.0

            // Without exchange trade tape, candle location is only a proxy.
            // Give more weight to candles closing near their highs/lows and
            // to larger directional moves.
            val directionalWeight = (1.0 + (abs(returnPct) / 2.0).coerceAtMost(3.0))
            val estimatedVolume = (volume24h / 24.0).coerceAtLeast(1e-12) * directionalWeight
            buyWeight += estimatedVolume * location
            sellWeight += estimatedVolume * (1.0 - location)
            volumes += estimatedVolume
        }

        val total = buyWeight + sellWeight
        if (total <= 0.0) {
            return CandleFlow(50.0, 50.0, 0.0, false, false, false, 0.0,
                "FLOW WAIT • no usable candle flow")
        }

        val buyerPct = buyWeight / total * 100.0
        val sellerPct = 100.0 - buyerPct
        val recentAvg = volumes.takeLast(5).average()
        val oldAvg = volumes.dropLast(5).takeLast(10).average().coerceAtLeast(1e-12)
        val volumeTrend = ((recentAvg / oldAvg) - 1.0) * 100.0

        val last = closes.last()
        val emaReference = closes.takeLast(20).average()
        val oversold = last < emaReference * 0.985
        val bullish = buyerPct >= 55.0 && volumeTrend >= -15.0
        val bearish = sellerPct >= 55.0 && volumeTrend >= -15.0
        val reversal = bearish && oversold && closes.last() > closes[n - 2]

        val score = when {
            bullish -> ((buyerPct - 50.0) * 1.4 + volumeTrend * 0.15).coerceIn(-100.0, 100.0)
            reversal -> 12.0 + (sellerPct - 55.0).coerceAtMost(20.0) * 0.25
            bearish -> ((buyerPct - 50.0) * 1.4 + volumeTrend * 0.15).coerceIn(-100.0, 100.0)
            else -> ((buyerPct - 50.0) * 1.2).coerceIn(-100.0, 100.0)
        }

        val reason = when {
            bullish -> "BUY FLOW • estimated buyer pressure ${fmt(buyerPct)}% • volume ${fmt(volumeTrend)}%"
            reversal -> "REVERSAL FLOW • heavy sell volume + recovery candle"
            bearish -> "SELL FLOW • estimated seller pressure ${fmt(sellerPct)}%"
            else -> "MIXED FLOW • buyers ${fmt(buyerPct)}% / sellers ${fmt(sellerPct)}%"
        }

        return CandleFlow(
            buyerPressurePct = buyerPct,
            sellerPressurePct = sellerPct,
            volumeTrendPct = volumeTrend,
            bullishVolume = bullish,
            bearishVolume = bearish,
            reversalCandidate = reversal,
            score = score,
            reason = reason
        )
    }

    suspend fun latestGlobalNews(): NewsSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - cachedAt.get() < 10 * 60 * 1000L && cachedNews.isNotBlank()) {
            return@withContext NewsSnapshot(cachedNews, cachedAt.get())
        }

        newsFetchLock.withLock {
            val lockedNow = System.currentTimeMillis()
            if (lockedNow - cachedAt.get() < 10 * 60 * 1000L && cachedNews.isNotBlank()) {
                return@withContext NewsSnapshot(cachedNews, cachedAt.get())
            }

        val url = "https://news.google.com/rss/search?q=crypto%20bitcoin%20ethereum%20altcoin%20ETF%20regulation%20market&hl=en-US&gl=US&ceid=US:en"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AlgoBot/1.0")
            .get()
            .build()

        val summary = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use "Global news unavailable"
                val xml = response.body?.string().orEmpty()
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
                val nodes = doc.getElementsByTagName("item")
                val lines = mutableListOf<String>()
                for (i in 0 until minOf(nodes.length, 12)) {
                    val item = nodes.item(i) as? Element ?: continue
                    val title = item.getElementsByTagName("title").item(0)?.textContent?.trim().orEmpty()
                    if (title.isNotBlank()) lines += title
                }
                if (lines.isEmpty()) "Global news unavailable" else lines.joinToString(" | ")
            }
        }.getOrElse { "Global news unavailable" }

        cachedNews = summary.take(6000)
        cachedAt.set(lockedNow)
        NewsSnapshot(cachedNews, lockedNow)
        }
    }

    fun globalBreadth(changes: List<Double>): String {
        if (changes.isEmpty()) return "Market breadth unavailable"
        val up = changes.count { it > 0.0 }
        val down = changes.count { it < 0.0 }
        val flat = changes.size - up - down
        val upPct = up * 100.0 / changes.size
        val downPct = down * 100.0 / changes.size
        return "Market breadth: ${changes.size} coins • up=${up} (${fmt(upPct)}%) • down=${down} (${fmt(downPct)}%) • flat=$flat"
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
}
