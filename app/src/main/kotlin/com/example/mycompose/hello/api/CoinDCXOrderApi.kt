package com.example.mycompose.hello.api

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface CoinDCXOrderApi {

    @Headers("Content-Type: application/json")
    @POST("exchange/v1/orders/create")
    suspend fun createOrder(
        @Body request: JsonObject
    ): JsonObject

    @Headers("Content-Type: application/json")
    @POST("exchange/v1/orders/cancel")
    suspend fun cancelOrder(
        @Body request: JsonObject
    ): JsonObject
}