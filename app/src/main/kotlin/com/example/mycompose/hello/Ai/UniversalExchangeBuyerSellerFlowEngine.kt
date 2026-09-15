
package com.example.mycompose.hello.Ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Exchange-agnostic market-flow engine.
 *
 * IMPORTANT:
 * The API key itself is NOT a reliable way to identify an exchange.
 * The credential profile must carry the exchange selected by the user
 * (ExchangeId). The selected adapter then supplies that exchange's own
 * candles, public trades and order-book data.
 *
 * BUY is allowed only after buyer/seller pressure is independently
 * calculated from trade tape + order book + candle volume and then
 * combined with the technical/AI score.
 */
enum class GlobalExchangeId {
    COINDCX,
    DELTA_INDIA,
    BINANCE,
    BYBIT,
    OKX,
    BITGET,
    MEXC,
    KUCOIN,
    GATE_IO,
    HTX,
    KRAKEN,
    CRYPTO_COM,
    BITFINEX,
    WAZIRX,
    GIOTTUS,
    COINBASE,
    ZEBPAY,
    COINSWITCH,
    OTHER
}

data class ExchangeCredentialProfile(
    val exchangeId: GlobalExchangeId,
    val apiKey: String,
    val apiSecret: String,
    val passphrase: String = "",
    val accountLabel: String = ""
)

/**
 * Normalized trade from the currently selected exchange.
 *
 * takerSide:
 *   BUY  = aggressive buyer lifted the ask
 *   SELL = aggressive seller hit the bid
 */
data class FlowTrade(
    val price: Double,
    val quantity: Double,
    val takerSide: TakerSide,
    val timestampMs: Long
)

enum class TakerSide { BUY, SELL, UNKNOWN }

data class FlowLevel(
    val price: Double,
    val quantity: Double
)

data class FlowCandle(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val timestampMs: Long
)

data class ExchangeFlowSnapshot(
    val exchangeId: GlobalExchangeId,
    val symbol: String,
    val lastPrice: Double,
    val candles: List<FlowCandle>,
    val trades: List<FlowTrade>,
    val bids: List<FlowLevel>,
    val asks: List<FlowLevel>,
    val market24hChangePct: Double = 0.0
)

data class BuyerSellerFlowResult(
    val exchangeId: GlobalExchangeId,
    val symbol: String,
    val buyerVolume: Double,
    val sellerVolume: Double,
    val unknownVolume: Double,
    val buyerRatioPct: Double,
    val sellerRatioPct: Double,
    val bidVolume: Double,
    val askVolume: Double,
    val orderBookImbalancePct: Double,
    val candleBuyVolumeEstimate: Double,
    val candleSellVolumeEstimate: Double,
    val flowScore: Double,
    val buyerPressure: Boolean,
    val sellerPressure: Boolean,
    val dataSufficient: Boolean,
    val reason: String
)

data class GlobalBuyDecision(
    val allowed: Boolean,
    val score: Double,
    val reason: String,
    val flow: BuyerSellerFlowResult
)

interface ExchangeMarketFlowProvider {
    suspend fun snapshot(
        credential: ExchangeCredentialProfile,
        symbol: String,
        limit: Int = 100
    ): ExchangeFlowSnapshot?
}

/**
 * Adapter contract for every exchange.
 *
 * Each adapter converts that exchange's native REST/WebSocket data into
 * ExchangeFlowSnapshot. The flow algorithm itself never contains
 * CoinDCX/Delta/Binance-specific calculations.
 */
interface GlobalExchangeMarketAdapter : ExchangeMarketFlowProvider {
    val exchangeId: GlobalExchangeId
}

object UniversalBuyerSellerFlowEngine {

