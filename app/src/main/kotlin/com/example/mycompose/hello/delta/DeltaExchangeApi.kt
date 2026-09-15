package com.example.mycompose.hello.delta

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Delta Exchange India REAL order lifecycle adapter.
 *
 * REAL only: no demo/static balance or fake order confirmation.
 *
 * Covers:
 *  - authenticated wallet/balance
 *  - market order creation
 *  - order lookup by id
 *  - open-order snapshot
 *  - cancel one order
 *  - cancel all open orders
 *  - post-create lifecycle synchronization
 *
 * IMPORTANT: bot stop must call cancelAllOrders() if the desired behaviour is
 * "turning the bot off cancels outstanding exchange orders".
 */
class DeltaExchangeApi(
    private val apiKey: String,
    private val apiSecret: String
) {
    companion object {
        private const val TAG = "DELTA_REAL_LIFECYCLE"
        private const val BASE_URL = "https://api.india.delta.exchange"
        private const val WALLET_PATH = "/v2/wallet/balances"
        private const val ORDERS_PATH = "/v2/orders"
        private const val CANCEL_ALL_PATH = "/v2/orders/all"
        private const val JSON_TYPE = "application/json; charset=utf-8"
    }

    data class Holding(
        val asset: String,
        val balance: Double,
        val availableBalance: Double
    )

    data class LiveBalance(
        val total: Double,
        val currency: String,
        val holdings: List<Holding>,
        val raw: String
    )

    data class AuthResult(
        val authenticated: Boolean,
        val tradingPermissionVerified: Boolean,
        val ipAllowed: Boolean,
        val message: String,
        val balance: LiveBalance? = null,
        val httpCode: Int = -1,
        val raw: String = ""
    )

    data class DeltaOrder(
        val id: String,
        val clientOrderId: String,
        val symbol: String,
        val productId: Long,
        val side: String,
        val size: Double,
        val unfilledSize: Double,
        val averageFillPrice: Double?,
        val state: String,
        val raw: String = ""
    ) {
        val isOpen: Boolean
            get() = state.equals("open", true) ||
                state.equals("pending", true)

        val isFilled: Boolean
            get() = state.equals("closed", true)

        val isCancelled: Boolean
            get() = state.equals("cancelled", true)
    }

    data class OrderResult(
        val success: Boolean,
        val orderId: String = "",
        val state: String = "",
        val message: String,
        val order: DeltaOrder? = null,
        val raw: String = "",
        val httpCode: Int = -1
    )

    data class CancelResult(
        val success: Boolean,
        val orderId: String = "",
        val message: String,
        val order: DeltaOrder? = null,
        val raw: String = "",
        val httpCode: Int = -1
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun hmac(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun timestamp(): String =
        (System.currentTimeMillis() / 1000L).toString()

    private fun request(
        method: String,
        path: String,
        queryString: String = "",
        body: String = ""
    ): Pair<Int, String> = runCatching {
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return -2 to "API key/secret missing"
        }

        val ts = timestamp()
        val signature = hmac(
            apiSecret,
            method.uppercase(Locale.US) + ts + path + queryString + body
        )

        val builder = Request.Builder()
            .url(BASE_URL + path + queryString)
            .addHeader("Accept", "application/json")
            .addHeader("api-key", apiKey)
            .addHeader("signature", signature)
            .addHeader("timestamp", ts)
            .addHeader("User-Agent", "NitinProTrading/1.0 Android")

        if (body.isNotEmpty()) {
            builder.addHeader("Content-Type", JSON_TYPE)
            builder.method(
                method.uppercase(Locale.US),
                body.toRequestBody(JSON_TYPE.toMediaType())
            )
        } else {
            builder.method(method.uppercase(Locale.US), null)
        }

        client.newCall(builder.build()).execute().use { response ->
            response.code to (response.body?.string().orEmpty())
        }
    }.getOrElse { e ->
        Log.e(TAG, "HTTP request failed", e)
        -1 to (e.message ?: "network error")
    }

    private fun errorMessage(code: Int, body: String): String {
        val lower = body.lowercase(Locale.US)
        return when {
            lower.contains("ip_blocked") ||
                lower.contains("ip blocked") ||
                lower.contains("ip_not_whitelisted") ||
                lower.contains("whitelist") ->
                "❌ Delta IP whitelist/block rejected the request"

            lower.contains("permission") ||
                lower.contains("unauthorizedapiaccess") ||
                lower.contains("not authorized") ||
                lower.contains("not authorised") ->
                "❌ Delta API trading permission is missing"

            code == 401 || code == 403 ->
                "❌ Delta authentication failed: HTTP $code"

            else ->
                "❌ Delta API failed: HTTP $code"
        }
    }

    suspend fun authenticateAndReadBalance(): AuthResult = withContext(Dispatchers.IO) {
        val (code, body) = request("GET", WALLET_PATH)
        if (code !in 200..299) {
            return@withContext AuthResult(
                authenticated = false,
                tradingPermissionVerified = false,
                ipAllowed = !body.lowercase(Locale.US).contains("ip"),
                message = errorMessage(code, body),
                httpCode = code,
                raw = body
            )
        }

        try {
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) {
                return@withContext AuthResult(
                    authenticated = false,
                    tradingPermissionVerified = false,
                    ipAllowed = true,
                    message = "❌ Delta wallet returned success=false",
                    httpCode = code,
                    raw = body
                )
            }

            val result = root.optJSONArray("result") ?: JSONArray()
            val holdings = mutableListOf<Holding>()
            var total = 0.0
            var currency = ""

            for (i in 0 until result.length()) {
                val item = result.optJSONObject(i) ?: continue
                val asset = item.optString("asset", item.optString("currency", ""))
                val balance = item.optDouble("balance", 0.0)
                val available = item.optDouble(
                    "available_balance",
                    item.optDouble("available", balance)
                )
                if (asset.isNotBlank()) {
                    holdings += Holding(asset, balance, available)
                }
                if (asset.equals("USDT", true) || asset.equals("USD", true)) {
                    total = balance
                    currency = asset
                }
            }

            if (currency.isBlank() && holdings.isNotEmpty()) {
                total = holdings.first().balance
                currency = holdings.first().asset
            }

            AuthResult(
                authenticated = true,
                tradingPermissionVerified = true,
                ipAllowed = true,
                message = "✅ DELTA LIVE authenticated • wallet verified",
                balance = LiveBalance(total, currency, holdings, body),
                httpCode = code,
                raw = body
            )
        } catch (e: Exception) {
            Log.e(TAG, "Wallet parse failed", e)
            AuthResult(
                authenticated = false,
                tradingPermissionVerified = false,
                ipAllowed = true,
                message = "❌ Delta wallet response parse failed",
                httpCode = code,
                raw = body
            )
        }
    }

    suspend fun createMarketOrder(
        productId: Long,
        side: String,
        size: Long,
        clientOrderId: String? = null,
        reduceOnly: Boolean = false
    ): OrderResult = withContext(Dispatchers.IO) {
        if (productId <= 0L) {
            return@withContext OrderResult(false, message = "❌ Invalid Delta product_id")
        }
        if (size <= 0L) {
            return@withContext OrderResult(false, message = "❌ Delta size must be > 0")
        }

        val normalizedSide = side.trim().lowercase(Locale.US)
        if (normalizedSide != "buy" && normalizedSide != "sell") {
            return@withContext OrderResult(false, message = "❌ Delta side must be buy or sell")
        }

        val body = JSONObject().apply {
            put("product_id", productId)
            put("size", size)
            put("order_type", "market_order")
            put("side", normalizedSide)
            if (!clientOrderId.isNullOrBlank()) {
                put("client_order_id", clientOrderId.take(32))
            }
            if (reduceOnly) put("reduce_only", true)
        }.toString()

        val (code, responseBody) = request("POST", ORDERS_PATH, body = body)
        if (code !in 200..299) {
            return@withContext OrderResult(
                success = false,
                message = errorMessage(code, responseBody),
                raw = responseBody,
                httpCode = code
            )
        }

        parseOrderResponse(code, responseBody, "create")
    }

    suspend fun getOrder(orderId: String): OrderResult = withContext(Dispatchers.IO) {
        if (orderId.isBlank()) {
            return@withContext OrderResult(false, message = "❌ Missing Delta order id")
        }

        val query = "?id=${java.net.URLEncoder.encode(orderId, "UTF-8")}"
        val (code, body) = request("GET", ORDERS_PATH, queryString = query)
        if (code !in 200..299) {
            return@withContext OrderResult(
                false,
                orderId = orderId,
                message = errorMessage(code, body),
                raw = body,
                httpCode = code
            )
        }
        parseOrderResponse(code, body, "lookup", orderId)
    }

    suspend fun getOpenOrders(productId: Long? = null): List<DeltaOrder> = withContext(Dispatchers.IO) {
        val query = productId?.let { "?product_ids=$it" } ?: ""
        val (code, body) = request("GET", ORDERS_PATH, queryString = query)
        if (code !in 200..299) {
            Log.e(TAG, "Open orders failed: ${errorMessage(code, body)}")
            return@withContext emptyList()
        }

        try {
            val root = JSONObject(body)
            val result = root.optJSONArray("result") ?: JSONArray()
            buildList {
                for (i in 0 until result.length()) {
                    parseOrder(result.optJSONObject(i), body)?.let { add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Open orders parse failed", e)
            emptyList()
        }
    }

    suspend fun cancelOrder(orderId: String, productId: Long): CancelResult = withContext(Dispatchers.IO) {
        if (orderId.isBlank() || productId <= 0L) {
            return@withContext CancelResult(false, orderId, "❌ Invalid Delta cancel parameters")
        }

        val body = JSONObject().apply {
            put("id", orderId)
            put("product_id", productId)
        }.toString()

        val (code, responseBody) = request("DELETE", ORDERS_PATH, body = body)
        if (code !in 200..299) {
            return@withContext CancelResult(
                false,
                orderId,
                errorMessage(code, responseBody),
                raw = responseBody,
                httpCode = code
            )
        }

        val parsed = runCatching {
            val root = JSONObject(responseBody)
            val success = root.optBoolean("success", false)
            val order = parseOrder(root.optJSONObject("result"), responseBody)
            CancelResult(
                success = success,
                orderId = orderId,
                message = if (success) "✅ DELTA order cancelled" else "❌ Delta cancel not confirmed",
                order = order,
                raw = responseBody,
                httpCode = code
            )
        }.getOrElse {
            CancelResult(false, orderId, "❌ Delta cancel response parse failed", raw = responseBody, httpCode = code)
        }
        parsed
    }

    /** Cancels all open orders. Call this from bot stop if stop means cancel outstanding orders. */
    suspend fun cancelAllOrders(
        productId: Long? = null,
        cancelLimitOrders: Boolean = true,
        cancelStopOrders: Boolean = true,
        cancelReduceOnlyOrders: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            productId?.let { put("product_id", it) }
            put("cancel_limit_orders", cancelLimitOrders)
            put("cancel_stop_orders", cancelStopOrders)
            put("cancel_reduce_only_orders", cancelReduceOnlyOrders)
        }.toString()

        val (code, responseBody) = request("DELETE", CANCEL_ALL_PATH, body = body)
        if (code !in 200..299) {
            Log.e(TAG, "Cancel-all failed: ${errorMessage(code, responseBody)}")
            return@withContext false
        }

        runCatching {
            JSONObject(responseBody).optBoolean("success", false)
        }.getOrDefault(false)
    }

    /**
     * After REST create, keep querying the exchange until it reaches a terminal
     * state or the timeout is reached. This prevents the UI from inventing FILLED.
     */
    suspend fun waitForTerminalState(
        orderId: String,
        timeoutMs: Long = 15_000L,
        pollMs: Long = 500L
    ): OrderResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var last = OrderResult(false, orderId = orderId, message = "Waiting for Delta order state")

        while (System.currentTimeMillis() - started < timeoutMs) {
            last = getOrder(orderId)
            val state = last.state.lowercase(Locale.US)
            if (state == "closed" || state == "cancelled" || state == "rejected") {
                return@withContext last
            }
            delay(pollMs)
        }

        last.copy(
            message = "⚠️ DELTA order still ${last.state.ifBlank { "open/pending" }} — exchange has not reached a terminal state yet"
        )
    }

    private fun parseOrderResponse(
        code: Int,
        body: String,
        operation: String,
        fallbackId: String = ""
    ): OrderResult {
        return try {
            val root = JSONObject(body)
            val success = root.optBoolean("success", false)
            val result = root.optJSONObject("result")
                ?: root.optJSONArray("result")?.optJSONObject(0)
            val order = parseOrder(result, body)
            val id = order?.id.orEmpty().ifBlank { fallbackId }
            val state = order?.state.orEmpty()

            OrderResult(
                success = success && id.isNotBlank(),
                orderId = id,
                state = state,
                message = when {
                    success && state.equals("closed", true) -> "✅ DELTA order FILLED"
                    success && state.equals("cancelled", true) -> "🛑 DELTA order CANCELLED"
                    success -> "🟡 DELTA order ${state.ifBlank { "accepted" }}"
                    else -> "❌ DELTA $operation not confirmed"
                },
                order = order,
                raw = body,
                httpCode = code
            )
        } catch (e: Exception) {
            Log.e(TAG, "Order parse failed", e)
            OrderResult(false, orderId = fallbackId, message = "❌ Delta order response parse failed", raw = body, httpCode = code)
        }
    }

    private fun parseOrder(obj: JSONObject?, raw: String): DeltaOrder? {
        if (obj == null) return null
        val id = obj.optString("id", obj.optString("order_id", ""))
        if (id.isBlank()) return null

        return DeltaOrder(
            id = id,
            clientOrderId = obj.optString("client_order_id", ""),
            symbol = obj.optString("product_symbol", obj.optString("symbol", "")),
            productId = obj.optLong("product_id", 0L),
            side = obj.optString("side", ""),
            size = obj.optDouble("size", 0.0),
            unfilledSize = obj.optDouble("unfilled_size", 0.0),
            averageFillPrice = obj.optString("average_fill_price", "")
                .toDoubleOrNull(),
            state = obj.optString("state", "").lowercase(Locale.US),
            raw = raw
        )
    }
}
