package com.example.mycompose.hello.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Production-safe Android <-> Cloud VPS bridge.
 *
 * Compatible with:
 *   GET  /health
 *   POST /webhook/tradingview
 *   POST /webhook/signal   (legacy fallback)
 *
 * The VPS must own exchange secrets. Never put exchange API secrets here.
 */
class CloudVpsTradingBridge(private val context: Context) {

    companion object {
        private const val TAG = "CloudVpsBridge"
        private const val PREFS = "hello_cloud_vps"
        private const val LEGACY_PREFS = "trading_bot_prefs"

        private const val KEY_URL = "url"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENABLED = "enabled"

        private const val KEY_LEGACY_SERVER_IP = "server_ip"
        private const val KEY_LEGACY_SERVER_PORT = "server_port"
        private const val DEFAULT_PORT = 8080

        const val DEFAULT_HTTPS_PORT = 443
        const val DEFAULT_HTTP_PORT = 8080
    }

    data class CloudVpsResult(
        val connected: Boolean,
        val code: Int = 0,
        val message: String = "",
        val body: String = ""
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Save a full URL, for example:
     *   https://bot.example.com
     * or, for the Kotlin server supplied with this fix:
     *   http://1.2.3.4:8080
     */
    fun saveConfig(url: String, token: String = "", enabled: Boolean = true) {
        val normalized = normalizeEndpoint(url)
        prefs.edit()
            .putString(KEY_URL, normalized)
            .putString(KEY_TOKEN, token.trim())
            .putBoolean(KEY_ENABLED, enabled && normalized.isNotBlank())
            .apply()
    }

    /** Compatibility helper for a UI that only has an IP field. */
    fun saveServerIp(ipOrUrl: String, port: Int = DEFAULT_PORT, token: String = "", enabled: Boolean = true) {
        val endpoint = normalizeIpOrUrl(ipOrUrl, port)
        saveConfig(endpoint, token, enabled)
        legacyPrefs.edit()
            .putString(KEY_LEGACY_SERVER_IP, ipOrUrl.trim())
            .putInt(KEY_LEGACY_SERVER_PORT, port)
            .apply()
    }

    /** Reads the existing server_ip preference if the older UI is still used. */
    fun migrateLegacyServerIp(token: String = ""): Boolean {
        val old = legacyPrefs.getString(KEY_LEGACY_SERVER_IP, "").orEmpty().trim()
        if (old.isBlank()) return false
        val port = legacyPrefs.getInt(KEY_LEGACY_SERVER_PORT, DEFAULT_PORT)
        saveServerIp(old, port, token, true)
        return true
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun clearConfig() {
        prefs.edit().clear().apply()
    }

    fun configuredUrl(): String = prefs.getString(KEY_URL, "").orEmpty()

    fun configuredToken(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

    fun isConfigured(): Boolean {
        val url = configuredUrl()
        return prefs.getBoolean(KEY_ENABLED, false) && isHttpUrl(url)
    }

    /**
     * Real connection test.
     *
     * /health is tried first. A 404/405 from an old server is not treated as a
     * network failure; the root URL is then probed so the UI can distinguish
     * "server reachable but endpoint wrong" from "server offline".
     */
    suspend fun connect(): CloudVpsResult = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext CloudVpsResult(false, message = "VPS URL not configured")
        }

        val base = configuredUrl()
        val token = configuredToken()

        val health = executeGet("$base/health", token)
        if (health.connected && health.code in 200..299) {
            return@withContext health.copy(message = "CLOUD VPS CONNECTED")
        }

        // Backward-compatible reachability probe. 404 is useful evidence that
        // the server is alive even when /health has not yet been deployed.
        val root = executeGet(base, token)
        if (root.code > 0) {
            val message = if (root.code in 200..499) {
                "VPS REACHABLE • /health endpoint needs deployment"
            } else {
                "VPS SERVER ERROR • HTTP ${root.code}"
            }
            return@withContext root.copy(connected = root.code in 200..499, message = message)
        }

        return@withContext health.copy(
            connected = false,
            message = health.message.ifBlank { "VPS OFFLINE / TIMEOUT" }
        )
    }

    suspend fun health(): Boolean = connect().connected

    suspend fun healthResult(): CloudVpsResult = connect()

    /**
     * Sends the normalized signal used by the Android trading engine.
     * /webhook/signal is retained as a compatibility fallback for the older
     * Ktor server shown in the original project notes.
     */
    suspend fun sendSignal(
        symbol: String,
        action: String,
        strategy: String,
        confidence: Double,
        price: Double,
        timestamp: Long = System.currentTimeMillis(),
        quantity: Double? = null
    ): CloudVpsResult = withContext(Dispatchers.IO) {
        val cleanSymbol = symbol.trim().uppercase()
        val cleanAction = action.trim().uppercase()

        if (!isConfigured()) {
            return@withContext CloudVpsResult(false, message = "VPS URL not configured")
        }
        if (cleanSymbol.isBlank()) {
            return@withContext CloudVpsResult(false, message = "Symbol is blank")
        }
        if (cleanAction !in setOf("BUY", "SELL", "HOLD")) {
            return@withContext CloudVpsResult(false, message = "Invalid action: $cleanAction")
        }
        if (!price.isFinite() || price <= 0.0) {
            return@withContext CloudVpsResult(false, message = "Invalid price")
        }

        val json = JSONObject().apply {
            put("action", cleanAction)
            put("ticker", cleanSymbol)
            put("symbol", cleanSymbol)
            put("strategy", strategy.trim())
            put("confidence", confidence.coerceIn(0.0, 100.0))
            put("price", price)
            put("timestamp", timestamp)
            if (quantity != null && quantity.isFinite() && quantity > 0.0) {
                put("quantity", quantity.toString())
            }
        }.toString()

        val token = configuredToken()
        val primary = executePost(
            "${baseUrl()}/webhook/tradingview",
            json,
            token
        )

        if (primary.connected && primary.code in 200..299) {
            return@withContext primary.copy(message = "SIGNAL ACCEPTED BY VPS")
        }

        // Legacy endpoint used by the older Ktor code.
        val fallback = executePost("${baseUrl()}/webhook/signal", json, token)
        if (fallback.connected && fallback.code in 200..299) {
            return@withContext fallback.copy(message = "SIGNAL ACCEPTED BY VPS (legacy endpoint)")
        }

        val best = if (fallback.code != 0) fallback else primary
        return@withContext best.copy(
            connected = false,
            message = "VPS SIGNAL FAILED • ${best.message.ifBlank { "HTTP ${best.code}" }}"
        )
    }

    /** Convenience aliases used by different UI implementations. */
    suspend fun sendTradingViewSignal(
        symbol: String,
        action: String,
        strategy: String = "TradingView",
        confidence: Double = 100.0,
        price: Double,
        timestamp: Long = System.currentTimeMillis(),
        quantity: Double? = null
    ): CloudVpsResult = sendSignal(symbol, action, strategy, confidence, price, timestamp, quantity)

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }

