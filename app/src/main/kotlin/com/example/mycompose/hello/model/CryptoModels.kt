package com.example.mycompose.hello.data.model

import com.google.gson.annotations.SerializedName

data class CryptoPrice(
    @SerializedName("id") val id: String,
    @SerializedName("symbol") val symbol: String,
    @SerializedName("name") val name: String,
    @SerializedName("current_price") val currentPrice: Double,
    @SerializedName("price_change_percentage_24h") val priceChangePercentage24h: Double,
    @SerializedName("image") val image: String
)

data class PortfolioItem(
    val cryptoId: String,
    val symbol: String,
    val name: String,
    val quantity: Double,
    val buyPrice: Double,
    val currentPrice: Double = 0.0
) {
    val totalValue: Double get() = quantity * currentPrice
}

data class Trade(
    val id: String,
    val type: String,
    val cryptoId: String,
    val symbol: String,
    val quantity: Double,
    val price: Double,
    val timestamp: Long
)