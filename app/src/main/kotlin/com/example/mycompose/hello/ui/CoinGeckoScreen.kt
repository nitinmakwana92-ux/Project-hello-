package com.example.mycompose.hello.ui

import android.annotation.SuppressLint
import android.net.Uri
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.*
import com.example.mycompose.hello.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

// ============================================================
// SCROLL THUMB — MANUAL SETTINGS (A to Z)
// Change these values to manually tune the coin-list thumb.
// ============================================================
private object CoinScrollThumbSettings {
    // ---------- MASTER ----------
    var enabled = true

    // ---------- POSITION ----------
    // Fine adjustment from the real coin #0 position. 0 = exact.
    var coinListStartOffsetDp = 0f
    // LazyColumn index where the first actual coin row begins.
    // 0=MarketHeader, 1=AI search, 2=Crypto Coins title,
    // 3=loading/status, 4=first actual coin row.
    var coinListStartItemIndex = 4


    // Negative = left (toward coin list), positive = right.
    var horizontalOffsetDp = -11f
    // Negative = up, positive = down.
    var verticalOffsetDp = 0f

    // ---------- TRACK ----------
    var trackWidthDp = 10f
    var trackPaddingTopDp = 8f
    var trackPaddingBottomDp = 40f

    // ---------- THUMB SIZE ----------
    var widthDp = 6f
    var minHeightDp = 40f
    // 1.0 = automatic content ratio.
    var thumbFractionScale = 1.0f
    var minThumbFraction = 0.08f

    // ---------- MOVEMENT ----------
    // 1.0 = normal travel. 0.8 = shorter, 1.2 = longer (clamped by track).
    var travelScale = 1.0f
    // 1.0 = normal finger sensitivity.
    var dragSensitivity = 1.0f

    // ---------- LOOK ----------
    // 0xAARRGGBB. Example: opaque white = 0xFFFFFFFF.
    var thumbColor = Color(255, 152, 0)
    // 0.0 = invisible, 1.0 = solid.
    var alpha = 0.90f
    var cornerRadiusDp = 3f

    // ---------- BEHAVIOR ----------
    // Reserved for optional smooth animation implementation.
    var useSmoothScroll = false
    // Reserved safety limit for future jump-based scrolling.
    var maxJumpItems = 100000

    // ---------- TOUCH AREA ----------
    // Wider invisible touch area makes the thumb easier to grab.
    var touchWidthDp = 34f
    var touchHeightExtraDp = 8f
}


private val ACCENT = Color(0xFFFF8A1E)
private val CARD = Color(0x66000000)
private val CARD2 = Color(0x55000000)
private val ICONBG = Color(0x55000000)
private val UP = Color(0xFF0ECB81)
private val DN = Color(0xFFF6465D)

// ============================================================
// GLOBAL COIN SYMBOL NORMALIZER
// ============================================================

private fun normalizeCoinSymbol(value: String): String {
    return value
        .trim()
        .substringBefore("/")
        .substringBefore("-")
        .substringBefore("_")
        .substringBefore(":")
        .uppercase(Locale.US)
}

private const val CG_BASE =
    "https://api.coingecko.com/api/v3/coins"

private const val CG_MARKETS =
    "$CG_BASE/markets"

private const val CG_SIMPLE =
    "https://api.coingecko.com/api/v3/simple/price"

private const val CG_LIST =
    "https://api.coingecko.com/api/v3/coins/list?include_platform=false"

private const val BINANCE =
    "https://api.binance.com"

private const val COINDCX =
    "https://api.coindcx.com"

// ============================================================
// PERSISTENT COIN-LIST CACHE
// Keeps the last successfully loaded live coin universe on disk so
// reopening the app does not start from the 5-coin fallback.
// Network refresh still runs in the background and updates this cache.
// ============================================================
private const val COIN_LIST_CACHE_FILE = "live_coin_list_cache_v1.json"

private fun saveCoinListCache(
    context: android.content.Context,
    coins: List<CoinGeckoCoin>
) {
    if (coins.isEmpty()) return

    try {
        val array = JSONArray()

        coins.forEach { coin ->
            val obj = JSONObject()
                .put("id", coin.id)
                .put("symbol", coin.symbol)
                .put("name", coin.name)
                .put("image", coin.image)
                .put("price", coin.currentPrice)
                .put("change", coin.priceChange24h)
                .put("volume", coin.totalVolume)
                .put("source", coin.source)
                .put("exchangeSymbol", coin.exchangeSymbol)

            array.put(obj)
        }

        context.openFileOutput(
            COIN_LIST_CACHE_FILE,
            android.content.Context.MODE_PRIVATE
        ).use { output ->
            output.write(array.toString().toByteArray(Charsets.UTF_8))
        }
    } catch (_: Exception) {
        // Cache is only an optimisation. Never break live market loading.
    }
}

private fun loadCoinListCache(
    context: android.content.Context
): List<CoinGeckoCoin> {
    return try {
        val file = context.getFileStreamPath(COIN_LIST_CACHE_FILE)
        if (!file.exists() || file.length() <= 2L) return emptyList()

        val json = context.openFileInput(COIN_LIST_CACHE_FILE).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }

        val array = JSONArray(json)
        val result = ArrayList<CoinGeckoCoin>(array.length())

        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val id = obj.optString("id")
            val symbol = obj.optString("symbol")
            val name = obj.optString("name")

            if (id.isBlank() || symbol.isBlank()) continue

            result += CoinGeckoCoin(
                id = id,
                symbol = symbol,
                name = name.ifBlank { symbol },
                image = obj.optString("image"),
                currentPrice = obj.optDouble("price", 0.0),
                priceChange24h = obj.optDouble("change", 0.0),
                totalVolume = obj.optDouble("volume", 0.0),
                source = obj.optString("source", "COINGECKO"),
                exchangeSymbol = obj.optString("exchangeSymbol")
            )
        }

        result
    } catch (_: Exception) {
        emptyList()
    }
}


private const val COINDCX_PUBLIC =
    "https://public.coindcx.com"

private const val PAGE_SIZE = 250
private const val GLOBAL_MARKET_PAGES = 70 // automatic live pagination for the current CoinGecko active-market universe
private const val MARKET_PAGE_RETRIES = 4

data class CoinGeckoCoin(
    val id: String,
    val symbol: String,
    val name: String,
    val image: String,
    val currentPrice: Double,
    val priceChange24h: Double,
    val totalVolume: Double,
    val sparkline: List<Double> = emptyList(),
    val source: String = "COINGECKO",
    val exchangeSymbol: String = ""
)
data class EquityPoint(
    val value: Float
)

data class ExchangeCoin(
    val exchange: String,
    val symbol: String,
    val baseAsset: String,
    val quoteAsset: String,
    val name: String,
    val icon: String?,
    val price: Double = 0.0,
    val change24h: Double = 0.0,
    val volume: Double = 0.0,
    val pair: String = ""
)

private fun norm(value: String): String =
    value
        .substringBefore("/")
        .substringBefore("-")
        .substringBefore("_")
        .trim()
        .uppercase(Locale.US)

private fun pct(value: Double): String =
    if (abs(value) < 0.01) {
        String.format(Locale.US, "%.4f", value)
    } else {
        String.format(Locale.US, "%.2f", value)
    }

fun formatPrice(price: Double): String =
    when {
        !price.isFinite() || price <= 0.0 ->
            "$0.00"

        price >= 1000 ->
            String.format(Locale.US, "$%,.2f", price)

        price >= 1 ->
            String.format(Locale.US, "$%.2f", price)

        price >= 0.01 ->
            String.format(Locale.US, "$%.4f", price)

        else ->
            String.format(Locale.US, "$%.8f", price)
    }

fun formatMarketCap(value: Long): String =
    when {
        value >= 1_000_000_000 ->
            String.format(
                Locale.US,
                "$%.2fB",
                value / 1e9
            )

        value >= 1_000_000 ->
            String.format(
                Locale.US,
                "$%.2fM",
                value / 1e6
            )

        else ->
            String.format(
                Locale.US,
                "$%.2fK",
                value / 1e3
            )
    }

private val iconCache =
    mutableMapOf<String, String>()

private val nameCache =
    mutableMapOf<String, String>()

private val idCache =
    mutableMapOf<String, String>()

private val coinIdIconCache =
    mutableMapOf<String, String>()

private val coinIdNameCache =
    mutableMapOf<String, String>()

private val coinIdSymbolCache =
    mutableMapOf<String, String>()

private val coinGeckoIdBySymbol =
    mutableMapOf<String, String>()

@Volatile
private var cacheInitialized = false

private fun cachedIcon(symbol: String): String? =
    synchronized(iconCache) {
        iconCache[norm(symbol)]
    }

private fun cachedId(symbol: String): String? =
    synchronized(idCache) {
        idCache[norm(symbol)]
    }

private fun cachedName(symbol: String): String? =
    synchronized(nameCache) {
        nameCache[norm(symbol)]
    }

private fun coinName(symbol: String): String =
    cachedName(symbol) ?: norm(symbol)

private fun getCoinIconById(id: String): String? =
    synchronized(coinIdIconCache) {
        coinIdIconCache[id.trim()]
    }

private fun requestText(
    url: String,
    timeout: Int = 15_000
): String? {

    var connection: HttpURLConnection? = null

    return try {

        connection =
            URL(url).openConnection() as HttpURLConnection

        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        connection.requestMethod = "GET"

        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        connection.setRequestProperty(
            "User-Agent",
            "NitinCryptoApp/1.0 Android"
        )

        if (connection.responseCode !in 200..299) {
            null
        } else {
            connection.inputStream
                .bufferedReader()
                .use { it.readText() }
        }

    } catch (_: Exception) {

        null

    } finally {

        connection?.disconnect()
    }
}

private fun jDouble(
    obj: JSONObject,
    key: String
): Double =
    obj.optString(key, "")
        .toDoubleOrNull()
        ?: obj.optDouble(key, 0.0)
        
        private suspend fun fetchCoinGeckoPage(
    page: Int,
    perPage: Int = PAGE_SIZE
): List<CoinGeckoCoin> =
    withContext(Dispatchers.IO) {

        val json =
            requestText(
                "$CG_MARKETS" +
                    "?vs_currency=usd" +
                    "&order=market_cap_desc" +
                    "&per_page=$perPage" +
                    "&page=$page" +
                    "&sparkline=true" +
                    "&price_change_percentage=24h"
            )
                ?: return@withContext emptyList()

        try {

            val array =
                JSONArray(json)

            buildList {

                for (i in 0 until array.length()) {

                    val obj =
                        array.optJSONObject(i)
                            ?: continue

                    val id =
                        obj.optString("id")
                            .trim()

                    val symbol =
                        norm(
                            obj.optString("symbol")
                        )

                    val name =
                        obj.optString("name")
                            .trim()

                    if (
                        id.isBlank() ||
                        symbol.isBlank() ||
                        name.isBlank()
                    ) {
                        continue
                    }

                    val image =
                        obj.optString("image")
                            .trim()

                    val sparkline =
                        obj.optJSONObject(
                            "sparkline_in_7d"
                        )
                            ?.optJSONArray("price")
                            ?.let { prices ->
                                List(prices.length()) { index ->
                                    jDouble(
                                        prices,
                                        index
                                    )
                                }
                            }
                            ?: emptyList()

                    add(
                        CoinGeckoCoin(
                            id = id,
                            symbol = symbol,
                            name = name,
                            image = image,
                            currentPrice =
                                jDouble(
                                    obj,
                                    "current_price"
                                ),
                            priceChange24h =
                                jDouble(
                                    obj,
                                    "price_change_percentage_24h"
                                ),
                            totalVolume =
                                jDouble(
                                    obj,
                                    "total_volume"
                                ),
                            sparkline = sparkline
                        )
                    )
                }
            }

        } catch (_: Exception) {

            emptyList()
        }
    }

