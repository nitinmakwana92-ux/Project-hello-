package com.example.mycompose.hello.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// ============================================================
// GLOBAL EXCHANGE COIN ENGINE
// CoinDCX + Binance
//
// Purpose:
// - Fetch active exchange markets
// - Extract real coin symbols/names
// - Merge duplicate coins
// - Keep exchange + trading pair information
// - No API key required for public market endpoints
// ============================================================

data class GlobalExchangeCoin(
    val id: String,
    val symbol: String,
    val name: String,

    val exchanges: Set<String> = emptySet(),

    val pairs: Set<String> = emptySet(),

    val coinDcxPairs: Set<String> = emptySet(),

    val binancePairs: Set<String> = emptySet(),

    val coinDcxPairIds: Set<String> = emptySet(),

    val active: Boolean = true
)

object GlobalExchangeCoinEngine {

    // ========================================================
    // ENDPOINTS
    // ========================================================

    private const val COINDCX_MARKETS =
        "https://api.coindcx.com/exchange/v1/markets"

    private const val COINDCX_MARKET_DETAILS =
        "https://api.coindcx.com/exchange/v1/markets_details"

    private const val BINANCE_EXCHANGE_INFO =
        "https://api.binance.com/api/v3/exchangeInfo"

    // ========================================================
    // MEMORY CACHE
    // ========================================================

    @Volatile
    private var globalCoins: List<GlobalExchangeCoin> =
        emptyList()

    @Volatile
    private var lastLoadTime: Long = 0L

    private const val CACHE_TIME_MS =
        5 * 60 * 1000L

    // ========================================================
    // PUBLIC ACCESS
    // ========================================================

    fun getCoins(): List<GlobalExchangeCoin> {
        return globalCoins
    }

    fun getCoin(symbol: String): GlobalExchangeCoin? {

        val normalized =
            normalizeSymbol(symbol)

        return globalCoins.firstOrNull {
            it.symbol == normalized
        }
    }

    fun isLoaded(): Boolean {
        return globalCoins.isNotEmpty()
    }

    // ========================================================
    // MAIN LOADER
    // ========================================================

    suspend fun loadGlobalCoins(
        forceRefresh: Boolean = false
    ): List<GlobalExchangeCoin> =
        withContext(Dispatchers.IO) {

            val now = System.currentTimeMillis()

            if (
                !forceRefresh &&
                globalCoins.isNotEmpty() &&
                now - lastLoadTime < CACHE_TIME_MS
            ) {
                return@withContext globalCoins
            }

            val merged =
                LinkedHashMap<String, MutableGlobalCoin>()

            // ------------------------------------------------
            // COINDCX
            // ------------------------------------------------

            try {

                val details =
                    fetchCoinDcxMarketDetails()

                for (coin in details) {

                    if (!coin.active) {
                        continue
                    }

                    val key =
                        normalizeSymbol(coin.symbol)

                    if (key.isBlank()) {
                        continue
                    }

                    val item =
                        merged.getOrPut(key) {
                            MutableGlobalCoin(
                                symbol = key,
                                name = coin.name.ifBlank {
                                    key
                                }
                            )
                        }

                    item.name =
                        chooseBetterName(
                            item.name,
                            coin.name
                        )

                    item.exchanges.add("CoinDCX")

                    item.pairs.addAll(
                        coin.pairs
                    )

                    item.coinDcxPairs.addAll(
                        coin.coinDcxPairs
                    )

                    item.coinDcxPairIds.addAll(
                        coin.coinDcxPairIds
                    )
                }

            } catch (_: Exception) {
                // CoinDCX failure must not stop Binance.
            }

            // ------------------------------------------------
            // BINANCE
            // ------------------------------------------------

            try {

                val symbols =
                    fetchBinanceSymbols()

                for (coin in symbols) {

                    if (!coin.active) {
                        continue
                    }

                    val key =
                        normalizeSymbol(coin.symbol)

                    if (key.isBlank()) {
                        continue
                    }

                    val item =
                        merged.getOrPut(key) {
                            MutableGlobalCoin(
                                symbol = key,
                                name = coin.name.ifBlank {
                                    key
                                }
                            )
                        }

                    item.name =
                        chooseBetterName(
                            item.name,
                            coin.name
                        )

                    item.exchanges.add("Binance")

                    item.pairs.addAll(
                        coin.pairs
                    )

                    item.binancePairs.addAll(
                        coin.binancePairs
                    )
                }

            } catch (_: Exception) {
                // Binance failure must not stop CoinDCX.
            }

            // ------------------------------------------------
            // FINAL LIST
            // ------------------------------------------------

            val finalList =
                merged.values
                    .map {
                        it.toImmutable()
                    }
                    .sortedWith(
                        compareBy(
                            { it.name.lowercase(Locale.US) },
                            { it.symbol }
                        )
                    )

            if (finalList.isNotEmpty()) {

                globalCoins =
                    finalList

                lastLoadTime =
                    System.currentTimeMillis()
            }

            globalCoins
        }

