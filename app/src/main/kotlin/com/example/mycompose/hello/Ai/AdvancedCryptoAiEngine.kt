package com.example.mycompose.hello.Ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/**
 * Advanced Crypto AI Market Intelligence Engine.
 *
 * This file is intentionally standalone: it does NOT change the existing
 * TradingViewModel, exchange router, order execution, scrolling, or UI code.
 *
 * Data layers:
 *  - Technical market snapshot supplied by the existing bot
 *  - Global news from GDELT DOC API
 *  - Optional open-source LLM through an OpenAI-compatible endpoint
 *    (Qwen3 / llama.cpp / Ollama / vLLM / SGLang can expose such an endpoint)
 *
 * The engine returns BUY / SELL / HOLD + confidence and reasons. It does NOT
 * place orders by itself. The existing risk/order layer must approve a trade.
 */

object AdvancedCryptoAiEngine {

    data class Config(
        val newsLimit: Int = 12,
        val newsLookbackHours: Int = 24,
        val minConfidenceForTrade: Double = 78.0,
        val llmEnabled: Boolean = false,
        val llmBaseUrl: String = "http://127.0.0.1:8080/v1",
        val llmModel: String = "Qwen3-4B-Instruct-2507",
        val connectTimeoutMs: Int = 8_000,
        val readTimeoutMs: Int = 15_000
    )

    data class MarketSnapshot(
        val symbol: String,
        val price: Double,
        val change24hPct: Double,
        val volume24h: Double,
        val rsi: Double = 50.0,
        val macd: Double = 0.0,
        val macdSignal: Double = 0.0,
        val ema9: Double = 0.0,
        val ema21: Double = 0.0,
        val ema50: Double = 0.0,
        val ema200: Double = 0.0,
        val bollingerUpper: Double = 0.0,
        val bollingerLower: Double = 0.0,
        val atr: Double = 0.0
    )

    data class NewsItem(
        val title: String,
        val source: String,
        val url: String,
        val publishedAt: String,
        val sentiment: Double
    )

    enum class Decision { BUY, SELL, HOLD }

    data class Analysis(
        val symbol: String,
        val decision: Decision,
        val confidence: Double,
        val technicalScore: Double,
        val newsScore: Double,
        val momentumScore: Double,
        val riskScore: Double,
        val reasons: List<String>,
        val news: List<NewsItem>,
        val aiExplanation: String = "",
        val generatedAt: Long = System.currentTimeMillis()
    )

    suspend fun analyze(
        market: MarketSnapshot,
        config: Config = Config()
    ): Analysis = withContext(Dispatchers.IO) {
        val news = fetchGlobalNews(market.symbol, config)
        val technical = technicalScore(market)
        val newsScore = newsScore(news)
        val momentum = momentumScore(market)
        val risk = riskScore(market)

        val heuristic = combineScores(
            technical = technical,
            news = newsScore,
            momentum = momentum,
            risk = risk
        )

        val llm = if (config.llmEnabled) {
            requestOpenSourceAi(
                market = market,
                news = news,
                heuristic = heuristic,
                config = config
            )
        } else null

        val finalDecision = llm?.decision ?: heuristic.decision
        val finalConfidence = llm?.confidence
            ?.coerceIn(0.0, 100.0)
            ?.let { (it * 0.65 + heuristic.confidence * 0.35).coerceIn(0.0, 100.0) }
            ?: heuristic.confidence

        val reasons = buildList {
            addAll(heuristic.reasons)
            if (llm != null && llm.explanation.isNotBlank()) {
                add("Open-source AI: ${llm.explanation}")
            }
            if (finalConfidence < config.minConfidenceForTrade) {
                add("Confidence below trade threshold: HOLD")
            }
        }

        Analysis(
            symbol = market.symbol,
            decision = if (finalConfidence >= config.minConfidenceForTrade) {
                finalDecision
            } else Decision.HOLD,
            confidence = finalConfidence,
            technicalScore = technical,
            newsScore = newsScore,
            momentumScore = momentum,
            riskScore = risk,
            reasons = reasons.take(8),
            news = news,
            aiExplanation = llm?.explanation.orEmpty()
        )
    }