private fun jDouble(
    array: JSONArray,
    index: Int
): Double =
    array.optString(index, "")
        .toDoubleOrNull()
        ?: array.optDouble(index, 0.0)

suspend fun preloadIconCache(
    forceRefresh: Boolean = false
) {

    if (
        cacheInitialized &&
        !forceRefresh
    ) {
        return
    }

    withContext(Dispatchers.IO) {

        try {

            for (page in 1..8) {

                val coins =
                    fetchCoinGeckoPage(page)

                if (coins.isEmpty()) {
                    break
                }

                coins.forEach { coin ->

                    val symbol =
                        norm(coin.symbol)

                    if (symbol.isBlank()) {
                        return@forEach
                    }

                    if (
                        coin.image.isNotBlank()
                    ) {

                        synchronized(iconCache) {
                            if (
                                !iconCache.containsKey(
                                    symbol
                                )
                            ) {
                                iconCache[symbol] =
                                    coin.image
                            }
                        }

                        synchronized(
                            coinIdIconCache
                        ) {
                            coinIdIconCache[
                                coin.id
                            ] = coin.image
                        }
                    }

                    synchronized(nameCache) {
                        nameCache[symbol] =
                            coin.name
                    }

                    synchronized(idCache) {
                        idCache[symbol] =
                            coin.id
                    }

                    synchronized(
                        coinIdNameCache
                    ) {
                        coinIdNameCache[
                            coin.id
                        ] = coin.name
                    }

                    synchronized(
                        coinIdSymbolCache
                    ) {
                        coinIdSymbolCache[
                            coin.id
                        ] = symbol
                    }

                    synchronized(
                        coinGeckoIdBySymbol
                    ) {
                        coinGeckoIdBySymbol[
                            symbol
                        ] = coin.id
                    }
                }

                delay(120)
            }

            cacheInitialized = true

        } catch (_: Exception) {
        }
    }
}

suspend fun fetchCoinCapCoins(
    maxPages: Int = GLOBAL_MARKET_PAGES
): List<CoinGeckoCoin> =
    withContext(Dispatchers.IO) {

        val result =
            LinkedHashMap<String, CoinGeckoCoin>()

        try {

            for (page in 1..maxPages.coerceAtMost(GLOBAL_MARKET_PAGES)) {

                var coins = emptyList<CoinGeckoCoin>()
                var attempt = 0

                while (attempt < MARKET_PAGE_RETRIES && coins.isEmpty()) {
                    coins = fetchCoinGeckoPage(page)
                    if (coins.isNotEmpty()) break

                    attempt++
                    if (attempt < MARKET_PAGE_RETRIES) {
                        // CoinGecko public API is IP-rate-limited. Back off instead
                        // of treating a temporary 429/network failure as end-of-list.
                        delay(1500L * (1L shl (attempt - 1)))
                    }
                }

                // Empty after retries means the list has ended or this page is
                // temporarily unavailable. Keep all pages already loaded.
                if (coins.isEmpty()) break

                coins.forEach { coin ->
                    result[coin.id] = coin
                    cacheCoin(coin)
                }

                // Avoid hammering the public endpoint between pages.
                delay(1200)
            }

        } catch (_: Exception) {
        }

        if (
            result.isEmpty()
        ) {
            getFallbackCoins()
        } else {
            result.values.toList()
        }
    }

private suspend fun fetchCoinGeckoPagesParallel(
    startPage: Int,
    endPage: Int,
    concurrency: Int = 4
): List<CoinGeckoCoin> =
    withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, CoinGeckoCoin>()
        val safeStart = startPage.coerceAtLeast(1)
        val safeEnd = endPage.coerceAtMost(GLOBAL_MARKET_PAGES)
        if (safeStart > safeEnd) return@withContext emptyList()

        var page = safeStart
        while (page <= safeEnd) {
            val batchEnd = (page + concurrency - 1).coerceAtMost(safeEnd)
            val batch = coroutineScope {
                (page..batchEnd).map { pageNumber ->
                    async(Dispatchers.IO) {
                        fetchCoinGeckoPage(pageNumber)
                    }
                }.awaitAll()
            }

            batch.flatten().forEach { coin ->
                result[coin.id] = coin
                cacheCoin(coin)
            }

            page = batchEnd + 1
            if (page <= safeEnd) delay(250L)
        }

        result.values.toList()
    }

private fun cacheCoin(
    coin: CoinGeckoCoin
) {

    val symbol =
        norm(coin.symbol)

    if (symbol.isBlank()) {
        return
    }

    if (
        coin.image.isNotBlank()
    ) {

        synchronized(iconCache) {
            iconCache[symbol] =
                coin.image
        }

        synchronized(
            coinIdIconCache
        ) {
            coinIdIconCache[
                coin.id
            ] = coin.image
        }
    }

    synchronized(nameCache) {
        nameCache[symbol] =
            coin.name
    }

    synchronized(idCache) {
        idCache[symbol] =
            coin.id
    }

    synchronized(
        coinIdNameCache
    ) {
        coinIdNameCache[
            coin.id
        ] = coin.name
    }

    synchronized(
        coinIdSymbolCache
    ) {
        coinIdSymbolCache[
            coin.id
        ] = symbol
    }

    synchronized(
        coinGeckoIdBySymbol
    ) {
        coinGeckoIdBySymbol[
            symbol
        ] = coin.id
    }
}

private suspend fun fetchBinanceExchangeCoins():
    List<ExchangeCoin> =
    withContext(Dispatchers.IO) {

        try {

            val info =
                JSONArray(
                    requestText(
                        "$BINANCE/api/v3/exchangeInfo"
                    )
                        ?: return@withContext emptyList()
                )

            val ticker =
                JSONArray(
                    requestText(
                        "$BINANCE/api/v3/ticker/24hr"
                    )
                        ?: return@withContext emptyList()
                )

            val allowedQuotes =
                setOf(
                    "USDT",
                    "USDC",
                    "FDUSD",
                    "BUSD",
                    "BTC",
                    "ETH",
                    "BNB",
                    "EUR",
                    "TRY",
                    "BRL",
                    "GBP",
                    "AUD",
                    "JPY",
                    "INR"
                )

            val metadata =
                HashMap<
                    String,
                    Pair<String, String>
                >()

            for (
                i in 0 until info.length()
            ) {

                val obj =
                    info.optJSONObject(i)
                        ?: continue

                if (
                    obj.optString("status") !=
                    "TRADING"
                ) {
                    continue
                }

                val quote =
                    obj.optString(
                        "quoteAsset"
                    )

                if (
                    quote !in allowedQuotes
                ) {
                    continue
                }

                metadata[
                    obj.optString("symbol")
                ] =
                    obj.optString(
                        "baseAsset"
                    ) to quote
            }

            buildList {

                for (
                    i in 0 until ticker.length()
                ) {

                    val obj =
                        ticker.optJSONObject(i)
                            ?: continue

                    val symbol =
                        obj.optString("symbol")

                    val meta =
                        metadata[symbol]
                            ?: continue

                    val price =
                        jDouble(
                            obj,
                            "lastPrice"
                        )

                    if (
                        price <= 0.0
                    ) {
                        continue
                    }

                    val base =
                        norm(meta.first)

                    add(
                        ExchangeCoin(
                            exchange = "BINANCE",
                            symbol = symbol,
                            baseAsset = base,
                            quoteAsset =
                                norm(meta.second),
                            name = coinName(base),
                            icon =
                                resolveCoinIcon(
                                    base
                                ),
                            price = price,
                            change24h =
                                jDouble(
                                    obj,
                                    "priceChangePercent"
                                ),
                            volume =
                                jDouble(
                                    obj,
                                    "quoteVolume"
                                ),
                            pair = symbol
                        )
                    )
                }
            }

        } catch (_: Exception) {

            emptyList()
        }
    }

private suspend fun fetchCoinDCXExchangeCoins():
    List<ExchangeCoin> =
    withContext(Dispatchers.IO) {

        try {

            val details =
                JSONArray(
                    requestText(
                        "$COINDCX/exchange/v1/markets_details"
                    )
                        ?: "[]"
                )

            val pairMap =
                HashMap<String, String>()

            for (
                i in 0 until details.length()
            ) {

                val obj =
                    details.optJSONObject(i)
                        ?: continue

                if (
                    obj.optString(
                        "status",
                        "active"
                    ) != "active"
                ) {
                    continue
                }

                val symbol =
                    obj.optString(
                        "coindcx_name",
                        obj.optString("symbol")
                    )
                        .trim()
                        .uppercase(Locale.US)

                val pair =
                    obj.optString("pair")

                if (
                    symbol.isNotBlank()
                ) {
                    pairMap[symbol] =
                        pair
                }
            }

            val ticker =
                JSONArray(
                    requestText(
                        "$COINDCX/exchange/ticker"
                    )
                        ?: return@withContext emptyList()
                )

            val quoteAssets =
                listOf(
                    "USDT",
                    "USDC",
                    "INR",
                    "BTC",
                    "ETH",
                    "BUSD"
                )

            buildList {

                for (
                    i in 0 until ticker.length()
                ) {

                    val obj =
                        ticker.optJSONObject(i)
                            ?: continue

                    val market =
                        obj.optString("market")
                            .trim()
                            .uppercase(Locale.US)

                    if (
                        market.isBlank()
                    ) {
                        continue
                    }

                    val quote =
                        quoteAssets
                            .firstOrNull {
                                market.endsWith(it)
                            }
                            ?: continue

                    val base =
                        norm(
                            market.removeSuffix(
                                quote
                            )
                        )

                    val price =
                        jDouble(
                            obj,
                            "last_price"
                        )

                    if (
                        base.isBlank() ||
                        price <= 0.0
                    ) {
                        continue
                    }

                    add(
                        ExchangeCoin(
                            exchange = "COINDCX",
                            symbol = market,
                            baseAsset = base,
                            quoteAsset = quote,
                            name = coinName(base),
                            icon =
                                resolveCoinIcon(
                                    base
                                ),
                            price = price,
                            change24h =
                                jDouble(
                                    obj,
                                    "change_24_hour"
                                ),
                            volume =
                                jDouble(
                                    obj,
                                    "volume"
                                ),
                            pair =
                                pairMap[market]
                                    ?: "B-${base}_${quote}"
                        )
                    )
                }
            }

        } catch (_: Exception) {

            emptyList()
        }
    }

