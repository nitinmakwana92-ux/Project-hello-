package com.example.mycompose.hello.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/*
 * ADDITIONAL PUBLIC EXCHANGE MARKET-DATA MODULE
 *
 * This file is intentionally separate from CoinGeckoScreen.kt.
 * It adds public market-data discovery for:
 *   OKX, Bybit, KuCoin, Gate, Bitget, MEXC
 *
 * No API keys are required for these public market-data endpoints.
 *
 * Existing CoinGeckoScreen.kt functions are not replaced or deleted.
 * To merge these rows into the existing list, call:
 *
 *   val extra = fetchAdditionalExchangeCoins()
 *
 * and merge the returned ExchangeCoin rows using the same pattern
 * already used by CoinGeckoScreen.kt.
 */

private fun exRequestText(url: String, timeout: Int = 10_000): String? {
    var c: HttpURLConnection? = null
    return try {
        c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "NitinCryptoApp/1.0 Android")
        if (c.responseCode !in 200..299) null
        else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    } finally {
        c?.disconnect()
    }
}

private fun exDouble(o: JSONObject, key: String): Double =
    o.optString(key, "").toDoubleOrNull() ?: o.optDouble(key, 0.0)

private fun exNorm(s: String): String =
    s.trim().uppercase(Locale.US)

private fun exBaseFromPair(pair: String): Pair<String, String>? {
    val p = pair.trim().uppercase(Locale.US)
    val quotes = listOf("USDT", "USDC", "FDUSD", "BUSD", "USD", "EUR")
    val q = quotes.firstOrNull { p.endsWith(it) } ?: return null
    val base = p.removeSuffix(q).removeSuffix("-").removeSuffix("_")
    if (base.isBlank()) return null
    return exNorm(base) to q
}

private fun exCoin(
    exchange: String,
    symbol: String,
    base: String,
    quote: String,
    price: Double,
    change24h: Double,
    volume: Double
): ExchangeCoin? {
    if (price <= 0.0 || base.isBlank()) return null
    return ExchangeCoin(
        exchange = exchange,
        symbol = symbol,
        baseAsset = base,
        quoteAsset = quote,
        name = base,
        icon = null,
        price = price,
        change24h = change24h,
        volume = volume,
        pair = symbol
    )
}

private suspend fun fetchOkx(): List<ExchangeCoin> = withContext(Dispatchers.IO) {
    try {
        val root = JSONObject(
            exRequestText("https://www.okx.com/api/v5/market/tickers?instType=SPOT")
                ?: return@withContext emptyList()
        )
        val arr = root.optJSONArray("data") ?: return@withContext emptyList()

        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pair = o.optString("instId")
                val bq = exBaseFromPair(pair) ?: continue
                val price = exDouble(o, "last")
                val open = exDouble(o, "open24h")
                val change = if (open > 0) ((price - open) / open) * 100.0 else 0.0
                val volume = exDouble(o, "volCcy24h")
                exCoin("OKX", pair, bq.first, bq.second, price, change, volume)?.let(::add)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private suspend fun fetchBybit(): List<ExchangeCoin> = withContext(Dispatchers.IO) {
    try {
        val root = JSONObject(
            exRequestText("https://api.bybit.com/v5/market/tickers?category=spot")
                ?: return@withContext emptyList()
        )
        val result = root.optJSONObject("result") ?: return@withContext emptyList()
        val arr = result.optJSONArray("list") ?: return@withContext emptyList()

        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pair = o.optString("symbol")
                val bq = exBaseFromPair(pair) ?: continue
                val price = exDouble(o, "lastPrice")
                val change = exDouble(o, "price24hPcnt") * 100.0
                val volume = exDouble(o, "turnover24h")
                exCoin("BYBIT", pair, bq.first, bq.second, price, change, volume)?.let(::add)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private suspend fun fetchKuCoin(): List<ExchangeCoin> = withContext(Dispatchers.IO) {
    try {
        val root = JSONObject(
            exRequestText("https://api.kucoin.com/api/v1/market/allTickers")
                ?: return@withContext emptyList()
        )
        val data = root.optJSONObject("data") ?: return@withContext emptyList()
        val arr = data.optJSONArray("ticker") ?: return@withContext emptyList()

        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pair = o.optString("symbol")
                val bq = exBaseFromPair(pair) ?: continue
                val price = exDouble(o, "last")
                val change = exDouble(o, "changeRate") * 100.0
                val volume = exDouble(o, "volValue")
                exCoin("KUCOIN", pair, bq.first, bq.second, price, change, volume)?.let(::add)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private suspend fun fetchGate(): List<ExchangeCoin> = withContext(Dispatchers.IO) {
    try {
        val arr = JSONArray(
            exRequestText("https://api.gateio.ws/api/v4/spot/tickers")
                ?: return@withContext emptyList()
        )

        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pair = o.optString("currency_pair")
                val bq = exBaseFromPair(pair) ?: continue
                val price = exDouble(o, "last")
                val change = exDouble(o, "change_percentage")
                val volume = exDouble(o, "quote_volume")
                exCoin("GATE", pair, bq.first, bq.second, price, change, volume)?.let(::add)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private suspend fun fetchBitget(): List<ExchangeCoin> = withContext(Dispatchers.IO) {
    try {
        val root = JSONObject(
            exRequestText("https://api.bitget.com/api/v2/spot/market/tickers")
                ?: return@withContext emptyList()
        )
        val arr = root.optJSONArray("data") ?: return@withContext emptyList()

        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pair = o.optString("symbol")
                val bq = exBaseFromPair(pair) ?: continue
                val price = exDouble(o, "lastPr")
                val change = exDouble(o, "change24h") * 100.0
                val volume = exDouble(o, "quoteVolume")
                exCoin("BITGET", pair, bq.first, bq.second, price, change, volume)?.let(::add)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private suspend fun fetchMexc(): List<ExchangeCoin> = withContext(Dispatchers.IO) {
    try {
        val root = JSONArray(
            exRequestText("https://api.mexc.com/api/v3/ticker/24hr")
                ?: return@withContext emptyList()
        )

        buildList {
            for (i in 0 until root.length()) {
                val o = root.optJSONObject(i) ?: continue
                val pair = o.optString("symbol")
                val bq = exBaseFromPair(pair) ?: continue
                val price = exDouble(o, "lastPrice")
                val change = exDouble(o, "priceChangePercent")
                val volume = exDouble(o, "quoteVolume")
                exCoin("MEXC", pair, bq.first, bq.second, price, change, volume)?.let(::add)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Public, no-key market-data aggregator.
 *
 * Exchanges are queried independently so one unavailable exchange
 * cannot break the others.
 */
suspend fun fetchAdditionalExchangeCoins(): List<ExchangeCoin> {
    val result = mutableListOf<ExchangeCoin>()

    try { result += fetchOkx() } catch (_: Exception) {}
    try { result += fetchBybit() } catch (_: Exception) {}
    try { result += fetchKuCoin() } catch (_: Exception) {}
    try { result += fetchGate() } catch (_: Exception) {}
    try { result += fetchBitget() } catch (_: Exception) {}
    try { result += fetchMexc() } catch (_: Exception) {}

    return result
        .filter { coin: ExchangeCoin -> coin.price > 0.0 }
        .distinctBy { coin: ExchangeCoin -> "${coin.exchange}:${coin.symbol}" }
}

