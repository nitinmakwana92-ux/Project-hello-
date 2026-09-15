package com.example.mycompose.hello.api

data class CoinDCXBalance(
    val currency: String,
    val balance: Double,
    val locked_balance: Double
)

data class CoinDCXOrderResponse(
    val id: String,
    val status: String,
    val side: String,
    val market: String,
    val price: Double,
    val total_quantity: Double
)