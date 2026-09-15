package com.example.mycompose.hello.engine

import android.content.Context
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object CoinIconEngine {

    private const val MARKETS_URL =
        "https://api.coingecko.com/api/v3/coins/markets" +
            "?vs_currency=usd" +
            "&order=market_cap_desc" +
            "&per_page=1000" +
            "&page=1" +
            "&page=2" +
            "&page=3" +
            "&page=4" +
            "&page=5" +
            "&page=6" +
            "&page=7" +
            "&page=8" +
            "&page=9" +
            "&page=10" +
            "&page=11" +
            "&page=12" +
            "&page=13" +
            "&page=14" +
            "&sparkline=price"

    private val iconById =
        mutableMapOf<String, String>()

    private val iconBySymbol =
        mutableMapOf<String, String>()

    private val idBySymbol =
        mutableMapOf<String, String>()

    private val lock = Any()

    private var initialized = false

    /**
     * Load original CoinGecko image URLs.
     *
     * IMPORTANT:
     * The image URL is taken from CoinGecko's actual
     * API response. We do NOT construct fake image URLs.
     */
    suspend fun preload(
        forceRefresh: Boolean = false
    ) = withContext(Dispatchers.IO) {

        synchronized(lock) {

            if (
                initialized &&
                !forceRefresh
            ) {
                return@withContext
            }
        }

        var connection:
            HttpURLConnection? = null

        try {

            val url =
                URL(MARKETS_URL)

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.connectTimeout =
                20_000

            connection.readTimeout =
                20_000

            connection.requestMethod =
                "GET"

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "User-Agent",
                "NitinCryptoApp/1.0 Android"
            )

            if (
                connection.responseCode !=
                HttpURLConnection.HTTP_OK
            ) {
                return@withContext
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            val array =
                JSONArray(response)

            synchronized(lock) {

                for (
                    i in 0 until array.length()
                ) {

                    val obj =
                        array.optJSONObject(i)
                            ?: continue

                    val id =
                        obj.optString(
                            "id",
                            ""
                        ).trim()

                    val symbol =
                        normalize(
                            obj.optString(
                                "symbol",
                                ""
                            )
                        )

                    val image =
                        obj.optString(
                            "image",
                            ""
                        ).trim()

                    if (
                        id.isBlank() ||
                        symbol.isBlank() ||
                        image.isBlank()
                    ) {
                        continue
                    }

                    /*
                     * ID is the safest mapping.
                     */
                    iconById[id] = image

                    /*
                     * Symbol is convenient for exchange
                     * symbols such as BTCUSDT.
                     *
                     * If duplicate symbols exist, keep
                     * the first known mapping.
                     */
                    if (
                        !iconBySymbol.containsKey(
                            symbol
                        )
                    ) {
                        iconBySymbol[
                            symbol
                        ] = image
                    }

                    if (
                        !idBySymbol.containsKey(
                            symbol
                        )
                    ) {
                        idBySymbol[
                            symbol
                        ] = id
                    }
                }

                initialized = true
            }

        } catch (_: Exception) {

            // Network failure:
            // existing cache remains untouched.

        } finally {

            connection?.disconnect()
        }
    }

    /**
     * Resolve an icon using CoinGecko ID first.
     */
    fun iconById(
        id: String
    ): String? {

        val key =
            id.trim()

        if (key.isBlank()) {
            return null
        }

        return synchronized(lock) {
            iconById[key]
        }
    }

    /**
     * Resolve icon using symbol.
     */
    fun iconBySymbol(
        symbol: String
    ): String? {

        val key =
            normalize(symbol)

        if (key.isBlank()) {
            return null
        }

        return synchronized(lock) {
            iconBySymbol[key]
        }
    }

    /**
     * Resolve ID from symbol.
     */
    fun idBySymbol(
        symbol: String
    ): String? {

        val key =
            normalize(symbol)

        return synchronized(lock) {
            idBySymbol[key]
        }
    }

    /**
     * Automatic icon resolver.
     *
     * Priority:
     *
     * 1. Exact CoinGecko ID
     * 2. Exact symbol
     * 3. Known major-coin fallback
     * 4. null
     *
     * The UI can then display initials when null.
     */
    fun resolve(
        symbol: String,
        coinGeckoId: String? = null
    ): String? {

        if (
            !coinGeckoId.isNullOrBlank()
        ) {

            iconById(
                coinGeckoId
            )?.let {
                return it
            }
        }

        iconBySymbol(
            symbol
        )?.let {
            return it
        }

        return majorFallback(
            symbol
        )
    }

    /**
     * Preload an icon into Coil's memory/disk cache.
     *
     * This does NOT download arbitrary URLs.
     * It only caches a URL already resolved by the engine.
     */
    fun preloadCoil(
        context: Context,
        imageUrl: String
    ) {

        if (imageUrl.isBlank()) {
            return
        }

        val request =
            ImageRequest.Builder(context)
                .data(imageUrl)
                .memoryCachePolicy(
                    CachePolicy.ENABLED
                )
                .diskCachePolicy(
                    CachePolicy.ENABLED
                )
                .build()

        ImageLoader(context)
            .enqueue(request)
    }

    fun cachedIconCount(): Int {

        return synchronized(lock) {
            iconById.size
        }
    }

    fun clearCache() {

        synchronized(lock) {
            iconById.clear()
            iconBySymbol.clear()
            idBySymbol.clear()
            initialized = false
        }
    }

    private fun normalize(
        value: String
    ): String {

        return value
            .substringBefore("/")
            .substringBefore("-")
            .substringBefore("_")
            .trim()
            .uppercase(Locale.US)
    }

    /**
     * Small emergency fallback for major coins only.
     *
     * This is NOT the primary global icon database.
     */
    private fun majorFallback(
        symbol: String
    ): String? {

        return when (
            normalize(symbol)
        ) {

            "BTC" ->
                "https://assets.coingecko.com/coins/images/1/large/bitcoin.png"

            "ETH" ->
                "https://assets.coingecko.com/coins/images/279/large/ethereum.png"

            "BNB" ->
                "https://assets.coingecko.com/coins/images/825/large/bnb-icon2_2x.png"

            "SOL" ->
                "https://assets.coingecko.com/coins/images/4128/large/solana.png"

            "XRP" ->
                "https://assets.coingecko.com/coins/images/44/large/xrp-symbol-white-128.png"

            "ADA" ->
                "https://assets.coingecko.com/coins/images/975/large/cardano.png"

            "DOGE" ->
                "https://assets.coingecko.com/coins/images/5/large/dogecoin.png"

            "DOT" ->
                "https://assets.coingecko.com/coins/images/12171/large/polkadot.png"

            "AVAX" ->
                "https://assets.coingecko.com/coins/images/12559/large/coin-round-red.png"

            "LINK" ->
                "https://assets.coingecko.com/coins/images/877/large/chainlink-new-logo.png"

            "TRX" ->
                "https://assets.coingecko.com/coins/images/1094/large/tron-logo.png"

            "LTC" ->
                "https://assets.coingecko.com/coins/images/2/large/litecoin.png"

            "UNI" ->
                "https://assets.coingecko.com/coins/images/12504/large/uniswap-uni.png"

            "ATOM" ->
                "https://assets.coingecko.com/coins/images/1481/large/cosmos_hub.png"

            "XLM" ->
                "https://assets.coingecko.com/coins/images/100/large/stellar_symbol_black_RGB.png"

            "SHIB" ->
                "https://assets.coingecko.com/coins/images/11939/large/shiba.png"

            "PEPE" ->
                "https://assets.coingecko.com/coins/images/29850/large/pepe-token.jpeg"

            "SUI" ->
                "https://assets.coingecko.com/coins/images/26375/large/sui-ocean-square.png"

            "TON" ->
                "https://assets.coingecko.com/coins/images/17980/large/photo_2024-09-10_17.09.00.jpeg"

            else -> null
        }
    }
}