    /** Fetches global crypto-related news from GDELT DOC 2.0. */
    private fun fetchGlobalNews(
        symbol: String,
        config: Config
    ): List<NewsItem> {
        return try {
            val query = "($symbol OR cryptocurrency OR crypto)"
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL(
                "https://api.gdeltproject.org/api/v2/doc/doc" +
                    "?query=$encoded" +
                    "&mode=artlist" +
                    "&format=json" +
                    "&maxrecords=${config.newsLimit}" +
                    "&timespan=${config.newsLookbackHours}h" +
                    "&sort=datedesc"
            )

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "NitinCryptoAI/1.0")
            }

            try {
                if (connection.responseCode !in 200..299) return emptyList()
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val articles = root.optJSONArray("articles") ?: JSONArray()
                val result = ArrayList<NewsItem>(articles.length())

                for (i in 0 until articles.length()) {
                    val item = articles.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    if (title.isBlank()) continue
                    val source = item.optString("domain").ifBlank { "Global News" }
                    val articleUrl = item.optString("url")
                    val published = item.optString("seendate")
                    result += NewsItem(
                        title = title,
                        source = source,
                        url = articleUrl,
                        publishedAt = published,
                        sentiment = quickSentiment(title)
                    )
                }
                result
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun technicalScore(m: MarketSnapshot): Double {
        var score = 50.0

        if (m.rsi in 45.0..65.0) score += 6.0
        if (m.rsi < 30.0) score += 8.0
        if (m.rsi > 70.0) score -= 8.0

        if (m.macd > m.macdSignal) score += 10.0 else score -= 10.0
        if (m.ema9 > m.ema21) score += 6.0 else score -= 6.0
        if (m.ema21 > m.ema50) score += 6.0 else score -= 6.0
        if (m.ema50 > m.ema200) score += 8.0 else score -= 8.0

        if (m.price > m.ema200 && m.ema200 > 0.0) score += 6.0
        if (m.bollingerUpper > 0.0 && m.price > m.bollingerUpper) score -= 5.0
        if (m.bollingerLower > 0.0 && m.price < m.bollingerLower) score += 5.0

        return score.coerceIn(0.0, 100.0)
    }

    private fun momentumScore(m: MarketSnapshot): Double {
        val change = m.change24hPct
        return (50.0 + change.coerceIn(-20.0, 20.0) * 2.2).coerceIn(0.0, 100.0)
    }

    private fun riskScore(m: MarketSnapshot): Double {
        if (m.price <= 0.0) return 0.0
        val atrPct = if (m.atr > 0.0) (m.atr / m.price) * 100.0 else 0.0
        val risk = 100.0 - atrPct.coerceIn(0.0, 20.0) * 4.0
        return risk.coerceIn(0.0, 100.0)
    }

    private fun newsScore(news: List<NewsItem>): Double {
        if (news.isEmpty()) return 50.0
        return (50.0 + news.map { it.sentiment }.average() * 50.0)
            .coerceIn(0.0, 100.0)
    }

    private data class HeuristicResult(
        val decision: Decision,
        val confidence: Double,
        val reasons: List<String>
    )

    private fun combineScores(
        technical: Double,
        news: Double,
        momentum: Double,
        risk: Double
    ): HeuristicResult {
        val bullish = (
            technical * 0.40 +
                news * 0.20 +
                momentum * 0.20 +
                risk * 0.20
            )
        val bearish = 100.0 - bullish
        val edge = abs(bullish - 50.0) * 2.0
        val confidence = (55.0 + edge * 0.45).coerceIn(0.0, 96.0)

        val decision = when {
            bullish >= 68.0 -> Decision.BUY
            bullish <= 32.0 -> Decision.SELL
            else -> Decision.HOLD
        }

        val reasons = mutableListOf<String>()
        reasons += "Technical score ${technical.round1()}/100"
        reasons += "News score ${news.round1()}/100"
        reasons += "Momentum score ${momentum.round1()}/100"
        reasons += "Risk score ${risk.round1()}/100"
        reasons += if (bullish >= 50.0) {
            "Bullish pressure ${bullish.round1()}%"
        } else {
            "Bearish pressure ${bearish.round1()}%"
        }

        return HeuristicResult(decision, confidence, reasons)
    }

    private data class LlmResult(
        val decision: Decision,
        val confidence: Double,
        val explanation: String
    )

    /**
     * Calls an open-source model through an OpenAI-compatible endpoint.
     * Works with Qwen3/llama.cpp/Ollama/vLLM/SGLang when configured accordingly.
     */
    private fun requestOpenSourceAi(
        market: MarketSnapshot,
        news: List<NewsItem>,
        heuristic: HeuristicResult,
        config: Config
    ): LlmResult? {
        return try {
            val endpoint = config.llmBaseUrl.trimEnd('/') + "/chat/completions"
            val prompt = buildPrompt(market, news, heuristic)

            val body = JSONObject().apply {
                put("model", config.llmModel)
                put("temperature", 0.1)
                put("max_tokens", 300)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "You are a conservative professional crypto market analyst. " +
                                "Return only JSON: {decision:BUY|SELL|HOLD,confidence:0-100,explanation:string}. " +
                                "Never invent unavailable facts. Prefer HOLD when evidence conflicts."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }.toString()

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs
                doInput = true
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            try {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }

                if (connection.responseCode !in 200..299) return null

                val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(raw)
                val content = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()

                parseLlm(content)
            } finally {
                connection.disconnect()
            }

        } catch (_: Exception) {
            null
        }
    }