    /**
     * Calculates aggressive buyer/seller volume from the exchange's
     * normalized public trade tape.
     *
     * This is preferable to pretending that candle volume alone can tell
     * the exact buyer/seller split.
     */
    fun analyze(snapshot: ExchangeFlowSnapshot): BuyerSellerFlowResult {
        val trades = snapshot.trades.filter {
            it.quantity > 0.0 && it.price > 0.0
        }

        val buyer = trades
            .filter { it.takerSide == TakerSide.BUY }
            .sumOf { it.price * it.quantity }

        val seller = trades
            .filter { it.takerSide == TakerSide.SELL }
            .sumOf { it.price * it.quantity }

        val unknown = trades
            .filter { it.takerSide == TakerSide.UNKNOWN }
            .sumOf { it.price * it.quantity }

        val knownTotal = buyer + seller
        val buyerRatio = if (knownTotal > 0.0) buyer / knownTotal * 100.0 else 0.0
        val sellerRatio = if (knownTotal > 0.0) seller / knownTotal * 100.0 else 0.0

        // Use a bounded top-of-book/depth window supplied by the adapter.
        val bidVolume = snapshot.bids
            .filter { it.price > 0.0 && it.quantity > 0.0 }
            .sumOf { it.quantity }

        val askVolume = snapshot.asks
            .filter { it.price > 0.0 && it.quantity > 0.0 }
            .sumOf { it.quantity }

        val bookTotal = bidVolume + askVolume
        val bookImbalance = if (bookTotal > 0.0) {
            ((bidVolume - askVolume) / bookTotal) * 100.0
        } else 0.0

        // Candle volume is only a secondary directional estimate.
        // It is NOT treated as exact buyer/seller volume.
        val recentCandles = snapshot.candles.takeLast(20)
        var candleBuy = 0.0
        var candleSell = 0.0

        recentCandles.forEach { c ->
            if (c.volume <= 0.0) return@forEach

            val range = max(c.high - c.low, 1e-12)
            val closeLocation = ((c.close - c.low) / range).coerceIn(0.0, 1.0)

            // Weight volume by where the candle closed.
            candleBuy += c.volume * closeLocation
            candleSell += c.volume * (1.0 - closeLocation)
        }

        val candleTotal = candleBuy + candleSell
        val candleBuyerRatio = if (candleTotal > 0.0) {
            candleBuy / candleTotal * 100.0
        } else 50.0

        /*
         * Flow score:
         *   60% trade-tape aggression
         *   25% order-book imbalance
         *   15% candle-volume direction
         *
         * The trade tape gets the highest weight because it directly
         * represents executed transactions.
         */
        val tradePressure = if (knownTotal > 0.0) {
            (buyerRatio - sellerRatio).coerceIn(-100.0, 100.0)
        } else 0.0

        val bookPressure = bookImbalance.coerceIn(-100.0, 100.0)
        val candlePressure = ((candleBuyerRatio - 50.0) * 2.0)
            .coerceIn(-100.0, 100.0)

        val score = (
            tradePressure * 0.60 +
            bookPressure * 0.25 +
            candlePressure * 0.15
        ).coerceIn(-100.0, 100.0)

        val sufficient =
            trades.size >= 20 &&
            knownTotal > 0.0 &&
            snapshot.lastPrice > 0.0

        val buyerPressure =
            sufficient &&
            buyerRatio >= 55.0 &&
            buyer > seller &&
            bookImbalance >= -10.0 &&
            score >= 12.0

        val sellerPressure =
            sufficient &&
            sellerRatio >= 55.0 &&
            seller > buyer &&
            bookImbalance <= 10.0 &&
            score <= -12.0

        val reason = when {
            !sufficient ->
                "FLOW WAIT • insufficient exchange trade data"
            buyerPressure ->
                "BUYER FLOW CONFIRMED • aggressive buyers ${"%.1f".format(buyerRatio)}% • book ${"%.1f".format(bookImbalance)}%"
            sellerPressure ->
                "SELLER PRESSURE • aggressive sellers ${"%.1f".format(sellerRatio)}% • book ${"%.1f".format(bookImbalance)}%"
            else ->
                "FLOW MIXED • buyers ${"%.1f".format(buyerRatio)}% • sellers ${"%.1f".format(sellerRatio)}% • book ${"%.1f".format(bookImbalance)}%"
        }

        return BuyerSellerFlowResult(
            exchangeId = snapshot.exchangeId,
            symbol = snapshot.symbol,
            buyerVolume = buyer,
            sellerVolume = seller,
            unknownVolume = unknown,
            buyerRatioPct = buyerRatio,
            sellerRatioPct = sellerRatio,
            bidVolume = bidVolume,
            askVolume = askVolume,
            orderBookImbalancePct = bookImbalance,
            candleBuyVolumeEstimate = candleBuy,
            candleSellVolumeEstimate = candleSell,
            flowScore = score,
            buyerPressure = buyerPressure,
            sellerPressure = sellerPressure,
            dataSufficient = sufficient,
            reason = reason
        )
    }

