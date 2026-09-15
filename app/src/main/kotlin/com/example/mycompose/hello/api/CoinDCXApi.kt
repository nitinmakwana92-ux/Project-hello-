package com.example.mycompose.hello.api

import retrofit2.http.GET
import retrofit2.http.Query

interface CoinDCXApi {

    @GET("exchange/ticker")
    suspend fun getLiveMarkets(): List<CoinDCXTicker>

    @GET("market_data/candles")
    suspend fun getCandles(
        @Query("pair") pair: String,
        @Query("interval") interval: String = "1m",
        @Query("limit") limit: Int = 200
    ): List<CoinDCXCandle>
}

data class CoinDCXTicker(
    val market: String = "",
    val volume: String = "0",
    val last_price: String = "0",
    val change_24_hour: String = "0"
)

data class CoinDCXCandle(
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val volume: Double = 0.0,
    val close: Double = 0.0,
    val time: Long = 0L
)