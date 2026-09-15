package com.example.mycompose.hello.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface DeltaApi {
    @GET("/v2/wallet/balances")
    suspend fun getWalletBalances(
        @Header("api-key") apiKey: String,
        @Header("timestamp") timestamp: String,
        @Header("signature") signature: String
    ): Response<List<DeltaBalance>>

    @GET("/v2/profile")
    suspend fun getProfile(
        @Header("api-key") apiKey: String,
        @Header("timestamp") timestamp: String,
        @Header("signature") signature: String
    ): Response<DeltaProfile>
}

// Models added here to prevent Unresolved Reference errors
data class DeltaBalance(
    val currency: String,
    val balance: Double,
    val available_balance: Double
)

data class DeltaProfile(
    val id: String,
    val email: String,
    val referral_code: String
)