    /**
     * Final BUY gate. AI/technical confidence can contribute, but it cannot
     * override missing/negative order-flow evidence.
     */
    fun decideBuy(
        snapshot: ExchangeFlowSnapshot,
        aiConfidencePct: Double,
        technicalScore: Double
    ): GlobalBuyDecision {
        val flow = analyze(snapshot)

        if (!flow.dataSufficient) {
            return GlobalBuyDecision(
                allowed = false,
                score = flow.flowScore,
                reason = flow.reason,
                flow = flow
            )
        }

        if (flow.sellerPressure) {
            return GlobalBuyDecision(
                allowed = false,
                score = flow.flowScore,
                reason = "BUY BLOCKED • seller pressure dominates",
                flow = flow
            )
        }

        if (!flow.buyerPressure) {
            return GlobalBuyDecision(
                allowed = false,
                score = flow.flowScore,
                reason = "BUY WAIT • buyer volume/flow not sufficiently confirmed",
                flow = flow
            )
        }

        // AI + technical score are confirmation layers, not replacements
        // for real exchange flow.
        val combined = (
            flow.flowScore * 0.50 +
            technicalScore.coerceIn(-100.0, 100.0) * 0.25 +
            ((aiConfidencePct.coerceIn(0.0, 100.0) - 50.0) * 2.0) * 0.25
        ).coerceIn(-100.0, 100.0)

        val allowed = combined >= 18.0

        return GlobalBuyDecision(
            allowed = allowed,
            score = combined,
            reason = if (allowed) {
                "BUY APPROVED • ${flow.exchangeId} • buyer flow + book + AI/technical confirmed"
            } else {
                "BUY WAIT • flow positive but AI/technical confirmation weak"
            },
            flow = flow
        )
    }

    /**
     * This is the context sent to the AI model. It explicitly tells the AI
     * which exchange supplied the data, so it never assumes CoinDCX.
     */
    fun aiContext(
        credential: ExchangeCredentialProfile,
        flow: BuyerSellerFlowResult
    ): String {
        return buildString {
            append("exchange=${credential.exchangeId.name}\n")
            append("symbol=${flow.symbol}\n")
            append("buyerVolume=${flow.buyerVolume}\n")
            append("sellerVolume=${flow.sellerVolume}\n")
            append("buyerRatioPct=${flow.buyerRatioPct}\n")
            append("sellerRatioPct=${flow.sellerRatioPct}\n")
            append("bidVolume=${flow.bidVolume}\n")
            append("askVolume=${flow.askVolume}\n")
            append("orderBookImbalancePct=${flow.orderBookImbalancePct}\n")
            append("candleBuyVolumeEstimate=${flow.candleBuyVolumeEstimate}\n")
            append("candleSellVolumeEstimate=${flow.candleSellVolumeEstimate}\n")
            append("flowScore=${flow.flowScore}\n")
            append("buyerPressure=${flow.buyerPressure}\n")
            append("sellerPressure=${flow.sellerPressure}\n")
            append("dataSufficient=${flow.dataSufficient}\n")
            append("rule=Do not BUY when seller pressure dominates or flow data is insufficient.\n")
        }
    }
}

/**
 * Persist this profile together with the API credentials.
 *
 * Do NOT attempt to identify an exchange from the API-key string itself.
 * Instead, Account API UI stores the selected exchangeId alongside the key.
 */
object GlobalExchangeIdentity {

    fun normalize(
        selectedExchange: String,
        apiKey: String,
        apiSecret: String,
        passphrase: String = ""
    ): ExchangeCredentialProfile {
        val id = when (selectedExchange.trim().lowercase()) {
            "coindcx" -> GlobalExchangeId.COINDCX
            "delta", "deltaindia", "delta india" -> GlobalExchangeId.DELTA_INDIA
            "binance" -> GlobalExchangeId.BINANCE
            "bybit" -> GlobalExchangeId.BYBIT
            "okx" -> GlobalExchangeId.OKX
            "bitget" -> GlobalExchangeId.BITGET
            "mexc" -> GlobalExchangeId.MEXC
            "kucoin" -> GlobalExchangeId.KUCOIN
            "gate", "gate.io", "gateio" -> GlobalExchangeId.GATE_IO
            "htx", "huobi" -> GlobalExchangeId.HTX
            "kraken" -> GlobalExchangeId.KRAKEN
            "crypto.com", "cryptocom" -> GlobalExchangeId.CRYPTO_COM
            "bitfinex" -> GlobalExchangeId.BITFINEX
            "wazirx" -> GlobalExchangeId.WAZIRX
            "giottus" -> GlobalExchangeId.GIOTTUS
            "coinbase" -> GlobalExchangeId.COINBASE
            "zebpay" -> GlobalExchangeId.ZEBPAY
            "coinswitch" -> GlobalExchangeId.COINSWITCH
            else -> GlobalExchangeId.OTHER
        }

        return ExchangeCredentialProfile(
            exchangeId = id,
            apiKey = apiKey.trim(),
            apiSecret = apiSecret.trim(),
            passphrase = passphrase.trim()
        )
    }
}