fun popularIcon(
    symbol: String
): String? =
    when (norm(symbol)) {

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

        "MATIC", "POL" ->
            "https://assets.coingecko.com/coins/images/4713/large/matic-token-icon.png"

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

        "NEAR" ->
            "https://assets.coingecko.com/coins/images/10365/large/near.jpg"

        "ICP" ->
            "https://assets.coingecko.com/coins/images/14495/large/Internet_Computer_logo.png"

        "APT" ->
            "https://assets.coingecko.com/coins/images/26455/large/aptos_round.png"

        "FIL" ->
            "https://assets.coingecko.com/coins/images/12817/large/filecoin.png"

        "ARB" ->
            "https://assets.coingecko.com/coins/images/16547/large/photo_2023-03-29_21.47.00.jpeg"

        "OP" ->
            "https://assets.coingecko.com/coins/images/25244/large/Optimism.png"

        "INJ" ->
            "https://assets.coingecko.com/coins/images/12882/large/Secondary_Symbol.png"

        "GRT" ->
            "https://assets.coingecko.com/coins/images/13397/large/Graph_Token.png"

        "AAVE" ->
            "https://assets.coingecko.com/coins/images/12645/large/AAVE.png"

        "ALGO" ->
            "https://assets.coingecko.com/coins/images/4380/large/download.png"

        "VET" ->
            "https://assets.coingecko.com/coins/images/1167/large/VeChain.png"

        "HBAR" ->
            "https://assets.coingecko.com/coins/images/3688/large/hbar.png"

        "FTM" ->
            "https://assets.coingecko.com/coins/images/4001/large/Fantom_round.png"

        "AXS" ->
            "https://assets.coingecko.com/coins/images/13029/large/axie_infinity.png"

        "SAND" ->
            "https://assets.coingecko.com/coins/images/12129/large/sandbox_logo.jpg"

        "MANA" ->
            "https://assets.coingecko.com/coins/images/878/large/decentraland-mana.png"

        "XTZ" ->
            "https://assets.coingecko.com/coins/images/976/large/Tezos-logo.png"

        "IMX" ->
            "https://assets.coingecko.com/coins/images/17233/large/immutableX-symbol-BLK-RGB.png"

        "STX" ->
            "https://assets.coingecko.com/coins/images/2069/large/Stacks_logo.png"

        "RNDR", "RENDER" ->
            "https://assets.coingecko.com/coins/images/11636/large/rndr.png"

        "GALA" ->
            "https://assets.coingecko.com/coins/images/12493/large/GALA_token_image_-_200PNG.png"

        "APE" ->
            "https://assets.coingecko.com/coins/images/24383/large/apecoin.jpg"

        "CHZ" ->
            "https://assets.coingecko.com/coins/images/8834/large/Chiliz.png"

        "ENJ" ->
            "https://assets.coingecko.com/coins/images/1102/large/Symbol_Only_-_Purple.png"

        "BAT" ->
            "https://assets.coingecko.com/coins/images/677/large/basic-attention-token.png"

        "ZIL" ->
            "https://assets.coingecko.com/coins/images/2687/large/Zilliqa-logo.png"

        "COMP" ->
            "https://assets.coingecko.com/coins/images/10775/large/COMP.png"

        "SNX" ->
            "https://assets.coingecko.com/coins/images/3406/large/SNX.png"

        "CRV" ->
            "https://assets.coingecko.com/coins/images/12124/large/Curve.png"

        "1INCH" ->
            "https://assets.coingecko.com/coins/images/13469/large/1inch-token.png"

        "SHIB" ->
            "https://assets.coingecko.com/coins/images/11939/large/shiba.png"

        "PEPE" ->
            "https://assets.coingecko.com/coins/images/29850/large/pepe-token.jpeg"

        "FLOKI" ->
            "https://assets.coingecko.com/coins/images/16746/large/PNG_image.png"

        "BONK" ->
            "https://assets.coingecko.com/coins/images/28600/large/bonk.jpg"

        "WIF" ->
            "https://assets.coingecko.com/coins/images/33566/large/dogwifhat.jpg"

        "SEI" ->
            "https://assets.coingecko.com/coins/images/28205/large/Sei_Logo_-_Transparent.png"

        "SUI" ->
            "https://assets.coingecko.com/coins/images/26375/large/sui-ocean-square.png"

        "TON" ->
            "https://assets.coingecko.com/coins/images/17980/large/photo_2024-09-10_17.09.00.jpeg"

        "ONDO" ->
            "https://assets.coingecko.com/coins/images/29655/large/ONDO.png"

        "FET" ->
            "https://assets.coingecko.com/coins/images/5681/large/Fetch.jpg"

        "TAO" ->
            "https://assets.coingecko.com/coins/images/28452/large/ARUsPeNQ_400x400.jpg"

        "BCH" ->
            "https://assets.coingecko.com/coins/images/780/large/bitcoin-cash-circle.png"

        "ETC" ->
            "https://assets.coingecko.com/coins/images/453/large/ethereum-classic-logo.png"

        "XMR" ->
            "https://assets.coingecko.com/coins/images/69/large/monero_logo.png"

        "EOS" ->
            "https://assets.coingecko.com/coins/images/738/large/eos-eos-logo.png"

        "THETA" ->
            "https://assets.coingecko.com/coins/images/2538/large/theta-token-logo.png"

        "FLOW" ->
            "https://assets.coingecko.com/coins/images/13446/large/5f6294c0c7a8cda55cb1c936_Flow_Wordmark.png"

        "MKR" ->
            "https://assets.coingecko.com/coins/images/1364/large/Mark_Maker.png"

        "QNT" ->
            "https://assets.coingecko.com/coins/images/3370/large/5ZOu7brX_400x400.jpg"

        "ENA" ->
            "https://assets.coingecko.com/coins/images/29655/large/ENA.png"

        "BICO" ->
            "https://assets.coingecko.com/coins/images/20327/large/biconomy.jpg"

        else ->
            null
    }
    
    fun resolveCoinIcon(
    symbol: String,
    apiImage: String? = null,
    coinId: String? = null
): String? {

    apiImage
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return it
        }

    coinId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { id ->

            getCoinIconById(id)
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    return it
                }
        }

    cachedIcon(symbol)
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return it
        }

    return popularIcon(symbol)
}

private fun getFallbackCoins() =
    listOf(

        CoinGeckoCoin(
            "bitcoin",
            "BTC",
            "Bitcoin",
            popularIcon("BTC")!!,
            0.0,
            0.0,
            0.0
        ),

        CoinGeckoCoin(
            "ethereum",
            "ETH",
            "Ethereum",
            popularIcon("ETH")!!,
            0.0,
            0.0,
            0.0
        ),

        CoinGeckoCoin(
            "binancecoin",
            "BNB",
            "BNB",
            popularIcon("BNB")!!,
            0.0,
            0.0,
            0.0
        ),

        CoinGeckoCoin(
            "solana",
            "SOL",
            "Solana",
            popularIcon("SOL")!!,
            0.0,
            0.0,
            0.0
        ),

        CoinGeckoCoin(
            "ripple",
            "XRP",
            "XRP",
            popularIcon("XRP")!!,
            0.0,
            0.0,
            0.0
        )
    )

