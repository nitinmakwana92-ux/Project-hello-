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
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Drop-in replacement for the project's current MasterMindAutoStrategyEngine.
 *
 * Compatible with the existing TradingViewModel MarketInput/Decision API.
 *
 * The engine is intentionally deterministic first:
 *   1) Technical trend: EMA 9/21/50/200
 *   2) Momentum: RSI + MACD
 *   3) Bollinger location
 *   4) ATR/volatility regime
 *   5) 24h momentum + volume sanity
 *   6) Buyer/seller tape flow
 *   7) L2 order-book imbalance
 *   8) supplied news/global-market context
 *   9) position-aware EXIT logic
 *  10) optional OpenAI second-opinion layer
 *
 * Important:
 * - This file does NOT place exchange orders.
 * - Existing authenticated exchange router remains responsible for execution.
 * - SELL is only emitted for an existing bot-owned position.
 * - Missing/stale/conflicting data is handled as HOLD.
 * - No profit guarantee is claimed.
 */
class MasterMindAutoStrategyEngine(
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String = { "gpt-5.6-luna" },
    private val baseUrlProvider: () -> String = { "https://api.openai.com/v1" },
    private val enabledProvider: () -> Boolean = { true }
) {

    enum class Action { BUY, SELL, HOLD }

    data class Strategy(
        val strategyId: String
    )

    data class Decision(
        val action: Action,
        val confidence: Double,
        val reason: String,
        val riskBlocked: Boolean,
        val strategy: Strategy
    )

    /**
     * Kept source-compatible with the current TradingViewModel.
     * The optional fields at the end allow a future caller to pass richer
     * candle/volume/news data without changing this class again.
     */
    data class MarketInput(
        val symbol: String,
        val market: String,
        val price: Double,
        val change24hPct: Double,
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
        val buyerVolume: Double = 0.0,
        val sellerVolume: Double = 0.0,
        val buyerRatioPct: Double = 0.0,
        val sellerRatioPct: Double = 0.0,
        val bidVolume: Double = 0.0,
        val askVolume: Double = 0.0,
        val orderBookImbalancePct: Double = 0.0,
        val volumeDelta: Double = 0.0,
        val flowScore: Double = 0.0,
        val flowDataSufficient: Boolean = false,
        val buyerPressure: Boolean = false,
        val sellerPressure: Boolean = false,
        val newsSummary: String = "",
        val globalMarketSummary: String = "",
        val existingPosition: Boolean = false,
        val entryPrice: Double = 0.0,
        val highestPrice: Double = 0.0,
        val positionPnlPct: Double = 0.0,
        val minutesInPosition: Long = 0L,

        // Optional richer inputs. Existing call sites do not need to change.
        val recentCloses: List<Double> = emptyList(),
        val recentHighs: List<Double> = emptyList(),
        val recentLows: List<Double> = emptyList(),
        val recentVolumes: List<Double> = emptyList(),
        val spreadPct: Double = 0.0,
        val candleTimeframe: String = "",
        val dataAgeMs: Long = 0L
    )

    private data class ScoreBreakdown(
        val trend: Double,
        val momentum: Double,
        val meanReversion: Double,
        val breakout: Double,
        val volatility: Double,
        val volume: Double,
        val flow: Double,
        val orderBook: Double,
        val marketContext: Double,
        val news: Double,
        val candle: Double,
        val total: Double,
        val confidence: Double,
        val bullishConfirmations: Int,
        val bearishConfirmations: Int,
        val reasons: List<String>
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun evaluate(input: MarketInput): Decision = withContext(Dispatchers.IO) {
        val strategy = Strategy("GPT_MASTER_MIND_STRONG_AZ_V3")

        val local = scoreLocalMarket(input)

        // Hard local validation. AI cannot override invalid data.
        if (!isValidMarket(input)) {
            return@withContext hold(
                strategy,
                "AI WAIT • incomplete/invalid live market data"
            )
        }

        // A stale feed must never become a fresh BUY/SELL.
        if (input.dataAgeMs > STALE_DATA_LIMIT_MS) {
            return@withContext hold(
                strategy,
                "AI WAIT • live market data stale (${input.dataAgeMs / 1000L}s)"
            )
        }

        // Never close an unknown/manual position.
        if (!input.existingPosition && local.total <= -SELL_HARD_BLOCK_SCORE) {
            // Strong bearish analysis with no bot position is still not a SELL.
            // The caller may continue its own entry logic, but this AI layer
            // must not create an invalid exit order.
        }

        // Existing-position exit logic is evaluated locally before the model.
        if (input.existingPosition) {
            val exit = localExitDecision(input, local)
            if (exit != null) {
                return@withContext exit.copy(strategy = strategy)
            }
        }

        // Strong local BUY/SELL gates. They prevent a weak/contradictory model
        // response from overriding hard market evidence.
        val localAction = localAction(local, input)

        if (localAction == Action.SELL && input.existingPosition) {
            return@withContext Decision(
                action = Action.SELL,
                confidence = local.confidence,
                reason = "LOCAL STRONG EXIT • ${local.reasons.take(4).joinToString(" • ")}",
                riskBlocked = false,
                strategy = strategy
            )
        }

        // Optional OpenAI second opinion. If disabled/unconfigured, the local
        // engine remains fully functional and returns its deterministic signal.
        if (!enabledProvider()) {
            return@withContext localDecision(strategy, local, input)
        }

        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) {
            return@withContext localDecision(strategy, local, input)
        }

        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        val model = modelProvider().trim().ifBlank { "gpt-5.6-luna" }

        if (baseUrl.isBlank()) {
            return@withContext localDecision(strategy, local, input)
        }

        val prompt = buildPrompt(input, local)

        try {
            val messages = JSONArray()
                .put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            """
                            You are the final market-analysis second-opinion layer
                            of a professional crypto trading bot.

                            Analyze ONLY supplied facts. Never invent missing values.

                            Return exactly one JSON object:
                            {
                              "action":"BUY|SELL|HOLD",
                              "confidence":0-100,
                              "riskBlocked":true|false,
                              "reason":"short reason"
                            }

                            Decision hierarchy:
                            1. Invalid/stale/conflicting data => HOLD.
                            2. SELL is only for an existing bot-owned position.
                            3. BUY needs multiple independent confirmations:
                               trend + momentum + liquidity/flow + volume/candle context.
                            4. Strong BUY requires strong positive agreement, not one
                               indicator.
                            5. Strong bearish conditions with no existing position
                               must remain HOLD, never an invalid SELL.
                            6. News is a context signal, not a replacement for price,
                               volume, flow or order-book evidence.
                            7. Never claim guaranteed profit.
                            8. Respect local risk/position state.
                            """.trimIndent()
                        )
                )
                .put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                )

            val bodyJson = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.05)
                .put("max_tokens", 240)

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .post(
                    bodyJson.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    Log.w(TAG, "OpenAI HTTP ${response.code}: ${raw.take(500)}")
                    return@withContext localDecision(
                        strategy,
                        local,
                        input,
                        suffix = "AI API unavailable"
                    )
                }

                val content = extractContent(raw)
                if (content.isBlank()) {
                    return@withContext localDecision(
                        strategy,
                        local,
                        input,
                        suffix = "AI returned no decision"
                    )
                }

                val parsed = parseDecision(content, input, local, strategy)
                parsed
            }
        } catch (t: Throwable) {
            Log.w(TAG, "OpenAI Master Mind request failed: ${t.message}")
            localDecision(
                strategy,
                local,
                input,
                suffix = "AI network fallback"
            )
        }
    }

    fun clear() {
        // Stateless. TradingViewModel controls refresh timing.
    }

    private fun scoreLocalMarket(input: MarketInput): ScoreBreakdown {
        var trend = 0.0
        var momentum = 0.0
        var mean = 0.0
        var breakout = 0.0
        var volatility = 0.0
        var volume = 0.0
        var flow = 0.0
        var book = 0.0
        var context = 0.0
        var news = 0.0
        var candle = 0.0

        val reasons = mutableListOf<String>()
        var bull = 0
        var bear = 0

        // ------------------------------------------------------------
        // 1) EMA / TREND: 9, 21, 50, 200
        // ------------------------------------------------------------
        if (input.ema9 > 0 && input.ema21 > 0) {
            when {
                input.ema9 > input.ema21 -> {
                    trend += 12.0
                    bull++
                    reasons += "EMA9>EMA21"
                }
                input.ema9 < input.ema21 -> {
                    trend -= 12.0
                    bear++
                    reasons += "EMA9<EMA21"
                }
            }
        }

        if (input.ema21 > 0 && input.ema50 > 0) {
            when {
                input.ema21 > input.ema50 -> {
                    trend += 12.0
                    bull++
                    reasons += "EMA21>EMA50"
                }
                input.ema21 < input.ema50 -> {
                    trend -= 12.0
                    bear++
                    reasons += "EMA21<EMA50"
                }
            }
        }

        if (input.ema50 > 0 && input.ema200 > 0) {
            when {
                input.ema50 > input.ema200 -> {
                    trend += 16.0
                    bull++
                    reasons += "EMA50>EMA200"
                }
                input.ema50 < input.ema200 -> {
                    trend -= 16.0
                    bear++
                    reasons += "EMA50<EMA200"
                }
            }
        }

        if (input.price > 0 && input.ema200 > 0) {
            when {
                input.price > input.ema200 -> {
                    trend += 10.0
                    bull++
                    reasons += "price>EMA200"
                }
                input.price < input.ema200 -> {
                    trend -= 10.0
                    bear++
                    reasons += "price<EMA200"
                }
            }
        }

        // ------------------------------------------------------------
        // 2) RSI + MACD MOMENTUM
        // ------------------------------------------------------------
        when {
            input.rsi in 45.0..58.0 -> {
                momentum += 3.0
            }
            input.rsi in 58.0..68.0 -> {
                momentum += 10.0
                bull++
                reasons += "RSI bullish"
            }
            input.rsi > 68.0 -> {
                momentum -= 5.0
                bear++
                reasons += "RSI overheated"
            }
            input.rsi in 32.0..45.0 -> {
                momentum -= 8.0
                bear++
                reasons += "RSI weak"
            }
            input.rsi < 32.0 -> {
                // Oversold is bullish only if other confirmations support it.
                momentum += 4.0
                reasons += "RSI oversold"
            }
        }

        val macdDelta = input.macd - input.macdSignal
        when {
            macdDelta > 0.0 -> {
                momentum += 14.0
                bull++
                reasons += "MACD>signal"
            }
            macdDelta < 0.0 -> {
                momentum -= 14.0
                bear++
                reasons += "MACD<signal"
            }
        }

        if (input.macd > 0.0) {
            momentum += 5.0
            bull++
            reasons += "MACD positive"
        } else if (input.macd < 0.0) {
            momentum -= 5.0
            bear++
            reasons += "MACD negative"
        }

        // ------------------------------------------------------------
        // 3) BOLLINGER / MEAN REVERSION
        // ------------------------------------------------------------
        if (input.bollingerUpper > input.bollingerLower &&
            input.bollingerUpper > 0.0 &&
            input.bollingerLower > 0.0
        ) {
            val width = input.bollingerUpper - input.bollingerLower
            val location = ((input.price - input.bollingerLower) / max(width, EPS))
                .coerceIn(0.0, 1.0)

            when {
                location <= 0.08 -> {
                    mean += 9.0
                    reasons += "near lower Bollinger"
                }
                location >= 0.92 -> {
                    mean -= 9.0
                    reasons += "near upper Bollinger"
                }
                location in 0.55..0.90 -> {
                    mean += 3.0
                }
                location in 0.10..0.45 -> {
                    mean -= 3.0
                }
            }

            // A very narrow band can precede expansion, but direction is
            // deliberately taken from trend/momentum instead of guessed.
            val widthPct = width / max(abs(input.price), EPS) * 100.0
            if (widthPct < 1.0) {
                breakout += 4.0
                reasons += "BB compression"
            }
        }

        // ------------------------------------------------------------
        // 4) CANDLE STRUCTURE / BREAKOUT
        // ------------------------------------------------------------
        if (input.recentCloses.size >= 3) {
            val closes = input.recentCloses.filter { it > 0.0 }
            if (closes.size >= 3) {
                val last = closes.last()
                val prev = closes[closes.lastIndex - 1]
                val prev2 = closes[closes.lastIndex - 2]

                if (last > prev && prev > prev2) {
                    candle += 12.0
                    bull++
                    reasons += "higher closes"
                } else if (last < prev && prev < prev2) {
                    candle -= 12.0
                    bear++
                    reasons += "lower closes"
                }

                val lookback = closes.dropLast(1).takeLast(20)
                if (lookback.isNotEmpty()) {
                    val priorHigh = lookback.maxOrNull() ?: last
                    val priorLow = lookback.minOrNull() ?: last
                    if (last > priorHigh) {
                        breakout += 14.0
                        bull++
                        reasons += "breakout above prior high"
                    } else if (last < priorLow) {
                        breakout -= 14.0
                        bear++
                        reasons += "breakdown below prior low"
                    }
                }
            }
        }

        // If OHLC is supplied, inspect the most recent candle's body/wicks.
        if (input.recentCloses.isNotEmpty() &&
            input.recentHighs.size == input.recentCloses.size &&
            input.recentLows.size == input.recentCloses.size
        ) {
            val idx = input.recentCloses.lastIndex
            val c = input.recentCloses[idx]
            val h = input.recentHighs[idx]
            val l = input.recentLows[idx]
            val p = if (idx > 0) input.recentCloses[idx - 1] else c

            val range = max(h - l, EPS)
            val body = abs(c - p)
            val closeLocation = ((c - l) / range).coerceIn(0.0, 1.0)

            if (body / range >= 0.55 && closeLocation >= 0.75) {
                candle += 8.0
                bull++
                reasons += "strong bullish candle"
            } else if (body / range >= 0.55 && closeLocation <= 0.25) {
                candle -= 8.0
                bear++
                reasons += "strong bearish candle"
            }
        }

        // ------------------------------------------------------------
        // 5) VOLUME CONFIRMATION
        // ------------------------------------------------------------
        if (input.recentVolumes.size >= 6) {
            val volumes = input.recentVolumes.filter { it > 0.0 }
            if (volumes.size >= 6) {
                val avg = volumes.dropLast(1).takeLast(20).average()
                val current = volumes.last()
                if (avg > 0.0) {
                    val ratio = current / avg
                    when {
                        ratio >= 1.8 -> {
                            volume += 12.0
                            reasons += "volume surge ${"%.1f".format(Locale.US, ratio)}x"
                        }
                        ratio >= 1.2 -> {
                            volume += 6.0
                        }
                        ratio < 0.7 -> {
                            volume -= 5.0
                            reasons += "low volume"
                        }
                    }
                }
            }
        } else if (input.volume24h > 0.0) {
            // With only 24h volume available, do not pretend it is a
            // relative-volume series. It contributes only a tiny sanity score.
            volume += 1.0
        }

        // ------------------------------------------------------------
        // 6) BUYER VS SELLER FLOW
        // ------------------------------------------------------------
        if (input.flowDataSufficient) {
            val flowNorm = input.flowScore.coerceIn(-100.0, 100.0)
            flow += flowNorm * 0.22

            if (input.buyerPressure) {
                flow += 18.0
                bull++
                reasons += "buyer flow confirmed"
            }
            if (input.sellerPressure) {
                flow -= 18.0
                bear++
                reasons += "seller flow confirmed"
            }

            if (input.buyerRatioPct >= 60.0 &&
                input.buyerVolume > input.sellerVolume
            ) {
                flow += 10.0
                bull++
                reasons += "buyers dominate tape"
            } else if (input.sellerRatioPct >= 60.0 &&
                input.sellerVolume > input.buyerVolume
            ) {
                flow -= 10.0
                bear++
                reasons += "sellers dominate tape"
            }
        }

        // ------------------------------------------------------------
        // 7) ORDER BOOK IMBALANCE + LIQUIDITY
        // ------------------------------------------------------------
        if (input.bidVolume > 0.0 || input.askVolume > 0.0) {
            val imbalance = input.orderBookImbalancePct.coerceIn(-100.0, 100.0)
            book += imbalance * 0.25

            when {
                imbalance >= 20.0 -> {
                    book += 12.0
                    bull++
                    reasons += "bid book dominant"
                }
                imbalance <= -20.0 -> {
                    book -= 12.0
                    bear++
                    reasons += "ask book dominant"
                }
            }
        }

        if (input.spreadPct > 0.0) {
            when {
                input.spreadPct > 1.0 -> {
                    book -= 10.0
                    reasons += "wide spread"
                }
                input.spreadPct > 0.5 -> {
                    book -= 4.0
                }
                input.spreadPct < 0.15 -> {
                    book += 4.0
                }
            }
        }

        // ------------------------------------------------------------
        // 8) ATR / VOLATILITY
        // ------------------------------------------------------------
        if (input.atr > 0.0 && input.price > 0.0) {
            val atrPct = input.atr / input.price * 100.0
            when {
                atrPct > 8.0 -> {
                    volatility -= 8.0
                    reasons += "extreme ATR volatility"
                }
                atrPct > 4.0 -> {
                    volatility -= 3.0
                }
                atrPct in 0.4..3.0 -> {
                    volatility += 4.0
                }
            }
        }

        // ------------------------------------------------------------
        // 9) MARKET CONTEXT / BTC OR GLOBAL DIRECTION
        // ------------------------------------------------------------
        val globalText = input.globalMarketSummary.lowercase(Locale.US)
        context += parseContextBias(globalText).also {
            if (it >= 6.0) {
                bull++
                reasons += "global market bullish"
            } else if (it <= -6.0) {
                bear++
                reasons += "global market bearish"
            }
        }

        // 24h price direction is context only.
        when {
            input.change24hPct >= 5.0 -> {
                context += 7.0
                bull++
                reasons += "strong 24h momentum"
            }
            input.change24hPct >= 1.0 -> context += 3.0
            input.change24hPct <= -5.0 -> {
                context -= 7.0
                bear++
                reasons += "strong 24h weakness"
            }
            input.change24hPct <= -1.0 -> context -= 3.0
        }

        // ------------------------------------------------------------
        // 10) NEWS / SENTIMENT
        // ------------------------------------------------------------
        news += parseNewsBias(input.newsSummary).also {
            when {
                it >= 6.0 -> {
                    bull++
                    reasons += "positive news context"
                }
                it <= -6.0 -> {
                    bear++
                    reasons += "negative news context"
                }
            }
        }

        // Total score is bounded. Weights deliberately avoid making news
        // or a single indicator dominant.
        val total = (
            trend * 1.00 +
            momentum * 1.00 +
            mean * 0.75 +
            breakout * 0.85 +
            volatility * 0.55 +
            volume * 0.80 +
            flow * 1.00 +
            book * 0.90 +
            context * 0.65 +
            news * 0.45 +
            candle * 0.85
        ).coerceIn(-100.0, 100.0)

        val agreement = min(bull, 8) * 2.2 + min(bear, 8) * 0.0
        val contradiction = if (bull > 0 && bear > 0) {
            min(bull, bear) * 3.0
        } else 0.0

        val confidence = (
            50.0 +
            abs(total) * 0.48 +
            agreement -
            contradiction
        ).coerceIn(0.0, 99.0)

        return ScoreBreakdown(
            trend = trend.coerceIn(-100.0, 100.0),
            momentum = momentum.coerceIn(-100.0, 100.0),
            meanReversion = mean.coerceIn(-100.0, 100.0),
            breakout = breakout.coerceIn(-100.0, 100.0),
            volatility = volatility.coerceIn(-100.0, 100.0),
            volume = volume.coerceIn(-100.0, 100.0),
            flow = flow.coerceIn(-100.0, 100.0),
            orderBook = book.coerceIn(-100.0, 100.0),
            marketContext = context.coerceIn(-100.0, 100.0),
            news = news.coerceIn(-100.0, 100.0),
            candle = candle.coerceIn(-100.0, 100.0),
            total = total,
            confidence = confidence,
            bullishConfirmations = bull,
            bearishConfirmations = bear,
            reasons = reasons.distinct()
        )
    }

    private fun localAction(score: ScoreBreakdown, input: MarketInput): Action {
        if (!isValidMarket(input)) return Action.HOLD
        if (input.existingPosition && score.total <= -STRONG_SELL_SCORE) {
            return Action.SELL
        }
        if (!input.existingPosition &&
            score.total >= STRONG_BUY_SCORE &&
            score.bullishConfirmations >= MIN_STRONG_CONFIRMATIONS &&
            !sellerDominates(input)
        ) {
            return Action.BUY
        }
        return Action.HOLD
    }

    private fun localDecision(
        strategy: Strategy,
        score: ScoreBreakdown,
        input: MarketInput,
        suffix: String = ""
    ): Decision {
        val action = when {
            input.existingPosition && score.total <= STRONG_SELL_SCORE_NEGATIVE -> Action.SELL
            !input.existingPosition &&
                score.total >= STRONG_BUY_SCORE &&
                score.bullishConfirmations >= MIN_STRONG_CONFIRMATIONS &&
                !sellerDominates(input) -> Action.BUY
            !input.existingPosition &&
                score.total >= BUY_SCORE &&
                score.bullishConfirmations >= MIN_BUY_CONFIRMATIONS &&
                !sellerDominates(input) -> Action.BUY
            else -> Action.HOLD
        }

        val confidence = when (action) {
            Action.BUY -> max(score.confidence, 62.0).coerceAtMost(96.0)
            Action.SELL -> max(score.confidence, 62.0).coerceAtMost(96.0)
            Action.HOLD -> score.confidence.coerceAtMost(59.0)
        }

        val label = when {
            action == Action.BUY && score.total >= STRONG_BUY_SCORE -> "STRONG BUY"
            action == Action.BUY -> "BUY"
            action == Action.SELL && score.total <= STRONG_SELL_SCORE_NEGATIVE -> "STRONG SELL"
            action == Action.SELL -> "SELL"
            else -> "HOLD"
        }

        val reason = buildString {
            append("LOCAL $label")
            append(" • score ${"%.1f".format(Locale.US, score.total)}")
            append(" • conf ${"%.0f".format(Locale.US, confidence)}%")
            if (score.reasons.isNotEmpty()) {
                append(" • ")
                append(score.reasons.take(5).joinToString(" • "))
            }
            if (suffix.isNotBlank()) append(" • $suffix")
        }

        return Decision(
            action = action,
            confidence = confidence,
            reason = reason.take(900),
            riskBlocked = action == Action.HOLD && score.total < -BUY_SCORE,
            strategy = strategy
        )
    }

    private fun localExitDecision(
        input: MarketInput,
        score: ScoreBreakdown
    ): Decision? {
        if (!input.existingPosition) return null

        // Profit-protection: when a live position is profitable and the
        // multi-factor score turns clearly bearish, request an exit.
        if (input.positionPnlPct >= MIN_PROFIT_EXIT_PCT &&
            score.total <= -PROFIT_EXIT_SCORE
        ) {
            return Decision(
                action = Action.SELL,
                confidence = max(score.confidence, 70.0).coerceAtMost(96.0),
                reason = "PROFIT PROTECTION EXIT • PnL ${"%.2f".format(Locale.US, input.positionPnlPct)}% • " +
                    score.reasons.take(5).joinToString(" • "),
                riskBlocked = false,
                strategy = Strategy("GPT_MASTER_MIND_STRONG_AZ_V3")
            )
        }

        // Trailing-style protection using the highest price supplied by the
        // current bot position tracker. No order is placed here.
        if (input.highestPrice > 0.0 &&
            input.price > 0.0 &&
            input.positionPnlPct > 0.0
        ) {
            val retracePct =
                ((input.highestPrice - input.price) / input.highestPrice) * 100.0
            if (retracePct >= TRAILING_RETRACE_PCT &&
                score.total < 0.0
            ) {
                return Decision(
                    action = Action.SELL,
                    confidence = max(score.confidence, 68.0).coerceAtMost(95.0),
                    reason = "TRAILING EXIT • retrace ${"%.2f".format(Locale.US, retracePct)}% • " +
                        score.reasons.take(4).joinToString(" • "),
                    riskBlocked = false,
                    strategy = Strategy("GPT_MASTER_MIND_STRONG_AZ_V3")
                )
            }
        }

        // Clear bearish reversal for an existing position.
        if (score.total <= STRONG_SELL_SCORE_NEGATIVE &&
            score.bearishConfirmations >= MIN_STRONG_CONFIRMATIONS
        ) {
            return Decision(
                action = Action.SELL,
                confidence = max(score.confidence, 65.0).coerceAtMost(96.0),
                reason = "STRONG REVERSAL EXIT • ${score.reasons.take(6).joinToString(" • ")}",
                riskBlocked = false,
                strategy = Strategy("GPT_MASTER_MIND_STRONG_AZ_V3")
            )
        }

        return null
    }

    private fun parseDecision(
        content: String,
        input: MarketInput,
        local: ScoreBreakdown,
        strategy: Strategy
    ): Decision {
        val jsonText = extractJsonObject(content)

        return try {
            val json = JSONObject(jsonText)
            val requestedAction = when (
                json.optString("action", "HOLD")
                    .trim()
                    .uppercase(Locale.US)
            ) {
                "BUY" -> Action.BUY
                "SELL" -> Action.SELL
                else -> Action.HOLD
            }

            val modelConfidence = json.optDouble("confidence", 0.0)
                .coerceIn(0.0, 100.0)

            val requestedRiskBlocked = json.optBoolean("riskBlocked", false)
            val modelReason = json.optString(
                "reason",
                "GPT Master Mind decision"
            ).trim().take(300)

            // Hard local gates.
            val technicalDataValid = isValidMarket(input)
            val sellWithoutPosition =
                requestedAction == Action.SELL && !input.existingPosition

            val sellerDominant = sellerDominates(input)

            // BUY requires local positive evidence as well as model agreement.
            val localBuyGate =
                local.total >= BUY_SCORE &&
                    local.bullishConfirmations >= MIN_BUY_CONFIRMATIONS &&
                    !sellerDominant

            val strongBuyGate =
                local.total >= STRONG_BUY_SCORE &&
                    local.bullishConfirmations >= MIN_STRONG_CONFIRMATIONS &&
                    !sellerDominant &&
                    local.confidence >= 65.0

            val localSellGate =
                input.existingPosition &&
                    local.total <= STRONG_SELL_SCORE_NEGATIVE &&
                    local.bearishConfirmations >= MIN_STRONG_CONFIRMATIONS

            val finalAction = when {
                !technicalDataValid -> Action.HOLD
                sellWithoutPosition -> Action.HOLD

                requestedAction == Action.BUY &&
                    (strongBuyGate || localBuyGate) &&
                    modelConfidence >= MODEL_BUY_MIN_CONFIDENCE -> Action.BUY

                requestedAction == Action.SELL &&
                    input.existingPosition &&
                    (localSellGate || local.total <= SELL_SCORE) &&
                    modelConfidence >= MODEL_SELL_MIN_CONFIDENCE -> Action.SELL

                // The model may be unavailable/too conservative. A strong
                // deterministic local signal remains usable.
                strongBuyGate &&
                    modelConfidence >= MODEL_SECOND_OPINION_MIN -> Action.BUY

                localSellGate &&
                    modelConfidence >= MODEL_SECOND_OPINION_MIN -> Action.SELL

                else -> Action.HOLD
            }

            val finalConfidence = when (finalAction) {
                Action.BUY -> min(
                    96.0,
                    max(modelConfidence, local.confidence)
                )
                Action.SELL -> min(
                    96.0,
                    max(modelConfidence, local.confidence)
                )
                Action.HOLD -> min(
                    59.0,
                    min(modelConfidence, local.confidence)
                )
            }

            val label = when {
                finalAction == Action.BUY && strongBuyGate -> "STRONG BUY"
                finalAction == Action.BUY -> "BUY"
                finalAction == Action.SELL && local.total <= STRONG_SELL_SCORE_NEGATIVE ->
                    "STRONG SELL"
                finalAction == Action.SELL -> "SELL"
                else -> "HOLD"
            }

            val reason = buildString {
                append("$label • AI ${"%.0f".format(Locale.US, modelConfidence)}%")
                append(" • LOCAL ${"%.1f".format(Locale.US, local.total)}")
                append(" • ")
                append(modelReason)
                if (local.reasons.isNotEmpty()) {
                    append(" • ")
                    append(local.reasons.take(4).joinToString(" • "))
                }
            }

            Decision(
                action = finalAction,
                confidence = finalConfidence,
                reason = reason.take(1000),
                riskBlocked =
                    requestedRiskBlocked ||
                    !technicalDataValid ||
                    sellWithoutPosition ||
                    (finalAction == Action.HOLD && requestedAction == Action.BUY),
                strategy = strategy
            )
        } catch (_: Exception) {
            localDecision(
                strategy,
                local,
                input,
                suffix = "invalid AI JSON"
            )
        }
    }

    private fun buildPrompt(
        input: MarketInput,
        local: ScoreBreakdown
    ): String = buildString {
        appendLine("SYMBOL=${input.symbol}")
        appendLine("MARKET=${input.market}")
        appendLine("PRICE=${input.price}")
        appendLine("CHANGE24H_PCT=${input.change24hPct}")
        appendLine("VOLUME24H=${input.volume24h}")
        appendLine("RSI=${input.rsi}")
        appendLine("MACD=${input.macd}")
        appendLine("MACD_SIGNAL=${input.macdSignal}")
        appendLine("EMA9=${input.ema9}")
        appendLine("EMA21=${input.ema21}")
        appendLine("EMA50=${input.ema50}")
        appendLine("EMA200=${input.ema200}")
        appendLine("BOLLINGER_UPPER=${input.bollingerUpper}")
        appendLine("BOLLINGER_LOWER=${input.bollingerLower}")
        appendLine("ATR=${input.atr}")

        appendLine("BUYER_VOLUME=${input.buyerVolume}")
        appendLine("SELLER_VOLUME=${input.sellerVolume}")
        appendLine("BUYER_RATIO_PCT=${input.buyerRatioPct}")
        appendLine("SELLER_RATIO_PCT=${input.sellerRatioPct}")
        appendLine("BID_VOLUME=${input.bidVolume}")
        appendLine("ASK_VOLUME=${input.askVolume}")
        appendLine("ORDERBOOK_IMBALANCE_PCT=${input.orderBookImbalancePct}")
        appendLine("VOLUME_DELTA=${input.volumeDelta}")
        appendLine("FLOW_SCORE=${input.flowScore}")
        appendLine("FLOW_DATA_SUFFICIENT=${input.flowDataSufficient}")
        appendLine("BUYER_PRESSURE=${input.buyerPressure}")
        appendLine("SELLER_PRESSURE=${input.sellerPressure}")
        appendLine("SPREAD_PCT=${input.spreadPct}")

        appendLine("LOCAL_TREND_SCORE=${local.trend}")
        appendLine("LOCAL_MOMENTUM_SCORE=${local.momentum}")
        appendLine("LOCAL_BOLLINGER_SCORE=${local.meanReversion}")
        appendLine("LOCAL_BREAKOUT_SCORE=${local.breakout}")
        appendLine("LOCAL_VOLATILITY_SCORE=${local.volatility}")
        appendLine("LOCAL_VOLUME_SCORE=${local.volume}")
        appendLine("LOCAL_FLOW_SCORE=${local.flow}")
        appendLine("LOCAL_ORDERBOOK_SCORE=${local.orderBook}")
        appendLine("LOCAL_MARKET_CONTEXT_SCORE=${local.marketContext}")
        appendLine("LOCAL_NEWS_SCORE=${local.news}")
        appendLine("LOCAL_CANDLE_SCORE=${local.candle}")
        appendLine("LOCAL_TOTAL=${local.total}")
        appendLine("LOCAL_CONFIDENCE=${local.confidence}")
        appendLine("BULLISH_CONFIRMATIONS=${local.bullishConfirmations}")
        appendLine("BEARISH_CONFIRMATIONS=${local.bearishConfirmations}")

        appendLine("EXISTING_POSITION=${input.existingPosition}")
        appendLine("ENTRY_PRICE=${input.entryPrice}")
        appendLine("HIGHEST_PRICE=${input.highestPrice}")
        appendLine("POSITION_PNL_PCT=${input.positionPnlPct}")
        appendLine("MINUTES_IN_POSITION=${input.minutesInPosition}")

        appendLine("CANDLE_TIMEFRAME=${input.candleTimeframe}")
        appendLine("DATA_AGE_MS=${input.dataAgeMs}")

        appendLine("NEWS=${input.newsSummary.take(5000)}")
        appendLine("GLOBAL_MARKET=${input.globalMarketSummary.take(5000)}")

        if (input.recentCloses.isNotEmpty()) {
            appendLine("RECENT_CLOSES=${input.recentCloses.takeLast(50)}")
        }
        if (input.recentHighs.isNotEmpty()) {
            appendLine("RECENT_HIGHS=${input.recentHighs.takeLast(50)}")
        }
        if (input.recentLows.isNotEmpty()) {
            appendLine("RECENT_LOWS=${input.recentLows.takeLast(50)}")
        }
        if (input.recentVolumes.isNotEmpty()) {
            appendLine("RECENT_VOLUMES=${input.recentVolumes.takeLast(50)}")
        }
    }

    private fun parseContextBias(text: String): Double {
        if (text.isBlank()) return 0.0

        val bullishWords = listOf(
            "bullish", "risk-on", "strong uptrend", "uptrend",
            "positive", "buyers dominate", "buy pressure"
        )
        val bearishWords = listOf(
            "bearish", "risk-off", "strong downtrend", "downtrend",
            "negative", "sellers dominate", "sell pressure"
        )

        var score = 0.0
        bullishWords.forEach { if (text.contains(it)) score += 3.0 }
        bearishWords.forEach { if (text.contains(it)) score -= 3.0 }
        return score.coerceIn(-12.0, 12.0)
    }

    private fun parseNewsBias(text: String): Double {
        if (text.isBlank()) return 0.0

        val positive = listOf(
            "bullish", "positive", "approval", "adoption",
            "inflow", "growth", "partnership", "launch"
        )
        val negative = listOf(
            "bearish", "negative", "hack", "exploit",
            "outflow", "lawsuit", "liquidation", "ban"
        )

        var score = 0.0
        positive.forEach { if (text.contains(it, ignoreCase = true)) score += 2.0 }
        negative.forEach { if (text.contains(it, ignoreCase = true)) score -= 2.0 }
        return score.coerceIn(-10.0, 10.0)
    }

    private fun sellerDominates(input: MarketInput): Boolean {
        return input.sellerPressure ||
            (input.flowDataSufficient &&
                input.sellerRatioPct >= 60.0 &&
                input.sellerVolume > input.buyerVolume) ||
            input.orderBookImbalancePct <= -35.0
    }

    private fun isValidMarket(input: MarketInput): Boolean {
        val values = listOf(
            input.price,
            input.change24hPct,
            input.volume24h,
            input.rsi,
            input.macd,
            input.macdSignal,
            input.ema9,
            input.ema21,
            input.ema50,
            input.ema200,
            input.bollingerUpper,
            input.bollingerLower,
            input.atr
        )

        return input.price > 0.0 &&
            values.all { it.isFinite() } &&
            input.rsi in 0.0..100.0 &&
            input.atr >= 0.0 &&
            input.bollingerUpper >= input.bollingerLower
    }

    private fun extractContent(raw: String): String {
        return try {
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""

            val first = choices.optJSONObject(0) ?: return ""
            val message = first.optJSONObject("message") ?: return ""

            when {
                message.has("content") -> message.optString("content")
                else -> ""
            }.trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractJsonObject(text: String): String {
        val cleaned = text
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')

        return if (start >= 0 && end > start) {
            cleaned.substring(start, end + 1)
        } else {
            "{}"
        }
    }

    private fun hold(
        strategy: Strategy,
        reason: String
    ): Decision = Decision(
        action = Action.HOLD,
        confidence = 0.0,
        reason = reason,
        riskBlocked = true,
        strategy = strategy
    )

    companion object {
        private const val TAG = "OPENAI_MASTER_MIND_AZ"

        private const val EPS = 1e-12

        // Entry thresholds.
        private const val BUY_SCORE = 30.0
        private const val STRONG_BUY_SCORE = 58.0

        // Exit thresholds.
        private const val SELL_SCORE = -28.0
        private const val STRONG_SELL_SCORE = -58.0
        private const val STRONG_SELL_SCORE_NEGATIVE = -58.0

        private const val MIN_BUY_CONFIRMATIONS = 4
        private const val MIN_STRONG_CONFIRMATIONS = 6

        private const val MODEL_BUY_MIN_CONFIDENCE = 68.0
        private const val MODEL_SELL_MIN_CONFIDENCE = 62.0
        private const val MODEL_SECOND_OPINION_MIN = 60.0

        // Profit protection is deliberately a decision layer only. The
        // existing exchange/router code still performs the real SELL.
        private const val MIN_PROFIT_EXIT_PCT = 0.25
        private const val PROFIT_EXIT_SCORE = 34.0
        private const val TRAILING_RETRACE_PCT = 1.20

        // 90 seconds. Caller may leave dataAgeMs at 0 when it cannot measure
        // age; in that case no stale-data block is applied.
        private const val STALE_DATA_LIMIT_MS = 90_000L

        // Retained as a named constant so future strategy tuning can use it.
        private const val SELL_HARD_BLOCK_SCORE = 70.0
    }
}