    private fun buildPrompt(
        market: MarketSnapshot,
        news: List<NewsItem>,
        heuristic: HeuristicResult
    ): String {
        val newsText = news.take(12).joinToString("\n") {
            "- ${it.source}: ${it.title} | sentiment=${"%.2f".format(Locale.US, it.sentiment)}"
        }

        return """
            Analyse ${market.symbol} for a live crypto trading decision.
            Price=${market.price}
            24hChange=${market.change24hPct}%
            Volume24h=${market.volume24h}
            RSI=${market.rsi}
            MACD=${market.macd}
            MACDSignal=${market.macdSignal}
            EMA9=${market.ema9}
            EMA21=${market.ema21}
            EMA50=${market.ema50}
            EMA200=${market.ema200}
            BollingerUpper=${market.bollingerUpper}
            BollingerLower=${market.bollingerLower}
            ATR=${market.atr}
            Heuristic=${heuristic.decision}, confidence=${heuristic.confidence}

            Global news:
            $newsText

            Do not guarantee profit. If news is stale, contradictory, manipulated-looking,
            or technical/risk evidence is weak, choose HOLD.
        """.trimIndent()
    }

    private fun parseLlm(content: String): LlmResult? {
        val cleaned = content
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()

        val jsonStart = cleaned.indexOf('{')
        val jsonEnd = cleaned.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null

        return try {
            val j = JSONObject(cleaned.substring(jsonStart, jsonEnd + 1))
            val decision = when (j.optString("decision").uppercase(Locale.US)) {
                "BUY" -> Decision.BUY
                "SELL" -> Decision.SELL
                else -> Decision.HOLD
            }
            LlmResult(
                decision = decision,
                confidence = j.optDouble("confidence", 0.0).coerceIn(0.0, 100.0),
                explanation = j.optString("explanation", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun quickSentiment(text: String): Double {
        val positive = listOf(
            "surge", "rally", "bullish", "approval", "approved", "adoption",
            "partnership", "launch", "inflow", "breakout", "growth", "record high"
        )
        val negative = listOf(
            "crash", "hack", "exploit", "lawsuit", "ban", "banned", "fraud",
            "outflow", "liquidation", "bearish", "delist", "warning", "collapse"
        )

        val t = text.lowercase(Locale.US)
        var score = 0.0
        positive.forEach { if (t.contains(it)) score += 0.12 }
        negative.forEach { if (t.contains(it)) score -= 0.14 }
        return score.coerceIn(-1.0, 1.0)
    }

    private fun Double.round1(): String = String.format(Locale.US, "%.1f", this)
}
