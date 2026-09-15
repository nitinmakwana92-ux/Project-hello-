package com.example.mycompose.hello.bot

import android.util.Log
import com.example.mycompose.hello.data.api.CoinDCXApi
import com.example.mycompose.hello.utils.CoinDCXSigner
import kotlinx.coroutines.delay

class AlgoBotEngine(
    private val api: CoinDCXApi,
    private val apiKey: String,
    private val secretKey: String,
    private val onLog: (String) -> Unit // UI par log dikhane ke liye
) {
    private var isRunning = false

    fun startBot() {
        isRunning = true
        onLog("🤖 Bot Started! Monitoring BTC/INR...")
        
        // Background loop
        kotlinx.coroutines.GlobalScope.launch {
            while (isRunning) {
                try {
                    runAlgoCycle()
                } catch (e: Exception) {
                    onLog(" Error: ${e.message}")
                }
                // Har 60 seconds mein check karo
                delay(60000) 
            }
        }
    }

    fun stopBot() {
        isRunning = false
        onLog("🛑 Bot Stopped.")
    }

    private suspend fun runAlgoCycle() {
        // 1. Live Price Fetch Karo
        val tickers = api.getLivePrices()
        val btcTicker = tickers.find { it.market == "BTCINR" } ?: return
        
        val currentPrice = btcTicker.last_price.toDouble()
        onLog(" Current BTC Price: ₹$currentPrice")

        // 2. Fake RSI Calculation (Real bot mein pichle 14 candles ka data aayega)
        // Yahan hum example ke liye random RSI le rahe hain. 
        // Real implementation mein tumhe /exchange/v1/candles endpoint call karna hoga.
        val simulatedRSI = Math.random() * 100 
        
        onLog("📊 Simulated RSI: ${String.format("%.2f", simulatedRSI)}")

        // 3. AI Decision
        if (simulatedRSI < 30.0) {
            onLog("🟢 RSI < 30. Sending BUY Signal...")
            placeOrder("buy", currentPrice)
        } else if (simulatedRSI > 70.0) {
            onLog("🔴 RSI > 70. Sending SELL Signal...")
            placeOrder("sell", currentPrice)
        } else {
            onLog(" No Signal. Holding...")
        }
    }

    private suspend fun placeOrder(side: String, price: Double) {
        // Order Payload
        val params = mapOf(
            "side" to side,
            "market" to "BTCINR",
            "total_quantity" to "0.0001", // 0.0001 BTC (Minimum order)
            "price" to price.toString(),
            "order_type" to "limit_order"
        )
        
        val payload = CoinDCXSigner.createPayload(params)
        val signature = CoinDCXSigner.generateSignature(secretKey, payload)

        try {
            val response = api.placeOrder(apiKey, payload, signature)
            onLog("✅ Order Placed! ID: ${response.order_id}")
        } catch (e: Exception) {
            onLog("❌ Order Failed: ${e.message}")
        }
    }
}