package com.tradingbot

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cloud VPS API for the Hello Android bot.
 *
 * Endpoints:
 *   GET  /health
 *   POST /webhook/tradingview
 *   POST /webhook/signal  (legacy compatibility)
 *
 * Environment variables:
 *   VPS_PORT=8080
 *   VPS_TOKEN=change-this-long-random-token   (optional but recommended)
 *   EXCHANGE_BASE_URL=https://api.binance.com
 *   EXCHANGE_API_KEY=...
 *   EXCHANGE_API_SECRET=...
 *   EXCHANGE_TRADE_QUANTITY=0.001
 *   EXECUTE_ORDERS=false  (default: false; set true only after testing)
 */

@Serializable
data class TradingViewSignal(
    val action: String = "",
    val ticker: String = "",
    val symbol: String = "",
    val quantity: String? = null,
    val strategy: String? = null,
    val confidence: Double? = null,
    val price: Double? = null,
    val timestamp: Long? = null
)

private data class ServerConfig(
    val port: Int = envInt("VPS_PORT", 8080),
    val token: String = System.getenv("VPS_TOKEN").orEmpty().trim(),
    val exchangeBaseUrl: String = System.getenv("EXCHANGE_BASE_URL")
        ?.trim()?.trimEnd('/') ?: "https://api.binance.com",
    val apiKey: String = System.getenv("EXCHANGE_API_KEY").orEmpty().trim(),
    val apiSecret: String = System.getenv("EXCHANGE_API_SECRET").orEmpty().trim(),
    val tradeQuantity: String = System.getenv("EXCHANGE_TRADE_QUANTITY")
        ?.trim().takeUnless { it.isNullOrBlank() } ?: "0.001",
    val executeOrders: Boolean = System.getenv("EXECUTE_ORDERS")
        ?.trim()?.equals("true", true) == true
)

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

fun main() {
    val config = ServerConfig()

    println("====================================================")
    println("Hello Cloud VPS Server")
    println("Listening on 0.0.0.0:${config.port}")
    println("Health endpoint: /health")
    println("TradingView endpoint: /webhook/tradingview")
    println("Legacy endpoint: /webhook/signal")
    println("Token protection: ${if (config.token.isBlank()) "DISABLED" else "ENABLED"}")
    println("Order execution: ${if (config.executeOrders) "ENABLED" else "DISABLED"}")
    println("====================================================")

    embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }

        routing {
            get("/health") {
                if (!authorized(call.request.headers["Authorization"], config.token)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("status" to "unauthorized"))
                    return@get
                }

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "status" to "ok",
                        "service" to "hello-cloud-vps",
                        "timestamp" to System.currentTimeMillis()
                    )
                )
            }

            post("/webhook/tradingview") {
                if (!authorized(call.request.headers["Authorization"], config.token)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("status" to "unauthorized"))
                    return@post
                }
                handleSignal(call.receiveText(), config, call)
            }

            // Compatibility with the user's older server code.
            post("/webhook/signal") {
                if (!authorized(call.request.headers["Authorization"], config.token)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("status" to "unauthorized"))
                    return@post
                }
                handleSignal(call.receiveText(), config, call)
            }
        }
    }.start(wait = true)
}

private suspend fun handleSignal(
    rawJson: String,
    config: ServerConfig,
    call: io.ktor.server.application.ApplicationCall
) {
    try {
        val signal = parseSignal(rawJson)
        val action = signal.action.trim().uppercase()
        val symbol = (signal.symbol.ifBlank { signal.ticker }).trim().uppercase()

        if (action !in setOf("BUY", "SELL", "HOLD")) {
            call.respond(HttpStatusCode.BadRequest, mapOf("status" to "error", "message" to "Invalid action"))
            return
        }
        if (symbol.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("status" to "error", "message" to "Missing symbol"))
            return
        }

        println(
            "Signal received: action=$action symbol=$symbol " +
                "strategy=${signal.strategy.orEmpty()} confidence=${signal.confidence ?: 0.0}"
        )

        if (action == "HOLD") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "accepted", "message" to "HOLD received", "symbol" to symbol))
            return
        }

        if (!config.executeOrders) {
            // Safe default: verify the VPS endpoint and signal path without
            // sending a real exchange order until EXECUTE_ORDERS=true.
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "accepted",
                    "message" to "Signal received; order execution is disabled",
                    "symbol" to symbol,
                    "action" to action
                )
            )
            return
        }

        if (config.apiKey.isBlank() || config.apiSecret.isBlank()) {
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("status" to "error", "message" to "Exchange credentials missing on VPS")
            )
            return
        }

        val quantity = signal.quantity?.takeUnless { it.isBlank() } ?: config.tradeQuantity
        val result = placeBinanceSpotMarketOrder(
            config = config,
            symbol = symbol,
            side = action,
            quantity = quantity
        )

        if (result.first) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "message" to result.second))
        } else {
            call.respond(HttpStatusCode.BadGateway, mapOf("status" to "error", "message" to result.second))
        }
    } catch (e: Exception) {
        call.respond(
            HttpStatusCode.BadRequest,
            mapOf("status" to "error", "message" to (e.message ?: "Invalid request"))
        )
    }
}

private fun parseSignal(raw: String): TradingViewSignal {
    val j = JSONObject(raw)
    return TradingViewSignal(
        action = j.optString("action", ""),
        ticker = j.optString("ticker", ""),
        symbol = j.optString("symbol", ""),
        quantity = if (j.has("quantity")) j.optString("quantity", null) else null,
        strategy = if (j.has("strategy")) j.optString("strategy", null) else null,
        confidence = if (j.has("confidence")) j.optDouble("confidence", 0.0) else null,
        price = if (j.has("price")) j.optDouble("price", 0.0) else null,
        timestamp = if (j.has("timestamp")) j.optLong("timestamp", 0L) else null
    )
}

private fun authorized(header: String?, expectedToken: String): Boolean {
    if (expectedToken.isBlank()) return true
    val value = header?.trim().orEmpty()
    return value.equals("Bearer $expectedToken", ignoreCase = false)
}

private fun placeBinanceSpotMarketOrder(
    config: ServerConfig,
    symbol: String,
    side: String,
    quantity: String
): Pair<Boolean, String> {
    val timestamp = System.currentTimeMillis().toString()
    val query = "symbol=$symbol&side=$side&type=MARKET&quantity=$quantity&timestamp=$timestamp"
    val signature = hmacSha256(query, config.apiSecret)
    val url = "${config.exchangeBaseUrl}/api/v3/order?$query&signature=$signature"

    val request = Request.Builder()
        .url(url)
        .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        .addHeader("X-MBX-APIKEY", config.apiKey)
        .build()

    return try {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                true to "Exchange order accepted: $body"
            } else {
                false to "Exchange rejected order HTTP ${response.code}: $body"
            }
        }
    } catch (e: Exception) {
        false to "Exchange network error: ${e.message}"
    }
}

private fun hmacSha256(data: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun envInt(name: String, default: Int): Int =
    System.getenv(name)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: default
