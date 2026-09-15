package com.example.mycompose.hello.Ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * UNIVERSAL EXCHANGE LIVE MARKET DATA ENGINE
 *
 * This is the exchange-neutral live-data layer for the bot.
 *
 * It deliberately does NOT invent buyer/seller data. An exchange adapter
 * must normalize the official exchange WebSocket/REST payload into
 * ExchangeFlowSnapshot. If an exchange does not expose aggressor side, the
 * adapter must use TakerSide.UNKNOWN.
 *
 * Architecture:
 * official exchange WS/REST adapter
 *          -> publish(snapshot)
 *          -> latest snapshot + 1-second decision window
 *          -> UniversalBuyerSellerFlowEngine
 *          -> MasterMind/technical strategy
 *
 * The engine supports multiple adapters and automatic reconnect/stale checks.
 * Exchange-specific subscription messages belong in the corresponding
 * adapter, because every exchange has different WebSocket protocols and
 * subscription/rate limits.
 */
class UniversalExchangeLiveMarketDataEngine(
    private val scope: CoroutineScope,
    private val staleAfterMs: Long = 5_000L,
    private val decisionWindowMs: Long = 1_000L
) {

    private val snapshots =
        ConcurrentHashMap<String, ExchangeFlowSnapshot>()

    private val lastUpdate =
        ConcurrentHashMap<String, Long>()

    private val reconnectJobs =
        ConcurrentHashMap<String, Job>()

    private val sockets =
        ConcurrentHashMap<String, WebSocket>()

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Publishes an already-normalized snapshot received from an official
     * exchange adapter.
     */
    fun publish(snapshot: ExchangeFlowSnapshot) {
        val key = key(snapshot.exchangeId, snapshot.symbol)
        snapshots[key] = snapshot
        lastUpdate[key] = System.currentTimeMillis()
    }

    fun latest(
        exchangeId: GlobalExchangeId,
        symbol: String
    ): ExchangeFlowSnapshot? {
        val key = key(exchangeId, symbol)
        val snapshot = snapshots[key] ?: return null
        return if (isFresh(exchangeId, symbol)) snapshot else null
    }

    fun isFresh(
        exchangeId: GlobalExchangeId,
        symbol: String
    ): Boolean {
        val at = lastUpdate[key(exchangeId, symbol)] ?: return false
        return System.currentTimeMillis() - at <= staleAfterMs
    }

    fun analyze(
        exchangeId: GlobalExchangeId,
        symbol: String,
        aiConfidencePct: Double,
        technicalScore: Double
    ): GlobalBuyDecision? {
        val snapshot = latest(exchangeId, symbol) ?: return null
        return UniversalBuyerSellerFlowEngine.decideBuy(
            snapshot = snapshot,
            aiConfidencePct = aiConfidencePct,
            technicalScore = technicalScore
        )
    }

    /**
     * Registers a generic WebSocket connection for an adapter.
     *
     * onOpen/onMessage should convert native messages to
     * ExchangeFlowSnapshot and call publish().
     *
     * reconnect is automatic with bounded exponential backoff.
     */
    fun connect(
        connectionId: String,
        url: String,
        listener: WebSocketListener,
        initialBackoffMs: Long = 1_000L,
        maxBackoffMs: Long = 30_000L
    ) {
        disconnect(connectionId)

        fun connectNow(backoffMs: Long) {
            if (!scope.isActive) return

            val request = Request.Builder()
                .url(url)
                .build()

            val wrapped = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets[connectionId] = webSocket
                    listener.onOpen(webSocket, response)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onMessage(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    listener.onMessage(webSocket, bytes)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosing(webSocket, code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(connectionId)
                    listener.onClosed(webSocket, code, reason)
                    scheduleReconnect(connectionId, url, listener, backoffMs, maxBackoffMs)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    sockets.remove(connectionId)
                    listener.onFailure(webSocket, t, response)
                    scheduleReconnect(
                        connectionId,
                        url,
                        listener,
                        (backoffMs * 2L).coerceAtMost(maxBackoffMs),
                        maxBackoffMs
                    )
                }
            }

            client.newWebSocket(request, wrapped)
        }

        connectNow(initialBackoffMs)
    }

    private fun scheduleReconnect(
        connectionId: String,
        url: String,
        listener: WebSocketListener,
        backoffMs: Long,
        maxBackoffMs: Long
    ) {
        if (!scope.isActive) return

        reconnectJobs[connectionId]?.cancel()
        reconnectJobs[connectionId] = scope.launch(Dispatchers.IO) {
            delay(backoffMs.coerceIn(1_000L, maxBackoffMs))
            if (!isActive) return@launch

            val request = Request.Builder().url(url).build()

            val wrapped = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets[connectionId] = webSocket
                    listener.onOpen(webSocket, response)
                }

                override fun onMessage(webSocket: WebSocket, text: String) =
                    listener.onMessage(webSocket, text)

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) =
                    listener.onMessage(webSocket, bytes)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
                    listener.onClosing(webSocket, code, reason)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(connectionId)
                    listener.onClosed(webSocket, code, reason)
                    scheduleReconnect(
                        connectionId, url, listener,
                        (backoffMs * 2L).coerceAtMost(maxBackoffMs),
                        maxBackoffMs
                    )
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    sockets.remove(connectionId)
                    listener.onFailure(webSocket, t, response)
                    scheduleReconnect(
                        connectionId, url, listener,
                        (backoffMs * 2L).coerceAtMost(maxBackoffMs),
                        maxBackoffMs
                    )
                }
            }

            client.newWebSocket(request, wrapped)
        }
    }

    /**
     * One-second decision-window collector. It never polls REST by itself.
     * Adapters push WebSocket data through publish().
     */
    fun startDecisionTicker(
        onWindow: (List<ExchangeFlowSnapshot>) -> Unit
    ): Job {
        return scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(decisionWindowMs.coerceAtLeast(250L))

                val now = System.currentTimeMillis()
                val fresh = snapshots.values.filter { snapshot ->
                    val at = lastUpdate[key(snapshot.exchangeId, snapshot.symbol)]
                    at != null && now - at <= staleAfterMs
                }

                if (fresh.isNotEmpty()) {
                    onWindow(fresh)
                }
            }
        }
    }

    fun disconnect(connectionId: String) {
        reconnectJobs.remove(connectionId)?.cancel()
        sockets.remove(connectionId)?.close(1000, "engine disconnect")
    }

    fun clear() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()

        sockets.values.forEach {
            runCatching { it.close(1000, "engine clear") }
        }
        sockets.clear()

        snapshots.clear()
        lastUpdate.clear()
    }

    private fun key(
        exchangeId: GlobalExchangeId,
        symbol: String
    ): String =
        "${exchangeId.name}:${symbol.trim().uppercase(Locale.US)}"

    companion object {
        fun logDataQuality(
            snapshot: ExchangeFlowSnapshot
        ) {
            val unknown = snapshot.trades.count {
                it.takerSide == TakerSide.UNKNOWN
            }

            if (unknown > 0) {
                Log.d(
                    "UNIVERSAL_FLOW",
                    "${snapshot.exchangeId}:${snapshot.symbol} " +
                        "contains $unknown trades with UNKNOWN aggressor side"
                )
            }
        }
    }
}