private suspend fun fetchLiveExchangeFallbackCoins(): List<CoinGeckoCoin> =
    withContext(Dispatchers.IO) {
        try {
            val fresh =
                fetchBinanceExchangeCoins() +
                    fetchCoinDCXExchangeCoins()

            if (fresh.isEmpty()) return@withContext emptyList()

            val byBase =
                fresh
                    .filter { it.price > 0.0 && it.baseAsset.isNotBlank() }
                    .groupBy { norm(it.baseAsset) }
                    .mapNotNull { (base, rows) ->
                        val row = rows.maxByOrNull { it.volume } ?: return@mapNotNull null
                        base to row
                    }
                    .toMap(LinkedHashMap())

            // Keep the familiar top-crypto order first, then append the
            // remaining live exchange universe by volume.
            val preferred = listOf(
                "BTC", "ETH", "BNB", "SOL", "XRP", "USDT", "USDC",
                "ADA", "DOGE", "TRX", "AVAX", "LINK", "DOT", "MATIC",
                "LTC", "BCH", "UNI", "XLM", "ATOM", "ETC", "FIL",
                "NEAR", "APT", "ARB", "OP", "SUI", "PEPE", "SHIB"
            )

            val orderedBases =
                preferred.filter { byBase.containsKey(it) } +
                    byBase.keys
                        .filter { it !in preferred }
                        .sortedByDescending { byBase[it]?.volume ?: 0.0 }

            orderedBases.mapNotNull { base ->
                val row = byBase[base] ?: return@mapNotNull null
                CoinGeckoCoin(
                    id = "exchange:${row.exchange}:$base",
                    symbol = base,
                    name = row.name.ifBlank { coinName(base) },
                    image = resolveCoinIcon(base, row.icon, cachedId(base)).orEmpty(),
                    currentPrice = row.price,
                    priceChange24h = row.change24h,
                    totalVolume = row.volume,
                    source = row.exchange,
                    exchangeSymbol = if (row.exchange == "COINDCX") row.pair else row.symbol
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

suspend fun loadAllGlobalCoins(
    maxPages: Int = GLOBAL_MARKET_PAGES
): List<CoinGeckoCoin> =
    withContext(Dispatchers.IO) {

        // IMPORTANT: Keep the Crypto Coin list on the original CoinGecko
        // market-cap ordered live source. Do not append exchange-only rows
        // here; that was causing unrelated/new low-cap symbols to replace
        // the original top-crypto list and also removed their real icons.
        // CoinGecko supplies the live price, 24h change, volume, name, id,
        // and original image URL for every returned coin.
        fetchCoinCapCoins(
            maxPages = maxPages.coerceIn(1, GLOBAL_MARKET_PAGES)
        )
    }

private suspend fun fetchCommodityRows(): List<CoinGeckoCoin> =
    withContext(Dispatchers.IO) {
        try {
            val json =
                requestText("https://xaus.com/api/v1/spot", 8_000)
                    ?: return@withContext emptyList()

            val obj = JSONObject(json)
            val gold = obj.optDouble("spot_usd_oz", 0.0)
            val silver = obj.optDouble("silver_usd_oz", 0.0)

            buildList {
                if (gold > 0.0) {
                    add(
                        CoinGeckoCoin(
                            id = "COMMODITY:XAU",
                            symbol = "XAU",
                            name = "Gold",
                            image = "",
                            currentPrice = gold,
                            priceChange24h = 0.0,
                            totalVolume = 0.0,
                            source = "COMMODITY"
                        )
                    )
                }
                if (silver > 0.0) {
                    add(
                        CoinGeckoCoin(
                            id = "COMMODITY:XAG",
                            symbol = "XAG",
                            name = "Silver",
                            image = "",
                            currentPrice = silver,
                            priceChange24h = 0.0,
                            totalVolume = 0.0,
                            source = "COMMODITY"
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

private suspend fun refreshExchangeRows(
    old: List<CoinGeckoCoin>
): List<CoinGeckoCoin> =
    withContext(Dispatchers.IO) {

        val fresh =
            fetchBinanceExchangeCoins() +
                fetchCoinDCXExchangeCoins()

        if (
            fresh.isEmpty()
        ) {
            return@withContext old
        }

        val byExchangeId =
            fresh.associateBy {
                "${it.exchange}:${it.symbol}"
            }

        // Keep the complete CoinGecko list as the canonical list, but update
        // any matching coin with the newest live exchange ticker. This avoids
        // duplicate rows while keeping price/change live.
        val byBaseSymbol =
            fresh
                .filter { it.price > 0.0 }
                .groupBy { norm(it.baseAsset) }
                .mapValues { (_, rows) ->
                    rows.maxByOrNull { it.volume }
                }

        old.map { coin ->

            val exchangeCoin =
                byExchangeId[coin.id]
                    ?: byBaseSymbol[norm(coin.symbol)]

            if (
                exchangeCoin != null &&
                exchangeCoin.price > 0.0
            ) {

                coin.copy(
                    name =
                        exchangeCoin.name
                            .ifBlank {
                                coin.name
                            },

                    image =
                        resolveCoinIcon(
                            exchangeCoin.baseAsset,
                            exchangeCoin.icon,
                            cachedId(
                                exchangeCoin.baseAsset
                            )
                        )
                            ?: coin.image,

                    currentPrice =
                        exchangeCoin.price,

                    priceChange24h =
                        exchangeCoin.change24h,

                    totalVolume =
                        exchangeCoin.volume,

                    source =
                        exchangeCoin.exchange,

                    exchangeSymbol =
                        if (
                            exchangeCoin.exchange ==
                            "COINDCX"
                        ) {
                            exchangeCoin.pair
                        } else {
                            exchangeCoin.symbol
                        }
                )

            } else {
                coin
            }
        }
    }

private suspend fun fetchBinanceSparkline(
    symbol: String
): List<Double> =
    withContext(Dispatchers.IO) {

        try {

            val array =
                JSONArray(
                    requestText(
                        "$BINANCE/api/v3/klines" +
                            "?symbol=${symbol.uppercase(Locale.US)}" +
                            "&interval=1h" +
                            "&limit=48"
                    )
                        ?: return@withContext emptyList()
                )

            List(array.length()) { index ->

                array
                    .optJSONArray(index)
                    ?.optString(4)
                    ?.toDoubleOrNull()
                    ?: 0.0

            }.filter {
                it > 0
            }

        } catch (_: Exception) {

            emptyList()
        }
    }

private suspend fun fetchCoinDcxSparkline(
    pair: String
): List<Double> =
    withContext(Dispatchers.IO) {

        if (
            pair.isBlank()
        ) {
            return@withContext emptyList()
        }

        try {

            val array =
                JSONArray(
                    requestText(
                        "$COINDCX_PUBLIC" +
                            "/market_data/candles" +
                            "?pair=$pair" +
                            "&interval=1h" +
                            "&limit=48"
                    )
                        ?: return@withContext emptyList()
                )

            List(array.length()) { index ->

                jDouble(
                    array.optJSONObject(index)
                        ?: JSONObject(),
                    "close"
                )

            }
                .filter { it > 0 }
                .reversed()

        } catch (_: Exception) {

            emptyList()
        }
    }

private suspend fun enrichSparklines(
    coins: List<CoinGeckoCoin>
): List<CoinGeckoCoin> =
    withContext(Dispatchers.IO) {

        val top =
            coins.take(5)

        val jobs =
            top.map { coin ->

                async {

                    if (
                        coin.sparkline.size >= 2
                    ) {
                        coin

                    } else if (
                        coin.source ==
                        "BINANCE"
                    ) {

                        coin.copy(
                            sparkline =
                                fetchBinanceSparkline(
                                    coin.exchangeSymbol
                                )
                        )

                    } else if (
                        coin.source ==
                        "COINDCX"
                    ) {

                        coin.copy(
                            sparkline =
                                fetchCoinDcxSparkline(
                                    coin.exchangeSymbol
                                )
                        )

                    } else {

                        coin
                    }
                }
            }

        val enriched =
            jobs.awaitAll()

        val byId =
            enriched.associateBy {
                it.id
            }

        coins.map {
            byId[it.id] ?: it
        }
    }
    
    

@Composable
fun CoinIcon(
    url: String,
    symbol: String,
    coinId: String? = null,
    size: Int = 40
) {

    val context =
        LocalContext.current

    val normalized =
        remember(symbol) {
            norm(symbol)
        }

    val primary =
        remember(
            url,
            normalized,
            coinId
        ) {
            resolveCoinIcon(
                symbol = normalized,
                apiImage = url,
                coinId = coinId
            )
        }

    var primaryFailed by
        remember(primary) {
            mutableStateOf(false)
        }

    var fallbackFailed by
        remember(normalized) {
            mutableStateOf(false)
        }

    val fallback =
        remember(normalized) {
            popularIcon(normalized)
        }

    val imageLoader =
        remember(context) {

            ImageLoader.Builder(context)
                .memoryCachePolicy(
                    CachePolicy.ENABLED
                )
                .diskCachePolicy(
                    CachePolicy.ENABLED
                )
                .crossfade(true)
                .build()
        }

    val finalUrl =
        when {

            !primary.isNullOrBlank() &&
                !primaryFailed ->
                primary

            !fallback.isNullOrBlank() &&
                !fallbackFailed ->
                fallback

            else ->
                null
        }

    if (
        finalUrl == null
    ) {

        val hue =
            (
                normalized
                    .hashCode()
                    .toUInt() %
                    360u
            ).toInt()

        val c1 =
            Color(
                android.graphics.Color.HSVToColor(
                    floatArrayOf(
                        hue.toFloat(),
                        0.65f,
                        0.90f
                    )
                )
            )

        val c2 =
            Color(
                android.graphics.Color.HSVToColor(
                    floatArrayOf(
                        ((hue + 45) % 360)
                            .toFloat(),
                        0.80f,
                        0.55f
                    )
                )
            )

        Box(
            modifier =
                Modifier
                    .size(size.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(c1, c2)
                        )
                    ),
            contentAlignment =
                Alignment.Center
        ) {

            Text(
                text =
                    normalized.take(2),
                color = Color.White,
                fontWeight =
                    FontWeight.Bold,
                fontSize =
                    (size / 3.5).sp
            )
        }

    } else {

        AsyncImage(

            model =
                ImageRequest.Builder(
                    context
                )
                    .data(finalUrl)
                    .crossfade(true)
                    .diskCachePolicy(
                        CachePolicy.ENABLED
                    )
                    .memoryCachePolicy(
                        CachePolicy.ENABLED
                    )
                    .build(),

            imageLoader =
                imageLoader,

            contentDescription =
                normalized,

            modifier =
                Modifier
                    .size(size.dp)
                    .clip(CircleShape),

            contentScale =
                ContentScale.Fit,

            onError = {

                if (
                    finalUrl == primary
                ) {
                    primaryFailed = true
                } else {
                    fallbackFailed = true
                }
            }
        )
    }
}

@Composable
fun SparklineChart(
    data: List<Double>,
    up: Boolean
) {

    if (
        data.size < 2
    ) {
        return
    }

    val line =
        if (up) UP else DN

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
    ) {

        val width =
            size.width

        val height =
            size.height

        val minValue =
            data.minOrNull() ?: 0.0

        val maxValue =
            (
                data.maxOrNull()
                    ?: minValue + 1
            ).let {
                if (
                    it <= minValue
                ) {
                    minValue + 1
                } else {
                    it
                }
            }

        fun x(index: Int): Float =
            width *
                index.toFloat() /
                (data.size - 1)

        fun y(value: Double): Float =
            height -
                (
                    (value - minValue) /
                        (maxValue - minValue)
                ).toFloat() *
                height

        val path =
            Path().apply {

                moveTo(
                    x(0),
                    y(data[0])
                )

                for (
                    index in 1 until data.size
                ) {

                    lineTo(
                        x(index),
                        y(data[index])
                    )
                }
            }

        val fill =
            Path().apply {

                addPath(path)

                lineTo(
                    width,
                    height
                )

                lineTo(
                    0f,
                    height
                )

                close()
            }

        drawPath(
            fill,
            Brush.verticalGradient(
                listOf(
                    line.copy(alpha = 0.35f),
                    Color.Transparent
                )
            )
        )

        drawPath(
            path,
            SolidColor(line),
            style =
                androidx.compose.ui.graphics
                    .drawscope.Stroke(
                        width = 2f
                    )
        )
    }
}

@Composable
fun TrendTile(
    coin: CoinGeckoCoin
) {

    val up =
        coin.priceChange24h >= 0

    val color =
        if (up) UP else DN

    Card(
        modifier =
            Modifier
                .width(170.dp)
                .height(96.dp)
                .border(
                    width = 1.dp,
                    color = Color(0xFF3A414D),
                    shape = RoundedCornerShape(12.dp)
                ),
        shape =
            RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = CARD
            )
    ) {

        Column(
            modifier =
                Modifier
                    .padding(12.dp)
                    .fillMaxSize()
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(
                    text =
                        coin.symbol + "USD",
                    fontWeight =
                        FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier =
                        Modifier.weight(1f)
                )

                Text(
                    if (up) "▲ " else "▼ ",
                    color = color,
                    fontSize = 10.sp
                )

                Text(
                    "${if (up) "+" else ""}" +
                        "${pct(coin.priceChange24h)}%",
                    color = color,
                    fontSize = 11.sp,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            Text(
                formatPrice(
                    coin.currentPrice
                ),
                fontWeight =
                    FontWeight.Bold,
                fontSize = 16.sp
            )

            Spacer(
                Modifier.weight(1f)
            )

            SparklineChart(
                coin.sparkline,
                up
            )
        }
    }
}

@Composable
fun TrendingRow(
    spark: List<CoinGeckoCoin>
) {

    if (
        spark.isEmpty()
    ) {
        return
    }

    LazyRow(
        horizontalArrangement =
            Arrangement.spacedBy(10.dp)
    ) {

        items(
            spark,
            key = { it.id }
        ) {

            TrendTile(it)
        }
    }
}

@Composable
fun QuickAction(
    icon: String,
    label: String,
    onClick: () -> Unit = {},
    bordered: Boolean = false
) {

    Column(
        horizontalAlignment =
            Alignment.CenterHorizontally,
        modifier =
            Modifier.clickable {
                onClick()
            }
    ) {

        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(
                        RoundedCornerShape(12.dp)
                    )
                    .background(CARD2)
                    .then(
                        if (bordered) {
                            Modifier.border(
                                width = 1.dp,
                                color = Color(0xFF2A313C),
                                shape = RoundedCornerShape(12.dp)
                            )
                        } else {
                            Modifier
                        }
                    ),
            contentAlignment =
                Alignment.Center
        ) {

            Text(
                icon,
                fontSize = 20.sp
            )
        }

        Spacer(
            Modifier.height(4.dp)
        )

        Text(
            label,
            color =
                Color(0xFF848E9C),
            fontSize = 10.sp
        )
    }
}

@Composable
fun QuickActionsRow(
    onAlgoClick: () -> Unit,
    onSettingsClick: () -> Unit
) {

    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {

        QuickAction(
            "⚙️",
            "Settings",
            onSettingsClick,
            bordered = true
        )

        QuickAction(
            "🛒",
            "Market",
            bordered = true
        )

        QuickAction(
            "🤖",
            "Algo",
            onAlgoClick,
            bordered = true
        )

        QuickAction(
            "💬",
            "Chat",
            bordered = true
        )

        QuickAction(
            "⋮",
            "More",
            bordered = true
        )
    }
}

@Composable
fun TopBar() {

    val viewModel:
        TradingViewModel =
        viewModel()

    var showAccount by
        remember {
            mutableStateOf(false)
        }

    Row(
        verticalAlignment =
            Alignment.CenterVertically,
        horizontalArrangement =
            Arrangement.spacedBy(8.dp)
    ) {

        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(CARD2)
                    .border(
                    width = 1.dp,
                    color = Color(0xFF3A414D),
                    shape = CircleShape
                )
                    .clickable {
                        showAccount = true
                    },
            contentAlignment =
                Alignment.Center
        ) {

            Text(
                "👤",
                fontSize = 16.sp
            )
        }

        Spacer(
            Modifier.weight(1f)
        )

        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(CARD2)
                .border(
                    width = 1.dp,
                    color = Color(0xFF3A414D),
                    shape = CircleShape
                ),
            contentAlignment =
                Alignment.Center
        ) {
            Text("⛶")
        }

        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(CARD2)
                .border(
                    width = 1.dp,
                    color = Color(0xFF3A414D),
                    shape = CircleShape
                ),
            contentAlignment =
                Alignment.Center
        ) {
            Text("💳")
        }

        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(CARD2)
                .border(
                    width = 1.dp,
                    color = Color(0xFF3A414D),
                    shape = CircleShape
                ),
            contentAlignment =
                Alignment.Center
        ) {

            Text("🔔")

            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(ACCENT)
                    .align(
                        Alignment.TopEnd
                    )
            )
        }
    }

    if (
        showAccount
    ) {

        AccountApiDialogV2(
            viewModel
        ) {
            showAccount = false
        }
    }
}

@Composable
fun RgbAiIcon() {

    val infinite =
        rememberInfiniteTransition(
            label = "rgb"
        )

    val angle by
        infinite.animateFloat(
            0f,
            360f,
            infiniteRepeatable(
                tween(
                    2400,
                    easing =
                        LinearEasing
                ),
                RepeatMode.Restart
            ),
            label = "rot"
        )

    Box(
        Modifier
            .size(26.dp)
            .rotate(angle)
            .background(
                Brush.sweepGradient(
                    listOf(
                        Color.Red,
                        Color.Yellow,
                        Color.Green,
                        Color.Blue,
                        Color.Magenta,
                        Color.Red
                    )
                ),
                CircleShape
            ),
        contentAlignment =
            Alignment.Center
    ) {

        Box(
            Modifier
                .size(18.dp)
                .background(
                    Color(0xFF16181D),
                    CircleShape
                ),
            contentAlignment =
                Alignment.Center
        ) {

            Text(
                "AI",
                color = Color.White,
                fontSize = 8.sp,
                fontWeight =
                    FontWeight.Bold
            )
        }
    }
}

@Composable
fun AiSearchBar(
    query: String,
    onQuery: (String) -> Unit
) {

    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        leadingIcon = {
            RgbAiIcon()
        },
        placeholder = {
            Text(
                "AI search any coin (PEPE, SHIB, FLOKI...)",
                fontSize = 13.sp,
                color = Color(0xFF848E9C)
            )
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .height(54.dp),
        singleLine = true,
        textStyle =
            LocalTextStyle.current.copy(
                fontSize = 14.sp,
                color = Color.White
            ),
        shape =
            RoundedCornerShape(27.dp)
    )
}

@Composable
fun AccountCard(
    balance: Double,
    hidden: Boolean,
    onToggleEye: () -> Unit,
    botRunning: Boolean
) {

    Card(
        Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = Color(0xFF2A313C),
                shape = RoundedCornerShape(25.dp)
            ),
        shape =
            RoundedCornerShape(14.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = CARD
            )
    ) {

        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 1.dp)
        ) {

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Row {

                    Text(
                        "Account Value",
                        color = 
                            Color(0xFF848E9C),
                        fontSize = 13.sp
                    )

                    Text(
                        "  ›",
                        color = ACCENT
                    )

                    if (botRunning) {
                        // RGB live signature: no background/card, only animated
                        // running indicator. It is completely hidden when the bot is OFF.
                        val botRgb = rememberInfiniteTransition(
                            label = "autoBotRgb"
                        )

                        val rgbGreen by botRgb.animateColor(
                            initialValue = Color(0xFF00FF66),
                            targetValue = Color(0xFF00FFFF),
                            animationSpec = infiniteRepeatable(
                                animation = tween(900),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "botGreen"
                        )

                        val rgbBlue by botRgb.animateColor(
                            initialValue = Color(0xFF00B7FF),
                            targetValue = Color(0xFFB000FF),
                            animationSpec = infiniteRepeatable(
                                animation = tween(1100),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "botBlue"
                        )

                        val rgbPink by botRgb.animateColor(
                            initialValue = Color(0xFFFF2DB2),
                            targetValue = Color(0xFFFF0066),
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "botPink"
                        )

                        Spacer(
                            Modifier.width(8.dp)
                        )

                        Row(
                            modifier = Modifier.wrapContentWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // RGB running dot
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        rgbGreen,
                                        CircleShape
                                    )
                            )

                            Spacer(Modifier.width(4.dp))

                            Text(
                                "〰",
                                color = rgbBlue,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "AUTO BOT",
                                color = rgbBlue,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                " • ",
                                color = Color(0xFFFFFF00),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "LIVE",
                                color = rgbGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "〰",
                                color = rgbPink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

            }

            Spacer(
                Modifier.height(5.dp)
            )

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    if (hidden)
                        "• • • • •"
                    else
                        String.format(
                            Locale.US,
                            "$%,.2f",
                            balance
                        ),
                    fontWeight =
                        FontWeight.Bold,
                    fontSize = 22.sp
                )

                Spacer(
                    Modifier.width(8.dp)
                )

                if (!hidden) {

                    Text(
                        String.format(
                            Locale.US,
                            "₹%,.0f",
                            balance * 83
                        ),
                        color =
                            Color(0xFF848E9C),
                        fontSize = 13.sp
                    )
                }

                Spacer(
                    Modifier.weight(1f)
                )

                Text(
                    if (hidden)
                        "🙈"
                    else
                        "👁",
                    modifier =
                        Modifier.clickable {
                            onToggleEye()
                        }
                )
            }

            Spacer(
                Modifier.height(6.dp)
            )

            Card(
                shape =
                    RoundedCornerShape(10.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            CARD2
                    )
            ) {

                Row(
                    Modifier
                        .padding(12.dp)
                        .fillMaxWidth()
                ) {

                    Column(
                        Modifier.weight(1f)
                    ) {

                        Text(
                            "UPNL",
                            color =
                                Color(0xFF848E9C),
                            fontSize = 12.sp
                        )

                        Spacer(
                            Modifier.height(4.dp)
                        )

                        Text(
                            "₹0.00  $0.00",
                            fontWeight =
                                FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Box(
                        Modifier
                            .width(1.dp)
                            .height(38.dp)
                            .background(
                                Color(0xFF3A3F47)
                            )
                    )

                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {

                        Text(
                            "Positions / Orders",
                            color =
                                Color(0xFF848E9C),
                            fontSize = 12.sp
                        )

                        Spacer(
                            Modifier.height(4.dp)
                        )

                        Text(
                            "0 / 0",
                            fontWeight =
                                FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DistributionBar(
    decl: Int,
    adv: Int
) {

    Column {

        Text(
            "Price Change Distribution",
            color =
                Color(0xFF848E9C),
            fontSize = 12.sp
        )

        Spacer(
            Modifier.height(6.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(
                    RoundedCornerShape(3.dp)
                )
        ) {

            Box(
                Modifier
                    .weight(
                        maxOf(
                            1,
                            decl
                        ).toFloat()
                    )
                    .fillMaxHeight()
                    .background(DN)
            )

            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        Color(0xFF3A3F47)
                    )
            )

            Box(
                Modifier
                    .weight(
                        maxOf(
                            1,
                            adv
                        ).toFloat()
                    )
                    .fillMaxHeight()
                    .background(UP)
            )
        }

        Spacer(
            Modifier.height(4.dp)
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            Text(
                "Decliners: $decl",
                color =
                    Color(0xFF848E9C),
                fontSize = 11.sp
            )

            Text(
                "Advancers: $adv",
                color =
                    Color(0xFF848E9C),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun FilterChips(
    filter: String,
    onFilter: (String) -> Unit
) {

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        // IMPORTANT: UI labels contain icons, but the filter state uses
        // plain keys (All/Gainers/Losers/Volume). Keep those two separate
        // so every filter click actually changes the displayed coin list.
        listOf(
            "All" to "🪐",
            "Gainers" to "🔋",
            "Losers" to "🪫",
            "Volume" to "☄️"
        ).forEach { (key, icon) ->

            val selected =
                filter == key

            Surface(
                onClick = {
                    onFilter(key)
                },
                shape =
                    RoundedCornerShape(20.dp),
                color =
                    if (selected)
                        CARD2
                    else
                        Color.Transparent,
                border =
                    if (selected)
                        null
                    else
                        BorderStroke(
                            1.dp,
                            Color(0xFF3A3F47)
                        )
            ) {

                Text(
                    "$icon${key}",
                    maxLines = 1,
                    softWrap = false,
                    color =
                        if (selected)
                            Color.Red
                        else
                            Color(0xFFB7BDC4),
                    fontSize = 12.sp,
                    modifier =
                        Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        )
                )
            }
        }
    }
}

@Composable
fun CoinRow(
    coin: CoinGeckoCoin,
    onChart: () -> Unit,
    onTrade: () -> Unit
) {

    val up =
        coin.priceChange24h >= 0

    val color =
        if (up) UP else DN

    Card(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .height(60.dp),
        shape =
            RoundedCornerShape(30.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = CARD
            ),
        border =
            BorderStroke(
                1.dp,
                Color(0xFF26384A)
            )
    ) {

        Row(
            verticalAlignment =
                Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .clickable {
                        onChart()
                    }
        ) {

            CoinIcon(
                coin.image,
                coin.symbol,
                coin.id,
                30
            )

            Spacer(
                Modifier.width(8.dp)
            )

            Column(
                Modifier.weight(1f)
            ) {

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        coin.symbol,
                        fontWeight =
                            FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )

                    if (
                        abs(
                            coin.priceChange24h
                        ) > 5
                    ) {

                        Spacer(
                            Modifier.width(4.dp)
                        )

                        Text(
                            text = "🔥",
                            fontSize = 16.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }

                Text(
                    coin.name,
                    color =
                        Color(0xFF848E9C),
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
                
                Spacer(
                    Modifier.width(12.dp)
                )

            Box(
                modifier =
                    Modifier
                        .width(80.dp)
                        .height(30.dp),
                contentAlignment =
                    Alignment.Center
            ) {
                SparklineChart(
                    data = coin.sparkline,
                    up = up
                )
            }
            
                Spacer(
                    Modifier.width(14.dp)
                )
                
            Column(
                horizontalAlignment =
                    Alignment.End
            ) {

                Text(
                    formatPrice(
                        coin.currentPrice
                    ),
                    fontWeight =
                        FontWeight.Bold,
                    fontSize = 10.sp
                )

                Spacer(
                    Modifier.height(4.dp)
                )

                Surface(
                    shape =
                        RoundedCornerShape(15.dp),
                    color = color
                ) {

                    Text(
                        "${if (up) "+" else ""}" +
                            "${pct(coin.priceChange24h)}%",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight =
                            FontWeight.Bold,
                        modifier =
                            Modifier.padding(
                                horizontal = 6.dp,
                                vertical = 2.dp
                            )
                    )
                }
            }

            Spacer(
                Modifier.width(10.dp)
            )

            Text(
                "💱",
                fontSize = 14.sp,
                modifier =
                    Modifier.clickable {
                        onTrade()
                    }
            )
        }
    }
}

@Composable
fun MarketHeader(
    spark: List<CoinGeckoCoin>,
    balance: Double,
    filter: String,
    onFilter: (String) -> Unit,
    hidden: Boolean,
    onToggleEye: () -> Unit,
    decl: Int,
    adv: Int,
    onAlgoClick: () -> Unit,
    onSettingsClick: () -> Unit,
    botRunning: Boolean
) {

    Column(
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        TopBar()

        AccountCard(
            balance,
            hidden,
            onToggleEye,
            botRunning
        )

        QuickActionsRow(
            onAlgoClick,
            onSettingsClick
        )

        TrendingRow(spark)

        DistributionBar(
            decl,
            adv
        )

        FilterChips(
            filter,
            onFilter
        )
    }
}

private suspend fun fetchAllExchangeCoinRows(): List<CoinGeckoCoin> =
    withContext(Dispatchers.IO) {
        try {
            val rows=fetchBinanceExchangeCoins()+fetchCoinDCXExchangeCoins()+fetchAdditionalExchangeCoins()
            rows.filter{it.price>0.0 && it.baseAsset.isNotBlank()}
                .groupBy{"${it.exchange}:${norm(it.baseAsset)}:${it.quoteAsset}"}
                .values.mapNotNull{list->
                    val row=list.maxByOrNull{it.volume} ?: return@mapNotNull null
                    val base=norm(row.baseAsset)
                    CoinGeckoCoin(
                        id="exchange:${row.exchange}:$base:${row.pair.ifBlank{row.symbol}}",
                        symbol=base,name=row.name.ifBlank{coinName(base)},
                        image=resolveCoinIcon(base,row.icon,cachedId(base)).orEmpty(),
                        currentPrice=row.price,priceChange24h=row.change24h,totalVolume=row.volume,
                        source=row.exchange,exchangeSymbol=row.pair.ifBlank{row.symbol}
                    )
                }
        } catch(_:Exception){ emptyList() }
    }

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun CoinGeckoMarketScreen(
    viewModel: TradingViewModel
) {

    var coins by
        remember {
            mutableStateOf(
                getFallbackCoins()
            )
        }

    var sparkCoins by
        remember {
            mutableStateOf(
                emptyList<CoinGeckoCoin>()
            )
        }

    var loading by
        remember {
            mutableStateOf(false)
        }

    var initialLoadFinished by
        remember {
            mutableStateOf(false)
        }

    var error by
        remember {
            mutableStateOf<String?>(null)
        }

    var query by
        remember {
            mutableStateOf("")
        }

    var chartCoin by
        remember {
            mutableStateOf<CoinGeckoCoin?>(null)
        }

    var tradeCoin by
        remember {
            mutableStateOf<CoinGeckoCoin?>(null)
        }

    var hidden by
        remember {
            mutableStateOf(false)
        }

    var filter by
        remember {
            mutableStateOf("All")
        }

    val coinListState = rememberLazyListState()

    var showBotAlgo by
        remember {
            mutableStateOf(false)
        }

    var showSettings by
        remember {
            mutableStateOf(false)
        }

    val scope =
        rememberCoroutineScope()

    var lastGeckoRefresh by
        remember {
            mutableStateOf(0L)
        }

    val appContext = LocalContext.current

    fun loadData() {

        scope.launch {

            error = null

            try {

                // FAST INITIAL LOAD: fetch the first five CoinGecko market pages
                // in parallel and render them within a 5-second startup window.
                // The remaining pages continue loading in the background, so the
                // list eventually contains the full live CoinGecko universe.
                // Start CoinGecko and live-exchange fallback together. The UI
                // gets a real live universe within the startup window even when
                // CoinGecko is temporarily rate-limited.
                val initialResult =
                    withTimeoutOrNull(5_000L) {
                        coroutineScope {
                            val gecko = async(Dispatchers.IO) {
                                fetchCoinGeckoPagesParallel(1, 5, concurrency = 4)
                            }
                            val exchange = async(Dispatchers.IO) {
                                fetchLiveExchangeFallbackCoins()
                            }

                            // Whichever live source responds first wins. This
                            // prevents a slow/rate-limited CoinGecko request
                            // from consuming the complete 5-second startup.
                            select<List<CoinGeckoCoin>> {
                                gecko.onAwait { data ->
                                    if (data.any { it.currentPrice > 0.0 }) {
                                        data
                                    } else {
                                        exchange.await()
                                    }
                                }
                                exchange.onAwait { data ->
                                    if (data.any { it.currentPrice > 0.0 }) {
                                        data
                                    } else {
                                        gecko.await()
                                    }
                                }
                            }
                        }
                    }.orEmpty()

                if (initialResult.any { it.currentPrice > 0.0 }) {
                    coins = initialResult
                    scope.launch {
                        val exchangeRows=fetchAllExchangeCoinRows()
                        if(exchangeRows.isNotEmpty()){
                            val merged=LinkedHashMap<String,CoinGeckoCoin>(coins.size+exchangeRows.size)
                            coins.forEach{merged[it.id]=it}
                            exchangeRows.forEach{merged[it.id]=it}
                            coins=merged.values.toList()
                            saveCoinListCache(appContext,coins.toList())
                        }
                    }
                    sparkCoins =
                        enrichSparklines(
                            initialResult.sortedByDescending { it.totalVolume }
                        ).take(5)
                    lastGeckoRefresh = System.currentTimeMillis()

                    // Persist immediately so the next app launch can restore
                    // the live list without waiting for the network.
                    val initialSnapshot = initialResult.toList()
                    scope.launch(Dispatchers.IO) {
                        saveCoinListCache(appContext, initialSnapshot)
                    }

                    // Continue loading the complete CoinGecko universe in the
                    // background. If that endpoint is unavailable, keep the
                    // live exchange list instead of replacing it with 5 fallbacks.
                    scope.launch {
                        try {
                            // Load the remaining universe in small batches instead
                            // of fetching 65 pages and replacing the entire list in
                            // one giant recomposition. This keeps scrolling smooth.
                            var page = 6
                            while (page <= GLOBAL_MARKET_PAGES && isActive) {
                                val batchEnd =
                                    (page + 4).coerceAtMost(GLOBAL_MARKET_PAGES)

                                val batch =
                                    fetchCoinGeckoPagesParallel(
                                        page,
                                        batchEnd,
                                        concurrency = 3
                                    )

                                if (batch.isNotEmpty()) {
                                    val merged =
                                        LinkedHashMap<String, CoinGeckoCoin>(
                                            coins.size + batch.size
                                        )
                                    coins.forEach { merged[it.id] = it }
                                    batch.forEach { merged[it.id] = it }

                                    // One small UI update per 5 pages rather than
                                    // one huge update after the entire universe.
                                    val mergedSnapshot = merged.values.toList()
                                    coins = mergedSnapshot

                                    // Keep the persistent cache in sync with each
                                    // completed batch. Disk work stays off the UI.
                                    val cacheSnapshot = mergedSnapshot.toList()
                                    scope.launch(Dispatchers.IO) {
                                        saveCoinListCache(appContext, cacheSnapshot)
                                    }
                                }

                                page = batchEnd + 1

                                // Give Compose/UI a frame between network batches.
                                yield()
                                delay(120L)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Never blank a working live list because a background
                            // page failed.
                        }
                    }
                } else {
                    error = "Live market data unavailable. Retry."
                }

            } catch (_: Exception) {

                error =
                    "Live data nahi aa raha. Retry karo."
            }

            initialLoadFinished = true
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        // Restore the last complete/live list first. This makes app reopen
        // instant and avoids showing the 5-coin fallback while the network
        // refresh is happening. The network result still replaces stale data.
        val cached =
            withContext(Dispatchers.IO) {
                loadCoinListCache(appContext)
            }

        if (cached.size >= 5) {
            coins = cached
            initialLoadFinished = true
            sparkCoins =
                cached
                    .sortedByDescending { it.totalVolume }
                    .take(5)
        }

        loadData()
    }

    LaunchedEffect(Unit) {

        while (isActive) {

            delay(1_000)

            if (initialLoadFinished && !loading) {

                try {

                    // Refresh the same original CoinGecko market-cap list.
                    // The 1-second loop remains only as the scheduler; network
                    // refresh is throttled so the public API is not hammered.
                    if (
                        System.currentTimeMillis() -
                            lastGeckoRefresh >=
                        30_000
                    ) {

                        // Refresh only the first live market pages on the
                        // timer. Re-fetching all 70 pages every 30 seconds causes
                        // unnecessary network traffic and large list churn.
                        val gecko =
                            fetchCoinGeckoPagesParallel(
                                1,
                                5,
                                concurrency = 3
                            )

                        if (gecko.any { it.currentPrice > 0 }) {
                            val freshById =
                                gecko.associateBy { it.id }

                            coins =
                                coins.map { old ->
                                    freshById[old.id] ?: old
                                } +
                                    gecko.filter { fresh ->
                                        coins.none { it.id == fresh.id }
                                    }

                            sparkCoins =
                                enrichSparklines(
                                    coins
                                        .sortedByDescending { it.totalVolume }
                                ).take(5)

                            val refreshSnapshot = coins.toList()
                            scope.launch(Dispatchers.IO) {
                                saveCoinListCache(appContext, refreshSnapshot)
                            }

                            lastGeckoRefresh =
                                System.currentTimeMillis()
                        }
                    }

                } catch (_: Exception) {
                }
            }
        }
    }

    val base =
        if (
            query.isBlank()
        ) {
            coins
        } else {

            coins.filter {

                it.name.contains(
                    query,
                    true
                ) ||
                    it.symbol.contains(
                        query,
                        true
                    ) ||
                    it.id.contains(
                        query,
                        true
                    )
            }
        }

    // IMPORTANT PERFORMANCE FIX:
    // These lists can contain thousands of coins. Do not filter/sort/count
    // them on every LazyColumn scroll recomposition.
    val display =
        remember(coins, query, filter) {
            when (filter) {

                "Gainers" ->
                    base
                        .filter {
                            it.priceChange24h > 0
                        }
                        .sortedByDescending {
                            it.priceChange24h
                        }

                "Losers" ->
                    base
                        .filter {
                            it.priceChange24h < 0
                        }
                        .sortedBy {
                            it.priceChange24h
                        }

                "Volume" ->
                    base.sortedByDescending {
                        it.totalVolume
                    }

                else ->
                    // Keep CoinGecko's original market-cap order for the All tab.
                    base
            }
        }

    val decl =
        remember(coins) {
            coins.count {
                it.priceChange24h < 0
            }
        }

    val adv =
        remember(coins) {
            coins.count {
                it.priceChange24h > 0
            }
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.28f))
    ) {
        LazyColumn(
            state = coinListState,
            userScrollEnabled = true,
            modifier =
                Modifier.fillMaxSize(),
            // Only the coin-list viewport gets extra right room for the
            // scrollbar. Nothing outside this LazyColumn is resized.
            // Keep the full screen width for the header, filters and search.
            // Only individual coin cards reserve space for the scrollbar.
            contentPadding =
                PaddingValues(
                    start = 0.dp,
                    top = 14.dp,
                    end = 0.dp,
                    bottom = 14.dp
                ),
            // Native Compose touch scrolling remains enabled: swipe up/down
            // anywhere in the list to scroll; the scrollbar is only an extra
            // drag control on the right edge.
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

        item {

            MarketHeader(
                sparkCoins,
                viewModel.portfolioValue.value,
                filter,
                { filter = it },
                hidden,
                {
                    hidden = !hidden
                },
                decl,
                adv,
                {
                    showBotAlgo = true
                },
                {
                    showSettings = true
                },
                viewModel.isBotRunning.value
            )
        }

        item {

            AiSearchBar(
                query
            ) {
                query = it
            }
        }

        item {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Crypto Coins",
                    color = Color(0xFF848E9C),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    "${display.size} coins",
                    color = Color(0xFFB7BDC4),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {

            when {

                loading &&
                    coins.all {
                        it.currentPrice <= 0
                    } -> {

                    Column(
                        horizontalAlignment =
                            Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                    ) {

                        CircularProgressIndicator(
                            color = ACCENT
                        )

                        Spacer(
                            Modifier.height(12.dp)
                        )

                        Text(
                            "Loading live markets...",
                            color =
                                Color(0xFF848E9C)
                        )

                        Spacer(
                            Modifier.height(8.dp)
                        )

                        Button(
                            onClick = {
                                loadData()
                            },
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        ACCENT
                                )
                        ) {

                            Text(
                                "Retry",
                                color =
                                    Color.White
                            )
                        }
                    }
                }

                error != null &&
                    coins.all {
                        it.currentPrice <= 0
                    } -> {

                    Column(
                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {

                        Text(
                            "⚠️ $error",
                            color = DN,
                            fontSize = 12.sp
                        )

                        Button(
                            onClick = {
                                loadData()
                            },
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor =
                                        ACCENT
                                )
                        ) {

                            Text(
                                "🔄 Retry",
                                color =
                                    Color.White
                            )
                        }
                    }
                }

                else -> {

                    Text(
                        "💯 LIVE • Exchange • Price • 24h Chg",
                        color = 
                            Color(0xFF5E6673),
                        fontSize = 11.sp
                    )
                }
            }
        }

        items(
            display,
            key = {
                it.id
            }
        ) { coin ->

            // Reserve scrollbar space ONLY for coin cards. The rest of the
            // screen keeps its original full width.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                CoinRow(
                    coin = coin,
                    onChart = {
                        chartCoin = coin
                    },
                    onTrade = {
                        tradeCoin = coin
                    }
                )
            }
        }
    }

        }


        // ============================================================
        // ============================================================
        // ============================================================
        // COIN-LIST SCROLL THUMB — FINAL
        //
        // The scrollbar belongs to the coin list:
        // • position 0 = first coin (coin #0)
        // • position 1 = last possible coin
        // • header/search/status rows are ignored
        // • the visual track follows the coin-list start, so it does not
        //   appear at the top of the page before the coins start
        // ============================================================
        if (CoinScrollThumbSettings.enabled) {
            // ============================================================
            // FULL RGB RAINBOW THUMB
            // Red -> Yellow -> Green -> Cyan -> Blue -> Magenta -> Red
            // ============================================================
            val rgbTransition = rememberInfiniteTransition(
                label = "RGB Thumb Animation"
            )

            val animatedThumbColor by rgbTransition.animateColor(
                initialValue = Color.Red,
                targetValue = Color.Yellow,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 5000

                        Color.Red at 0
                        Color.Yellow at 1000
                        Color.Green at 2000
                        Color.Cyan at 3000
                        Color.Blue at 4000
                        Color.Magenta at 5000
                        Color.Red at 6000
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "RGB Thumb Color"
            )

            val thumbDensity = LocalDensity.current

            val scrollThumbData by remember {
                derivedStateOf {
                    val info = coinListState.layoutInfo
                    val totalItems = info.totalItemsCount
                    val startIndex =
                        CoinScrollThumbSettings.coinListStartItemIndex

                    val coinCount =
                        (totalItems - startIndex).coerceAtLeast(0)

                    val visibleCoinItems =
                        info.visibleItemsInfo.filter {
                            it.index >= startIndex
                        }

                    if (
                        coinCount > 0 &&
                        visibleCoinItems.isNotEmpty()
                    ) {
                        val firstVisible =
                            coinListState.firstVisibleItemIndex

                        val firstOffset =
                            coinListState.firstVisibleItemScrollOffset

                        // CoinRow = 60dp and LazyColumn spacing = 12dp.
                        // Use the real measured coin-row size when available.
                        val rowSizePx =
                            visibleCoinItems
                                .firstOrNull()
                                ?.size
                                ?.toFloat()
                                ?.coerceAtLeast(1f)
                                ?: with(thumbDensity) {
                                    60.dp.toPx()
                                }

                        val spacingPx =
                            with(thumbDensity) {
                                12.dp.toPx()
                            }

                        val rowExtentPx =
                            (
                                rowSizePx +
                                    spacingPx
                            ).coerceAtLeast(1f)

                        val viewportPx =
                            (
                                info.viewportEndOffset -
                                    info.viewportStartOffset
                            )
                                .toFloat()
                                .coerceAtLeast(1f)

                        val visibleCoinSlots =
                            (
                                (
                                    viewportPx +
                                        spacingPx
                                ) /
                                    rowExtentPx
                            )
                                .roundToInt()
                                .coerceAtLeast(1)

                        // Continuous coin position:
                        // coin #0 = 0, coin #1 = 1, ... last = max.
                        val coinPosition =
                            if (firstVisible < startIndex) {
                                0f
                            } else {
                                (
                                    firstVisible -
                                        startIndex
                                ).toFloat() +
                                    firstOffset.toFloat() /
                                        rowExtentPx
                            }

                        val maxCoinPosition =
                            (
                                coinCount -
                                    visibleCoinSlots
                            )
                                .coerceAtLeast(1)
                                .toFloat()

                        val progress =
                            (
                                coinPosition /
                                    maxCoinPosition
                            )
                                .coerceIn(0f, 1f)

                        val thumbFraction =
                            (
                                visibleCoinSlots.toFloat() /
                                    coinCount.toFloat()
                            )
                                .coerceIn(
                                    CoinScrollThumbSettings
                                        .minThumbFraction,
                                    1f
                                ) *
                                CoinScrollThumbSettings
                                    .thumbFractionScale

                        // Current screen position of coin #0.
                        // When coin #0 is visible, use its REAL measured
                        // LazyColumn offset. This is critical because the
                        // header rows are not the same height as CoinRow.
                        // After coin #0 leaves the viewport, derive its
                        // position from the measured coin-row extent.
                        val visibleCoinZeroOffsetPx =
                            info.visibleItemsInfo
                                .firstOrNull {
                                    it.index == startIndex
                                }
                                ?.offset
                                ?.toFloat()

                        val coinZeroOffsetPx =
                            visibleCoinZeroOffsetPx
                                ?: if (
                                    firstVisible >= startIndex
                                ) {
                                    (
                                        startIndex -
                                            firstVisible
                                    ).toFloat() *
                                        rowExtentPx -
                                        firstOffset.toFloat()
                                } else {
                                    // Very short viewport fallback: keep the
                                    // track at the bottom until coin #0 enters.
                                    viewportPx
                                }

                        Triple(
                            progress,
                            thumbFraction.coerceIn(
                                CoinScrollThumbSettings
                                    .minThumbFraction,
                                1f
                            ),
                            coinZeroOffsetPx
                        )
                    } else {
                        Triple(
                            0f,
                            CoinScrollThumbSettings
                                .minThumbFraction,
                            0f
                        )
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {
                val progress = scrollThumbData.first
                val thumbFraction = scrollThumbData.second
                val coinZeroOffsetPx = scrollThumbData.third

                val density = LocalDensity.current

                // The track starts where coin #0 is currently located.
                // Once coin #0 scrolls above the screen, the track starts
                // at the viewport top (0dp) and uses the full remaining
                // viewport. This prevents the thumb from being stuck at
                // the header or stopping after only a few coins.
                val coinZeroOffsetDp =
                    with(density) {
                        coinZeroOffsetPx.toDp()
                    }

                val trackStart =
                    (
                        coinZeroOffsetDp +
                            CoinScrollThumbSettings
                                .coinListStartOffsetDp.dp
                    )
                        .coerceIn(
                            0.dp,
                            maxHeight
                        )

                val trackHeight =
                    (
                        maxHeight -
                            trackStart -
                            CoinScrollThumbSettings
                                .trackPaddingBottomDp.dp
                    )
                        .coerceAtLeast(0.dp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.End)
                        .width(
                            CoinScrollThumbSettings
                                .trackWidthDp.dp
                        )
                        .height(trackHeight)
                        .offset(y = trackStart)
                        .padding(
                            top =
                                CoinScrollThumbSettings
                                    .trackPaddingTopDp.dp
                        )
                ) {
                    val thumbHeight =
                        (
                            trackHeight *
                                thumbFraction
                        )
                            .coerceAtLeast(
                                CoinScrollThumbSettings
                                    .minHeightDp.dp
                            )
                            .coerceAtMost(trackHeight)

                    val travel =
                        (
                            trackHeight -
                                thumbHeight
                        )
                            .coerceAtLeast(0.dp) *
                            CoinScrollThumbSettings
                                .travelScale

                    val travelPx =
                        with(density) {
                            travel.toPx()
                        }

                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(
                                    x =
                                        CoinScrollThumbSettings
                                            .horizontalOffsetDp.dp,
                                    y =
                                        travel * progress +
                                            CoinScrollThumbSettings
                                                .verticalOffsetDp.dp
                                )
                                .width(
                                    CoinScrollThumbSettings
                                        .widthDp.dp
                                )
                                .height(
                                    thumbHeight +
                                        CoinScrollThumbSettings
                                            .touchHeightExtraDp.dp
                                )
                                .background(
                                    animatedThumbColor.copy(
                                        alpha =
                                            CoinScrollThumbSettings
                                                .alpha
                                    ),
                                    RoundedCornerShape(
                                        CoinScrollThumbSettings
                                            .cornerRadiusDp.dp
                                    )
                                )
                                .draggable(
                                    orientation =
                                        Orientation.Vertical,
                                    state =
                                        rememberDraggableState {
                                            delta ->
                                            if (
                                                travelPx > 0f
                                            ) {
                                                val newProgress =
                                                    (
                                                        scrollThumbData
                                                            .first +
                                                            (
                                                                delta *
                                                                    CoinScrollThumbSettings
                                                                        .dragSensitivity
                                                            ) /
                                                            travelPx
                                                    )
                                                        .coerceIn(
                                                            0f,
                                                            1f
                                                        )

                                                val total =
                                                    coinListState
                                                        .layoutInfo
                                                        .totalItemsCount

                                                val visible =
                                                    coinListState
                                                        .layoutInfo
                                                        .visibleItemsInfo
                                                        .size

                                                val start =
                                                    CoinScrollThumbSettings
                                                        .coinListStartItemIndex

                                                val lastFirstVisible =
                                                    (
                                                        total -
                                                            visible
                                                    )
                                                        .coerceAtLeast(
                                                            start
                                                        )

                                                val target =
                                                    (
                                                        start +
                                                            newProgress *
                                                                (
                                                                    lastFirstVisible -
                                                                        start
                                                                )
                                                    )
                                                        .roundToInt()
                                                        .coerceIn(
                                                            start,
                                                            lastFirstVisible
                                                        )

                                                scope.launch {
                                                    coinListState
                                                        .scrollToItem(
                                                            target,
                                                            0
                                                        )
                                                }
                                            }
                                        }
                                )
                        )
                    }
                }
            }
        }

    chartCoin?.let { coin ->
        val context = LocalContext.current
        LaunchedEffect(coin.id, coin.symbol, coin.exchangeSymbol) {
            CryptoChartActivity.open(
                context = context,
                symbol = coin.exchangeSymbol.ifBlank { coin.symbol }
            )
            chartCoin = null
        }
    }

    tradeCoin?.let { coin ->

        CoinTradeDialog(
            coin,
            viewModel,
            {
                tradeCoin = null
            },
            {
                chartCoin = coin
                tradeCoin = null
            }
        )
    }

    if (
        showBotAlgo
    ) {

        FullScreenDialog(
            title =
                "🛸 Algo Bot ",
            onClose = {
                showBotAlgo = false
            }
        ) {

            BotDashboardScreen(
                viewModel
            )
        }
    }

    if (
        showSettings
    ) {

        FullScreenDialog(
            title = "⚙️ Settings",
            onClose = {
                showSettings = false
            }
        ) {

            SettingsScreen(
                viewModel
            )
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
private fun FullScreenDialog(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {

    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false
            )
    ) {

        Surface(
            Modifier.fillMaxSize(),
            color =
                MaterialTheme
                    .colorScheme
                    .background
        ) {

            Column(
                Modifier.fillMaxSize()
            ) {

                TopAppBar(
                    title = {
                        Text(
                            title,
                            fontWeight =
                                FontWeight.Bold
                        )
                    },
                    navigationIcon = {

                        IconButton(
                            onClick = onClose
                        ) {

                            Text(
                                "✖",
                                fontSize = 20.sp
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults
                            .topAppBarColors(
                                containerColor =
                                    MaterialTheme
                                        .colorScheme
                                        .surface
                            )
                )

                Box(
                    Modifier.weight(1f)
                ) {
                    content()
                }
            }
        }
    }
}



private suspend fun fetchLiveQuote(
    coin: CoinGeckoCoin
): Pair<Double, Double>? =
    withContext(Dispatchers.IO) {

        try {

            when (coin.source) {

                "BINANCE" -> {

                    val obj =
                        JSONObject(
                            requestText(
                                "$BINANCE/api/v3/ticker/24hr" +
                                    "?symbol=${coin.exchangeSymbol}"
                            )
                                ?: return@withContext null
                        )

                    jDouble(
                        obj,
                        "lastPrice"
                    ) to
                        jDouble(
                            obj,
                            "priceChangePercent"
                        )
                }

                "COINDCX" -> {

                    val array =
                        JSONArray(
                            requestText(
                                "$COINDCX/exchange/ticker"
                            )
                                ?: return@withContext null
                        )

                    for (
                        index in 0 until array.length()
                    ) {

                        val obj =
                            array.optJSONObject(index)
                                ?: continue

                        if (
                            obj.optString(
                                "market"
                            )
                                .equals(
                                    coin.symbol,
                                    true
                                )
                        ) {

                            return@withContext (
                                jDouble(
                                    obj,
                                    "last_price"
                                ) to
                                    jDouble(
                                        obj,
                                        "change_24_hour"
                                    )
                                )
                        }
                    }

                    null
                }

                else -> {

                    val obj =
                        JSONObject(
                            requestText(
                                "$CG_SIMPLE" +
                                    "?ids=${coin.id}" +
                                    "&vs_currencies=usd" +
                                    "&include_24hr_change=true"
                            )
                                ?: return@withContext null
                        )
                            .optJSONObject(
                                coin.id
                            )
                            ?: return@withContext null

                    jDouble(
                        obj,
                        "usd"
                    ) to
                        jDouble(
                            obj,
                            "usd_24h_change"
                        )
                }
            }

        } catch (_: Exception) {

            null
        }
    }
private suspend fun fetchCoinDcxLivePrice(
    symbol: String
): Pair<Double, Double>? =
    withContext(Dispatchers.IO) {

        var connection: HttpURLConnection? = null

        try {

            val clean =
                normalizeCoinSymbol(symbol)

            val url =
                URL(
                    "https://api.coindcx.com/exchange/ticker"
                )

            connection =
                url.openConnection()
                    as HttpURLConnection

            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            if (
                connection.responseCode !=
                HttpURLConnection.HTTP_OK
            ) {
                return@withContext null
            }

            val response =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            val array =
                JSONArray(response)

            val target =
                clean + "USDT"

            for (i in 0 until array.length()) {

                val obj =
                    array.optJSONObject(i)
                        ?: continue

                val market =
                    obj.optString(
                        "market",
                        ""
                    ).uppercase(Locale.US)

                if (
                    market != target &&
                    market !=
                    "B-${clean}_USDT"
                ) {
                    continue
                }

                val price =
                    obj.optString(
                        "last_price",
                        "0"
                    ).toDoubleOrNull()
                        ?: 0.0

                val change =
                    obj.optString(
                        "change_24_hour",
                        "0"
                    ).toDoubleOrNull()
                        ?: 0.0

                if (price > 0.0) {
                    return@withContext price to change
                }
            }

            null

        } catch (_: Exception) {

            null

        } finally {

            connection?.disconnect()
        }
    }

@Composable
fun CoinTradeDialog(
    coin: CoinGeckoCoin,
    viewModel: TradingViewModel,
    onDismiss: () -> Unit,
    onChart: () -> Unit
) {

    var amount by
        remember {
            mutableStateOf("100")
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "💱 Trade ${coin.name}"
            )
        },
        containerColor = CARD,

        text = {

            Column {

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    CoinIcon(
                        coin.image,
                        coin.symbol,
                        coin.id,
                        28
                    )

                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text(
                        "${coin.symbol} • " +
                            formatPrice(
                                coin.currentPrice
                            ),
                        color =
                            Color(0xFF848E9C),
                        fontSize = 12.sp
                    )
                }

                Spacer(
                    Modifier.height(12.dp)
                )

                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        amount = it
                    },
                    label = {
                        Text(
                            "Amount (USD)"
                        )
                    },
                    modifier =
                        Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(
                    Modifier.height(12.dp)
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    Button(
                        onClick = {

                            viewModel
                                .setTradeAmount(
                                    amount
                                )

                            viewModel.executeTrade(
                                type = "BUY",
                                entryPrice = coin.currentPrice,
                                symbol = coin.symbol
                            )

                            onDismiss()
                        },
                        modifier =
                            Modifier.weight(1f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    UP
                            )
                    ) {

                        Text(
                            "BUY",
                            color =
                                Color.White
                        )
                    }

                    Button(
                        onClick = {

                            viewModel
                                .setTradeAmount(
                                    amount
                                )

                            viewModel.executeTrade(
                                type = "SELL",
                                entryPrice = coin.currentPrice,
                                symbol = coin.symbol
                            )

                            onDismiss()
                        },
                        modifier =
                            Modifier.weight(1f),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor =
                                    DN
                            )
                    ) {

                        Text(
                            "SELL",
                            color =
                                Color.White
                        )
                    }
                }

                Spacer(
                    Modifier.height(6.dp)
                )

                TextButton(
                    onClick = onChart,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        "📈 Open Live Chart",
                        color = ACCENT
                    )
                }
            }
        },

        confirmButton = {

            TextButton(
                onClick = onDismiss
            ) {

                Text("Cancel")
            }
        }
    )
}

private val CardGradient =
    Brush.linearGradient(
        listOf(
            Color(0xFF171B23),
            Color(0xFF202733),
            Color(0xFF10141B)
        )
    )

@Composable
fun PremiumEquityCard(
    points: List<EquityPoint>
) {

    val infinite =
        rememberInfiniteTransition(
            label = "equity"
        )

    val glow by
        infinite.animateFloat(
            0.4f,
            1f,
            infiniteRepeatable(
                tween(1500),
                RepeatMode.Reverse
            ),
            label = "glow"
        )

    Card(
        Modifier
            .fillMaxWidth()
            .height(340.dp),
        shape =
            RoundedCornerShape(24.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color.Transparent
            )
    ) {

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    CardGradient
                )
                .padding(18.dp)
        ) {

            Column {

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween,
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {

                    Text(
                        "📈 Equity Curve",
                        fontSize = 24.sp,
                        fontWeight =
                            FontWeight.Bold,
                        color =
                            Color.White
                    )

                    Text(
                        "+12.84%",
                        color = UP,
                        fontWeight =
                            FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }

                Spacer(
                    Modifier.height(16.dp)
                )

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .clip(
                            RoundedCornerShape(16.dp)
                        )
                        .background(
                            Color.White.copy(
                                alpha =
                                    0.03f * glow
                            )
                        )
                ) {

                    AnimatedEquityGraph(
                        points,
                        Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun AnimatedEquityGraph(
    points: List<EquityPoint>,
    modifier: Modifier = Modifier
) {

    val progress by
        animateFloatAsState(
            targetValue = 1f,
            animationSpec =
                tween(
                    2500,
                    easing =
                        FastOutSlowInEasing
                ),
            label = "graph"
        )

    Canvas(modifier) {

        if (
            points.size < 2
        ) {
            return@Canvas
        }

        val left = 24f
        val right =
            size.width - 24f
        val top = 24f
        val bottom =
            size.height - 24f

        val height =
            bottom - top

        val width =
            right - left

        val minimum =
            points.minOf {
                it.value
            }

        val maximum =
            points.maxOf {
                it.value
            }

        val range =
            (maximum - minimum)
                .coerceAtLeast(1f)

        repeat(5) { index ->

            val y =
                top +
                    height / 4f *
                    index

            drawLine(
                Color.White.copy(
                    alpha = 0.08f
                ),
                Offset(
                    left,
                    y
                ),
                Offset(
                    right,
                    y
                ),
                strokeWidth = 1f
            )
        }

        val path =
            Path()

        points.forEachIndexed {
                index,
                point ->

            val x =
                left +
                    width *
                    index /
                    (points.size - 1)

            val y =
                bottom -
                    (
                        (point.value -
                            minimum) /
                            range
                    ) *
                    height

            if (
                index == 0
            ) {
                path.moveTo(
                    x,
                    y
                )
            } else {
                path.lineTo(
                    x,
                    y
                )
            }
        }

        val fill =
            Path().apply {

                addPath(path)

                lineTo(
                    right,
                    bottom
                )

                lineTo(
                    left,
                    bottom
                )

                close()
            }

        drawPath(
            fill,
            Brush.verticalGradient(
                listOf(
                    UP.copy(
                        alpha = 0.35f
                    ),
                    Color.Transparent
                )
            )
        )

        drawPath(
            path,
            UP,
            style =
                androidx.compose.ui.graphics
                    .drawscope.Stroke(
                        width = 6f,
                        cap =
                            StrokeCap.Round,
                        join =
                            StrokeJoin.Round
                    ),
            alpha = progress
        )

        val last =
            points.last()

        val lastX =
            right

        val lastY =
            bottom -
                (
                    (last.value -
                        minimum) /
                        range
                ) *
                height

        drawCircle(
            UP,
            10f,
            Offset(
                lastX,
                lastY
            )
        )

        drawCircle(
            UP.copy(
                alpha = 0.25f
            ),
            22f,
            Offset(
                lastX,
                lastY
            )
        )
    }
}

@Composable
fun PremiumStatCard(
    title: String,
    value: String,
    color: Color
) {

    Card(
        Modifier
            .width(160.dp)
            .height(90.dp),
        shape =
            RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color.White.copy(
                        alpha = 0.05f
                    )
            )
    ) {

        Column(
            Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement =
                Arrangement.SpaceBetween
        ) {

            Text(
                title,
                color =
                    Color.LightGray,
                fontSize = 12.sp
            )

            Text(
                value,
                color = color,
                fontWeight =
                    FontWeight.Bold,
                fontSize = 20.sp
            )
        }
    }
}
