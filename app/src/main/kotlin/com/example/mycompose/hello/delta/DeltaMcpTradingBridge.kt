package com.example.mycompose.hello.delta

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * DeltaMcpTradingBridge
 *
 * Android-side bridge for the official Delta Exchange MCP tool surface.
 *
 * IMPORTANT:
 * The official Delta Exchange MCP server is a Python stdio server
 * (delta-exchange-mcp). Android cannot directly launch/consume that stdio
 * server as an MCP client without a separate MCP transport layer.
 *
 * This bridge therefore keeps the existing Android DeltaExchangeApi as the
 * actual exchange transport and exposes MCP-style tool names/arguments:
 *
 *   get_wallet_balances
 *   get_open_orders
 *   get_order
 *   place_market_order
 *   cancel_order
 *   cancel_all_orders
 *
 * It does NOT fake an MCP server and does NOT change the existing BUY/SELL
 * REST signing implementation.
 *
 * Put this file at:
 * app/src/main/kotlin/com/example/mycompose/hello/delta/DeltaMcpTradingBridge.kt
 */
class DeltaMcpTradingBridge(
    private val apiKey: String,
    private val apiSecret: String
) {
    companion object {
        private const val TAG = "DELTA_MCP_BRIDGE"
    }

    private val delta = DeltaExchangeApi(apiKey, apiSecret)

    data class ToolResult(
        val ok: Boolean,
        val tool: String,
        val message: String,
        val data: JSONObject = JSONObject()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("success", ok)
            put("tool", tool)
            put("message", message)
            put("data", data)
        }
    }

    /**
     * MCP-style tool list. Useful if another part of the app wants to expose
     * the available Delta tools to an AI/MCP layer.
     */
    fun listTools(): JSONArray = JSONArray().apply {
        put(tool("get_wallet_balances", false))
        put(tool("get_open_orders", false))
        put(tool("get_order", false))
        put(tool("place_market_order", true))
        put(tool("cancel_order", true))
        put(tool("cancel_all_orders", true))
    }

    private fun tool(name: String, mutating: Boolean): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("mutating", mutating)
            put("exchange", "delta_exchange_india")
        }

    /**
     * Generic MCP-style dispatcher.
     *
     * Example:
     * {
     *   "name":"place_market_order",
     *   "arguments":{
     *      "product_id":27,
     *      "side":"buy",
     *      "size":1,
     *      "reduce_only":false
     *   }
     * }
     */
    suspend fun callTool(requestJson: String): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val request = JSONObject(requestJson)
                val name = request.optString("name").trim()
                val args = request.optJSONObject("arguments") ?: JSONObject()

                when (name) {
                    "get_wallet_balances" -> getWalletBalances()
                    "get_open_orders" -> {
                        val productId =
                            if (args.has("product_id") && !args.isNull("product_id"))
                                args.optLong("product_id")
                            else null
                        getOpenOrders(productId)
                    }

                    "get_order" -> getOrder(
                        args.optString("order_id").trim()
                    )

                    "place_market_order" -> {
                        val productId = args.optLong("product_id", 0L)
                        val side = args.optString("side", "").lowercase(Locale.US)
                        val size = args.optLong("size", 0L)
                        val reduceOnly = args.optBoolean("reduce_only", false)

                        val clientOrderId =
                            if (args.has("client_order_id") &&
                                !args.isNull("client_order_id")
                            ) {
                                args.optString("client_order_id").trim()
                            } else {
                                "mcp-${UUID.randomUUID()}"
                            }

                        placeMarketOrder(
                            productId = productId,
                            side = side,
                            size = size,
                            clientOrderId = clientOrderId,
                            reduceOnly = reduceOnly
                        )
                    }

                    "cancel_order" -> cancelOrder(
                        orderId = args.optString("order_id").trim(),
                        productId = args.optLong("product_id", 0L)
                    )

                    "cancel_all_orders" -> {
                        val productId =
                            if (args.has("product_id") && !args.isNull("product_id"))
                                args.optLong("product_id")
                            else null

                        cancelAllOrders(productId)
                    }

                    else -> ToolResult(
                        ok = false,
                        tool = name,
                        message = "Unknown Delta MCP tool: $name"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "MCP tool dispatch failed", e)
                ToolResult(
                    ok = false,
                    tool = "unknown",
                    message = "Delta MCP request parse/dispatch failed: ${e.message}"
                )
            }
        }

    suspend fun getWalletBalances(): ToolResult {
        val result = delta.authenticateAndReadBalance()

        val data = JSONObject().apply {
            put("authenticated", result.authenticated)
            put("trading_permission_verified", result.tradingPermissionVerified)
            put("ip_allowed", result.ipAllowed)
            put("http_code", result.httpCode)

            result.balance?.let { balance ->
                put("total", balance.total)
                put("currency", balance.currency)

                val holdings = JSONArray()
                balance.holdings.forEach { holding ->
                    holdings.put(
                        JSONObject().apply {
                            put("asset", holding.asset)
                            put("balance", holding.balance)
                            put("available_balance", holding.availableBalance)
                        }
                    )
                }
                put("holdings", holdings)
            }
        }

        return ToolResult(
            ok = result.authenticated,
            tool = "get_wallet_balances",
            message = result.message,
            data = data
        )
    }

    suspend fun getOpenOrders(productId: Long? = null): ToolResult {
        val orders = delta.getOpenOrders(productId)
        val array = JSONArray()

        orders.forEach { order ->
            array.put(orderToJson(order))
        }

        return ToolResult(
            ok = true,
            tool = "get_open_orders",
            message = "Delta open-order snapshot received",
            data = JSONObject().apply {
                put("count", orders.size)
                put("orders", array)
            }
        )
    }

    suspend fun getOrder(orderId: String): ToolResult {
        if (orderId.isBlank()) {
            return ToolResult(
                false,
                "get_order",
                "order_id is required"
            )
        }

        val result = delta.getOrder(orderId)
        val data = JSONObject().apply {
            put("order_id", result.orderId)
            put("state", result.state)
            result.order?.let { put("order", orderToJson(it)) }
            put("http_code", result.httpCode)
        }

        return ToolResult(
            ok = result.success,
            tool = "get_order",
            message = result.message,
            data = data
        )
    }

    suspend fun placeMarketOrder(
        productId: Long,
        side: String,
        size: Long,
        clientOrderId: String? = null,
        reduceOnly: Boolean = false
    ): ToolResult {
        if (productId <= 0L) {
            return ToolResult(
                false,
                "place_market_order",
                "product_id must be > 0"
            )
        }

        if (size <= 0L) {
            return ToolResult(
                false,
                "place_market_order",
                "size must be > 0 contracts"
            )
        }

        if (side != "buy" && side != "sell") {
            return ToolResult(
                false,
                "place_market_order",
                "side must be buy or sell"
            )
        }

        val result = delta.createMarketOrder(
            productId = productId,
            side = side,
            size = size,
            clientOrderId = clientOrderId,
            reduceOnly = reduceOnly
        )

        val data = JSONObject().apply {
            put("order_id", result.orderId)
            put("state", result.state)
            put("http_code", result.httpCode)
            result.order?.let { put("order", orderToJson(it)) }
        }

        return ToolResult(
            ok = result.success,
            tool = "place_market_order",
            message = result.message,
            data = data
        )
    }

    suspend fun cancelOrder(
        orderId: String,
        productId: Long
    ): ToolResult {
        if (orderId.isBlank() || productId <= 0L) {
            return ToolResult(
                false,
                "cancel_order",
                "order_id and product_id are required"
            )
        }

        val result = delta.cancelOrder(orderId, productId)

        val data = JSONObject().apply {
            put("order_id", result.orderId)
            put("http_code", result.httpCode)
            result.order?.let { put("order", orderToJson(it)) }
        }

        return ToolResult(
            ok = result.success,
            tool = "cancel_order",
            message = result.message,
            data = data
        )
    }

    suspend fun cancelAllOrders(
        productId: Long? = null
    ): ToolResult {
        val ok = delta.cancelAllOrders(productId)

        return ToolResult(
            ok = ok,
            tool = "cancel_all_orders",
            message = if (ok) {
                "Delta open orders cancelled"
            } else {
                "Delta cancel-all was not confirmed"
            },
            data = JSONObject().apply {
                if (productId != null) put("product_id", productId)
            }
        )
    }

    /**
     * Creates a market order and then asks Delta for the authoritative state.
     *
     * This is deliberately used instead of assuming that HTTP 200 means FILLED.
     */
    suspend fun placeMarketOrderAndSync(
        productId: Long,
        side: String,
        size: Long,
        clientOrderId: String? = null,
        reduceOnly: Boolean = false
    ): ToolResult {
        val placed = delta.createMarketOrder(
            productId = productId,
            side = side,
            size = size,
            clientOrderId = clientOrderId ?: "mcp-${UUID.randomUUID()}",
            reduceOnly = reduceOnly
        )

        if (!placed.success || placed.orderId.isBlank()) {
            return ToolResult(
                ok = false,
                tool = "place_market_order",
                message = placed.message,
                data = JSONObject().apply {
                    put("order_id", placed.orderId)
                    put("state", placed.state)
                    put("http_code", placed.httpCode)
                }
            )
        }

        val finalState = delta.waitForTerminalState(
            orderId = placed.orderId
        )

        return ToolResult(
            ok = finalState.success,
            tool = "place_market_order",
            message = finalState.message,
            data = JSONObject().apply {
                put("order_id", finalState.orderId)
                put("state", finalState.state)
                put("http_code", finalState.httpCode)
                finalState.order?.let {
                    put("order", orderToJson(it))
                }
            }
        )
    }

    private fun orderToJson(order: DeltaExchangeApi.DeltaOrder): JSONObject =
        JSONObject().apply {
            put("id", order.id)
            put("client_order_id", order.clientOrderId)
            put("symbol", order.symbol)
            put("product_id", order.productId)
            put("side", order.side)
            put("size", order.size)
            put("unfilled_size", order.unfilledSize)
            if (order.averageFillPrice != null) {
                put("average_fill_price", order.averageFillPrice)
            } else {
                put("average_fill_price", JSONObject.NULL)
            }
            put("state", order.state)
            put("is_open", order.isOpen)
            put("is_filled", order.isFilled)
            put("is_cancelled", order.isCancelled)
        }
}
