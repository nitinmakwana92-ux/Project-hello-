package com.example.mycompose.hello.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Global coin master record.
 *
 * id       = CoinGecko unique ID
 * symbol   = BTC, ETH, etc.
 * name     = Bitcoin, Ethereum, etc.
 * iconUrl  = original CoinGecko image URL
 * rank     = CoinGecko market-cap rank when available
 */
data class GlobalCoin(
    val id: String,
    val symbol: String,
    val name: String,
    val iconUrl: String? = null,
    val rank: Int? = null
)

/**
 * Global Coin Engine - Part A
 *
 * Responsibilities:
 * 1. Download the complete CoinGecko coin list.
 * 2. Keep an in-memory cache.
 * 3. Search by symbol/name/id.
 * 4. Give Top coins first.
 * 5. Resolve original CoinGecko icon URL.
 *
 * Live prices/sparklines/charts are intentionally NOT handled here.
 * They will be added in later parts.
 */
object GlobalCoinEngine {

    private const val COINGECKO_LIST_URL =
        "https://api.coingecko.com/api/v3/coins/list?include_platform=false"

    private val lock = Any()

    private var cachedCoins: List<GlobalCoin> = emptyList()

    private var lastLoadTime: Long = 0L

    private const val CACHE_DURATION_MS =
        6L * 60L * 60L * 1000L

    /**
     * Load the global CoinGecko coin database.
     *
     * Returns cached data when it is still fresh.
     */
    suspend fun loadGlobalCoins(
        forceRefresh: Boolean = false
    ): List<GlobalCoin> = withContext(Dispatchers.IO) {

        synchronized(lock) {

            val cacheIsValid =
                cachedCoins.isNotEmpty() &&
                    (System.currentTimeMillis() - lastLoadTime) <
                    CACHE_DURATION_MS

            if (!forceRefresh && cacheIsValid) {
                return@withContext cachedCoins
            }
        }

        val downloaded = downloadCoinList()

        if (downloaded.isNotEmpty()) {

            synchronized(lock) {
                cachedCoins = downloaded
                lastLoadTime = System.currentTimeMillis()
            }

            downloaded

        } else {

            synchronized(lock) {
                cachedCoins
            }
        }
    }

    /**
     * Search the complete cached global database.
     */
    fun search(
        query: String,
        limit: Int = 13000
    ): List<GlobalCoin> {

        val q = query
            .trim()
            .lowercase(Locale.US)

        if (q.isBlank()) {
            return topCoins(limit)
        }

        val result = synchronized(lock) {
            cachedCoins.filter { coin ->

                coin.symbol
                    .lowercase(Locale.US)
                    .contains(q) ||

                coin.name
                    .lowercase(Locale.US)
                    .contains(q) ||

                coin.id
                    .lowercase(Locale.US)
                    .contains(q)
            }
        }

        return result
            .sortedWith(
                compareBy<GlobalCoin> {

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

    /**
     * Top coins are kept first.
     *
     * CoinGecko's coins/list endpoint does not provide
     * market-cap rank, so when rank is unavailable we use
     * the known major-coin priority below.
     *
     * Live market-cap ranking will be supplied by the
     * live market engine in the next part.
     */
    fun topCoins(limit: Int = 13000): List<GlobalCoin> {

        val coins = synchronized(lock) {
            cachedCoins
        }

        if (coins.isEmpty()) {
            return emptyList()
        }

        val priority = mapOf(
            "btc" to 0,
            "eth" to 1,
            "usdt" to 2,
            "bnb" to 3,
            "sol" to 4,
            "usdc" to 5,
            "xrp" to 6,
            "ada" to 7,
            "doge" to 8,
            "trx" to 9,
            "avax" to 10,
            "link" to 11,
            "dot" to 12,
            "matic" to 13,
            "shib" to 14,
            "ton" to 15,
            "ltc" to 16,
            "bch" to 17,
            "uni" to 18,
            "atom" to 19,
            "xlm" to 20,
            "near" to 21,
            "apt" to 22,
            "arb" to 23,
            "op" to 24,
            "sui" to 25,
            "fil" to 26,
            "etc" to 27,
            "icp" to 28,
            "inj" to 29,
            "render" to 30
        )

        return coins
            .sortedWith(
                compareBy<GlobalCoin> {
                    priority[
                        it.symbol.lowercase(Locale.US)
                    ] ?: Int.MAX_VALUE
                }.thenBy {
                    it.name.lowercase(Locale.US)
                }
            )
            .take(limit)
    }

    /**
     * Find a coin by exact symbol.
     */
    fun findBySymbol(
        symbol: String
    ): GlobalCoin? {

        val normalized =
            symbol
                .trim()
                .lowercase(Locale.US)

        return synchronized(lock) {
            cachedCoins.firstOrNull {
                it.symbol.lowercase(Locale.US) ==
                    normalized
            }
        }
    }

    /**
     * Find a coin by exact CoinGecko ID.
     */
    fun findById(
        id: String
    ): GlobalCoin? {

        val normalized =
            id
                .trim()
                .lowercase(Locale.US)

        return synchronized(lock) {
            cachedCoins.firstOrNull {
                it.id.lowercase(Locale.US) ==
                    normalized
            }
        }
    }

    /**
     * Original CoinGecko icon.
     *
     * CoinGecko's public coins/list endpoint only gives
     * ID/name/symbol, so this URL uses the CoinGecko
     * image CDN convention.
     *
     * The URL is returned only when a valid CoinGecko ID
     * exists.
     */
    fun getOriginalIconUrl(
        coin: GlobalCoin
    ): String {

        return coin.iconUrl
            ?.takeIf { it.isNotBlank() }
            ?: buildCoinGeckoIconUrl(coin.id)
    }

    /**
     * Clear local memory cache.
     */
    fun clearCache() {

        synchronized(lock) {
            cachedCoins = emptyList()
            lastLoadTime = 0L
        }
    }

    /**
     * Number of coins currently loaded in memory.
     */
    fun cachedCoinCount(): Int {

        return synchronized(lock) {
            cachedCoins.size
        }
    }

    private fun buildCoinGeckoIconUrl(
        coinId: String
    ): String {

        return "https://assets.coingecko.com/coins/images/" +
            "${coinId}/large.png"
    }

    /**
     * Download CoinGecko global coin list.
     */
    private fun downloadCoinList(): List<GlobalCoin> {

        var connection: HttpURLConnection? = null

        return try {

            val url = URL(
                COINGECKO_LIST_URL
            )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"

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

            val array = JSONArray(response)

            val result =
                ArrayList<GlobalCoin>(
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
                    ).trim()

                val symbol =
                    obj.optString(
                        "symbol",
                        ""
                    ).trim()

                val name =
                    obj.optString(
                        "name",
                        ""
                    ).trim()

                if (
                    id.isBlank() ||
                    symbol.isBlank() ||
                    name.isBlank()
                ) {
                    continue
                }

                result.add(
                    GlobalCoin(
                        id = id,
                        symbol = symbol
                            .uppercase(Locale.US),
                        name = name
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
}