package com.example.mycompose.hello.data.api

import com.example.mycompose.hello.data.model.CryptoPrice
import retrofit2.http.GET
import retrofit2.http.Query

interface CryptoApi {
    @GET("coins/markets")
    suspend fun getCryptoPrices(
        @Query("vs_currency") currency: String = "usd",
        @Query("ids") ids: String? = null, // null रखने से top coins आएंगे
        @Query("order") order: String = "market_cap_desc",
        @Query("per_page") perPage: Int = 50, // अब 50 coins दिखेंगे
        @Query("page") page: Int = 1,
        @Query("sparkline") sparkline: Boolean = false
    ): List<CryptoPrice>
}