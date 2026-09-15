package com.example.mycompose.hello.Ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Official Delta India PUBLIC WebSocket adapter (current public endpoint).
 *
 * Data sources:
 *  - trades: all public fills, including buyer role (taker/maker)
 *  - ob_l2: top 15 bid/ask levels, rotated in <=100-symbol chunks
 *  - candlestick_1m: live OHLC + volume for the currently rotated chunk
 *
 * Delta migrated these public channels to the public-socket endpoint; using
 * the old socket endpoint after the migration can leave the bot with stale
 * market-flow data, which correctly blocks new REAL entries.
 *
 * No buyer/seller values are fabricated. Delta's public trades identify the
 * buyer role: role=t means the buyer was the taker (aggressive BUY); role=m
 * means the buyer was the maker, therefore the seller was the aggressor.
 */
class DeltaIndiaLiveMarketAdapter(
    private val scope: CoroutineScope,
    private val symbolsProvider: () -> List<String>,
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "DELTA_WS_FLOW"
        private const val WS_URL = "wss://public-socket.india.delta.exchange"
        private const val CONNECTION_ID = "delta-india-public-flow"
        private const val MAX_L2_SYMBOLS = 100
        private const val ROTATION_MS = 10_000L
        private const val HEARTBEAT_MS = 25_000L
        private const val MAX_TRADES_PER_SYMBOL = 120
        private const val MAX_CANDLES_PER_SYMBOL = 30
    }

    private val engine = UniversalExchangeLiveMarketDataEngine(
        scope = scope,
        staleAfterMs = 15_000L,
        decisionWindowMs = 1_000L
    )

    private val trades = ConcurrentHashMap<String, ConcurrentLinkedDeque<FlowTrade>>()
    private val candles = ConcurrentHashMap<String, ConcurrentLinkedDeque<FlowCandle>>()
    private val books = ConcurrentHashMap<String, BookState>()
    private val prices = ConcurrentHashMap<String, Double>()
    private val changes = ConcurrentHashMap<String, Double>()

    private var rotationJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastRotatedSymbols: List<String> = emptyList()
    private val directSubscriptionAt = ConcurrentHashMap<String, Long>()
    @Volatile private var activeWebSocket: WebSocket? = null
    @Volatile private var started = false

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            activeWebSocket = webSocket
            onStatus("🟢 Delta public WebSocket connected")
            sendSubscribe(webSocket, "trades", listOf("all"))
            startRotation(webSocket)
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleMessage(bytes.utf8())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (activeWebSocket === webSocket) activeWebSocket = null
            onStatus("🟡 Delta WebSocket reconnecting • $code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            if (activeWebSocket === webSocket) activeWebSocket = null
            Log.w(TAG, "WebSocket failure: ${t.message}")
            onStatus("🟡 Delta WebSocket reconnecting")
        }
    }

    fun start() {
        if (started) return
        started = true
        engine.connect(
            connectionId = CONNECTION_ID,
            url = WS_URL,
            listener = listener,
            initialBackoffMs = 1_000L,
            maxBackoffMs = 30_000L
        )
    }

    fun stop() {
        started = false
        rotationJob?.cancel()
        heartbeatJob?.cancel()
        rotationJob = null
        heartbeatJob = null
        engine.disconnect(CONNECTION_ID)
        lastRotatedSymbols = emptyList()
        directSubscriptionAt.clear()
        activeWebSocket = null
        onStatus("⚪ Delta live market data stopped")
    }

    fun clear() {
        stop()
        engine.clear()
        trades.clear()
        candles.clear()
        books.clear()
        prices.clear()
        changes.clear()
    }

    fun latest(symbol: String): ExchangeFlowSnapshot? {
        return engine.latest(
            GlobalExchangeId.DELTA_INDIA,
            normalizeSymbol(symbol)
        )
    }

    fun isFresh(symbol: String): Boolean =
        engine.isFresh(GlobalExchangeId.DELTA_INDIA, normalizeSymbol(symbol))

    /**
     * Immediately subscribe the currently analysed contract to the official
     * Delta WebSocket L2 + 1m candle channels. The rotating 100-symbol
     * subscription remains unchanged; this only removes the race where the
     * AI selects a coin that is currently outside the rotation chunk.
     * No REST polling is used here.
     */
    fun ensureSymbol(rawSymbol: String) {
        val symbol = normalizeSymbol(rawSymbol)
        if (!started || symbol.isBlank()) return
        val ws = activeWebSocket ?: return
        val now = System.currentTimeMillis()
        val last = directSubscriptionAt[symbol] ?: 0L
        if (now - last < 5_000L) return
        directSubscriptionAt[symbol] = now
        sendSubscribe(ws, "ob_l2", listOf(symbol))
        sendSubscribe(ws, "candlestick_1m", listOf(symbol))
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && started) {
                delay(HEARTBEAT_MS)
                if (!isActive || !started) break
                webSocket.send(JSONObject().put("type", "ping").toString())
            }
        }
    }

    private fun startRotation(webSocket: WebSocket) {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            var cursor = 0
            while (isActive && started) {
                val symbols = symbolsProvider()
                    .mapNotNull { normalizeSymbol(it).takeIf(String::isNotBlank) }
                    .distinct()

                if (symbols.isEmpty()) {
                    delay(2_000L)
                    continue
                }

                val chunk = ArrayList<String>(MAX_L2_SYMBOLS)
                for (i in 0 until minOf(MAX_L2_SYMBOLS, symbols.size)) {
                    chunk += symbols[(cursor + i) % symbols.size]
                }

                if (chunk != lastRotatedSymbols) {
                    if (lastRotatedSymbols.isNotEmpty()) {
                        sendUnsubscribe(webSocket, "ob_l2", lastRotatedSymbols)
                        sendUnsubscribe(webSocket, "candlestick_1m", lastRotatedSymbols)
                    }
                    sendSubscribe(webSocket, "ob_l2", chunk)
                    sendSubscribe(webSocket, "candlestick_1m", chunk)
                    lastRotatedSymbols = chunk
                    onStatus("🟢 Delta WS • trades ALL • L2/candles ${chunk.size} symbols")
                }

                cursor = if (symbols.isEmpty()) 0 else (cursor + chunk.size) % symbols.size
                delay(ROTATION_MS)
            }
        }
    }

    private fun sendSubscribe(webSocket: WebSocket, channel: String, symbols: List<String>) {
        val channelObject = JSONObject().apply {
            put("name", channel)
            put("symbols", JSONArray(symbols))
        }
        val message = JSONObject().apply {
            put("type", "subscribe")
            put("payload", JSONObject().put("channels", JSONArray().put(channelObject)))
        }
        webSocket.send(message.toString())
    }

    private fun sendUnsubscribe(webSocket: WebSocket, channel: String, symbols: List<String>) {
        val channelObject = JSONObject().apply {
            put("name", channel)
            put("symbols", JSONArray(symbols))
        }
        val message = JSONObject().apply {
            put("type", "unsubscribe")
            put("payload", JSONObject().put("channels", JSONArray().put(channelObject)))
        }
        webSocket.send(message.toString())
    }

    private fun handleMessage(text: String) {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (root.optString("type")) {
            "trades" -> handleTrade(root)
            "ob_l2" -> handleBook(root)
            "candlestick_1m" -> handleCandle(root)
            "subscriptions" -> Log.d(TAG, "Subscriptions updated")
            "error" -> Log.w(TAG, "Delta WS error: ${root.optString("msg")}")
            "heartbeat" -> Unit
        }
    }

    private fun handleTrade(root: JSONObject) {
        val symbol = normalizeSymbol(root.optString("sy"))
        if (symbol.isBlank()) return

        val price = root.optString("p").toDoubleOrNull() ?: return
        val quantity = root.optDouble("s", 0.0)
        if (price <= 0.0 || quantity <= 0.0) return

        // Delta's compact trades feed uses buyer role: t=taker, m=maker.
        // If the buyer is the taker, the aggressive side is BUY. If the
        // buyer is maker, the seller is the aggressor. Unknown stays UNKNOWN.
        val takerSide = when (root.optString("r").lowercase(Locale.US)) {
            "t" -> TakerSide.BUY
            "m" -> TakerSide.SELL
            else -> TakerSide.UNKNOWN
        }

        val timestampMs = root.optLong("t", 0L).let {
            if (it > 100_000_000_000L) it / 1_000L else it
        }.takeIf { it > 0L } ?: System.currentTimeMillis()

        val deque = trades.getOrPut(symbol) { ConcurrentLinkedDeque() }
        deque.addLast(FlowTrade(price, quantity, takerSide, timestampMs))
        while (deque.size > MAX_TRADES_PER_SYMBOL) deque.pollFirst()
        prices[symbol] = price
        publish(symbol)
    }

    private fun handleBook(root: JSONObject) {
        val symbol = normalizeSymbol(root.optString("sy"))
        if (symbol.isBlank()) return

        val asks = parseLevels(root.optJSONArray("a"))
        val bids = parseLevels(root.optJSONArray("b"))
        if (asks.isEmpty() && bids.isEmpty()) return

        books[symbol] = BookState(bids = bids, asks = asks)
        publish(symbol)
    }

    private fun handleCandle(root: JSONObject) {
        val symbol = normalizeSymbol(root.optString("sy"))
        if (symbol.isBlank()) return

        val open = root.optDouble("o", 0.0)
        val high = root.optDouble("h", 0.0)
        val low = root.optDouble("l", 0.0)
        val close = root.optDouble("c", 0.0)
        val volume = root.optDouble("v", 0.0)
        if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) return

        val timestampMs = root.optLong("ts", System.currentTimeMillis() * 1_000L) / 1_000L
        val deque = candles.getOrPut(symbol) { ConcurrentLinkedDeque() }
        val candle = FlowCandle(open, high, low, close, volume, timestampMs)
        val last = deque.peekLast()
        if (last?.timestampMs == timestampMs) {
            deque.pollLast()
        }
        deque.addLast(candle)
        while (deque.size > MAX_CANDLES_PER_SYMBOL) deque.pollFirst()
        prices[symbol] = close
        publish(symbol)
    }

    private fun parseLevels(array: JSONArray?): List<FlowLevel> {
        if (array == null) return emptyList()
        val result = ArrayList<FlowLevel>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONArray(i) ?: continue
            val price = row.optString(0).toDoubleOrNull() ?: continue
            val quantity = row.optString(1).toDoubleOrNull() ?: continue
            if (price > 0.0 && quantity > 0.0) result += FlowLevel(price, quantity)
        }
        return result
    }

    private fun publish(symbol: String) {
        val price = prices[symbol] ?: return
        val book = books[symbol]
        val snapshot = ExchangeFlowSnapshot(
            exchangeId = GlobalExchangeId.DELTA_INDIA,
            symbol = symbol,
            lastPrice = price,
            candles = candles[symbol]?.toList().orEmpty(),
            trades = trades[symbol]?.toList().orEmpty(),
            bids = book?.bids.orEmpty(),
            asks = book?.asks.orEmpty(),
            market24hChangePct = changes[symbol] ?: 0.0
        )
        engine.publish(snapshot)
    }

    private fun normalizeSymbol(raw: String): String {
        var s = raw.trim().uppercase(Locale.US)
        if (s.startsWith("B-")) s = s.removePrefix("B-")
        if (s.isBlank()) return ""
        if (s.contains("-")) return s
        s = s.replace("_", "")
        return when {
            s.endsWith("USDT") -> s.removeSuffix("USDT") + "USD"
            s.endsWith("USDC") -> s.removeSuffix("USDC") + "USD"
            s.endsWith("USD") -> s
            else -> s + "USD"
        }
    }

    private data class BookState(
        val bids: List<FlowLevel>,
        val asks: List<FlowLevel>
    )
}
