package com.example.mycompose.hello

object Constants {
    
    // ✅ COINGECKO API (Market Data - 500 Coins)
    const val COINGECKO_BASE_URL = "https://api.coingecko.com/api/v3/"
    const val COINGECKO_MARKETS = "coins/markets?vs_currency=usd&order=market_cap_desc&per_page=500&page=1&sparkline=price"
    const val COINGECKO_PRICE = "simple/price?ids=bitcoin,ethereum&vs_currencies=usd"
    
    // ✅ COINDCX API (Indian Exchange - INR Pairs)
    const val COINDCX_BASE_URL = "https://api.coindcx.com/"
    const val COINDCX_TICKER = "exchange/ticker"
    const val COINDCX_MARKETS = "exchange/v1/markets"
    const val COINDCX_ORDER_BOOK = "exchange/v1/order_book"
    const val COINDCX_TRADES = "exchange/v1/trades"
    
    // ✅ BINANCE API (Backup/Alternative)
    const val BINANCE_BASE_URL = "https://api.binance.com/"
    const val BINANCE_TICKER = "api/v3/ticker/price"
    const val BINANCE_KLINES = "api/v3/klines"
    const val BINANCE_DEPTH = "api/v3/depth"
    
    // ✅ TRADINGVIEW CHART URL
    const val TRADINGVIEW_CHART_URL = "https://www.tradingview.com/chart/?symbol="
    const val TRADINGVIEW_WIDGET_JS = "https://s.tradingview.com/tv.js"
    
    // ✅ COIN IMAGE URL (CoinGecko)
    const val COIN_IMAGE_BASE = "https://assets.coingecko.com/coins/images/"
    
    // ✅ COMMODITY PRICES (Static Fallback)
    const val GOLD_PRICE_USD = 2345.50
    const val SILVER_PRICE_USD = 28.15
    const val CRUDE_OIL_PRICE_USD = 78.90
    
    // ✅ APP SETTINGS
    const val APP_NAME = "Pro Crypto Bot"
    const val DEFAULT_BALANCE = 10000.0
    const val BOT_CHECK_INTERVAL_MS = 8000L
    
    // ✅ NOTIFICATION CHANNEL
    const val NOTIFICATION_CHANNEL_ID = "trading_bot_channel"
    const val NOTIFICATION_CHANNEL_NAME = "Trading Bot Alerts"
}