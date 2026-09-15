package com.example.mycompose.hello.data.api

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface CoinDCXApi {
    
    // 1. Live Prices (Bina API key ke bhi chalta hai)
    @GET("exchange/ticker")
    suspend fun getLivePrices(): List<CoinDCXTicker>

    // 2. Account Balance (API Key chahiye)
    @GET("account/v1/balances")
    suspend fun getBalances(
        @Header("X-COINDCX-API-KEY") apiKey: String,
        @Query("payload") payload: String,
        @Query("signature") signature: String
    ): List<CoinDCXBalance>

    // 3. Place Order (Buy/Sell)
    @POST("exchange/v1/orders/create")
    suspend fun placeOrder(
        @Header("X-COINDCX-API-KEY") apiKey: String,
        @Query("payload") payload: String,
        @Query("signature") signature: String
    ): CoinDCXOrderResponse
}

// Data Models
data class CoinDCXTicker(
    val market: String, // e.g., "BTCINR"
    val last_price: String,
    val volume: String
)

data class CoinDCXBalance(
    val currency: String,
    val balance: String,
    val available: String
)

data class CoinDCXOrderResponse(
    val order_id: String,
    val status: String
)