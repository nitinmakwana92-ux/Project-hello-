package com.example.hello.viewmodel

data class TradingUiState(
    val currentPrice: Double = 0.0,
    val rsi: Double? = null,
    val signal: TradingSignal? = null,
    val isAutoTradingEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class TradingSignal(
    val action: Action,
    val reason: String
)

enum class Action {
    BUY, SELL, HOLD
}