    private fun baseUrl(): String = configuredUrl().trimEnd('/')

    private fun executeGet(url: String, token: String): CloudVpsResult {
        return try {
            val builder = Request.Builder().url(url).get()
            addAuth(builder, token)
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                CloudVpsResult(
                    connected = true,
                    code = response.code,
                    message = response.message,
                    body = body
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            CloudVpsResult(false, message = e.message.orEmpty())
        }
    }

    private fun executePost(url: String, json: String, token: String): CloudVpsResult {
        return try {
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val builder = Request.Builder().url(url).post(body)
            addAuth(builder, token)
            client.newCall(builder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                CloudVpsResult(
                    connected = true,
                    code = response.code,
                    message = response.message,
                    body = responseBody
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST $url failed: ${e.message}")
            CloudVpsResult(false, message = e.message.orEmpty())
        }
    }

    private fun addAuth(builder: Request.Builder, token: String) {
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        builder.header("Accept", "application/json")
    }

    private fun normalizeEndpoint(raw: String): String {
        val value = raw.trim().trimEnd('/')
        if (value.isBlank()) return ""
        return when {
            value.startsWith("https://", true) -> value
            value.startsWith("http://", true) -> value
            else -> normalizeIpOrUrl(value, DEFAULT_PORT)
        }
    }

    private fun normalizeIpOrUrl(raw: String, port: Int): String {
        var value = raw.trim().trimEnd('/')
        if (value.isBlank()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value

        // If the user supplied host:port, do not append another port.
        val parsed = runCatching { URI("http://$value") }.getOrNull()
        val hasPort = parsed?.port?.let { it > 0 } == true
        if (hasPort) return "http://$value"

        val safePort = port.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        return "http://$value:$safePort"
    }

    private fun isHttpUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }
}
