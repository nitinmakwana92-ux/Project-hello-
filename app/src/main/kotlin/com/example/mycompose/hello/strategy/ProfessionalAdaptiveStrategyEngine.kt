package com.example.mycompose.hello.Ai

import com.example.mycompose.hello.viewmodel.IndicatorValues
import com.example.mycompose.hello.viewmodel.LiveMarketFlow
import com.example.mycompose.hello.viewmodel.MarketStats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Exchange-neutral adaptive strategy layer.
 *
 * Technical indicators are supplied by the live exchange candle pipeline.
 * Buyer/seller flow is supplied only when the selected exchange exposes
 * authoritative aggressor-side trades and L2 data.
 *
 * No synthetic buyer/seller volume is generated.
 */
object ProfessionalAdaptiveStrategyEngine {

    enum class Profile { DEFENSIVE, BALANCED, MOMENTUM, RECOVERY }
    enum class Action { BUY, SELL, HOLD }

    data class Decision(
        val action: Action,
        val confidence: Double,
        val score: Double,
        val profile: Profile,
        val reason: String,
        val dynamicEntryThreshold: Double,
        val dynamicExitActivationPct: Double,
        val dynamicTrailPct: Double
    )

    fun decide(
        price: Double,
        indicators: IndicatorValues,
        flow: LiveMarketFlow?,
        stats: MarketStats,
        existingPosition: Boolean,
        positionPnlPct: Double = 0.0
    ): Decision {
        if (price <= 0.0 || indicators.ema200 <= 0.0 || indicators.atr <= 0.0) {
            return hold(Profile.DEFENSIVE, 0.0, "insufficient live technical data")
        }

        val profile = profile(stats, positionPnlPct, existingPosition)
        val atrPct = (indicators.atr / price * 100.0).coerceIn(0.01, 25.0)

        // RSI: adaptive around the trend rather than a fixed 30/70-only rule.
        val rsiScore = when {
            indicators.rsi < 25.0 -> 16.0
            indicators.rsi < 35.0 -> 10.0
            indicators.rsi in 45.0..62.0 -> 6.0
            indicators.rsi > 80.0 -> -16.0
            indicators.rsi > 70.0 -> -10.0
            else -> 0.0
        }

        val macdScore = when {
            indicators.macd > indicators.macdSignal && indicators.macdHistogram > 0.0 -> 14.0
            indicators.macd > indicators.macdSignal -> 7.0
            indicators.macd < indicators.macdSignal && indicators.macdHistogram < 0.0 -> -14.0
            else -> -7.0
        }

        val emaScore = when {
            indicators.ema9 > indicators.ema21 && indicators.ema21 > indicators.ema50 && indicators.ema50 > indicators.ema200 -> 18.0
            indicators.ema9 > indicators.ema21 && indicators.ema21 > indicators.ema50 -> 10.0
            indicators.ema9 < indicators.ema21 && indicators.ema21 < indicators.ema50 && indicators.ema50 < indicators.ema200 -> -18.0
            indicators.ema9 < indicators.ema21 && indicators.ema21 < indicators.ema50 -> -10.0
            else -> 0.0
        }

        val bbScore = when {
            indicators.bollingerLower > 0.0 && price <= indicators.bollingerLower -> 10.0
            indicators.bollingerUpper > 0.0 && price >= indicators.bollingerUpper -> -10.0
            indicators.bollingerUpper > indicators.bollingerLower -> {
                val mid = (indicators.bollingerUpper + indicators.bollingerLower) / 2.0
                if (price > mid) 4.0 else -4.0
            }
            else -> 0.0
        }

        // ATR contributes to confidence/risk, not direction by itself.
        val volatilityScore = when {
            atrPct < 0.25 -> -3.0
            atrPct > 8.0 -> -6.0
            else -> 3.0
        }

        var score = rsiScore + macdScore + emaScore + bbScore + volatilityScore
        var flowText = "flow unavailable"

        if (flow != null && flow.updatedAt > 0L) {
            val age = System.currentTimeMillis() - flow.updatedAt
            if (age <= 5_000L) {
                val tradeTotal = flow.buyerVolume + flow.sellerVolume
                val tradePressure = if (tradeTotal > 0.0) {
                    ((flow.buyerVolume - flow.sellerVolume) / tradeTotal * 100.0)
                } else 0.0
                val bookPressure = flow.bookImbalancePct.coerceIn(-100.0, 100.0)
                val combinedFlow = tradePressure * 0.70 + bookPressure * 0.30
                score += (combinedFlow * 0.24).coerceIn(-24.0, 24.0)
                flowText = "real flow B=${"%.2f".format(flow.buyerVolume)} S=${"%.2f".format(flow.sellerVolume)} book=${"%.1f".format(bookPressure)}%"
            } else {
                flowText = "flow stale"
            }
        }

        // The bot learns its aggressiveness from its own closed-trade stats.
        // Poor recent performance raises the entry threshold; strong performance
        // allows the normal threshold to relax slightly, never below a safe floor.
        val threshold = when (profile) {
            Profile.DEFENSIVE -> 32.0
            Profile.RECOVERY -> 34.0
            Profile.BALANCED -> 26.0
            Profile.MOMENTUM -> 22.0
        }

        val confidence = (55.0 + abs(score).coerceIn(0.0, 60.0) * 0.70)
            .coerceIn(0.0, 97.0)

        val action = when {
            existingPosition -> Action.HOLD
            score >= threshold && confidence >= 70.0 -> Action.BUY
            score <= -threshold && confidence >= 70.0 -> Action.SELL
            else -> Action.HOLD
        }

        val activation = max(0.15, atrPct * when (profile) {
            Profile.DEFENSIVE -> 0.90
            Profile.RECOVERY -> 0.80
            Profile.BALANCED -> 0.65
            Profile.MOMENTUM -> 0.55
        })
        val trail = max(0.10, atrPct * when (profile) {
            Profile.DEFENSIVE -> 0.65
            Profile.RECOVERY -> 0.55
            Profile.BALANCED -> 0.45
            Profile.MOMENTUM -> 0.40
        })

        return Decision(
            action = action,
            confidence = confidence,
            score = score.coerceIn(-100.0, 100.0),
            profile = profile,
            reason = "AI ${profile.name} • score=${"%.1f".format(score)} • RSI=${"%.1f".format(indicators.rsi)} • MACD=${"%.5f".format(indicators.macd)} • EMA trend=${if (indicators.ema50 >= indicators.ema200) "UP" else "DOWN"} • $flowText",
            dynamicEntryThreshold = threshold,
            dynamicExitActivationPct = activation,
            dynamicTrailPct = trail
        )
    }

    fun profile(stats: MarketStats, pnlPct: Double, existingPosition: Boolean): Profile {
        if (existingPosition && pnlPct < 0.0) return Profile.RECOVERY
        if (stats.totalTrades >= 8) {
            if (stats.winRate < 45.0 || (stats.profitFactor > 0.0 && stats.profitFactor < 0.90)) return Profile.DEFENSIVE
            if (stats.winRate >= 65.0 && stats.profitFactor >= 1.35) return Profile.MOMENTUM
        }
        return Profile.BALANCED
    }

    private fun hold(profile: Profile, confidence: Double, reason: String) = Decision(
        action = Action.HOLD,
        confidence = confidence,
        score = 0.0,
        profile = profile,
        reason = reason,
        dynamicEntryThreshold = 34.0,
        dynamicExitActivationPct = 0.50,
        dynamicTrailPct = 0.30
    )
}
