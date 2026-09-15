package com.example.mycompose.hello.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trades")
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val type: String, // BUY or SELL
    val quantity: Double,
    val price: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val profitLoss: Double = 0.0
)