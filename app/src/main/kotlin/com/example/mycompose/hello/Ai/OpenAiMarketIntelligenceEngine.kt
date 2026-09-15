package com.example.mycompose.hello.Ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * AI #1: OpenAI market-intelligence layer.
 * It never places orders. It analyzes news + real market flow/order book + indicators
 * and returns a compact bias for the order brain.
 */
class OpenAiMarketIntelligenceEngine(
    private val apiKeyProvider: () -> String,
    private val baseUrlProvider: () -> String = { DEFAULT_BASE_URL },
    private val modelProvider: () -> String = { DEFAULT_MODEL }
) {
    enum class Bias { BUY, SELL, HOLD }
    data class MarketInput(
        val symbol: String,
        val price: Double,
        val volume24h: Double,
        val rsi: Double,
        val macd: Double,
        val macdSignal: Double,
        val ema9: Double,
        val ema21: Double,
        val ema50: Double,
        val ema200: Double,
        val bollingerUpper: Double,
        val bollingerLower: Double,
        val atr: Double,
        val buyerVolume: Double,
        val sellerVolume: Double,
        val buyerRatioPct: Double,
        val sellerRatioPct: Double,
        val bidVolume: Double,
        val askVolume: Double,
        val orderBookImbalancePct: Double,
        val volumeDelta: Double,
        val flowScore: Double,
        val flowBias: String,
        val newsSummary: String,
        val exchange: String
    )
    data class Analysis(
        val bias: Bias,
        val confidence: Double,
        val marketScore: Double,
        val newsRisk: String,
        val reason: String,
        val updatedAt: Long = System.currentTimeMillis()
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()
    private val cache = ConcurrentHashMap<String, Pair<Long, Analysis>>()
    private val ttlMs = 8_000L

    companion object {
        /**
         * Singapore global OpenAI-compatible endpoint.
         *
         * This avoids requiring the user to manually enter a workspace ID.
         * Alibaba Cloud documents this endpoint as the existing Singapore
         * domain; workspace-dedicated domains are optional.
         */
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-5.6-luna"

        private fun normalizeBaseUrl(raw: String): String {
            val value = raw.trim().trimEnd('/')
            if (value.isBlank()) return DEFAULT_BASE_URL
            val lower = value.lowercase(Locale.US)
            if (lower.contains("{workspaceid}") || lower.contains("<actual-workspace-id>") ||
                lower.contains("your_singapore_workspace") || lower.contains("your-workspace-id")) {
                return DEFAULT_BASE_URL
            }
            return when {
                lower.endsWith("/v1") -> value
                lower.endsWith("/chat/completions") -> value.removeSuffix("/chat/completions")
                else -> "$value/v1"
            }.trimEnd('/')
        }
    }

    suspend fun analyze(input: MarketInput): Analysis? = withContext(Dispatchers.IO) {
        val key = input.symbol.uppercase(Locale.US)
        cache[key]?.let { if (it.first > System.currentTimeMillis()) return@withContext it.second }
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return@withContext null
        val baseUrl = normalizeBaseUrl(baseUrlProvider())
        val model = modelProvider().trim().ifBlank { DEFAULT_MODEL }

        val system = """
You are OpenAI Market Intelligence AI #1 for a crypto trading application.
Your job is ONLY market intelligence, not order execution.
Use ONLY supplied facts. Never invent buyer/seller volume, order-book data, news, price or candles.
Evaluate 1-minute trading conditions, RSI, MACD, EMA 9/21/50/200, Bollinger Bands, ATR,
real buyer/seller trade volume, real bid/ask book volume, imbalance, volume delta, flow score,
and the supplied news. Return JSON only:
{"bias":"BUY|SELL|HOLD","confidence":0-100,"marketScore":-100..100,"newsRisk":"LOW|MEDIUM|HIGH","reason":"short"}
""".trimIndent()
        val user = buildString {
            append("EXCHANGE=${input.exchange}\nSYMBOL=${input.symbol}\nPRICE=${input.price}\nVOLUME24H=${input.volume24h}\n")
            append("RSI=${input.rsi}\nMACD=${input.macd}\nMACD_SIGNAL=${input.macdSignal}\n")
            append("EMA9=${input.ema9}\nEMA21=${input.ema21}\nEMA50=${input.ema50}\nEMA200=${input.ema200}\n")
            append("BB_UPPER=${input.bollingerUpper}\nBB_LOWER=${input.bollingerLower}\nATR=${input.atr}\n")
            append("BUYER_VOLUME=${input.buyerVolume}\nSELLER_VOLUME=${input.sellerVolume}\n")
            append("BUYER_RATIO=${input.buyerRatioPct}\nSELLER_RATIO=${input.sellerRatioPct}\n")
            append("BID_VOLUME=${input.bidVolume}\nASK_VOLUME=${input.askVolume}\n")
            append("ORDERBOOK_IMBALANCE=${input.orderBookImbalancePct}\nVOLUME_DELTA=${input.volumeDelta}\nFLOW_SCORE=${input.flowScore}\nFLOW_BIAS=${input.flowBias}\n")
            append("NEWS=${input.newsSummary.take(5000)}")
        }
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", user))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 300)
            put("stream", false)
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("OPENAI_MARKET_AI", "HTTP ${response.code}: ${response.body?.string()?.take(500)}")
                    return@withContext null
                }
                val raw = response.body?.string().orEmpty()
                val root = JSONObject(raw)
                val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.optString("content").orEmpty()
                val jsonText = content.substringAfter('{', "{").substringBeforeLast('}', "}")
                val result = JSONObject(jsonText)
                val bias = runCatching { Bias.valueOf(result.optString("bias", "HOLD").uppercase(Locale.US)) }
                    .getOrDefault(Bias.HOLD)
                val analysis = Analysis(
                    bias = bias,
                    confidence = result.optDouble("confidence", 0.0).coerceIn(0.0, 100.0),
                    marketScore = result.optDouble("marketScore", 0.0).coerceIn(-100.0, 100.0),
                    newsRisk = result.optString("newsRisk", "MEDIUM"),
                    reason = result.optString("reason", "OpenAI market analysis")
                )
                cache[key] = System.currentTimeMillis() + ttlMs to analysis
                analysis
            }
        } catch (e: Exception) {
            Log.w("OPENAI_MARKET_AI", "Analysis failed for ${input.symbol}: ${e.message}")
            null
        }
    }

    fun clear() = cache.clear()
}
