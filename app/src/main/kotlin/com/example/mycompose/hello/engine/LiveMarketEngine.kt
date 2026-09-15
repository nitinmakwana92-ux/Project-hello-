package com.example.mycompose.hello.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class LiveCoinMarket(
    val id: String,
    val symbol: String,
    val name: String,
    val iconUrl: String?,
    val priceUsd: Double,
    val priceChange24h: Double,
    val marketCapUsd: Double,
    val volume24hUsd: Double,
    val high24hUsd: Double,
    val low24hUsd: Double,
    val sparkline: List<Double>,
    val marketCapRank: Int?
)

object LiveMarketEngine {

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
            "&sparkline=true" +
            "&price_change_percentage=1h,24h,7d"

    private val lock = Any()

    private var cachedMarkets:
        List<LiveCoinMarket> = emptyList()

    private var lastLoadTime = 0L

    private const val CACHE_TIME =
        60_000L

    suspend fun loadLiveMarkets(
        forceRefresh: Boolean = false
    ): List<LiveCoinMarket> =
        withContext(Dispatchers.IO) {

            synchronized(lock) {

                val cacheValid =
                    cachedMarkets.isNotEmpty() &&
                    System.currentTimeMillis() -
                    lastLoadTime < CACHE_TIME

                if (!forceRefresh && cacheValid) {
                    return@withContext cachedMarkets
                }
            }

            val fresh = requestMarkets()

            if (fresh.isNotEmpty()) {

                synchronized(lock) {
                    cachedMarkets = fresh
                    lastLoadTime =
                        System.currentTimeMillis()
                }

                fresh

            } else {

                synchronized(lock) {
                    cachedMarkets
                }
            }
        }

    fun find(
        symbol: String
    ): LiveCoinMarket? {

        val normalized =
            symbol
                .trim()
                .uppercase(Locale.US)

        return synchronized(lock) {
            cachedMarkets.firstOrNull {
                it.symbol.uppercase(Locale.US) ==
                    normalized
            }
        }
    }

    fun topMarkets(
        limit: Int = 13000
    ): List<LiveCoinMarket> {

        return synchronized(lock) {
            cachedMarkets
                .sortedWith(
                    compareBy<LiveCoinMarket> {
                        it.marketCapRank
                            ?: Int.MAX_VALUE
                    }
                )
                .take(limit)
        }
    }

    fun search(
        query: String,
        limit: Int = 13000
    ): List<LiveCoinMarket> {

        val q =
            query
                .trim()
                .lowercase(Locale.US)

        if (q.isBlank()) {
            return topMarkets(limit)
        }

        return synchronized(lock) {

            cachedMarkets
                .filter {

                    it.symbol
                        .lowercase(Locale.US)
                        .contains(q) ||

                    it.name
                        .lowercase(Locale.US)
                        .contains(q) ||

                    it.id
                        .lowercase(Locale.US)
                        .contains(q)
                }
                .sortedWith(
                    compareBy<LiveCoinMarket> {

                        when {

                            it.symbol.equals(
                                q,
                                ignoreCase = true
                            ) -> 0

                            it.name.equals(
                                q,
                                ignoreCase = true
                            ) -> 1

                            it.symbol.startsWith(
                                q,
                                ignoreCase = true
                            ) -> 2

                            it.name.startsWith(
                                q,
                                ignoreCase = true
                            ) -> 3

                            else -> 4
                        }
                    }
                )
                .take(limit)
        }
    }

    fun clearCache() {

        synchronized(lock) {
            cachedMarkets = emptyList()
            lastLoadTime = 0L
        }
    }

    fun cachedCount(): Int {

        return synchronized(lock) {
            cachedMarkets.size
        }
    }

    private fun requestMarkets():
        List<LiveCoinMarket> {

        var connection:
            HttpURLConnection? = null

        return try {

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
                return emptyList()
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            val array =
                JSONArray(response)

            val result =
                ArrayList<LiveCoinMarket>(
                    array.length()
                )

            for (i in 0 until array.length()) {

                val obj =
                    array.optJSONObject(i)
                        ?: continue

                val id =
                    obj.optString(
                        "id",
                        ""
                    )

                val symbol =
                    obj.optString(
                        "symbol",
                        ""
                    )
                        .uppercase(
                            Locale.US
                        )

                val name =
                    obj.optString(
                        "name",
                        ""
                    )

                if (
                    id.isBlank() ||
                    symbol.isBlank()
                ) {
                    continue
                }

                val sparkline =
                    readSparkline(obj)

                result.add(
                    LiveCoinMarket(

                        id = id,

                        symbol = symbol,

                        name = name,

                        iconUrl =
                            obj.optString(
                                "image",
                                ""
                            )
                                .takeIf {
                                    it.isNotBlank()
                                },

                        priceUsd =
                            obj.optDouble(
                                "current_price",
                                0.0
                            ),

                        priceChange24h =
                            obj.optDouble(
                                "price_change_percentage_24h",
                                0.0
                            ),

                        marketCapUsd =
                            obj.optDouble(
                                "market_cap",
                                0.0
                            ),

                        volume24hUsd =
                            obj.optDouble(
                                "total_volume",
                                0.0
                            ),

                        high24hUsd =
                            obj.optDouble(
                                "high_24h",
                                0.0
                            ),

                        low24hUsd =
                            obj.optDouble(
                                "low_24h",
                                0.0
                            ),

                        sparkline =
                            sparkline,

                        marketCapRank =
                            if (
                                obj.has(
                                    "market_cap_rank"
                                ) &&
                                !obj.isNull(
                                    "market_cap_rank"
                                )
                            ) {
                                obj.optInt(
                                    "market_cap_rank"
                                )
                            } else {
                                null
                            }
                    )
                )
            }

            result

        } catch (_: Exception) {

            emptyList()

        } finally {

            connection?.disconnect()
        }
    }

    private fun readSparkline(
        obj: org.json.JSONObject
    ): List<Double> {

        return try {

            val sparklineObj =
                obj.optJSONObject(
                    "sparkline_in_7d"
                )
                    ?: return emptyList()

            val prices =
                sparklineObj.optJSONArray(
                    "price"
                )
                    ?: return emptyList()

            val result =
                ArrayList<Double>(
                    prices.length()
                )

            for (
                i in 0 until prices.length()
            ) {

                val value =
                    prices.optDouble(
                        i,
                        Double.NaN
                    )

                if (!value.isNaN()) {
                    result.add(value)
                }
            }

            result

        } catch (_: Exception) {

            emptyList()
        }
    }
}