    // ========================================================
    // COINDCX MARKET DETAILS
    // ========================================================

    private fun fetchCoinDcxMarketDetails():
            List<MutableGlobalCoin> {

        val result =
            ArrayList<MutableGlobalCoin>()

        val response =
            httpGet(COINDCX_MARKET_DETAILS)

        val array =
            JSONArray(response)

        for (i in 0 until array.length()) {

            val obj =
                array.optJSONObject(i)
                    ?: continue

            val status =
                obj.optString(
                    "status",
                    "active"
                )
                    .trim()
                    .lowercase(Locale.US)

            if (status != "active") {
                continue
            }

            val symbol =
                firstNonBlank(
                    obj.optString(
                        "target_currency_short_name"
                    ),
                    obj.optString(
                        "symbol"
                    )
                )

            val name =
                firstNonBlank(
                    obj.optString(
                        "target_currency_name"
                    ),
                    symbol
                )

            val market =
                obj.optString(
                    "symbol"
                )
                    .trim()

            val pair =
                obj.optString(
                    "pair"
                )
                    .trim()

            if (symbol.isBlank()) {
                continue
            }

            val coin =
                MutableGlobalCoin(
                    symbol =
                        normalizeSymbol(symbol),
                    name =
                        name.trim()
                )

            if (market.isNotBlank()) {
                coin.pairs.add(market)
                coin.coinDcxPairs.add(market)
            }

            if (pair.isNotBlank()) {
                coin.coinDcxPairIds.add(pair)
            }

            result.add(coin)
        }

        return result
    }

    // ========================================================
    // COINDCX ACTIVE MARKET LIST
    //
    // Extra verification:
    // /markets returns currently active markets.
    // ========================================================

    private fun fetchCoinDcxActiveMarkets():
            Set<String> {

        return try {

            val response =
                httpGet(COINDCX_MARKETS)

            val array =
                JSONArray(response)

            buildSet {

                for (i in 0 until array.length()) {

                    val market =
                        array.optString(i)
                            .trim()

                    if (market.isNotBlank()) {
                        add(market)
                    }
                }
            }

        } catch (_: Exception) {

            emptySet()
        }
    }

    // ========================================================
    // BINANCE EXCHANGE INFO
    // ========================================================

    private fun fetchBinanceSymbols():
            List<MutableGlobalCoin> {

        val result =
            ArrayList<MutableGlobalCoin>()

        val response =
            httpGet(BINANCE_EXCHANGE_INFO)

        val root =
            JSONObject(response)

        val symbols =
            root.optJSONArray(
                "symbols"
            )
                ?: return result

        for (i in 0 until symbols.length()) {

            val obj =
                symbols.optJSONObject(i)
                    ?: continue

            val status =
                obj.optString(
                    "status"
                )
                    .trim()

            if (
                !status.equals(
                    "TRADING",
                    ignoreCase = true
                )
            ) {
                continue
            }

            val symbol =
                obj.optString(
                    "baseAsset"
                )
                    .trim()

            val quote =
                obj.optString(
                    "quoteAsset"
                )
                    .trim()

            val marketSymbol =
                obj.optString(
                    "symbol"
                )
                    .trim()

            if (
                symbol.isBlank() ||
                marketSymbol.isBlank()
            ) {
                continue
            }

            val coin =
                MutableGlobalCoin(
                    symbol =
                        normalizeSymbol(symbol),
                    name =
                        symbol
                )

            coin.binancePairs.add(
                marketSymbol
            )

            coin.pairs.add(
                marketSymbol
            )

            // Keep quote for possible future filtering.
            if (quote.isNotBlank()) {
                coin.binanceQuotes.add(
                    quote
                )
            }

            result.add(coin)
        }

        return result
    }

    // ========================================================
    // HTTP GET
    // ========================================================

    private fun httpGet(
        endpoint: String
    ): String {

        var connection:
                HttpURLConnection? = null

        try {

            connection =
                URL(endpoint)
                    .openConnection()
                        as HttpURLConnection

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                15_000

            connection.readTimeout =
                20_000

            connection.useCaches =
                false

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "User-Agent",
                "NitinCryptoApp/1.0 Android"
            )

            val code =
                connection.responseCode

            if (
                code !=
                HttpURLConnection.HTTP_OK
            ) {

                throw IllegalStateException(
                    "HTTP $code from $endpoint"
                )
            }

            return connection
                .inputStream
                .bufferedReader()
                .use {
                    it.readText()
                }

        } finally {

            connection?.disconnect()
        }
    }

    // ========================================================
    // SYMBOL NORMALIZATION
    // ========================================================

    private fun normalizeSymbol(
        value: String
    ): String {

        return value
            .substringBefore("/")
            .substringBefore("-")
            .substringBefore("_")
            .trim()
            .uppercase(Locale.US)
    }

    // ========================================================
    // NAME SELECTION
    // ========================================================

    private fun chooseBetterName(
        oldName: String,
        newName: String
    ): String {

        val old =
            oldName.trim()

        val new =
            newName.trim()

        if (old.isBlank()) {
            return new
        }

        if (new.isBlank()) {
            return old
        }

        // Prefer descriptive name over
        // symbol-only name.
        val oldLooksLikeSymbol =
            normalizeSymbol(old) == old.uppercase(
                Locale.US
            )

        val newLooksLikeSymbol =
            normalizeSymbol(new) == new.uppercase(
                Locale.US
            )

        return when {

            oldLooksLikeSymbol &&
                    !newLooksLikeSymbol ->
                new

            else ->
                old
        }
    }

    // ========================================================
    // FIRST NON-BLANK
    // ========================================================

    private fun firstNonBlank(
        vararg values: String
    ): String {

        return values.firstOrNull {
            it.trim().isNotBlank()
        }
            ?.trim()
            ?: ""
    }

    // ========================================================
    // INTERNAL MODEL
    // ========================================================

    private class MutableGlobalCoin(
        val symbol: String,
        var name: String
    ) {

        val exchanges =
            LinkedHashSet<String>()

        val pairs =
            LinkedHashSet<String>()

        val coinDcxPairs =
            LinkedHashSet<String>()

        val binancePairs =
            LinkedHashSet<String>()

        val coinDcxPairIds =
            LinkedHashSet<String>()

        val binanceQuotes =
            LinkedHashSet<String>()

        var active: Boolean = true

        fun toImmutable():
                GlobalExchangeCoin {

            return GlobalExchangeCoin(
                id =
                    symbol.lowercase(
                        Locale.US
                    ),

                symbol =
                    symbol,

                name =
                    name.ifBlank {
                        symbol
                    },

                exchanges =
                    exchanges.toSet(),

                pairs =
                    pairs.toSet(),

                coinDcxPairs =
                    coinDcxPairs.toSet(),

                binancePairs =
                    binancePairs.toSet(),

                coinDcxPairIds =
                    coinDcxPairIds.toSet(),

                active =
                    active
            )
        }
    }
}