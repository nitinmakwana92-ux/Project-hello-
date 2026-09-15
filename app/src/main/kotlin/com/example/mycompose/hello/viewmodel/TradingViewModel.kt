package com.example.mycompose.hello.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mycompose.hello.api.CoinDCXApi
import com.example.mycompose.hello.api.CoinDCXRetrofit
import com.example.mycompose.hello.api.CoinDCXCandle
import com.example.mycompose.hello.service.TradingBotService
import com.example.mycompose.hello.Ai.ProfessionalAdaptiveStrategyEngine
import com.example.mycompose.hello.Ai.MasterMindAutoStrategyEngine
import com.example.mycompose.hello.Ai.OpenAiMarketIntelligenceEngine
import com.example.mycompose.hello.Ai.AiKeyStore
import com.example.mycompose.hello.Ai.GlobalMarketIntelligence
import com.example.mycompose.hello.Ai.DeltaIndiaLiveMarketAdapter
import com.example.mycompose.hello.Ai.ExchangeFlowSnapshot
import com.example.mycompose.hello.Ai.TakerSide
import com.example.mycompose.hello.Ai.UniversalBuyerSellerFlowEngine
import com.example.mycompose.hello.delta.DeltaMcpTradingBridge
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.concurrent.ConcurrentHashMap

data class CryptoPrice(
    @SerializedName("market") val market:String,
    @SerializedName("symbol") val symbol:String,
    @SerializedName("name") val name:String,
    @SerializedName("last_price") val lastPrice:Double,
    @SerializedName("price_change_percentage_24h") val priceChangePercentage24h:Double,
    @SerializedName("volume_24h") val volume24h:Double,
    val image:String="",
    val category:String="CRYPTO"
)

data class Balance(
    val currency:String,
    val balance:Double,
    @SerializedName("locked_balance")
    val lockedBalance:Double
)

data class BotTrade(
    val symbol:String,
    val type:String,
    val entryPrice:Double,
    val exitPrice:Double=0.0,
    val quantity:Double,
    val profitLoss:Double=0.0,
    val time:String,
    val status:String="OPEN",
    val strategy:String="RSI+MACD+EMA+BB+ATR",
    val orderId:String="",
    val exchange:String="",
    val contractValue:Double=0.0,
    val notionalType:String="vanilla",
    val entryCommission:Double=0.0,
    val exitCommission:Double=0.0
)

data class LiveDeltaPosition(
    val symbol:String,
    val side:String,
    val quantity:Double,
    val entryPrice:Double,
    val markPrice:Double,
    val unrealizedPnl:Double,
    val productId:Long=0L
)

data class PendingRealOrder(
    val symbol:String,
    val side:String,
    val orderId:String,
    val quantity:Double,
    val price:Double,
    val createdAt:Long=System.currentTimeMillis()
)

data class LiveDeltaOpenOrder(
    val symbol:String,
    val side:String,
    val orderId:String,
    val quantity:Double,
    val unfilledQuantity:Double,
    val price:Double,
    val state:String,
    val orderType:String,
    val createdAt:Long=System.currentTimeMillis()
)

/**
 * Persisted record of positions opened by THIS bot.
 * Used only for safe shutdown cleanup. Manual exchange positions are never
 * inferred or closed by this registry.
 */
data class BotPositionRecord(
    val exchange:String,
    val symbol:String,
    val side:String,
    val quantity:Double,
    val entryPrice:Double,
    val orderId:String,
    val createdAt:Long=System.currentTimeMillis(),
    val contractValue:Double=0.0,
    val notionalType:String="vanilla",
    val entryCommission:Double=0.0
)

data class MarketStats(
    val totalTrades:Int=0,
    val winningTrades:Int=0,
    val losingTrades:Int=0,
    // Exchange-confirmed order-side counters.
    // A BUY opens a position and a filled SELL closes it in the current bot flow.
    val buyOrders:Int=0,
    val sellOrders:Int=0,
    val totalProfit:Double=0.0,
    val totalLoss:Double=0.0,

    // Percentage of CLOSED trades that ended in profit.
    val winRate:Double=0.0,

    // Percentage of CLOSED trades that ended in loss.
    val lossRate:Double=0.0,

    val profitFactor:Double=0.0,
    val avgProfit:Double=0.0,
    val avgLoss:Double=0.0
)

/**
 * REAL-ONLY trading mode.
 *
 * Paper trading has intentionally been removed from this production bot.
 * A local trade is recorded only after an authenticated exchange order
 * has been accepted by the selected exchange adapter.
 */
enum class OrderMode {
    REAL
}

/*
 * UI compatibility layer:
 * TradingDashboard reads strategyProfile and scannerStatus.
 * Keep these here so the existing dashboard compiles without removing
 * any trading/router functions.
 */
enum class StrategyProfile {
    BALANCED,
    TREND_FOLLOWING,
    MOMENTUM,
    MEAN_REVERSION,
    BREAKOUT,
    CONSERVATIVE
}

/**
 * TradingView-style strategy templates built from indicators that are already
 * available in this project. These are selectable from the app; they do not
 * pretend to execute arbitrary Pine Script.
 */
enum class TradingViewStrategy {
    COMBINED_CONFIRMATION,
    EMA_CROSSOVER,
    RSI_MOMENTUM,
    MACD_CROSSOVER,
    BOLLINGER_MEAN_REVERSION,
    BOLLINGER_BREAKOUT,
    ATR_BREAKOUT,
    VOLUME_MOMENTUM,
    EMA_TREND_PULLBACK,
    CONSERVATIVE_CONFIRMATION
}

/** User-configurable strategy engine settings. */
data class StrategyConfig(
    val profile: StrategyProfile = StrategyProfile.BALANCED,
    val tradingViewStrategy: TradingViewStrategy = TradingViewStrategy.COMBINED_CONFIRMATION,
    val minConfidence: Double = 60.0,
    val signalThreshold: Int = 18,
    val maxOpenPositions: Int = 20,
    val maxDrawdownPct: Double = 3.0
)

data class TradingPipelineState(
    val mode:String="REAL",
    val candleStatus:String="Waiting",
    val indicatorStatus:String="Waiting",
    val signal:String="HOLD",
    val confidence:Double=0.0,
    val orderStatus:String="No order",
    val lastPrice:Double=0.0,
    val lastUpdate:Long=0L
)


data class IndicatorValues(
    val rsi:Double=50.0,
    val macd:Double=0.0,
    val macdSignal:Double=0.0,
    val macdHistogram:Double=0.0,
    val ema9:Double=0.0,
    val ema21:Double=0.0,
    val ema50:Double=0.0,
    val ema200:Double=0.0,
    val bollingerUpper:Double=0.0,
    val bollingerLower:Double=0.0,
    val atr:Double=0.0
)

sealed class UiState {
    object Loading:UiState()
    data class Success(
        val markets:List<CryptoPrice>
    ):UiState()
    data class Error(
        val message:String
    ):UiState()
}

sealed class TradingSignal {
    data class STRONG_BUY(
        val reason:String,
        val confidence:Double
    ):TradingSignal()

    data class BUY(
        val reason:String,
        val confidence:Double
    ):TradingSignal()

    object HOLD:TradingSignal()

    data class SELL(
        val reason:String,
        val confidence:Double
    ):TradingSignal()

    data class STRONG_SELL(
        val reason:String,
        val confidence:Double
    ):TradingSignal()
}

data class CoinCapAsset(
    @SerializedName("symbol")
    val symbol:String="",
    @SerializedName("iconUrl")
    val iconUrl:String=""
)

data class CoinCapResp(
    @SerializedName("data")
    val data:List<CoinCapAsset> = emptyList()
)

interface CoinCapApi {
    @GET("v2/assets")
    suspend fun getAssets(
        @Query("limit") limit:Int,
        @Query("offset") offset:Int
    ):CoinCapResp
}

data class GeckoCoin(
    @SerializedName("symbol")
    val symbol:String="",
    @SerializedName("image")
    val image:String="",
    @SerializedName("current_price")
    val currentPrice:Double?=null,
    @SerializedName("total_volume")
    val totalVolume:Double?=null,
    @SerializedName("market_cap")
    val marketCap:Double?=null,
    @SerializedName("market_cap_rank")
    val marketCapRank:Int?=null,
    @SerializedName("price_change_percentage_24h")
    val change24h:Double?=null
)

interface GeckoLogoApi {
    @GET("api/v3/coins/markets")
    suspend fun getCoins(
        @Query("vs_currency") vsCurrency:String="usd",
        @Query("order") order:String="market_cap_desc",
        @Query("page") page:Int,
        @Query("per_page") perPage:Int=250,
        @Query("sparkline") sparkline:Boolean=false
    ):List<GeckoCoin>
}

interface CoinDCXCandleApi {
    @GET("market_data/candles")
    suspend fun getCandles(
        @Query("pair") pair:String,
        @Query("interval") interval:String,
        @Query("limit") limit:Int
    ):List<CoinDCXCandle>
}

// ============================================================
// DELTA INDIA PUBLIC MARKET FLOW (BUYER/SELLER + L2 ORDERBOOK)
// Public endpoints: no API key is required.  This data is used only
// as an AI analysis input; it never invents buyer/seller volume.
// ============================================================

data class DeltaFlowLevel(
    @SerializedName("price") val price:String = "",
    @SerializedName("size") val size:Long = 0L
)

data class DeltaL2OrderBook(
    @SerializedName("buy") val buy:List<DeltaFlowLevel> = emptyList(),
    @SerializedName("sell") val sell:List<DeltaFlowLevel> = emptyList(),
    @SerializedName("symbol") val symbol:String = "",
    @SerializedName("last_updated_at") val lastUpdatedAt:Long = 0L
)

data class DeltaPublicTrade(
    @SerializedName("side") val side:String = "",
    @SerializedName("size") val size:Long = 0L,
    @SerializedName("price") val price:String = "",
    @SerializedName("timestamp") val timestamp:Long = 0L
)

data class DeltaTradesResponse(
    @SerializedName("trades") val trades:List<DeltaPublicTrade> = emptyList()
)

interface DeltaPublicFlowApi {
    @GET("v2/l2orderbook/{symbol}")
    suspend fun getL2OrderBook(
        @retrofit2.http.Path("symbol") symbol:String,
        @Query("depth") depth:Int = 15
    ):retrofit2.Response<com.google.gson.JsonObject>

    @GET("v2/trades/{symbol}")
    suspend fun getTrades(
        @retrofit2.http.Path("symbol") symbol:String
    ):retrofit2.Response<com.google.gson.JsonObject>
}

data class LiveMarketFlow(
    val symbol:String,
    val buyerVolume:Double = 0.0,
    val sellerVolume:Double = 0.0,
    val buyerTrades:Long = 0L,
    val sellerTrades:Long = 0L,
    val bidBookVolume:Double = 0.0,
    val askBookVolume:Double = 0.0,
    val bidBookNotional:Double = 0.0,
    val askBookNotional:Double = 0.0,
    val bookImbalancePct:Double = 0.0,
    val flowBias:String = "UNKNOWN",
    val updatedAt:Long = 0L
)

data class RealOrderResult(
    val success:Boolean,
    val orderId:String="",
    val message:String="",
    val raw:String=""
)


// ============================================================
// PROFESSIONAL MULTI-EXCHANGE REAL ORDER ROUTER
// Official-API adapters only. Unsupported credential models are blocked
// instead of pretending an order succeeded.
// ============================================================

data class RouterCredentials(
    val apiKey:String,
    val secret:String,
    val passphrase:String=""
)

data class RouterOrderRequest(
    val symbol:String,
    val side:String,
    val quantity:Double,
    val price:Double,
    val quoteAmount:Double=0.0,
    val clientOrderId:String="AUTO-${System.currentTimeMillis()}"
)

data class RouterOrderResult(
    val success:Boolean,
    val exchange:String,
    val orderId:String="",
    val status:String="",
    val message:String="",
    val raw:String="",
    // TRUE only when the exchange reports the order as fully filled/closed.
    val filled:Boolean=false,
    val averageFillPrice:Double=0.0,
    val filledSize:Double=0.0,
    val paidCommission:Double=0.0,
    val contractValue:Double=0.0,
    val notionalType:String="vanilla"
)

data class RouterBalanceResult(
    val success:Boolean,
    val total:Double=0.0,
    val currency:String="USD",
    val holdings:List<Pair<String,Double>> = emptyList(),
    val message:String="",
    val raw:String=""
)

enum class ProfessionalExchange(val label:String, val docs:String, val realAdapter:Boolean) {
    COINDCX("CoinDCX","https://docs.coindcx.com/",true),
    DELTA_INDIA("DeltaIndia","https://docs.delta.exchange/",true),
    BINANCE("Binance","https://developers.binance.com/",true),
    WAZIRX("WazirX","https://docs.wazirx.com/",true),
    GIOTTUS("Giottus","https://api.giottus.com/docs/",true),
    BYBIT("Bybit","https://bybit-exchange.github.io/docs/",true),
    OKX("OKX","https://www.okx.com/docs-v5/",true),
    BITGET("Bitget","https://www.bitget.com/api-doc/",true),
    MEXC("MEXC","https://mexcdevelop.github.io/apidocs/spot_v3_en/",true),
    KUCOIN("KuCoin","https://www.kucoin.com/docs-new/",true),
    GATE_IO("Gate.io","https://www.gate.com/docs/developers/apiv4/en/",true),
    HTX("HTX","https://huobiapi.github.io/docs/spot/v1/en/",true),
    KRAKEN("Kraken","https://docs.kraken.com/api/",true),
    CRYPTO_COM("Crypto.com","https://exchange-docs.crypto.com/exchange/v1/rest-ws/",true),
    COINBASE("Coinbase Advanced Trade","https://docs.cdp.coinbase.com/api-reference/advanced-trade-api/",false),
    ZEBPAY("ZebPay","https://docs.zebpay.com/",false),
    COINSWITCH_PRO("CoinSwitch PRO","https://coinswitch.co/",false),
    BITFINEX("Bitfinex","https://docs.bitfinex.com/",true)
}

object ProfessionalExchangeRouter {

    // Delta India order symbols must come from the LIVE product catalog.
    // The market scanner can contain coins that are not listed as Delta
    // perpetual contracts; never submit those symbols to /v2/orders.
    private data class DeltaProductMeta(
        val id:Long,
        val symbol:String,
        val underlying:String,
        val contractValue:Double,
        val notionalType:String,
        val state:String,
        val tradingStatus:String
    )

    @Volatile
    private var deltaProductsCache:List<DeltaProductMeta> = emptyList()

    @Volatile
    private var deltaProductsCacheAt:Long = 0L

    private suspend fun getLiveDeltaProducts():List<DeltaProductMeta> {
        val now=System.currentTimeMillis()
        val cached=deltaProductsCache
        if(cached.isNotEmpty() && now-deltaProductsCacheAt < 60_000L) return cached

        val all=mutableListOf<DeltaProductMeta>()
        var after:String?=null

        repeat(32) {
            val query=buildString {
                append("?contract_types=perpetual_futures")
                append("&states=live")
                append("&page_size=100")
                if(!after.isNullOrBlank()) append("&after=").append(enc(after!!))
            }
            val (code,body)=execute(
                Request.Builder()
                    .url("$DELTA/v2/products$query")
                    .get()
                    .addHeader("Accept","application/json")
                    .addHeader("User-Agent","NitinProTrading/1.0 (Android)")
                    .build()
            )

            if(code !in 200..299) return@repeat

            val root=runCatching { org.json.JSONObject(body) }.getOrNull() ?: return@repeat
            val arr=root.optJSONArray("result") ?: return@repeat

            for(i in 0 until arr.length()) {
                val o=arr.optJSONObject(i) ?: continue
                val id=o.optLong("id",0L)
                val symbol=o.optString("symbol","").trim().uppercase(Locale.US)
                if(id<=0L || symbol.isBlank()) continue

                // Delta perpetual product symbols are the authoritative
                // contract identity (for example BTCUSD). Do not let an
                // optional/variant underlying_asset field break symbol
                // resolution. Prefer the contract symbol itself and only
                // fall back to the underlying field when the symbol cannot
                // provide a base asset.
                val underlyingField=o.optString(
                    "underlying_asset_symbol",
                    o.optString("underlying_asset","")
                ).trim().uppercase(Locale.US)

                val symbolBaseFromContract = symbol
                    .removeSuffix("USD")
                    .removeSuffix("USDT")
                    .removeSuffix("USDC")
                    .trim()
                    .uppercase(Locale.US)

                val base = symbolBaseFromContract.ifBlank {
                    underlyingField
                }

                val cv=o.optString("contract_value","").toDoubleOrNull()
                    ?.takeIf { it>0.0 } ?: 1.0

                all += DeltaProductMeta(
                    id=id,
                    symbol=symbol,
                    underlying=base,
                    contractValue=cv,
                    notionalType=o.optString("notional_type","vanilla").ifBlank { "vanilla" },
                    state=o.optString("state","live").lowercase(Locale.US),
                    tradingStatus=o.optString("trading_status","operational").lowercase(Locale.US)
                )
            }

            val meta=root.optJSONObject("meta")
            val next=meta?.optString("after","").orEmpty()
            if(next.isBlank() || next==after) return@repeat
            after=next
        }

        val live=all.filter {
            it.state=="live" &&
            it.tradingStatus=="operational"
        }.distinctBy { it.id }

        if(live.isNotEmpty()) {
            deltaProductsCache=live
            deltaProductsCacheAt=now
            return live
        }

        return cached
    }

    private suspend fun resolveLiveDeltaProduct(rawSymbol:String):DeltaProductMeta? {
        val base=symbolBase(rawSymbol).trim().uppercase(Locale.US)
        if(base.isBlank()) return null

        // PERMANENT DELTA CONTRACT FIX:
        // The scanner can contain hundreds/thousands of markets while the
        // paginated product catalogue can be larger than the first cached
        // pages.  Resolve the exact LIVE contract directly from Delta first
        // so the order can never use a stale/missing product id.
        val exactSymbol = "${base}USD"
        runCatching {
            val (code, body) = execute(
                Request.Builder()
                    .url("$DELTA/v2/products/${enc(exactSymbol)}")
                    .get()
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", "NitinProTrading/1.0 (Android)")
                    .build()
            )
            if(code in 200..299) {
                val root = org.json.JSONObject(body)
                val o = root.optJSONObject("result")
                if(o != null) {
                    val id = o.optLong("id", 0L)
                    val symbol = o.optString("symbol", "").trim().uppercase(Locale.US)
                    val state = o.optString("state", "live").lowercase(Locale.US)
                    val tradingStatus = o.optString("trading_status", "operational").lowercase(Locale.US)
                    if(id > 0L && symbol.isNotBlank() &&
                        state == "live" && tradingStatus == "operational") {
                        val underlying = o.optString(
                            "underlying_asset_symbol",
                            o.optString("underlying_asset", base)
                        ).trim().uppercase(Locale.US)
                        val cv = o.optString("contract_value", "")
                            .toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
                        return DeltaProductMeta(
                            id = id,
                            symbol = symbol,
                            underlying = underlying,
                            contractValue = cv,
                            notionalType = o.optString("notional_type", "vanilla")
                                .ifBlank { "vanilla" },
                            state = state,
                            tradingStatus = tradingStatus
                        )
                    }
                }
            }
        }.onFailure {
            Log.w("DELTA_CONTRACT", "Direct product lookup failed for $exactSymbol: ${it.message}")
        }

        // Fallback to the live paginated catalogue for exchanges/contracts
        // whose symbol is represented differently.
        val products=getLiveDeltaProducts()
        return products.firstOrNull {
            it.symbol.equals(exactSymbol, true) &&
                it.state.equals("live", true) &&
                it.tradingStatus.equals("operational", true)
        } ?: products.firstOrNull {
            it.underlying.equals(base, true) &&
                it.state.equals("live", true) &&
                it.tradingStatus.equals("operational", true)
        }
    }
    private const val COINDCX="https://api.coindcx.com"
    private const val DELTA="https://api.india.delta.exchange"
    private const val BINANCE="https://api.binance.com"
    private const val WAZIRX="https://api.wazirx.com"
    private const val GIOTTUS="https://api.giottus.com"
    private const val BYBIT="https://api.bybit.com"
    private const val OKX="https://www.okx.com"
    private const val BITGET="https://api.bitget.com"
    private const val MEXC="https://api.mexc.com"
    private const val KUCOIN="https://api.kucoin.com"
    private const val GATE="https://api.gateio.ws"
    private const val HTX="https://api.huobi.pro"
    private const val KRAKEN="https://api.kraken.com"
    private const val CCOM="https://api.crypto.com/exchange/v1"

    private val client:OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun hmacHex(secret:String, data:String):String = try {
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8),"HmacSHA256"))
        mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it) }
    } catch(e:Exception){ "" }

    private fun hmacB64(secret:String, data:String):String = try {
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8),"HmacSHA256"))
        Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    } catch(e:Exception){ "" }

    private fun hmac384Hex(secret:String,data:String):String = try{
        val mac=Mac.getInstance("HmacSHA384")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8),"HmacSHA384"))
        mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it) }
    }catch(e:Exception){ "" }

    private fun hmacSha512B64(secretB64:String, data:ByteArray):String = try {
        val key=Base64.decode(secretB64,Base64.DEFAULT)
        val mac=Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key,"HmacSHA512"))
        Base64.encodeToString(mac.doFinal(data),Base64.NO_WRAP)
    } catch(e:Exception){ "" }

    private fun sha256Bytes(data:ByteArray):ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    private fun sha512Hex(data:String):String =
        java.security.MessageDigest.getInstance("SHA-512")
            .digest(data.toByteArray(Charsets.UTF_8))
            .joinToString(""){ "%02x".format(it) }

    private fun json(body:org.json.JSONObject):okhttp3.RequestBody =
        body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun form(body:String):okhttp3.RequestBody =
        body.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())

    /**
 * Direct signal-to-order gate.
 *
 * BUY/SELL is decided by the existing strategy/AI signal.
 * No separate "Positions Mode" decision is used here.
 * The existing real Delta order executor remains responsible for validation,
 * authentication, quantity and exchange confirmation.
 */
private fun directSignalOrder(signal: String): String? {
    return when (signal.uppercase(java.util.Locale.US)) {
        "BUY", "STRONG_BUY" -> "BUY"
        "SELL", "STRONG_SELL" -> "SELL"
        else -> null
    }
}

private suspend fun execute(request:Request):Pair<Int,String> = withContext(Dispatchers.IO){
        try{
            client.newCall(request).execute().use{ r ->
                val text=(if(r.isSuccessful) r.body else r.body)?.string().orEmpty()
                r.code to text
            }
        }catch(e:Exception){ -1 to (e.message ?: "network error") }
    }

    private fun enc(v:String):String = java.net.URLEncoder.encode(v,"UTF-8")

    // Binance signed-query helper.
    private fun binanceQuery(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (key, value) ->
            "${enc(key)}=${enc(value)}"
        }

    fun normalizeExchange(name:String):ProfessionalExchange? {
        val n=name.trim().lowercase(Locale.US).replace(" ","")
        return when(n){
            "coindcx","cdx"->ProfessionalExchange.COINDCX
            "deltaindia","deltaexchangeindia","delta"->ProfessionalExchange.DELTA_INDIA
            "binance","binanceus"->ProfessionalExchange.BINANCE
            "wazirx","wz"->ProfessionalExchange.WAZIRX
            "giottus","gio"->ProfessionalExchange.GIOTTUS
            "bybit","byb"->ProfessionalExchange.BYBIT
            "okx"->ProfessionalExchange.OKX
            "bitget","bgt"->ProfessionalExchange.BITGET
            "mexc","mex"->ProfessionalExchange.MEXC
            "kucoin","kuc"->ProfessionalExchange.KUCOIN
            "gate.io","gateio","gate","gat"->ProfessionalExchange.GATE_IO
            "htx","huobi"->ProfessionalExchange.HTX
            "kraken","krk"->ProfessionalExchange.KRAKEN
            "crypto.com","cryptocom","crypto"->ProfessionalExchange.CRYPTO_COM
            "coinbase","coinbaseadvancedtrade"->ProfessionalExchange.COINBASE
            "zebpay","zeb"->ProfessionalExchange.ZEBPAY
            "coinswitch","coinswitchpro"->ProfessionalExchange.COINSWITCH_PRO
            "bitfinex","bfx"->ProfessionalExchange.BITFINEX
            else->null
        }
    }

    fun capability(name:String):String {
        val e=normalizeExchange(name) ?: return "❌ Unknown exchange"
        return if(e.realAdapter) "🟢 ${e.label}: REAL adapter enabled" else
            "🟡 ${e.label}: official API uses a different credential model; REAL blocked until configured"
    }

    private fun parsedPair(raw:String):Pair<String,String>{
        var s=raw.trim().uppercase(Locale.US).substringAfterLast(":").removeSuffix(".P")
        if(s.contains("-") && s.substringBefore("-").length<=3){
            s=s.substringAfter("-") // CoinDCX B-/I-/KC- prefix
        }
        if(s.contains("_")){
            val p=s.split("_")
            if(p.size>=2) return p[0] to p[1]
        }
        if(s.contains("-")){
            val p=s.split("-")
            if(p.size>=2) return p[0] to p[1]
        }
        val quotes=listOf("USDT","USDC","BUSD","INR","BTC","ETH","USD")
        for(q in quotes) if(s.endsWith(q) && s.length>q.length) return s.removeSuffix(q) to q
        return s to "USDT"
    }

    private fun symbolBase(raw:String):String = parsedPair(raw).first
    private fun quote(raw:String):String = parsedPair(raw).second

    private fun symbolFor(e:ProfessionalExchange, raw:String):String {
        val b=symbolBase(raw); val q=quote(raw)
        return when(e){
            ProfessionalExchange.COINDCX->if(q=="INR") "I-${b}_INR" else "B-${b}_${q}"
            ProfessionalExchange.DELTA_INDIA->if(q=="INR"||q=="USDT"||q=="USDC") "${b}USD" else "${b}${q}"
            ProfessionalExchange.BINANCE->"$b$q"
            ProfessionalExchange.WAZIRX->"${b}${q}".lowercase(Locale.US)
            ProfessionalExchange.GIOTTUS->"$b/$q"
            ProfessionalExchange.BYBIT->"$b$q"
            ProfessionalExchange.OKX->"$b-$q"
            ProfessionalExchange.BITGET->"$b$q"
            ProfessionalExchange.MEXC->"$b$q"
            ProfessionalExchange.KUCOIN->"$b-$q"
            ProfessionalExchange.GATE_IO->"${b}_$q"
            ProfessionalExchange.HTX->"${b.lowercase(Locale.US)}${q.lowercase(Locale.US)}"
            ProfessionalExchange.KRAKEN->"${b}${q}"
            ProfessionalExchange.CRYPTO_COM->"${b}_$q"
            else->raw.trim().uppercase(Locale.US)
        }
    }

    private fun parseOrderId(root:org.json.JSONObject):String {
        return root.optString("orderId",
            root.optString("order_id",
                root.optString("id",
                    root.optString("clientOid","")
                )
            )
        )
    }

    private fun okMessage(code:Int, body:String):String =
        if(code in 200..299) "accepted" else "HTTP $code: ${body.take(220)}"

    suspend fun placeOrder(
        exchangeName:String,
        credentials:RouterCredentials,
        request:RouterOrderRequest
    ):RouterOrderResult {
        val e=normalizeExchange(exchangeName)
            ?: return RouterOrderResult(false,exchangeName,message="Unknown exchange")
        if(!e.realAdapter)
            return RouterOrderResult(false,e.label,message="REAL trading blocked: ${e.label} requires its official credential/auth model. See ${e.docs}")
        if(credentials.apiKey.isBlank()||credentials.secret.isBlank())
            return RouterOrderResult(false,e.label,message="API key/secret missing")
        if(request.quantity<=0.0||request.price<=0.0)
            return RouterOrderResult(false,e.label,message="Invalid quantity/price")
        return try {
            when(e){
                ProfessionalExchange.COINDCX->coinDcx(credentials,request)
                ProfessionalExchange.DELTA_INDIA->delta(credentials,request)
                ProfessionalExchange.BINANCE->binance(credentials,request)
                ProfessionalExchange.WAZIRX->wazirx(credentials,request)
                ProfessionalExchange.GIOTTUS->giottus(credentials,request)
                ProfessionalExchange.BYBIT->bybit(credentials,request)
                ProfessionalExchange.OKX->okx(credentials,request)
                ProfessionalExchange.BITGET->bitget(credentials,request)
                ProfessionalExchange.MEXC->mexc(credentials,request)
                ProfessionalExchange.KUCOIN->kucoin(credentials,request)
                ProfessionalExchange.GATE_IO->gate(credentials,request)
                ProfessionalExchange.HTX->htx(credentials,request)
                ProfessionalExchange.KRAKEN->kraken(credentials,request)
                ProfessionalExchange.CRYPTO_COM->cryptoCom(credentials,request)
                ProfessionalExchange.BITFINEX->bitfinex(credentials,request)
                else->RouterOrderResult(false,e.label,message="No verified adapter")
            }
        }catch(e2:Exception){
            RouterOrderResult(false,e.label,message="Adapter error: ${e2.message}")
        }
    }

    private suspend fun coinDcx(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val market=symbolFor(ProfessionalExchange.COINDCX,r.symbol)
            .removePrefix("B-").removePrefix("I-").replace("_","")
        val body=org.json.JSONObject().apply{
            put("side",r.side.lowercase(Locale.US)); put("order_type","market_order")
            put("market",market); put("total_quantity",r.quantity); put("timestamp",System.currentTimeMillis())
        }
        val payload=body.toString()
        val req=Request.Builder().url("$COINDCX/exchange/v1/orders/create")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .addHeader("X-AUTH-APIKEY",c.apiKey)
            .addHeader("X-AUTH-SIGNATURE",hmacHex(c.secret,payload)).build()
        val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}
        val id=j?.let{parseOrderId(it)} ?: ""
        val success=code in 200..299 && j?.optString("error","").isNullOrBlank()
        return RouterOrderResult(success,"CoinDCX",id,if(success)"PENDING" else "REJECTED",okMessage(code,res),res)
    }

    /**
     * Delta Exchange India REAL order path.
     *
     * IMPORTANT:
     * - Delta uses integer contract SIZE, not coin quantity.
     * - A successful POST only means the exchange accepted the order.
     * - Local BotTrade is created ONLY after Delta reports state=closed.
     * - open/pending is never treated as filled.
     */
    private suspend fun delta(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        // IMPORTANT: do not manufacture a Delta contract symbol from the
        // generic market symbol. Resolve the currently LIVE perpetual product
        // from Delta's official catalog first.
        val productMeta=resolveLiveDeltaProduct(r.symbol)
            ?: return RouterOrderResult(
                false,
                "DeltaIndia",
                status="REJECTED",
                message="Unsupported Delta perpetual: ${symbolBase(r.symbol).uppercase(Locale.US)}",
                raw=""
            )

        val product=productMeta.symbol
        val productId=productMeta.id
        val contractValue=productMeta.contractValue
        val notionalType=productMeta.notionalType

        // BUY sizing must use the product's actual notional convention.
        // Vanilla contracts: notional/contract = price * contract_value.
        // Inverse contracts: contract_value is already the quoted notional
        // per contract, so multiplying it by price would under-size the BUY
        // and can turn a valid trade amount into zero contracts.
        val notionalPerContract =
            if (notionalType.equals("inverse", true)) {
                contractValue.coerceAtLeast(0.00000001)
            } else {
                (r.price * contractValue).coerceAtLeast(0.00000001)
            }

        // Delta orders require WHOLE CONTRACTS.
        // SELL keeps the existing position-size conversion untouched.
        // BUY uses the user's quote amount and the LIVE product notional.
        val requestedContracts =
            if(r.side.equals("SELL",true)) {
                kotlin.math.floor(r.quantity / contractValue).toInt()
            } else {
                kotlin.math.floor(r.quoteAmount / notionalPerContract).toInt()
            }

        if(requestedContracts < 1) {
            return RouterOrderResult(
                false,
                "DeltaIndia",
                status="REJECTED",
                message="Trade amount too small for $product: need at least $notionalPerContract quote per contract"
            )
        }

        val contracts=requestedContracts

        val clientOrderId=
            r.clientOrderId
                .ifBlank { "NITINBOT-${System.currentTimeMillis()}" }
                .take(32)

        // Delta MCP bridge: the existing AI BUY/SELL decision, live product
        // resolution, integer contract conversion and existing risk checks
        // remain unchanged. Only the final signed Delta order submission and
        // exchange-state synchronization are routed through the bridge.
        val mcp = DeltaMcpTradingBridge(
            apiKey = c.apiKey.trim(),
            apiSecret = c.secret.trim()
        )

        val mcpResult = mcp.placeMarketOrder(
            productId = productId,
            side = r.side.lowercase(Locale.US),
            size = contracts.toLong(),
            clientOrderId = clientOrderId,
            reduceOnly = r.side.equals("SELL", true)
        )

        val data = mcpResult.data
        val order = data.optJSONObject("order")
        val id = data.optString("order_id", "")
        val state = data.optString("state", "").lowercase(Locale.US)
        val averageFill = order?.optDouble("average_fill_price", 0.0) ?: 0.0
        val filledSize = order?.let {
            val size = it.optDouble("size", contracts.toDouble())
            val unfilled = it.optDouble("unfilled_size", 0.0)
            (size - unfilled).coerceAtLeast(0.0)
        } ?: 0.0
        val terminalFilled = state == "closed" ||
            order?.optBoolean("is_filled", false) == true

        return RouterOrderResult(
            success = mcpResult.ok && id.isNotBlank(),
            exchange = "DeltaIndia",
            orderId = id,
            status = state.ifBlank { if (mcpResult.ok) "open" else "REJECTED" },
            message = mcpResult.message,
            raw = data.toString(),
            filled = terminalFilled,
            averageFillPrice = averageFill,
            filledSize = filledSize,
            paidCommission = order?.optDouble("paid_commission", 0.0) ?: 0.0,
            contractValue = contractValue,
            notionalType = notionalType
        )
    }

    private suspend fun getDeltaOrderById(
        c:RouterCredentials,
        orderId:String
    ):RouterOrderResult {
        if(orderId.isBlank()) {
            return RouterOrderResult(
                false,
                "DeltaIndia",
                status="ERROR",
                message="Missing Delta order id"
            )
        }

        val path="/v2/orders/${enc(orderId)}"
        val ts=(System.currentTimeMillis()/1000).toString()
        val sig=hmacHex(c.secret,"GET$ts$path")

        val req=Request.Builder()
            .url(DELTA+path)
            .get()
            .addHeader("Accept","application/json")
            .addHeader("api-key",c.apiKey)
            .addHeader("timestamp",ts)
            .addHeader("signature",sig)
            .addHeader("User-Agent","NitinProTrading/1.0 (Android)")
            .build()

        val (code,res)=execute(req)
        val j=try{org.json.JSONObject(res)}catch(_:Exception){null}
        val result=j?.optJSONObject("result")
        val state=result?.optString("state","")?.lowercase(Locale.US).orEmpty()
        val id=result?.optString("id",orderId) ?: orderId
        val ok=code in 200..299 && j?.optBoolean("success",false)==true
        val size=result?.optString("size","")?.toDoubleOrNull() ?: 0.0
        val unfilled=result?.optString("unfilled_size","")?.toDoubleOrNull() ?: 0.0
        val averageFill=result?.optString("average_fill_price","")?.toDoubleOrNull() ?: 0.0
        val commission=result?.optString("paid_commission","")?.toDoubleOrNull()
            ?: result?.optString("commission","")?.toDoubleOrNull()
            ?: 0.0

        var orderContractValue = 0.0
        var orderNotionalType = "vanilla"
        if (state == "closed") {
            val productSymbol = result?.optString("product_symbol", "").orEmpty()
            if (productSymbol.isNotBlank()) {
                runCatching {
                    val productPath = "/v2/products/${enc(productSymbol)}"
                    val (pc, pr) = execute(
                        Request.Builder()
                            .url(DELTA + productPath)
                            .get()
                            .addHeader("Accept", "application/json")
                            .addHeader("User-Agent", "NitinProTrading/1.0 (Android)")
                            .build()
                    )
                    if (pc in 200..299) {
                        val po = org.json.JSONObject(pr).optJSONObject("result")
                        orderContractValue = po?.optString("contract_value", "")?.toDoubleOrNull() ?: 0.0
                        orderNotionalType = po?.optString("notional_type", "vanilla") ?: "vanilla"
                    }
                }
            }
        }

        return RouterOrderResult(
            success=ok,
            exchange="DeltaIndia",
            orderId=id,
            status=state,
            message=if(ok) "Delta order state=$state" else okMessage(code,res),
            raw=res,
            filled=state=="closed",
            averageFillPrice=averageFill,
            filledSize=(size-unfilled).coerceAtLeast(0.0),
            paidCommission=commission,
            contractValue=orderContractValue,
            notionalType=orderNotionalType
        )
    }

    /**
     * Close one bot-owned Delta position safely.
     *
     * Delta documents closing as an opposite-side reduce-only market order.
     * We first query the real-time position so we use the exchange-confirmed
     * contract size and never guess from the UI quantity.
     */
    /**
     * Read the live Delta position size without placing any order.
     * Used only for positions previously owned/recorded by this bot.
     */
    /**
     * Read ALL currently open Delta positions for the connected account.
     * This is display/reconciliation data only. It does not mark manual
     * positions as bot-owned and therefore cannot make the bot close them.
     */
    suspend fun getDeltaLivePositions(
        credentials:RouterCredentials
    ):List<LiveDeltaPosition> {
        return try {
            // Delta /v2/positions is a SINGLE real-time position endpoint and
            // requires product_id or underlying_asset_symbol.  It must not be
            // used as an "all positions" endpoint.  For the complete account
            // snapshot use /v2/positions/margined, which returns the open
            // positions list when no product filter is supplied.
            val all = mutableListOf<LiveDeltaPosition>()
            var after:String? = null
            repeat(20) {
                val query = buildString {
                    append("?page_size=50")
                    if (!after.isNullOrBlank()) append("&after=").append(enc(after!!))
                }
                val path = "/v2/positions/margined"
                val ts = (System.currentTimeMillis()/1000).toString()
                val sig = hmacHex(credentials.secret, "GET$ts$path$query")
                val req = Request.Builder()
                    .url(DELTA + path + query)
                    .get()
                    .addHeader("Accept","application/json")
                    .addHeader("api-key",credentials.apiKey)
                    .addHeader("timestamp",ts)
                    .addHeader("signature",sig)
                    .addHeader("User-Agent","NitinProTrading/1.0 (Android)")
                    .build()

                val (code, body) = execute(req)
                if (code !in 200..299) return@repeat
                val root = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return@repeat
                val rawResult = root.opt("result")
                val objects = mutableListOf<org.json.JSONObject>()
                when (rawResult) {
                    is org.json.JSONArray -> for (i in 0 until rawResult.length()) {
                        rawResult.optJSONObject(i)?.let { objects += it }
                    }
                    is org.json.JSONObject -> objects += rawResult
                }

                objects.forEach { p ->
                    val signedSize = p.optString("size","").toDoubleOrNull()
                        ?: p.optDouble("size",0.0)
                    if (signedSize == 0.0) return@forEach
                    val product = p.optString("product_symbol","").trim().uppercase(Locale.US)
                    if (product.isBlank()) return@forEach
                    val entry = p.optString("entry_price","").toDoubleOrNull()
                        ?: p.optDouble("entry_price",0.0)
                    val mark = p.optString("mark_price","").toDoubleOrNull()
                        ?: p.optDouble("mark_price",0.0)
                    val pnl = p.optString("unrealized_pnl","").toDoubleOrNull()
                        ?: p.optString("unrealized_pnl_usd","").toDoubleOrNull()
                        ?: p.optDouble("unrealized_pnl",0.0)
                    all += LiveDeltaPosition(
                        symbol=product,
                        side=if (signedSize > 0.0) "LONG" else "SHORT",
                        quantity=abs(signedSize),
                        entryPrice=entry,
                        markPrice=mark,
                        unrealizedPnl=pnl,
                        productId=p.optLong("product_id",0L)
                    )
                }

                val next = root.optJSONObject("meta")?.optString("after","").orEmpty()
                if (objects.isEmpty() || next.isBlank() || next == after) return@repeat
                after = next
            }

            // Keep one live row per Delta product and never manufacture a
            // position from local bot state.  This makes the dashboard mirror
            // the exchange account even when the bot is OFF.
            all.distinctBy { it.symbol }
        } catch (e:Exception) {
            Log.w("LIVE_DELTA_POSITIONS","Delta all-positions read failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getDeltaOpenOrders(
        credentials:RouterCredentials
    ):List<LiveDeltaOpenOrder> {
        return try {
            val all = mutableListOf<LiveDeltaOpenOrder>()
            var after:String? = null
            repeat(20) {
                val query = buildString {
                    append("?states=open%2Cpending&page_size=50")
                    if (!after.isNullOrBlank()) append("&after=").append(enc(after!!))
                }
                val path = "/v2/orders"
                val ts = (System.currentTimeMillis()/1000).toString()
                val sig = hmacHex(credentials.secret, "GET$ts$path$query")
                val req = Request.Builder()
                    .url(DELTA + path + query)
                    .get()
                    .addHeader("Accept","application/json")
                    .addHeader("api-key",credentials.apiKey)
                    .addHeader("timestamp",ts)
                    .addHeader("signature",sig)
                    .addHeader("User-Agent","NitinProTrading/1.0 (Android)")
                    .build()

                val (code, body) = execute(req)
                if (code !in 200..299) return@repeat
                val root = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return@repeat
                val arr = root.optJSONArray("result") ?: org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val state = o.optString("state","").lowercase(Locale.US)
                    if (state !in setOf("open","pending")) continue
                    val orderId = o.optString("id","")
                    val symbol = o.optString("product_symbol",o.optString("symbol","")).trim().uppercase(Locale.US)
                    if (orderId.isBlank() || symbol.isBlank()) continue
                    val size = o.optString("size","").toDoubleOrNull() ?: o.optDouble("size",0.0)
                    val unfilled = o.optString("unfilled_size","").toDoubleOrNull() ?: o.optDouble("unfilled_size",size)
                    val price = o.optString("limit_price",o.optString("price","0")).toDoubleOrNull() ?: 0.0
                    val created = o.optString("created_at","").toLongOrNull() ?: System.currentTimeMillis()
                    all += LiveDeltaOpenOrder(
                        symbol=symbol,
                        side=o.optString("side","BUY").uppercase(Locale.US),
                        orderId=orderId,
                        quantity=size,
                        unfilledQuantity=unfilled,
                        price=price,
                        state=state,
                        orderType=o.optString("order_type","")
                            .ifBlank { o.optString("stop_order_type","") },
                        createdAt=created
                    )
                }

                val next = root.optJSONObject("meta")?.optString("after","").orEmpty()
                if (arr.length() == 0 || next.isBlank() || next == after) return@repeat
                after = next
            }
            all.distinctBy { it.orderId }
        } catch (e:Exception) {
            Log.w("DELTA_OPEN_ORDERS","Delta open/pending orders read failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getDeltaBotPositionSize(
        credentials:RouterCredentials,
        symbol:String
    ):Double? {
        return try {
            val base = symbolBase(symbol).uppercase(Locale.US)
            val path = "/v2/positions"
            val query = "?underlying_asset_symbol=${enc(base)}"
            val ts = (System.currentTimeMillis()/1000).toString()
            val sig = hmacHex(credentials.secret, "GET$ts$path$query")
            val req = Request.Builder()
                .url(DELTA + path + query)
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("api-key", credentials.apiKey)
                .addHeader("timestamp", ts)
                .addHeader("signature", sig)
                .addHeader("User-Agent", "NitinProTrading/1.0 (Android)")
                .build()

            val (code, body) = execute(req)
            if (code !in 200..299) return null

            val root = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
            val rawResult = root.opt("result")
            val positions = mutableListOf<org.json.JSONObject>()
            when (rawResult) {
                is org.json.JSONObject -> positions += rawResult
                is org.json.JSONArray -> {
                    for (i in 0 until rawResult.length()) {
                        rawResult.optJSONObject(i)?.let { positions += it }
                    }
                }
            }

            val target = symbol.trim().uppercase(Locale.US)
            val position = positions.firstOrNull { p ->
                val ps = p.optString("product_symbol", "").uppercase(Locale.US)
                ps == target ||
                    ps == symbolFor(ProfessionalExchange.DELTA_INDIA, target).uppercase(Locale.US)
            } ?: return 0.0

            abs(
                position.optString("size", "0").toDoubleOrNull()
                    ?: position.optDouble("size", 0.0)
            )
        } catch (e:Exception) {
            Log.w("BOT_POSITION", "Delta position read failed for $symbol: ${e.message}")
            null
        }
    }

    suspend fun closeDeltaBotPosition(
        credentials:RouterCredentials,
        symbol:String,
        quantityHint:Double,
        clientOrderId:String,
        productIdHint:Long = 0L
    ):RouterOrderResult {
        return try {
            val base = symbolBase(symbol).uppercase(Locale.US)
            val path = "/v2/positions"
            val query = if (productIdHint > 0L)
                "?product_id=$productIdHint"
            else
                "?underlying_asset_symbol=${enc(base)}"
            val ts = (System.currentTimeMillis()/1000).toString()
            val sig = hmacHex(credentials.secret, "GET$ts$path$query")
            val req = Request.Builder()
                .url(DELTA + path + query)
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("api-key", credentials.apiKey)
                .addHeader("timestamp", ts)
                .addHeader("signature", sig)
                .addHeader("User-Agent", "NitinProTrading/1.0 (Android)")
                .build()

            val (code, body) = execute(req)
            if (code !in 200..299) {
                return RouterOrderResult(false, "DeltaIndia", status="ERROR", message=okMessage(code, body), raw=body)
            }

            val root = runCatching { org.json.JSONObject(body) }.getOrNull()
                ?: return RouterOrderResult(false, "DeltaIndia", status="ERROR", message="Invalid Delta position response", raw=body)

            val rawResult = root.opt("result")
            val positionObjects = mutableListOf<org.json.JSONObject>()
            when (rawResult) {
                is org.json.JSONObject -> positionObjects += rawResult
                is org.json.JSONArray -> for (i in 0 until rawResult.length()) rawResult.optJSONObject(i)?.let { positionObjects += it }
            }

            val position = positionObjects.firstOrNull { p ->
                val ps = p.optString("product_symbol", "").uppercase(Locale.US)
                ps == symbol.uppercase(Locale.US) || ps == symbolFor(ProfessionalExchange.DELTA_INDIA, symbol).uppercase(Locale.US)
            } ?: positionObjects.firstOrNull()

            if (position == null) {
                return RouterOrderResult(true, "DeltaIndia", status="closed", message="Position already closed", filled=true, raw=body)
            }

            val signedSize = position.optString("size", "0").toDoubleOrNull() ?: position.optDouble("size", 0.0)
            val size = kotlin.math.abs(signedSize).toLong()
            if (size <= 0L) {
                return RouterOrderResult(true, "DeltaIndia", status="closed", message="Position already flat", filled=true, raw=body)
            }

            val productId = position.optLong("product_id", 0L)
            val productSymbol = position.optString("product_symbol", symbolFor(ProfessionalExchange.DELTA_INDIA, symbol))

            var closeContractValue = 0.0
            var closeNotionalType = "vanilla"
            runCatching {
                val productPath = "/v2/products/${enc(productSymbol)}"
                val (pc, pr) = execute(
                    Request.Builder()
                        .url(DELTA + productPath)
                        .get()
                        .addHeader("Accept", "application/json")
                        .addHeader("User-Agent", "NitinProTrading/1.0 (Android)")
                        .build()
                )
                if (pc in 200..299) {
                    val productJson = org.json.JSONObject(pr).optJSONObject("result")
                    closeContractValue = productJson?.optString("contract_value","")?.toDoubleOrNull() ?: 0.0
                    closeNotionalType = productJson?.optString("notional_type","vanilla") ?: "vanilla"
                }
            }

            val closeSide = if (signedSize > 0.0) "sell" else "buy"
            val orderBody = org.json.JSONObject().apply {
                if (productId > 0L) put("product_id", productId)
                put("product_symbol", productSymbol)
                put("size", size)
                put("side", closeSide)
                put("order_type", "market_order")
                put("reduce_only", true)
                put("client_order_id", clientOrderId.take(32))
            }.toString()

            val orderPath = "/v2/orders"
            val orderTs = (System.currentTimeMillis()/1000).toString()
            val orderSig = hmacHex(credentials.secret, "POST$orderTs$orderPath$orderBody")
            val orderReq = Request.Builder()
                .url(DELTA + orderPath)
                .post(orderBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("api-key", credentials.apiKey)
                .addHeader("timestamp", orderTs)
                .addHeader("signature", orderSig)
                .addHeader("User-Agent", "NitinProTrading/1.0 (Android)")
                .build()

            val (oc, ob) = execute(orderReq)
            val oj = runCatching { org.json.JSONObject(ob) }.getOrNull()
            val or = oj?.optJSONObject("result")
            val orderId = or?.optString("id", "").orEmpty()
            val state = or?.optString("state", "").orEmpty().lowercase(Locale.US)
            val accepted = oc in 200..299 && oj?.optBoolean("success", false) == true && orderId.isNotBlank()
            if (!accepted) {
                return RouterOrderResult(false, "DeltaIndia", orderId, "REJECTED", okMessage(oc, ob), ob, filled=false)
            }

            val initialAverageFill = or?.optString("average_fill_price","")?.toDoubleOrNull() ?: 0.0
            val initialOrderSize = or?.optString("size","")?.toDoubleOrNull() ?: size.toDouble()
            val initialUnfilled = or?.optString("unfilled_size","")?.toDoubleOrNull() ?: 0.0
            val initialCommission = or?.optString("paid_commission","")?.toDoubleOrNull()
                ?: or?.optString("commission","")?.toDoubleOrNull()
                ?: 0.0

            if (state == "closed") {
                val confirmed = getDeltaOrderById(credentials, orderId).copy(
                    contractValue=closeContractValue,
                    notionalType=closeNotionalType
                )
                return confirmed.copy(
                    success=true,
                    status="closed",
                    message="Bot position fully closed",
                    filled=true,
                    averageFillPrice = if (confirmed.averageFillPrice > 0.0) confirmed.averageFillPrice else initialAverageFill,
                    filledSize = if (confirmed.filledSize > 0.0) confirmed.filledSize else (initialOrderSize-initialUnfilled).coerceAtLeast(0.0),
                    paidCommission = if (confirmed.paidCommission != 0.0) confirmed.paidCommission else initialCommission
                )
            }

            var latest = RouterOrderResult(
                true, "DeltaIndia", orderId, state.ifBlank { "open" }, "Close order accepted", ob, filled=false,
                averageFillPrice=initialAverageFill,
                filledSize=(initialOrderSize-initialUnfilled).coerceAtLeast(0.0),
                paidCommission=initialCommission,
                contractValue=closeContractValue,
                notionalType=closeNotionalType
            )
            repeat(20) {
                if (latest.filled || latest.status.equals("closed", true) || latest.status.equals("cancelled", true) || latest.status.equals("rejected", true)) return@repeat
                delay(300)
                latest = getDeltaOrderById(credentials, orderId).copy(
                    contractValue=closeContractValue,
                    notionalType=closeNotionalType
                )
            }
            latest.copy(message = if (latest.filled || latest.status.equals("closed", true)) "Bot position fully closed" else "Close order ${latest.status}")
        } catch (e:Exception) {
            RouterOrderResult(false, "DeltaIndia", status="ERROR", message="Delta position close error: ${e.message}")
        }
    }

    /**
     * Close a bot-owned spot position with an opposite market order.
     * Derivative exchanges are deliberately excluded here because an
     * opposite order could open a new position if reduce-only semantics differ.
     */
    suspend fun closeBotSpotPosition(
        exchangeName:String,
        credentials:RouterCredentials,
        record:BotPositionRecord
    ):RouterOrderResult {
        val e = normalizeExchange(exchangeName)
            ?: return RouterOrderResult(false, exchangeName, status="ERROR", message="Unknown exchange")
        val spotSafe = setOf(
            ProfessionalExchange.COINDCX,
            ProfessionalExchange.BINANCE,
            ProfessionalExchange.WAZIRX,
            ProfessionalExchange.GIOTTUS,
            ProfessionalExchange.MEXC,
            ProfessionalExchange.KRAKEN,
            ProfessionalExchange.CRYPTO_COM,
            ProfessionalExchange.BITFINEX
        )
        if (e !in spotSafe) {
            return RouterOrderResult(false, e.label, status="UNSUPPORTED", message="Safe automatic close is not enabled for derivative/ambiguous adapter ${e.label}")
        }
        val side = if (record.side.equals("BUY", true)) "SELL" else "BUY"
        return placeOrder(
            e.label,
            credentials,
            RouterOrderRequest(
                symbol = record.symbol,
                side = side,
                quantity = record.quantity,
                price = record.entryPrice.coerceAtLeast(0.00000001),
                quoteAmount = 0.0,
                clientOrderId = "NITINBOT-CLOSE-${System.currentTimeMillis()}"
            )
        )
    }

    /**
     * Find active Delta orders belonging to this bot. We deliberately filter
     * by the NITINBOT client-order prefix so manual/user-created orders are
     * never cancelled by the bot's STOP action.
     */
    private suspend fun cancelDeltaBotOwnedOpenOrders(
        c:RouterCredentials
    ):RouterOrderResult {
        return try {
            val path="/v2/orders"
            val query="?page_size=100"
            val ts=(System.currentTimeMillis()/1000).toString()
            val sig=hmacHex(c.secret,"GET$ts$path$query")

            val req=Request.Builder()
                .url(DELTA+path+query)
                .get()
                .addHeader("Accept","application/json")
                .addHeader("api-key",c.apiKey)
                .addHeader("timestamp",ts)
                .addHeader("signature",sig)
                .addHeader("User-Agent","NitinProTrading/1.0 (Android)")
                .build()

            val (code,res)=execute(req)
            val j=try{org.json.JSONObject(res)}catch(_:Exception){null}

            if(code !in 200..299 || j?.optBoolean("success",false)!=true) {
                return RouterOrderResult(
                    false,
                    "DeltaIndia",
                    status="ERROR",
                    message="Delta open-order lookup failed: ${okMessage(code,res)}",
                    raw=res
                )
            }

            val arr=j.optJSONArray("result") ?: org.json.JSONArray()
            var cancelled=0
            var failed=0

            for(i in 0 until arr.length()) {
                val order=arr.optJSONObject(i) ?: continue
                val clientId=order.optString("client_order_id","")
                val state=order.optString("state","").lowercase(Locale.US)

                if(
                    !clientId.startsWith("NITINBOT-",ignoreCase=true) ||
                    state !in setOf("open","pending")
                ) continue

                val orderId=order.optString("id","")
                val productId=order.optInt("product_id",0)

                if(orderId.isBlank()) {
                    failed++
                    continue
                }

                val result=cancelOrder(
                    "DeltaIndia",
                    c,
                    orderId,
                    productId.takeIf { it>0 }
                )

                if(result.success) cancelled++ else failed++
            }

            if(failed==0) {
                RouterOrderResult(
                    true,
                    "DeltaIndia",
                    status="CANCELLED",
                    message="Delta bot orders cleared ($cancelled)",
                    raw=res
                )
            } else {
                RouterOrderResult(
                    false,
                    "DeltaIndia",
                    status="PARTIAL",
                    message="Delta bot order cleanup: cancelled=$cancelled failed=$failed",
                    raw=res
                )
            }
        } catch(ex:Exception) {
            RouterOrderResult(
                false,
                "DeltaIndia",
                status="ERROR",
                message="Delta bot-order cleanup error: ${ex.message}"
            )
        }
    }

    internal suspend fun findBotOwnedOpenOrders(
        c:RouterCredentials
    ): List<PendingRealOrder> {
        return try {
            val path="/v2/orders"
            val query="?page_size=100"
            val ts=(System.currentTimeMillis()/1000).toString()
            val sig=hmacHex(c.secret,"GET$ts$path$query")
            val req=Request.Builder()
                .url(DELTA+path+query)
                .get()
                .addHeader("Accept","application/json")
                .addHeader("api-key",c.apiKey)
                .addHeader("timestamp",ts)
                .addHeader("signature",sig)
                .addHeader("User-Agent","NitinProTrading/1.0 (Android)")
                .build()
            val (code,res)=execute(req)
            if(code !in 200..299) return emptyList()
            val j=runCatching{org.json.JSONObject(res)}.getOrNull() ?: return emptyList()
            val arr=j.optJSONArray("result") ?: return emptyList()
            buildList {
                for(i in 0 until arr.length()) {
                    val o=arr.optJSONObject(i) ?: continue
                    val clientId=o.optString("client_order_id","")
                    val state=o.optString("state","").lowercase(Locale.US)
                    if(!clientId.startsWith("NITINBOT-", true) || state !in setOf("open","pending")) continue
                    val orderId=o.optString("id","")
                    val symbol=o.optString("product_symbol",o.optString("symbol",""))
                    if(orderId.isBlank() || symbol.isBlank()) continue
                    val side=o.optString("side","BUY").uppercase(Locale.US)
                    val size=o.optString("size","").toDoubleOrNull() ?: 0.0
                    val price=o.optString("limit_price",o.optString("price","0")).toDoubleOrNull() ?: 0.0
                    add(PendingRealOrder(
                        symbol=symbol.trim().uppercase(Locale.US),
                        side=side,
                        orderId=orderId,
                        quantity=size,
                        price=price
                    ))
                }
            }
        } catch(e:Exception) {
            Log.w("DELTA_PENDING","Open bot order recovery failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Public lifecycle cleanup used by STOP and startup recovery.
     */
    suspend fun cancelBotOwnedOpenOrders(
        exchangeName:String,
        credentials:RouterCredentials
    ):RouterOrderResult {
        val e=normalizeExchange(exchangeName)
            ?: return RouterOrderResult(false,exchangeName,status="ERROR",message="Unknown exchange")

        return if(e==ProfessionalExchange.DELTA_INDIA) {
            cancelDeltaBotOwnedOpenOrders(credentials)
        } else {
            RouterOrderResult(
                false,
                e.label,
                status="UNSUPPORTED",
                message="${e.label} bot-order cleanup is not implemented in this build"
            )
        }
    }

    /**
     * Reconcile an already-created Delta order without creating a new order.
     * Network errors deliberately keep the local pending state alive.
     */
    suspend fun getOrderStatus(
        exchangeName:String,
        credentials:RouterCredentials,
        orderId:String
    ):RouterOrderResult {
        val e=normalizeExchange(exchangeName)
            ?: return RouterOrderResult(false,exchangeName,status="ERROR",message="Unknown exchange")

        return try {
            when(e) {
                ProfessionalExchange.DELTA_INDIA ->
                    getDeltaOrderById(credentials,orderId)
                else ->
                    RouterOrderResult(
                        false,
                        e.label,
                        orderId=orderId,
                        status="UNSUPPORTED",
                        message="${e.label} order reconciliation is not implemented in this build"
                    )
            }
        } catch(ex:Exception) {
            RouterOrderResult(
                false,
                e.label,
                orderId=orderId,
                status="ERROR",
                message="Order status error: ${ex.message}"
            )
        }
    }

    /**
     * Cancel exactly one Delta order. It never cancels unrelated/manual orders.
     */
    suspend fun cancelOrder(
        exchangeName:String,
        credentials:RouterCredentials,
        orderId:String,
        productId:Int?=null
    ):RouterOrderResult {
        val e=normalizeExchange(exchangeName)
            ?: return RouterOrderResult(false,exchangeName,status="ERROR",message="Unknown exchange")

        if(e!=ProfessionalExchange.DELTA_INDIA) {
            return RouterOrderResult(
                false,
                e.label,
                orderId=orderId,
                status="UNSUPPORTED",
                message="${e.label} order cancellation is not implemented in this build"
            )
        }

        if(orderId.isBlank()) {
            return RouterOrderResult(false,"DeltaIndia",status="ERROR",message="Missing Delta order id")
        }

        return try {
            val body=org.json.JSONObject().apply{
                put("id",orderId.toLongOrNull() ?: orderId)
                if(productId!=null && productId>0) put("product_id",productId)
            }.toString()

            val path="/v2/orders"
            val ts=(System.currentTimeMillis()/1000).toString()
            val sig=hmacHex(credentials.secret,"DELETE$ts$path$body")

            val req=Request.Builder()
                .url(DELTA+path)
                .delete(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Accept","application/json")
                .addHeader("Content-Type","application/json")
                .addHeader("api-key",credentials.apiKey)
                .addHeader("timestamp",ts)
                .addHeader("signature",sig)
                .addHeader("User-Agent","NitinProTrading/1.0 (Android)")
                .build()

            val (code,res)=execute(req)
            val j=try{org.json.JSONObject(res)}catch(_:Exception){null}
            val result=j?.optJSONObject("result")
            val state=result?.optString("state","cancelled")?.lowercase(Locale.US)
                ?: if(code in 200..299) "cancelled" else "error"
            val ok=code in 200..299 && j?.optBoolean("success",false)==true

            RouterOrderResult(
                success=ok,
                exchange="DeltaIndia",
                orderId=result?.optString("id",orderId) ?: orderId,
                status=state,
                message=if(ok) "Delta order cancellation confirmed" else okMessage(code,res),
                raw=res,
                filled=false
            )
        } catch(ex:Exception) {
            RouterOrderResult(
                false,
                "DeltaIndia",
                orderId=orderId,
                status="ERROR",
                message="Cancel error: ${ex.message}"
            )
        }
    }

    private suspend fun binance(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val symbol=symbolFor(ProfessionalExchange.BINANCE,r.symbol)
        val p=mutableListOf("symbol" to symbol,"side" to r.side.uppercase(Locale.US),"type" to "MARKET","timestamp" to System.currentTimeMillis().toString(),"recvWindow" to "5000")
        if(r.side.equals("BUY",true) && r.quoteAmount>0) p += "quoteOrderQty" to r.quoteAmount.toString() else p += "quantity" to r.quantity.toString()
        val q=binanceQuery(p); val sig=hmacHex(c.secret,q); val req=Request.Builder().url("$BINANCE/api/v3/order?$q&signature=$sig").post(ByteArray(0).toRequestBody(null)).addHeader("X-MBX-APIKEY",c.apiKey).build()
        val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val id=j?.optString("orderId","") ?: ""; val status=j?.optString("status","") ?: ""
        return RouterOrderResult(code in 200..299,"Binance",id,status,okMessage(code,res),res)
    }

    private suspend fun wazirx(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        // WazirX Spot REST currently documents LIMIT/STOP_LIMIT, so use a bounded aggressive LIMIT instead of inventing a MARKET route.
        val symbol=symbolFor(ProfessionalExchange.WAZIRX,r.symbol)
        val side=r.side.lowercase(Locale.US); val p=if(side=="buy") r.price*1.001 else r.price*0.999
        val params=linkedMapOf("symbol" to symbol,"side" to side,"type" to "limit","quantity" to r.quantity.toString(),"price" to p.toString(),"recvWindow" to "5000","timestamp" to System.currentTimeMillis().toString(),"clientOrderId" to r.clientOrderId.take(32))
        val q=params.entries.joinToString("&"){ "${enc(it.key)}=${enc(it.value)}" }; val sig=hmacHex(c.secret,q); val req=Request.Builder().url("$WAZIRX/sapi/v1/order").post("$q&signature=$sig".toRequestBody("application/x-www-form-urlencoded".toMediaType())).addHeader("X-API-KEY",c.apiKey).build()
        val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val id=j?.optString("id","") ?: ""; val status=j?.optString("status","") ?: ""
        return RouterOrderResult(code in 200..299,"WazirX",id,status,okMessage(code,res),res)
    }

    private suspend fun giottus(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val symbol=symbolFor(ProfessionalExchange.GIOTTUS,r.symbol)
        val body=org.json.JSONObject().apply{put("symbol",symbol);put("action",r.side.uppercase(Locale.US));put("type","MARKET");put("quantity",r.quantity.toString())}.toString()
        val ts=System.currentTimeMillis().toString(); val recv="5000"; val canonical="recvWindow$recv"+"timestamp$ts"+"action${r.side.uppercase(Locale.US)}"+"quantity${r.quantity}"+"symbol$symbol"+"typeMARKET"; val sig=hmacHex(c.secret,canonical)
        val req=Request.Builder().url("$GIOTTUS/api/v1/spot/order/create?timestamp=$ts&recvWindow=$recv&signature=$sig").post(body.toRequestBody("application/json".toMediaType())).addHeader("X-GIOTTUS-APIKEY",c.apiKey).build()
        val (code,res)=execute(req); val success=code in 200..299; val msg=try{org.json.JSONObject(res).optString("msg",res.take(180))}catch(_:Exception){res.take(180)}
        return RouterOrderResult(success,"Giottus","","PENDING",msg,res)
    }

    private suspend fun bybit(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val body=org.json.JSONObject().apply{put("category","spot");put("symbol",symbolFor(ProfessionalExchange.BYBIT,r.symbol));put("side",if(r.side.equals("BUY",true))"Buy" else "Sell");put("orderType","Market");put("qty",r.quantity.toString());put("orderLinkId",r.clientOrderId.take(36))}.toString()
        val ts=System.currentTimeMillis().toString(); val rw="5000"; val sign=hmacHex(c.secret,ts+c.apiKey+rw+body)
        val req=Request.Builder().url("$BYBIT/v5/order/create").post(body.toRequestBody("application/json".toMediaType())).addHeader("X-BAPI-API-KEY",c.apiKey).addHeader("X-BAPI-TIMESTAMP",ts).addHeader("X-BAPI-RECV-WINDOW",rw).addHeader("X-BAPI-SIGN",sign).build()
        val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val ok=code in 200..299 && j?.optInt("retCode",-1)==0; val id=j?.optJSONObject("result")?.optString("orderId","") ?: ""
        return RouterOrderResult(ok,"Bybit",id,"PENDING",j?.optString("retMsg",okMessage(code,res)) ?: okMessage(code,res),res)
    }

    private fun okxIso():String=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(java.util.Date())
    private suspend fun okx(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val body=org.json.JSONObject().apply{put("instId",symbolFor(ProfessionalExchange.OKX,r.symbol));put("tdMode","cash");put("side",r.side.lowercase(Locale.US));put("ordType","market");put("sz",r.quantity.toString());put("clOrdId",r.clientOrderId.take(32))}.toString()
        val ts=okxIso(); val path="/api/v5/trade/order"; val sign=hmacB64(c.secret,ts+"POST"+path+body)
        val req=Request.Builder().url(OKX+path).post(body.toRequestBody("application/json".toMediaType())).addHeader("OK-ACCESS-KEY",c.apiKey).addHeader("OK-ACCESS-SIGN",sign).addHeader("OK-ACCESS-TIMESTAMP",ts).addHeader("OK-ACCESS-PASSPHRASE",c.passphrase).addHeader("Content-Type","application/json").build()
        val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val ok=code in 200..299 && j?.optString("code") == "0"; val id=j?.optJSONArray("data")?.optJSONObject(0)?.optString("ordId","") ?: ""
        return RouterOrderResult(ok,"OKX",id,"PENDING",j?.optString("msg",okMessage(code,res)) ?: okMessage(code,res),res)
    }

    private suspend fun bitget(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val body=org.json.JSONObject().apply{put("symbol",symbolFor(ProfessionalExchange.BITGET,r.symbol));put("side",r.side.lowercase(Locale.US));put("orderType","market");put("force","gtc");put("size",r.quantity.toString());put("clientOid",r.clientOrderId.take(32))}.toString()
        val ts=System.currentTimeMillis().toString(); val path="/api/v2/spot/trade/place-order"; val sign=hmacB64(c.secret,ts+"POST"+path+body)
        val req=Request.Builder().url(BITGET+path).post(body.toRequestBody("application/json".toMediaType())).addHeader("ACCESS-KEY",c.apiKey).addHeader("ACCESS-SIGN",sign).addHeader("ACCESS-TIMESTAMP",ts).addHeader("ACCESS-PASSPHRASE",c.passphrase).addHeader("locale","en-US").build()
        val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val ok=code in 200..299 && j?.optString("code") == "00000"; val id=j?.optJSONObject("data")?.optString("orderId","") ?: ""
        return RouterOrderResult(ok,"Bitget",id,"PENDING",j?.optString("msg",okMessage(code,res)) ?: okMessage(code,res),res)
    }

    private suspend fun mexc(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val p=linkedMapOf("symbol" to symbolFor(ProfessionalExchange.MEXC,r.symbol),"side" to r.side.uppercase(Locale.US),"type" to "MARKET","quantity" to r.quantity.toString(),"recvWindow" to "5000","timestamp" to System.currentTimeMillis().toString())
        val q=p.entries.joinToString("&"){ "${enc(it.key)}=${enc(it.value)}" }; val sig=hmacHex(c.secret,q)
        val req=Request.Builder().url("$MEXC/api/v3/order?$q&signature=$sig").post(ByteArray(0).toRequestBody(null)).addHeader("X-MEXC-APIKEY",c.apiKey).build()
        val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val id=j?.optString("orderId","") ?: ""; val status=j?.optString("status","") ?: ""
        return RouterOrderResult(code in 200..299,"MEXC",id,status,if(code in 200..299)"accepted" else okMessage(code,res),res)
    }

    private suspend fun kucoin(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val body=org.json.JSONObject().apply{put("clientOid",r.clientOrderId);put("side",r.side.lowercase(Locale.US));put("symbol",symbolFor(ProfessionalExchange.KUCOIN,r.symbol));put("type","market");put("size",r.quantity.toString());put("tradeType","TRADE")}.toString()
        val ts=System.currentTimeMillis().toString(); val path="/api/v1/orders"; val sign=hmacB64(c.secret,ts+"POST"+path+body); val pass=hmacB64(c.secret,c.passphrase)
        val req=Request.Builder().url(KUCOIN+path).post(body.toRequestBody("application/json".toMediaType())).addHeader("KC-API-KEY",c.apiKey).addHeader("KC-API-SIGN",sign).addHeader("KC-API-TIMESTAMP",ts).addHeader("KC-API-PASSPHRASE",pass).addHeader("KC-API-KEY-VERSION","2").build()
        val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val ok=code in 200..299 && j?.optString("code")=="200000"; val id=j?.optJSONObject("data")?.optString("orderId","") ?: ""
        return RouterOrderResult(ok,"KuCoin",id,"PENDING",j?.optString("msg",okMessage(code,res)) ?: okMessage(code,res),res)
    }

    private suspend fun gate(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val path="/api/v4/spot/orders"; val pair=symbolFor(ProfessionalExchange.GATE_IO,r.symbol); val body=org.json.JSONObject().apply{put("currency_pair",pair);put("type","market");put("account","spot");put("side",r.side.lowercase(Locale.US));put("amount",r.quantity.toString());put("text","t-${r.clientOrderId.take(20)}")}.toString(); val ts=(System.currentTimeMillis()/1000).toString(); val bodyHash=sha512Hex(body); val sign=hmacHex(c.secret,"POST\n$path\n\n$bodyHash\n$ts")
        val req=Request.Builder().url(GATE+path).post(body.toRequestBody("application/json".toMediaType())).addHeader("KEY",c.apiKey).addHeader("SIGN",sign).addHeader("Timestamp",ts).addHeader("Content-Type","application/json").build()
        val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val id=j?.optString("id","") ?: ""; val status=j?.optString("status","") ?: ""
        return RouterOrderResult(code in 200..299,"Gate.io",id,status,if(code in 200..299)"accepted" else okMessage(code,res),res)
    }

    private suspend fun htxAccountId(c:RouterCredentials):String{
        val path="/v1/account/accounts"; val ts=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(java.util.Date()); val q="AccessKeyId=${enc(c.apiKey)}&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=${enc(ts)}"; val pre="GET\napi.huobi.pro\n$path\n$q"; val sig=java.net.URLEncoder.encode(hmacB64(c.secret,pre),"UTF-8"); val req=Request.Builder().url("$HTX$path?$q&Signature=$sig").get().build(); val (code,res)=execute(req); if(code !in 200..299)return ""; val j=try{org.json.JSONObject(res)}catch(_:Exception){return ""}; val data=j.optJSONArray("data") ?: return ""; for(i in 0 until data.length()){val o=data.optJSONObject(i);if(o?.optString("type")=="spot")return o.optString("id","")}; return ""
    }

    private suspend fun htx(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val account=htxAccountId(c); if(account.isBlank())return RouterOrderResult(false,"HTX",message="Spot account id lookup failed")
        val path="/v1/order/orders/place"; val body=org.json.JSONObject().apply{put("account-id",account);put("amount",r.quantity.toString());put("source","spot-api");put("symbol",symbolFor(ProfessionalExchange.HTX,r.symbol));put("type",if(r.side.equals("BUY",true))"buy-market" else "sell-market");put("client-order-id",r.clientOrderId.take(64))}.toString()
        val ts=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(java.util.Date()); val q="AccessKeyId=${enc(c.apiKey)}&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=${enc(ts)}"; val pre="POST\napi.huobi.pro\n$path\n$q"; val sig=java.net.URLEncoder.encode(hmacB64(c.secret,pre),"UTF-8")
        val req=Request.Builder().url("$HTX$path?$q&Signature=$sig").post(body.toRequestBody("application/json".toMediaType())).build(); val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val ok=code in 200..299 && j?.optString("status")=="ok"; val id=j?.optString("data","") ?: ""; return RouterOrderResult(ok,"HTX",id,"PENDING",j?.optString("err-msg",okMessage(code,res)) ?: okMessage(code,res),res)
    }

    private suspend fun kraken(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val nonce=System.currentTimeMillis()*1000L; val pair=symbolFor(ProfessionalExchange.KRAKEN,r.symbol); val data=linkedMapOf("nonce" to nonce.toString(),"ordertype" to "market","type" to r.side.lowercase(Locale.US),"volume" to r.quantity.toString(),"pair" to pair); val post=data.entries.joinToString("&"){ "${enc(it.key)}=${enc(it.value)}" }; val path="/0/private/AddOrder"; val hash=sha256Bytes((nonce.toString()+post).toByteArray(Charsets.UTF_8)); val sigData=path.toByteArray(Charsets.UTF_8)+hash; val sign=hmacSha512B64(c.secret,sigData); val req=Request.Builder().url(KRAKEN+path).post(post.toRequestBody("application/x-www-form-urlencoded".toMediaType())).addHeader("API-Key",c.apiKey).addHeader("API-Sign",sign).build(); val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val errors=j?.optJSONArray("error"); val ok=code in 200..299 && (errors==null||errors.length()==0); val result=j?.optJSONObject("result"); val txid=result?.optJSONArray("txid")?.optString(0,"") ?: ""; return RouterOrderResult(ok,"Kraken",txid,"PENDING",if(ok)"accepted" else (errors?.toString() ?: okMessage(code,res)),res)
    }

    private fun cryptoParamString(params:org.json.JSONObject):String{
        val keys=params.keys().asSequence().toList().sorted(); return buildString{for(k in keys){val v=params.get(k); append(k); when(v){is org.json.JSONObject->append(cryptoParamString(v));is org.json.JSONArray->for(i in 0 until v.length()){val x=v.get(i);if(x is org.json.JSONObject)append(cryptoParamString(x))else append(x)};else->append(v)}}}
    }

    private suspend fun cryptoCom(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val id=System.currentTimeMillis(); val params=org.json.JSONObject().apply{put("instrument_name",symbolFor(ProfessionalExchange.CRYPTO_COM,r.symbol));put("side",r.side.uppercase(Locale.US));put("type","MARKET");put("quantity",r.quantity.toString());put("client_oid",r.clientOrderId.take(36))}; val nonce=System.currentTimeMillis(); val method="private/create-order"; val paramString=cryptoParamString(params); val sig=hmacHex(c.secret,method+id+c.apiKey+paramString+nonce); val body=org.json.JSONObject().apply{put("id",id);put("method",method);put("api_key",c.apiKey);put("params",params);put("nonce",nonce);put("sig",sig)}.toString(); val req=Request.Builder().url(CCOM).post(body.toRequestBody("application/json".toMediaType())).build(); val (code,res)=execute(req); val j=try{org.json.JSONObject(res)}catch(_:Exception){null}; val ok=code in 200..299 && j?.optInt("code",-1)==0; val oid=j?.optJSONObject("result")?.optString("order_id","") ?: ""; return RouterOrderResult(ok,"Crypto.com",oid,"PENDING",j?.optString("message",okMessage(code,res)) ?: okMessage(code,res),res)
    }

    private suspend fun bitfinex(c:RouterCredentials,r:RouterOrderRequest):RouterOrderResult{
        val pair="t${symbolBase(r.symbol)}${quote(r.symbol)}"
        val nonce=System.currentTimeMillis().toString()
        val body=org.json.JSONObject().apply{
            put("type","EXCHANGE MARKET");put("symbol",pair);put("amount",if(r.side.equals("BUY",true))r.quantity.toString() else "-${r.quantity}")
            put("cid",(System.currentTimeMillis()%1000000000).toInt())
        }.toString()
        val path="/api/v2/auth/w/order/submit"
        val sign=hmac384Hex(c.secret,path+nonce+body)
        val req=Request.Builder().url("https://api.bitfinex.com/v2/auth/w/order/submit")
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("bfx-apikey",c.apiKey).addHeader("bfx-nonce",nonce).addHeader("bfx-signature",sign).build()
        val(code,res)=execute(req)
        val ok=code in 200..299 && res.contains("SUCCESS",true)
        return RouterOrderResult(ok,"Bitfinex","","PENDING",if(ok)"accepted" else okMessage(code,res),res)
    }

    suspend fun readBalance(exchangeName:String,c:RouterCredentials):RouterBalanceResult{
        val e=normalizeExchange(exchangeName) ?: return RouterBalanceResult(false,message="Unknown exchange")
        if(!e.realAdapter)return RouterBalanceResult(false,message="${e.label}: official credential/auth model not configured")
        return try{
            when(e){
                ProfessionalExchange.COINDCX->readCoinDcxBalance(c)
                ProfessionalExchange.DELTA_INDIA->readDeltaBalance(c)
                ProfessionalExchange.BINANCE->readBinanceBalance(c)
                ProfessionalExchange.WAZIRX->readWazirxBalance(c)
                ProfessionalExchange.GIOTTUS->readGiottusBalance(c)
                ProfessionalExchange.BYBIT->readBybitBalance(c)
                ProfessionalExchange.OKX->readOkxBalance(c)
                ProfessionalExchange.BITGET->readBitgetBalance(c)
                ProfessionalExchange.MEXC->readMexcBalance(c)
                ProfessionalExchange.KUCOIN->readKucoinBalance(c)
                ProfessionalExchange.GATE_IO->readGateBalance(c)
                ProfessionalExchange.HTX->readHtxBalance(c)
                ProfessionalExchange.KRAKEN->readKrakenBalance(c)
                ProfessionalExchange.CRYPTO_COM->readCryptoComBalance(c)
                ProfessionalExchange.BITFINEX->readBitfinexBalance(c)
                else->RouterBalanceResult(false,message="No verified balance adapter")
            }
        }catch(ex:Exception){RouterBalanceResult(false,message="Balance adapter error: ${ex.message}")}
    }

    private fun parseSimpleHoldings(j:Any?):List<Pair<String,Double>>{
        val out=mutableListOf<Pair<String,Double>>()
        fun walk(v:Any?,depth:Int){if(depth>5)return;when(v){is org.json.JSONArray->for(i in 0 until v.length())walk(v.get(i),depth+1);is org.json.JSONObject->{val sym=listOf("asset","currency","coin","ccy","instrument_name").firstNotNullOfOrNull{key->v.optString(key,"").takeIf{it.isNotBlank()}};val amount=listOf("free","available","balance","cash","total","equity","available_balance").firstNotNullOfOrNull{key->v.optDouble(key,Double.NaN).takeIf{it.isFinite()}};if(sym!=null&&amount!=null&&amount>0)out+=sym.uppercase(Locale.US) to amount;val it=v.keys();while(it.hasNext())walk(v.get(it.next()),depth+1)}}};walk(j,0);return out.groupBy{it.first}.map{it.key to (it.value.maxOfOrNull{p->p.second}?:0.0)}.filter{it.second>0}
    }

    private fun snapshotFromHoldings(h:List<Pair<String,Double>>,currency:String="USD"):RouterBalanceResult{val stable=h.filter{it.first in setOf("USD","USDT","USDC","INR")};val total=stable.sumOf{it.second};return RouterBalanceResult(true,total,currency,h,"balance read OK","")}

    private suspend fun readCoinDcxBalance(c:RouterCredentials):RouterBalanceResult{
        val body="{\"timestamp\":${System.currentTimeMillis()}}"; val req=Request.Builder().url("$COINDCX/exchange/v1/users/balances").post(body.toRequestBody("application/json".toMediaType())).addHeader("X-AUTH-APIKEY",c.apiKey).addHeader("X-AUTH-SIGNATURE",hmacHex(c.secret,body)).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val a=org.json.JSONArray(res);val h=mutableListOf<Pair<String,Double>>();for(i in 0 until a.length()){val o=a.getJSONObject(i);val b=o.optDouble("balance",0.0);if(b>0)h+=o.optString("currency").uppercase(Locale.US) to b};return snapshotFromHoldings(h,"INR").copy(raw=res)
    }

    private suspend fun readDeltaBalance(c:RouterCredentials):RouterBalanceResult{
        val ts=(System.currentTimeMillis()/1000).toString()
        val path="/v2/wallet/balances"
        val req=Request.Builder()
            .url(DELTA+path)
            .get()
            .addHeader("api-key",c.apiKey)
            .addHeader("timestamp",ts)
            .addHeader("signature",hmacHex(c.secret,"GET$ts$path"))
            .addHeader("User-Agent","NitinProTrading/1.0 (Android)")
            .build()

        val(code,res)=execute(req)
        if(code !in 200..299) return RouterBalanceResult(false,message=okMessage(code,res),raw=res)

        val j=try{org.json.JSONObject(res)}catch(_:Exception){
            return RouterBalanceResult(false,message="Invalid Delta wallet response",raw=res)
        }

        val arr=j.optJSONArray("result") ?: j.optJSONArray("data") ?: org.json.JSONArray()
        val h=mutableListOf<Pair<String,Double>>()
        for(i in 0 until arr.length()){
            val o=arr.optJSONObject(i) ?: continue
            val a=o.optString("asset_symbol",o.optString("symbol",o.optString("currency","")))
            val b=o.optString("balance","").toDoubleOrNull()
                ?: o.optString("available_balance","").toDoubleOrNull()
                ?: 0.0
            if(a.isNotBlank() && b.isFinite() && b>0.0){
                h += a.uppercase(Locale.US) to b
            }
        }

        // Delta exposes the real consolidated account equity in meta.net_equity.
        // Use it for Account Value so realized exchange P/L is reflected.
        val meta=j.optJSONObject("meta")
        val netEquity=meta?.optString("net_equity","")?.toDoubleOrNull()
            ?.takeIf{it.isFinite() && it>=0.0}
        val roboEquity=meta?.optString("robo_trading_equity","")?.toDoubleOrNull()
            ?.takeIf{it.isFinite() && it>=0.0}
        // IMPORTANT: Account Value must use the actual Delta wallet balance.
        // net_equity/robo_trading_equity are equity fields and can include
        // margin/unrealized components, so they must not replace the real
        // wallet balance shown by the exchange account.
        val walletStableBalance = h
            .filter { it.first in setOf("USD", "USDT", "USDC") }
            .sumOf { it.second }

        if(walletStableBalance > 0.0){
            return RouterBalanceResult(
                success=true,
                total=walletStableBalance,
                currency="USD",
                holdings=h,
                message="Delta LIVE wallet balance",
                raw=res
            )
        }

        // If no stablecoin wallet row is returned, retain the verified
        // Delta equity fallback rather than inventing a balance.
        val realEquity=netEquity ?: roboEquity
        if(realEquity!=null){
            return RouterBalanceResult(
                success=true,
                total=realEquity,
                currency="USD",
                holdings=h,
                message="Delta LIVE equity fallback",
                raw=res
            )
        }

        return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readBinanceBalance(c:RouterCredentials):RouterBalanceResult{
        val p="timestamp=${System.currentTimeMillis()}&recvWindow=5000";val req=Request.Builder().url("$BINANCE/api/v3/account?$p&signature=${hmacHex(c.secret,p)}").get().addHeader("X-MBX-APIKEY",c.apiKey).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val j=org.json.JSONObject(res);val a=j.optJSONArray("balances")?:org.json.JSONArray();val h=mutableListOf<Pair<String,Double>>();for(i in 0 until a.length()){val o=a.getJSONObject(i);val b=o.optString("free","0").toDoubleOrNull()?:0.0;if(b>0)h+=o.optString("asset").uppercase(Locale.US) to b};return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readWazirxBalance(c:RouterCredentials):RouterBalanceResult{
        val p="recvWindow=5000&timestamp=${System.currentTimeMillis()}";val req=Request.Builder().url("$WAZIRX/sapi/v1/funds?$p&signature=${hmacHex(c.secret,p)}").get().addHeader("X-API-KEY",c.apiKey).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val a=try{org.json.JSONArray(res)}catch(_:Exception){org.json.JSONObject(res).optJSONArray("data")?:org.json.JSONArray()};val h=mutableListOf<Pair<String,Double>>();for(i in 0 until a.length()){val o=a.optJSONObject(i);val b=o?.optString("free","0")?.toDoubleOrNull()?:0.0;if(b>0)h+=o?.optString("asset","")?.uppercase(Locale.US).orEmpty() to b};return snapshotFromHoldings(h,"INR").copy(raw=res)
    }

    private suspend fun readGiottusBalance(c:RouterCredentials):RouterBalanceResult{
        val ts=System.currentTimeMillis().toString();val path="/api/v1/wallet/balances";val recv="5000";val sig=hmacHex(c.secret,"recvWindow$recv"+"timestamp$ts");val req=Request.Builder().url("$GIOTTUS$path?timestamp=$ts&recvWindow=$recv&signature=$sig").get().addHeader("X-GIOTTUS-APIKEY",c.apiKey).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val h=parseSimpleHoldings(try{org.json.JSONArray(res)}catch(_:Exception){org.json.JSONObject(res)});return snapshotFromHoldings(h,"INR").copy(raw=res)
    }

    private suspend fun readBybitBalance(c:RouterCredentials):RouterBalanceResult{
        val ts=System.currentTimeMillis().toString();val rw="5000";val q="accountType=UNIFIED";val sign=hmacHex(c.secret,ts+c.apiKey+rw+q);val req=Request.Builder().url("$BYBIT/v5/account/wallet-balance?$q").get().addHeader("X-BAPI-API-KEY",c.apiKey).addHeader("X-BAPI-TIMESTAMP",ts).addHeader("X-BAPI-RECV-WINDOW",rw).addHeader("X-BAPI-SIGN",sign).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val j=org.json.JSONObject(res);val list=j.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)?.optJSONArray("coin")?:org.json.JSONArray();val h=mutableListOf<Pair<String,Double>>();for(i in 0 until list.length()){val o=list.getJSONObject(i);val b=o.optString("walletBalance","0").toDoubleOrNull()?:0.0;if(b>0)h+=o.optString("coin").uppercase(Locale.US) to b};return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readOkxBalance(c:RouterCredentials):RouterBalanceResult{
        val ts=okxIso();val path="/api/v5/account/balance";val sign=hmacB64(c.secret,ts+"GET"+path);val req=Request.Builder().url(OKX+path).get().addHeader("OK-ACCESS-KEY",c.apiKey).addHeader("OK-ACCESS-SIGN",sign).addHeader("OK-ACCESS-TIMESTAMP",ts).addHeader("OK-ACCESS-PASSPHRASE",c.passphrase).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val j=org.json.JSONObject(res);val data=j.optJSONArray("data")?.optJSONObject(0)?.optJSONArray("details")?:org.json.JSONArray();val h=mutableListOf<Pair<String,Double>>();for(i in 0 until data.length()){val o=data.getJSONObject(i);val b=o.optString("cashBal","0").toDoubleOrNull()?:0.0;if(b>0)h+=o.optString("ccy").uppercase(Locale.US) to b};return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readBitgetBalance(c:RouterCredentials):RouterBalanceResult{
        val ts=System.currentTimeMillis().toString();val path="/api/v2/spot/account/assets";val sign=hmacB64(c.secret,ts+"GET"+path);val req=Request.Builder().url(BITGET+path).get().addHeader("ACCESS-KEY",c.apiKey).addHeader("ACCESS-SIGN",sign).addHeader("ACCESS-TIMESTAMP",ts).addHeader("ACCESS-PASSPHRASE",c.passphrase).addHeader("locale","en-US").build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val j=org.json.JSONObject(res);val a=j.optJSONArray("data")?:org.json.JSONArray();val h=parseSimpleHoldings(a);return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readMexcBalance(c:RouterCredentials):RouterBalanceResult{
        val p="timestamp=${System.currentTimeMillis()}&recvWindow=5000";val req=Request.Builder().url("$MEXC/api/v3/account?$p&signature=${hmacHex(c.secret,p)}").get().addHeader("X-MEXC-APIKEY",c.apiKey).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val j=org.json.JSONObject(res);val a=j.optJSONArray("balances")?:org.json.JSONArray();val h=mutableListOf<Pair<String,Double>>();for(i in 0 until a.length()){val o=a.getJSONObject(i);val b=o.optString("free","0").toDoubleOrNull()?:0.0;if(b>0)h+=o.optString("asset").uppercase(Locale.US) to b};return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readKucoinBalance(c:RouterCredentials):RouterBalanceResult{
        val ts=System.currentTimeMillis().toString();val path="/api/v1/accounts";val sign=hmacB64(c.secret,ts+"GET"+path);val pass=hmacB64(c.secret,c.passphrase);val req=Request.Builder().url(KUCOIN+path).get().addHeader("KC-API-KEY",c.apiKey).addHeader("KC-API-SIGN",sign).addHeader("KC-API-TIMESTAMP",ts).addHeader("KC-API-PASSPHRASE",pass).addHeader("KC-API-KEY-VERSION","2").build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val j=org.json.JSONObject(res);val a=j.optJSONArray("data")?:org.json.JSONArray();val h=parseSimpleHoldings(a);return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readGateBalance(c:RouterCredentials):RouterBalanceResult{
        val path="/api/v4/spot/accounts";val ts=(System.currentTimeMillis()/1000).toString();val sign=hmacHex(c.secret,"GET\n$path\n\n\n$ts");val req=Request.Builder().url(GATE+path).get().addHeader("KEY",c.apiKey).addHeader("SIGN",sign).addHeader("Timestamp",ts).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val a=org.json.JSONArray(res);val h=parseSimpleHoldings(a);return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readHtxBalance(c:RouterCredentials):RouterBalanceResult{
        val account=htxAccountId(c);if(account.isBlank())return RouterBalanceResult(false,message="HTX account id lookup failed");val path="/v1/account/accounts/$account/balance";val ts=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(java.util.Date());val q="AccessKeyId=${enc(c.apiKey)}&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=${enc(ts)}";val pre="GET\napi.huobi.pro\n$path\n$q";val sig=java.net.URLEncoder.encode(hmacB64(c.secret,pre),"UTF-8");val req=Request.Builder().url("$HTX$path?$q&Signature=$sig").get().build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val h=parseSimpleHoldings(org.json.JSONObject(res).optJSONObject("data")?.optJSONArray("list")?:org.json.JSONArray());return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readKrakenBalance(c:RouterCredentials):RouterBalanceResult{
        val nonce=System.currentTimeMillis()*1000L;val post="nonce=$nonce";val path="/0/private/Balance";val hash=sha256Bytes((nonce.toString()+post).toByteArray(Charsets.UTF_8));val sign=hmacSha512B64(c.secret,path.toByteArray(Charsets.UTF_8)+hash);val req=Request.Builder().url(KRAKEN+path).post(post.toRequestBody("application/x-www-form-urlencoded".toMediaType())).addHeader("API-Key",c.apiKey).addHeader("API-Sign",sign).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val h=parseSimpleHoldings(try{org.json.JSONObject(res).optJSONObject("result")?:org.json.JSONObject()}catch(_:Exception){org.json.JSONObject()});return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readBitfinexBalance(c:RouterCredentials):RouterBalanceResult{
        val nonce=System.currentTimeMillis().toString();val path="/api/v2/auth/r/wallets";val body="{}";val sign=hmac384Hex(c.secret,path+nonce+body)
        val req=Request.Builder().url("https://api.bitfinex.com/v2/auth/r/wallets").post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("bfx-apikey",c.apiKey).addHeader("bfx-nonce",nonce).addHeader("bfx-signature",sign).build()
        val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res)
        val a=try{org.json.JSONArray(res)}catch(_:Exception){org.json.JSONArray()};val h=mutableListOf<Pair<String,Double>>()
        for(i in 0 until a.length()){val row=a.optJSONArray(i);val type=row?.optString(0,"") ?: "";val ccy=row?.optString(1,"") ?: "";val amount=row?.optDouble(2,0.0) ?: 0.0;if(type=="exchange"&&ccy.isNotBlank()&&amount>0)h+=ccy.uppercase(Locale.US) to amount}
        return snapshotFromHoldings(h,"USD").copy(raw=res)
    }

    private suspend fun readCryptoComBalance(c:RouterCredentials):RouterBalanceResult{
        val id=System.currentTimeMillis();val method="private/get-account-summary";val params=org.json.JSONObject();val nonce=System.currentTimeMillis();val sig=hmacHex(c.secret,method+id+c.apiKey+""+nonce);val body=org.json.JSONObject().apply{put("id",id);put("method",method);put("api_key",c.apiKey);put("params",params);put("nonce",nonce);put("sig",sig)}.toString();val req=Request.Builder().url(CCOM).post(body.toRequestBody("application/json".toMediaType())).build();val(code,res)=execute(req);if(code !in 200..299)return RouterBalanceResult(false,message=okMessage(code,res),raw=res);val h=parseSimpleHoldings(try{org.json.JSONObject(res).optJSONObject("result")?.optJSONArray("accounts")?:org.json.JSONArray()}catch(_:Exception){org.json.JSONArray()});return snapshotFromHoldings(h,"USD").copy(raw=res)
    }
}


class TradingViewModel(
    application:Application
):AndroidViewModel(application) {

    private val prefs:SharedPreferences =
        application.getSharedPreferences(
            "trading_bot_prefs",
            Context.MODE_PRIVATE
        )

    init {
        AiKeyStore.migrateLegacy(application)
    }

    // ============================================================
    // DUAL AI ARCHITECTURE
    // AI #1 = official OpenAI Market AI market/news/flow intelligence.
    // AI #2 = official OpenAI GPT order brain.
    // API keys are stored through Android Keystore; no fake keys/endpoints.
    // ============================================================
    private val openAiMarketIntelligence = OpenAiMarketIntelligenceEngine(
        apiKeyProvider = { AiKeyStore.get(getApplication<Application>(), AiKeyStore.PROVIDER_OPENAI) },
        baseUrlProvider = {
            prefs.getString(
                "openai_market_base_url",
                OpenAiMarketIntelligenceEngine.DEFAULT_BASE_URL
            )?.trim().orEmpty().ifBlank { OpenAiMarketIntelligenceEngine.DEFAULT_BASE_URL }
        },
        modelProvider = {
            prefs.getString("openai_market_model", OpenAiMarketIntelligenceEngine.DEFAULT_MODEL)
                ?.trim().orEmpty().ifBlank { OpenAiMarketIntelligenceEngine.DEFAULT_MODEL }
        }
    )

    private val openAiMasterMind = MasterMindAutoStrategyEngine(
        apiKeyProvider = { AiKeyStore.get(getApplication<Application>(), AiKeyStore.PROVIDER_OPENAI) },
        modelProvider = { "gpt-5.6-luna" },
        baseUrlProvider = { "https://api.openai.com/v1" },
        enabledProvider = { AiKeyStore.get(getApplication<Application>(), AiKeyStore.PROVIDER_OPENAI).isNotBlank() }
    )

    fun setOpenAiMarketIntelligenceConfig(baseUrl: String, model: String = OpenAiMarketIntelligenceEngine.DEFAULT_MODEL) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val cleanModel = model.trim().ifBlank { OpenAiMarketIntelligenceEngine.DEFAULT_MODEL }
        prefs.edit()
            .putString("openai_market_base_url", cleanUrl)
            .putString("openai_market_model", cleanModel)
            .apply()
        openAiMarketIntelligence.clear()
    }

    fun setDualAiKeys(marketAiApiKey: String, openAiApiKey: String, enabled: Boolean = true) {
        AiKeyStore.put(getApplication<Application>(), AiKeyStore.PROVIDER_OPENAI, openAiApiKey.ifBlank { marketAiApiKey })
        prefs.edit().putBoolean("dual_ai_enabled", enabled).apply()
        openAiMarketIntelligence.clear()
        openAiMasterMind.clear()
        _pipeline.value = _pipeline.value.copy(
            orderStatus = if (openAiApiKey.isNotBlank() || marketAiApiKey.isNotBlank())
                "🧠 DUAL AI ACTIVE • OpenAI Market AI Market + GPT Order Brain"
            else
                "⚠️ DUAL AI WAITING • add a real OpenAI API key",
            lastUpdate = System.currentTimeMillis()
        )
    }

    /** Compatibility method for existing dashboard UI; base URL/model are fixed to official providers. */
    fun setOpenAiMasterMindConfig(enabled: Boolean, apiKey: String, model: String = "gpt-5.6-luna", baseUrl: String = "https://api.openai.com/v1") {
        setDualAiKeys(
            marketAiApiKey = AiKeyStore.get(getApplication<Application>(), AiKeyStore.PROVIDER_OPENAI),
            openAiApiKey = apiKey,
            enabled = enabled
        )
    }

    fun clearOpenAiMasterMindCache(symbol: String? = null) = openAiMasterMind.clear()
    fun clearMasterAiCache(symbol: String? = null) { openAiMarketIntelligence.clear(); openAiMasterMind.clear() }

    // Professional auto-trading exchange session. The Account/Exchange UI already
    // persists these credentials into the same SharedPreferences namespace.
    private val autoExchangeName = MutableStateFlow(
        prefs.getString("auto_trade_exchange", "CoinDCX") ?: "CoinDCX"
    )
    val autoTradingExchange:StateFlow<String> = autoExchangeName.asStateFlow()
    private var lastRealOrderId:String = ""
    private var lastRealFillPrice:Double = 0.0
    private var lastRealFilledSize:Double = 0.0
    private var lastRealCommission:Double = 0.0
    private var lastRealContractValue:Double = 0.0
    private var lastRealNotionalType:String = "vanilla"

    // OpenAI Master Mind is refreshed continuously per symbol without making
    // an API call on every 5-second market cycle.
    private val lastOpenAiAnalysisAt = ConcurrentHashMap<String,Long>()
    private val openAiAnalysisIntervalMs = 5_000L

    // Exchange-confirmed but not-yet-filled orders. These are reconciled
    // from Delta before another signal can create a duplicate order.
    private val pendingRealOrders =
        ConcurrentHashMap<String,PendingRealOrder>()

    // UI-visible snapshot of exchange-confirmed bot orders that are still
    // open/pending. The map remains the source of truth; this StateFlow only
    // exposes it so the Bot Dashboard cannot hide a real pending order.
    private val _pendingAutoOrders = MutableStateFlow<List<PendingRealOrder>>(emptyList())
    val pendingAutoOrders: StateFlow<List<PendingRealOrder>> = _pendingAutoOrders.asStateFlow()

    private fun publishPendingAutoOrders() {
        _pendingAutoOrders.value = pendingRealOrders.values
            .sortedByDescending { it.createdAt }
            .toList()
    }

    // Each exchange-order coroutine is tracked so STOP can cancel the
    // local request before it can submit another REAL order.
    private val realOrderJobs =
        ConcurrentHashMap<String,Job>()

    // Requested trading cadence/target:
    // - Gross take-profit target is 5%. It is a target, not a guarantee.
    // - Maximum 100 real order submissions in any rolling 60-minute window.
    // The hourly cap is a safety ceiling; the bot only submits orders when
    // its live strategy actually produces a qualified signal.
    private val targetProfitPct = 3.0
    private val maxOrdersPerHour = 100
    private val orderWindowLock = Any()

    private fun reserveHourlyOrderSlot(): Boolean {
        synchronized(orderWindowLock) {
            val now = System.currentTimeMillis()
            val storedStart = prefs.getLong("auto_order_window_start_ms", 0L)
            val storedCount = prefs.getInt("auto_order_window_count", 0)

            val start = if (storedStart <= 0L || now - storedStart >= 60L * 60L * 1000L) {
                prefs.edit()
                    .putLong("auto_order_window_start_ms", now)
                    .putInt("auto_order_window_count", 0)
                    .apply()
                now
            } else {
                storedStart
            }

            val count = if (start == now && storedStart != start) 0 else storedCount
            if (count >= maxOrdersPerHour) return false

            prefs.edit()
                .putLong("auto_order_window_start_ms", start)
                .putInt("auto_order_window_count", count + 1)
                .apply()
            return true
        }
    }

    fun setAutoTradingExchange(name:String){
        val normalized=ProfessionalExchangeRouter.normalizeExchange(name)?.label ?: name.trim()
        autoExchangeName.value=normalized
        prefs.edit().putString("auto_trade_exchange",normalized).apply()
        if (normalized.equals("DeltaIndia", true)) {
            startDeltaLiveMarketFlow()
        } else {
            stopDeltaLiveMarketFlow()
        }
        _pipeline.value=_pipeline.value.copy(
            orderStatus="🔌 Auto-router: $normalized",
            lastUpdate=System.currentTimeMillis()
        )
    }

    private fun vaultDecryptForRouter(value:String):String{
        if(value.isBlank()) return ""
        return try{
            val all=Base64.decode(value,Base64.NO_WRAP)
            if(all.size<=12) return ""
            val ks=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            if(!ks.containsAlias("ProNitinExchangeVault")) return ""
            val key=ks.getKey("ProNitinExchangeVault",null)
            val iv=all.copyOfRange(0,12)
            val encrypted=all.copyOfRange(12,all.size)
            val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,key,javax.crypto.spec.GCMParameterSpec(128,iv))
            String(cipher.doFinal(encrypted),Charsets.UTF_8)
        }catch(_:Exception){ value }
    }

    private fun loadRouterCredentials(exchange:String):RouterCredentials?{
        val e=ProfessionalExchangeRouter.normalizeExchange(exchange) ?: return null
        // CoinDCX legacy vault. Other exchanges use the same Android Keystore vault
        // used by ExchangeRepository_FINAL_FIXED_LIVE_BALANCE.kt.
        val exchangePrefs=getApplication<Application>().getSharedPreferences("exchange_credentials_v4",Context.MODE_PRIVATE)
        val prefix=e.label.lowercase(Locale.US)
        val apiStored=if(e==ProfessionalExchange.COINDCX) prefs.getString("cdc_api_key","") ?: "" else exchangePrefs.getString("${prefix}_api","") ?: ""
        val secretStored=if(e==ProfessionalExchange.COINDCX) prefs.getString("cdc_secret_key","") ?: "" else exchangePrefs.getString("${prefix}_secret","") ?: ""
        val passStored=if(e==ProfessionalExchange.COINDCX) "" else exchangePrefs.getString("${prefix}_pass","") ?: ""
        // Normalize credentials before they are placed into HTTP headers.
        // A pasted API key containing CR/LF (or other whitespace) causes
        // OkHttp to reject the request with: "Unexpected char 0x0a in header value".
        val apiKey=vaultDecryptForRouter(apiStored).ifBlank{if(e==ProfessionalExchange.COINDCX) decrypt(apiStored) else ""}
            .replace(Regex("\\s"), "")
        val secret=vaultDecryptForRouter(secretStored).ifBlank{if(e==ProfessionalExchange.COINDCX) decrypt(secretStored) else ""}
            .replace(Regex("\\s"), "")
        val pass=vaultDecryptForRouter(passStored)
        if(apiKey.isBlank()||secret.isBlank())return null
        return RouterCredentials(apiKey,secret,pass)
    }

    private suspend fun refreshAutoExchangeBalance(){
        val name=autoExchangeName.value
        val c=loadRouterCredentials(name) ?: return
        val b=ProfessionalExchangeRouter.readBalance(name,c)
        if(b.success){
            _realBalance.value=b.total
            _portfolioValue.value=b.total
            _balances.value=b.holdings.map{Balance(it.first,it.second,0.0)}
            _connectStatus.value="✅ LIVE • $name • REAL BALANCE"
        }
    }

    private val gson = Gson()

    private val httpClient:OkHttpClient by lazy {
        OkHttpClient.Builder()
            .build()
    }

    private val coinCapApi:CoinCapApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.coincap.io/")
            .client(httpClient)
            .addConverterFactory(
                GsonConverterFactory.create()
            )
            .build()
            .create(CoinCapApi::class.java)
    }

    private val geckoLogoApi:GeckoLogoApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.coingecko.com/")
            .client(httpClient)
            .addConverterFactory(
                GsonConverterFactory.create()
            )
            .build()
            .create(GeckoLogoApi::class.java)
    }

    private val candleApi:CoinDCXCandleApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://public.coindcx.com/")
            .client(httpClient)
            .addConverterFactory(
                GsonConverterFactory.create()
            )
            .build()
            .create(CoinDCXCandleApi::class.java)
    }

    private val deltaPublicFlowApi:DeltaPublicFlowApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.india.delta.exchange/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeltaPublicFlowApi::class.java)
    }

    // Official Delta India PUBLIC WebSocket bridge. REST remains a fallback
    // while the socket is connecting or temporarily stale.
    private val deltaLiveMarketAdapter:DeltaIndiaLiveMarketAdapter by lazy {
        DeltaIndiaLiveMarketAdapter(
            scope = viewModelScope,
            symbolsProvider = {
                (_uiState.value as? UiState.Success)?.markets.orEmpty().map { it.market }
            },
            onStatus = { status ->
                _pipeline.value = _pipeline.value.copy(
                    orderStatus = status,
                    lastUpdate = System.currentTimeMillis()
                )
            }
        )
    }

    private fun startDeltaLiveMarketFlow() {
        if (autoExchangeName.value.equals("DeltaIndia", true)) deltaLiveMarketAdapter.start()
    }

    private fun stopDeltaLiveMarketFlow() {
        runCatching { deltaLiveMarketAdapter.stop() }
    }

    private fun liveFlowFromSnapshot(snapshot:ExchangeFlowSnapshot):LiveMarketFlow {
        var buyerVolume = 0.0
        var sellerVolume = 0.0
        var buyerTrades = 0L
        var sellerTrades = 0L
        snapshot.trades.forEach { trade ->
            val notional = (trade.price * trade.quantity).coerceAtLeast(0.0)
            when (trade.takerSide) {
                TakerSide.BUY -> { buyerVolume += notional; buyerTrades++ }
                TakerSide.SELL -> { sellerVolume += notional; sellerTrades++ }
                TakerSide.UNKNOWN -> Unit
            }
        }
        val bidBookVolume = snapshot.bids.sumOf { it.quantity.coerceAtLeast(0.0) }
        val askBookVolume = snapshot.asks.sumOf { it.quantity.coerceAtLeast(0.0) }
        val bidBookNotional = snapshot.bids.sumOf { (it.price * it.quantity).coerceAtLeast(0.0) }
        val askBookNotional = snapshot.asks.sumOf { (it.price * it.quantity).coerceAtLeast(0.0) }
        val bookTotal = bidBookNotional + askBookNotional
        val bookImbalance = if (bookTotal > 0.0) ((bidBookNotional - askBookNotional) / bookTotal * 100.0).coerceIn(-100.0, 100.0) else 0.0
        val tradeTotal = buyerVolume + sellerVolume
        val flowBias = when {
            tradeTotal <= 0.0 && bookTotal <= 0.0 -> "UNKNOWN"
            tradeTotal > 0.0 && buyerVolume > sellerVolume * 1.05 -> "BUYER_DOMINANT"
            tradeTotal > 0.0 && sellerVolume > buyerVolume * 1.05 -> "SELLER_DOMINANT"
            bookImbalance >= 10.0 -> "BID_BOOK_DOMINANT"
            bookImbalance <= -10.0 -> "ASK_BOOK_DOMINANT"
            else -> "BALANCED"
        }
        return LiveMarketFlow(
            symbol = snapshot.symbol, buyerVolume = buyerVolume, sellerVolume = sellerVolume,
            buyerTrades = buyerTrades, sellerTrades = sellerTrades,
            bidBookVolume = bidBookVolume, askBookVolume = askBookVolume,
            bidBookNotional = bidBookNotional, askBookNotional = askBookNotional,
            bookImbalancePct = bookImbalance, flowBias = flowBias,
            updatedAt = System.currentTimeMillis()
        )
    }

    private val api:CoinDCXApi =
        CoinDCXRetrofit.api

    // REAL ONLY: never silently fall back to paper mode.
    private val _orderMode =
        MutableStateFlow(OrderMode.REAL)

    val orderMode:StateFlow<OrderMode> =
        _orderMode.asStateFlow()

    // Compatibility state used by the existing Bot Dashboard.
    private val _strategyProfile =
        MutableStateFlow(StrategyProfile.BALANCED)

    val strategyProfile:StateFlow<StrategyProfile> =
        _strategyProfile.asStateFlow()

    private val _strategyConfig = MutableStateFlow(loadStrategyConfig())

    val strategyConfig: StateFlow<StrategyConfig> =
        _strategyConfig.asStateFlow()

    private fun loadStrategyConfig(): StrategyConfig {
        val profile = runCatching {
            StrategyProfile.valueOf(
                prefs.getString("strategy_profile", StrategyProfile.BALANCED.name)
                    ?: StrategyProfile.BALANCED.name
            )
        }.getOrDefault(StrategyProfile.BALANCED)
        val tradingViewStrategy = runCatching {
            TradingViewStrategy.valueOf(
                prefs.getString("tradingview_strategy", TradingViewStrategy.COMBINED_CONFIRMATION.name)
                    ?: TradingViewStrategy.COMBINED_CONFIRMATION.name
            )
        }.getOrDefault(TradingViewStrategy.COMBINED_CONFIRMATION)
        return StrategyConfig(
            profile = profile,
            tradingViewStrategy = tradingViewStrategy,
            minConfidence = prefs.getFloat("strategy_min_confidence", 60f).toDouble(),
            signalThreshold = prefs.getInt("strategy_signal_threshold", 18).coerceIn(8, 30),
            maxOpenPositions = prefs.getInt("strategy_max_open_positions", 20).coerceIn(1, 20),
            maxDrawdownPct = prefs.getFloat("strategy_max_drawdown_pct", 3f).toDouble().coerceIn(0.5, 20.0)
        )
    }

    fun setStrategyConfig(config: StrategyConfig) {
        val safe = config.copy(
            minConfidence = config.minConfidence.coerceIn(0.0, 100.0),
            signalThreshold = config.signalThreshold.coerceIn(8, 30),
            maxOpenPositions = config.maxOpenPositions.coerceIn(1, 20),
            maxDrawdownPct = config.maxDrawdownPct.coerceIn(0.5, 20.0)
        )
        _strategyConfig.value = safe
        _strategyProfile.value = safe.profile
        prefs.edit()
            .putString("strategy_profile", safe.profile.name)
            .putString("tradingview_strategy", safe.tradingViewStrategy.name)
            .putFloat("strategy_min_confidence", safe.minConfidence.toFloat())
            .putInt("strategy_signal_threshold", safe.signalThreshold)
            .putInt("strategy_max_open_positions", safe.maxOpenPositions)
            .putFloat("strategy_max_drawdown_pct", safe.maxDrawdownPct.toFloat())
            .apply()
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "🧠 Strategy updated • ${safe.profile.name}",
            lastUpdate = System.currentTimeMillis()
        )
    }

    fun resetStrategySettings() {
        prefs.edit()
            .remove("strategy_profile")
            .remove("strategy_min_confidence")
            .remove("strategy_signal_threshold")
            .remove("strategy_max_open_positions")
            .remove("strategy_max_drawdown_pct")
            .apply()
        val defaults = StrategyConfig()
        _strategyConfig.value = defaults
        _strategyProfile.value = defaults.profile
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "🔄 Strategy reset • BALANCED defaults restored",
            lastUpdate = System.currentTimeMillis()
        )
    }

    private val _uiState =
        MutableStateFlow<UiState>(UiState.Loading)

    val uiState:StateFlow<UiState> =
        _uiState.asStateFlow()

    private val _portfolioValue =
        MutableStateFlow(0.0)

    val portfolioValue:StateFlow<Double> =
        _portfolioValue.asStateFlow()

    private val _tradeAmount =
        MutableStateFlow(
            prefs.getString("auto_trade_amount", "") ?: ""
        )

    val tradeAmount:StateFlow<String> =
        _tradeAmount.asStateFlow()

    private val _isBotRunning =
        MutableStateFlow(prefs.getBoolean("auto_bot_enabled", false))

    val isBotRunning:StateFlow<Boolean> =
        _isBotRunning.asStateFlow()

    private val _botTrades =
        MutableStateFlow<List<BotTrade>>(emptyList())

    // UI-facing list contains OPEN positions only. Closed trades are persisted
    // so Total Profit/Profit Factor survive Activity/service recreation.
    private val closedTradeHistory = loadClosedTradeHistory()

    val botTrades:StateFlow<List<BotTrade>> =
        _botTrades.asStateFlow()

    private val _liveDeltaPositions = MutableStateFlow<List<LiveDeltaPosition>>(emptyList())
    /** Exact live Delta positions. Display-only; never used to close manual positions. */
    val liveDeltaPositions:StateFlow<List<LiveDeltaPosition>> =
        _liveDeltaPositions.asStateFlow()

    private val _liveDeltaOpenOrders = MutableStateFlow<List<LiveDeltaOpenOrder>>(emptyList())
    /** Exact open/pending Delta orders for the connected account. */
    val liveDeltaOpenOrders: StateFlow<List<LiveDeltaOpenOrder>> =
        _liveDeltaOpenOrders.asStateFlow()

    private val _marketStats =
        MutableStateFlow(MarketStats())

    val marketStats:StateFlow<MarketStats> =
        _marketStats.asStateFlow()

    private val _currentIndicators =
        MutableStateFlow(IndicatorValues())

    val currentIndicators:StateFlow<IndicatorValues> =
        _currentIndicators.asStateFlow()

    private val _selectedMarket =
        MutableStateFlow<CryptoPrice?>(null)

    val selectedMarket:StateFlow<CryptoPrice?> =
        _selectedMarket.asStateFlow()

    private val _logoMap =
        MutableStateFlow<Map<String,String>>(emptyMap())

    val logoMap:StateFlow<Map<String,String>> =
        _logoMap.asStateFlow()

    // Broader market universe for AI research only. REAL orders remain
    // restricted to the selected exchange's live contracts.
    private val top3000AiMarkets =
        MutableStateFlow<List<GeckoCoin>>(emptyList())

    private val _equityHistory =
        MutableStateFlow<List<Double>>(emptyList())

    val equityHistory:StateFlow<List<Double>> =
        _equityHistory.asStateFlow()

    private val _realBalance =
        MutableStateFlow<Double?>(null)

    val realBalance:StateFlow<Double?> =
        _realBalance.asStateFlow()

    private val _balances =
        MutableStateFlow<List<Balance>>(emptyList())

    val balances:StateFlow<List<Balance>> =
        _balances.asStateFlow()

    private val _isConnected =
        MutableStateFlow(false)

    val isConnected:StateFlow<Boolean> =
        _isConnected.asStateFlow()

    private val _liveMode =
        MutableStateFlow(false)

    val liveMode:StateFlow<Boolean> =
        _liveMode.asStateFlow()

    private val _connectStatus =
        MutableStateFlow("Disconnected")

    val connectStatus:StateFlow<String> =
        _connectStatus.asStateFlow()

    private val _trendingSignal =
        MutableStateFlow<TradingSignal>(
            TradingSignal.HOLD
        )

    val trendingSignal:StateFlow<TradingSignal> =
        _trendingSignal.asStateFlow()

    private val _pipelineStatus =
        MutableStateFlow(
            "Waiting for live candle..."
        )

    val pipelineStatus:StateFlow<String> =
        _pipelineStatus.asStateFlow()

    // Existing dashboard name; points to the same live pipeline status.
    val scannerStatus:StateFlow<String> =
        pipelineStatus

    private val _pipeline =
        MutableStateFlow(TradingPipelineState())

    val pipeline:StateFlow<TradingPipelineState> =
        _pipeline.asStateFlow()

    private val priceHistory =
        mutableMapOf<String,MutableList<Double>>()

    private val highHistory =
        mutableMapOf<String,MutableList<Double>>()

    private val lowHistory =
        mutableMapOf<String,MutableList<Double>>()

    // Historical candle warm-up state. The previous version only appended
    // one ticker price every 5 seconds, so EMA200/Bollinger/ATR stayed at 0
    // for a long time and the bot could not make reliable signals.
    private val warmedSymbols =
        mutableSetOf<String>()

    private val warmupMutex =
        kotlinx.coroutines.sync.Mutex()

    private var botJob:Job? = null
    private var positionManagerJob: Job? = null
    private var balanceJob:Job? = null
    private var marketJob:Job? = null
    private var indicatorJob:Job? = null
    private var equityJob:Job? = null

    private var lastSignalTime:Long = 0L
    private var scanCursor:Int = 0

    init {
        // Never import the old local history that produced inflated/fake P/L.
        // New statistics begin only from exchange-confirmed fills.
        prefs.edit().remove("closed_bot_trade_history_v2").apply()
        fetchLogoData()
        fetchMarkets()
        startPriceTicker()
        startEquityRecorder()
        viewModelScope.launch {
            fetchLogos()
        }
        startCoinDCXPoller()
        updateMarketStats(closedTradeHistory)
        startDeltaLiveMarketFlow()
        autoReconnectSavedExchange()
    }

    private fun autoReconnectSavedExchange(){
        viewModelScope.launch(Dispatchers.IO){
            try{
                val ep=getApplication<Application>().getSharedPreferences("exchange_credentials_v4",Context.MODE_PRIVATE)
                val savedName=ep.getString("selected_exchange","").orEmpty()
                val exchange=if(savedName.isNotBlank()) savedName else if(decrypt(prefs.getString("cdc_api_key","").orEmpty()).isNotBlank()) "CoinDCX" else ""
                if(exchange.isBlank()) return@launch
                setAutoTradingExchange(exchange)
                val c=loadRouterCredentials(exchange) ?: return@launch
                val b=ProfessionalExchangeRouter.readBalance(exchange,c)
                if(b.success){
                    withContext(Dispatchers.Main){
                        _isConnected.value=true
                        _liveMode.value=true
                        _realBalance.value=b.total
                        _portfolioValue.value=b.total
                        _balances.value=b.holdings.map{Balance(it.first,it.second,0.0)}
                        _connectStatus.value="🟢 LIVE • $exchange • auto-restored"
                        _pipeline.value=_pipeline.value.copy(orderStatus="🔐 $exchange credentials restored • LIVE balance ready",lastUpdate=System.currentTimeMillis())
                    }
                    startUnifiedLiveBalancePolling()
                }
            }catch(e:Exception){ Log.w("AUTO_RECONNECT","Saved exchange restore failed: ${e.message}") }
        }
    }

    // ============================================================
    // PERSISTENT BOT + SINGLE LIVE BALANCE SOURCE
    // ============================================================

    internal fun restorePersistentBotState(){
        if(!prefs.getBoolean("auto_bot_enabled", false)) return
        TradingBotService.start(getApplication<Application>())
        _isBotRunning.value=true
        viewModelScope.launch {
            delay(250)
            startBotFromService()
        }
    }

    private fun startUnifiedLiveBalancePolling(){
        balanceJob?.cancel()
        balanceJob=viewModelScope.launch(Dispatchers.IO){
            while(isActive){
                try{
                    val name=autoExchangeName.value
                    val c=loadRouterCredentials(name)
                    if(c!=null){
                        val b=ProfessionalExchangeRouter.readBalance(name,c)
                        if(b.success){
                            withContext(Dispatchers.Main){
                                _isConnected.value=true
                                _liveMode.value=true
                                _realBalance.value=b.total
                                _portfolioValue.value=b.total
                                _balances.value=b.holdings.map{Balance(it.first,it.second,0.0)}
                                _connectStatus.value="🟢 LIVE • $name • REAL BALANCE"
                            }
                        }
                    }
                }catch(e:Exception){
                    Log.w("LIVE_BALANCE","Unified refresh failed: ${e.message}")
                }
                delay(30_000L)
            }
        }
    }

    internal fun startBotFromService(){
        // This function is called only by the persistent foreground service.
        // Do NOT gate it with SharedPreferences here: TradingBotService may
        // run in Android's :bot process, while SharedPreferences caches are
        // process-local and can contain a stale FALSE after the Activity saved
        // TRUE. The service lifecycle itself is the ON signal.
        _isBotRunning.value = true
        if(botJob?.isActive == true) return
        launchBotLoop()
    }

    // Called by the persistent foreground service. The position manager is
    // deliberately separate from new-entry bot state so UI OFF does not lose
    // control of already-open bot positions.
    internal fun startPositionManagerFromService() {
        if (positionManagerJob?.isActive == true) return

        positionManagerJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (!_isConnected.value || !_liveMode.value) {
                        verifyRealExchangeAccess()
                    }

                    reconcilePendingRealOrders()
                    refreshLiveDeltaPositions()
                    manageAllLiveDeltaPositionsForProfit()

                    val state = _uiState.value
                    if (state is UiState.Success && state.markets.isNotEmpty()) {
                        val ownedSymbols = loadBotOwnedPositions()
                            .filter { it.quantity > 0.0 && it.symbol.isNotBlank() }
                            .map { it.symbol.trim().uppercase(Locale.US) }
                            .toSet()

                        val pendingSymbols = pendingRealOrders.keys
                            .map { it.trim().uppercase(Locale.US) }
                            .toSet()

                        val managedSymbols = ownedSymbols + pendingSymbols

                        if (managedSymbols.isNotEmpty()) {
                            state.markets.asSequence()
                                .filter {
                                    it.lastPrice > 0.0 &&
                                        managedSymbols.contains(it.symbol.trim().uppercase(Locale.US))
                                }
                                .take(100)
                                .forEach { market ->
                                    if (!isActive) return@forEach
                                    warmupMarketHistory(market)
                                    processTradingPipeline(
                                        sourceMarket = market,
                                        allowNewEntries = false
                                    )
                                }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("POSITION_MANAGER", "Background manager error: ${e.message}", e)
                }

                delay(10_000L)
            }
        }
    }

    internal fun stopPositionManagerFromService() {
        positionManagerJob?.cancel()
        positionManagerJob = null
    }

    private fun launchBotLoop(){
        if(botJob?.isActive==true) return
        botJob=viewModelScope.launch {
            Log.d("AUTO_BOT","REAL bot starting • exchange=${autoExchangeName.value}")
            var deltaRecoveryDone=false

            while(isActive && _isBotRunning.value){
                try{
                    // A temporary network/API failure must never switch the
                    // persistent bot OFF. Keep the bot ON and retry auth.
                    if(!_isConnected.value || !_liveMode.value){
                        val ok=verifyRealExchangeAccess()
                        if(ok){
                            deltaRecoveryDone=false
                        }else{
                            _pipeline.value=_pipeline.value.copy(
                                orderStatus="⏳ Waiting for ${autoExchangeName.value} LIVE connection…",
                                lastUpdate=System.currentTimeMillis()
                            )
                            delay(5000)
                            continue
                        }
                    }

                    if(!deltaRecoveryDone && autoExchangeName.value.equals("DeltaIndia",true)){
                        deltaRecoveryDone=cancelDeltaBotOrdersOnStop()
                        if(!deltaRecoveryDone){
                            _pipeline.value=_pipeline.value.copy(
                                orderStatus="⚠️ Delta recovery pending • bot remains ON",
                                lastUpdate=System.currentTimeMillis()
                            )
                        }
                    }

                    reconcilePendingRealOrders()
                    val state=_uiState.value
                    if(state is UiState.Success && state.markets.isNotEmpty()){
                        // FULL-VOLUME ROTATING SCANNER
                        // Keep the complete live market universe sorted by real
                        // 24h volume. Scan a larger batch concurrently so one
                        // slow symbol/API call cannot serialize the entire bot.
                        // The per-symbol realOrderJobs guard still prevents
                        // duplicate orders for the same symbol.
                        val universe=state.markets.asSequence()
                            .filter{it.lastPrice>0.0 && it.symbol.isNotBlank()}
                            .distinctBy{it.symbol.trim().uppercase(Locale.US)}
                            .sortedByDescending{it.volume24h}
                            .take(3000)
                            .toList()
                        val batchSize=minOf(25,universe.size)
                        val candidates=if(universe.isEmpty()) emptyList() else {
                            val startIndex=scanCursor%universe.size
                            (0 until batchSize).map{universe[(startIndex+it)%universe.size]}
                        }
                        if(universe.isNotEmpty()) scanCursor=(scanCursor+batchSize)%universe.size

                        // Analyze multiple coins in parallel, but cap analysis
                        // concurrency to keep Android responsive and respect
                        // exchange/network limits. Real orders themselves are
                        // dispatched independently by the existing authenticated
                        // router, allowing multiple qualified symbols to trade.
                        coroutineScope {
                            val permits = kotlinx.coroutines.sync.Semaphore(20)
                            candidates.map { market ->
                                async(Dispatchers.Default) {
                                    permits.acquire()
                                    try {
                                        if (isActive) {
                                            warmupMarketHistory(market)
                                            processTradingPipeline(market)
                                        }
                                    } catch (e: Exception) {
                                        Log.w("AUTO_SCAN", "Symbol ${market.symbol} scan failed: ${e.message}")
                                    } finally {
                                        permits.release()
                                    }
                                }
                            }.awaitAll()
                        }

                        candidates.firstOrNull()?.let{market->
                            _selectedMarket.value=market
                            _pipelineStatus.value="🟢 FULL-VOLUME AI SCAN • ${candidates.size}/${universe.size} markets • parallel analysis • multi-order router ready"
                        }
                    }
                }catch(e:Exception){
                    Log.e("AUTO_BOT","Bot error: ${e.message}",e)
                }
                delay(5_000L)
            }
            Log.d("AUTO_BOT","Bot loop stopped")
        }
    }

    // ============================================================
// API CLIENTS + STATE
// ============================================================

// ============================================================
// ORDER MODE
// ============================================================

fun setOrderMode(@Suppress("UNUSED_PARAMETER") mode: OrderMode = OrderMode.REAL) {
    // REAL ONLY. The public function is retained for UI compatibility.
    _orderMode.value = OrderMode.REAL

    prefs.edit()
        .putBoolean("auto_real_order_mode", true)
        .apply()

    _pipeline.value = _pipeline.value.copy(
        mode = OrderMode.REAL.name,
        orderStatus = "🔐 REAL • exchange authentication required",
        lastUpdate = System.currentTimeMillis()
    )
}

fun toggleOrderMode() {
    // REAL ONLY. Do not toggle into a fake/local paper mode.
    setOrderMode(OrderMode.REAL)
}

// ============================================================
// API KEY STORAGE
// ============================================================

fun saveApiKeys(
    key: String,
    secret: String
) {
    if (
        key.isBlank() ||
        secret.isBlank()
    ) {
        _connectStatus.value =
            "❌ API key/secret empty"
        return
    }

    prefs.edit()
        .putString(
            "cdc_api_key",
            encrypt(key.trim())
        )
        .putString(
            "cdc_secret_key",
            encrypt(secret.trim())
        )
        .apply()

    _connectStatus.value =
        "🔐 API credentials saved"

    Log.d(
        "AUTH",
        "CoinDCX credentials saved"
    )
}

// ============================================================
// AES STORAGE
// ============================================================

private fun encrypt(
    value: String
): String {
    return try {
        val secretKey =
            "NitinProTrading2026SecretKey!@#"
                .take(16)
                .toByteArray()

        val cipher =
            javax.crypto.Cipher.getInstance(
                "AES/ECB/PKCS5Padding"
            )

        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(
                secretKey,
                "AES"
            )
        )

        android.util.Base64.encodeToString(
            cipher.doFinal(
                value.toByteArray(
                    Charsets.UTF_8
                )
            ),
            android.util.Base64.NO_WRAP
        )
    } catch (e: Exception) {
        Log.e(
            "AUTH",
            "Encryption failed",
            e
        )
        ""
    }
}

private fun decrypt(
    value: String
): String {
    return try {
        if (value.isBlank()) {
            return ""
        }

        val secretKey =
            "NitinProTrading2026SecretKey!@#"
                .take(16)
                .toByteArray()

        val cipher =
            javax.crypto.Cipher.getInstance(
                "AES/ECB/PKCS5Padding"
            )

        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(
                secretKey,
                "AES"
            )
        )

        String(
            cipher.doFinal(
                android.util.Base64.decode(
                    value,
                    android.util.Base64.NO_WRAP
                )
            ),
            Charsets.UTF_8
        )
    } catch (e: Exception) {
        Log.e(
            "AUTH",
            "Decryption failed",
            e
        )
        ""
    }
}

// ============================================================
// HMAC SHA256
// ============================================================

private fun createSignature(
    payload: String,
    secret: String
): String {
    return try {
        val mac =
            javax.crypto.Mac.getInstance(
                "HmacSHA256"
            )

        val secretKey =
            javax.crypto.spec.SecretKeySpec(
                secret.toByteArray(
                    Charsets.UTF_8
                ),
                "HmacSHA256"
            )

        mac.init(secretKey)

        mac.doFinal(
            payload.toByteArray(
                Charsets.UTF_8
            )
        ).joinToString("") {
            "%02x".format(it)
        }
    } catch (e: Exception) {
        Log.e(
            "AUTH",
            "Signature creation failed",
            e
        )
        ""
    }
}

// ============================================================
// AUTHENTICATED BALANCE REQUEST
// ============================================================


// ============================================================
// REAL COINDCX CONNECT
// ============================================================

fun connectCoinDCX(){
    setAutoTradingExchange("CoinDCX")
    if(_connectStatus.value=="Connecting...")return
    viewModelScope.launch{
        _connectStatus.value="Connecting..."
        _isConnected.value=false
        val key=decrypt(prefs.getString("cdc_api_key","")?:"")
        val secret=decrypt(prefs.getString("cdc_secret_key","")?:"")
        if(key.isBlank()||secret.isBlank()){
            _realBalance.value=null
            _connectStatus.value="❌ API key/secret missing"
            return@launch
        }
        try{
            val loaded=fetchRealBalances()
            if(!loaded){
                _isConnected.value=false
                _realBalance.value=null
                _connectStatus.value="❌ CoinDCX authentication/balance failed"
                return@launch
            }
            _isConnected.value=true
            _connectStatus.value="✅ LIVE AUTHENTICATED • REAL BALANCE"
            _pipeline.value=_pipeline.value.copy(mode=_orderMode.value.name,orderStatus="🟢 Exchange authenticated",lastUpdate=System.currentTimeMillis())
            Log.d("COINDCX_AUTH","Real account connected; live balances loaded")
        }catch(e:Exception){
            _isConnected.value=false
            _realBalance.value=null
            _connectStatus.value="❌ Connection error: ${e.message}"
            Log.e("COINDCX_AUTH","Connection failed",e)
        }
    }
}

fun refreshRealBalance(){
    if(!_isConnected.value || !autoExchangeName.value.equals("CoinDCX",true)) return
    viewModelScope.launch{
        if(!fetchRealBalances()){
            _isConnected.value=false
            _connectStatus.value="❌ Live balance refresh failed"
        }
    }
}

// ============================================================
// DISCONNECT
// ============================================================

fun disconnectCoinDCX() {
    _isConnected.value =
        false

    _realBalance.value =
        null

    _connectStatus.value =
        "Disconnected"

    Log.d(
        "AUTH",
        "CoinDCX disconnected"
    )
}

// ============================================================
// LIVE MODE
// ============================================================

fun enableLiveMode() {
    if (!_isConnected.value) {
        Log.w(
            "LIVE_MODE",
            "Live mode blocked: exchange not authenticated"
        )

        _connectStatus.value =
            "⚠️ Authenticate exchange first"

        return
    }

    _liveMode.value =
        true

    _pipeline.value =
        _pipeline.value.copy(
            orderStatus =
                "🔴 LIVE trading enabled",
            lastUpdate =
                System.currentTimeMillis()
        )

    Log.d(
        "LIVE_MODE",
        "LIVE mode enabled"
    )
}

fun disableLiveMode() {
    _liveMode.value =
        false

    _pipeline.value =
        _pipeline.value.copy(
            orderStatus =
                "⏸ Live trading disabled",
            lastUpdate =
                System.currentTimeMillis()
        )

    Log.d(
        "LIVE_MODE",
        "LIVE mode disabled"
    )
}

fun toggleLiveMode() {
    if (_liveMode.value) {
        disableLiveMode()
    } else {
        enableLiveMode()
    }
}

// ============================================================
// LOGO
// ============================================================

fun getLogo(
    symbol: String
): String {
    return _logoMap.value[
        symbol.trim()
            .lowercase(Locale.US)
    ] ?: ""
}

fun fetchLogoData() {
    _logoMap.value =
        mapOf(
            "btc" to
                "https://assets.coincap.io/assets/icons/btc@2x.png",
            "eth" to
                "https://assets.coincap.io/assets/icons/eth@2x.png",
            "bnb" to
                "https://assets.coincap.io/assets/icons/bnb@2x.png",
            "sol" to
                "https://assets.coincap.io/assets/icons/sol@2x.png",
            "xrp" to
                "https://assets.coincap.io/assets/icons/xrp@2x.png",
            "doge" to
                "https://assets.coincap.io/assets/icons/doge@2x.png",
            "ada" to
                "https://assets.coincap.io/assets/icons/ada@2x.png",
            "dot" to
                "https://assets.coincap.io/assets/icons/dot@2x.png",
            "link" to
                "https://assets.coincap.io/assets/icons/link@2x.png",
            "matic" to
                "https://assets.coincap.io/assets/icons/matic@2x.png",
            "avax" to
                "https://assets.coincap.io/assets/icons/avax@2x.png",
            "xau" to
                "https://img.icons8.com/color/96/gold-bars.png",
            "xag" to
                "https://img.icons8.com/color/96/silver-bars.png",
            "wti" to
                "https://img.icons8.com/color/96/oil-industry.png",
            "brent" to
                "https://img.icons8.com/color/96/oil-barrel.png",
            "ng" to
                "https://img.icons8.com/color/96/fire-element.png"
        )
}

// ============================================================
// MARKET SYMBOL HELPERS
// ============================================================

private fun extractBaseSymbol(
    market: String
): String {
    val upper =
        market.trim()
            .uppercase(Locale.US)

    return when {
        upper.contains("-") ->
            upper.substringBefore("-")

        upper.contains("_") ->
            upper.substringBefore("_")

        upper.endsWith("USDT") ->
            upper.removeSuffix("USDT")

        upper.endsWith("USDC") ->
            upper.removeSuffix("USDC")

        upper.endsWith("BUSD") ->
            upper.removeSuffix("BUSD")

        upper.endsWith("INR") ->
            upper.removeSuffix("INR")

        upper.endsWith("BTC") ->
            upper.removeSuffix("BTC")

        upper.endsWith("ETH") ->
            upper.removeSuffix("ETH")

        else ->
            upper
    }
}

private fun quoteOf(
    market: String
): String {
    val upper =
        market.trim()
            .uppercase(Locale.US)

    return when {
        upper.contains("-") ->
            upper.substringAfter("-")

        upper.contains("_") ->
            upper.substringAfter("_")

        upper.endsWith("USDT") ->
            "USDT"

        upper.endsWith("USDC") ->
            "USDC"

        upper.endsWith("BUSD") ->
            "BUSD"

        upper.endsWith("INR") ->
            "INR"

        upper.endsWith("BTC") ->
            "BTC"

        upper.endsWith("ETH") ->
            "ETH"

        else ->
            ""
    }
}

// ============================================================
// LIVE MARKET + INDICATOR ENGINE
// ============================================================

fun saveIPAddress(ip:String){
    prefs.edit().putString("server_ip",ip.trim()).apply()
}

fun setLiveMode(on:Boolean){
    if(on) enableLiveMode() else disableLiveMode()
}

fun setTradeAmount(amount:String){
    val clean = amount.trim()
    _tradeAmount.value = clean
    prefs.edit()
        .putString("auto_trade_amount", clean)
        .apply()
}

private suspend fun loadLiveMarkets():List<CryptoPrice>{
    return try{
        val tickers=api.getLiveMarkets()
        tickers.mapNotNull{ticker->
            val price=ticker.last_price.toDoubleOrNull()?:0.0
            val volume=ticker.volume.toDoubleOrNull()?:0.0
            if(price<=0.0)return@mapNotNull null
            val symbol=extractBaseSymbol(ticker.market)
            val quote=quoteOf(ticker.market)
            CryptoPrice(
                market=ticker.market,
                symbol=symbol,
                name="$symbol/$quote",
                lastPrice=price,
                priceChangePercentage24h=0.0,
                volume24h=volume,
                image=getLogoForSymbol(symbol),
                category="CRYPTO"
            )
        }
    }catch(e:Exception){
        Log.e("MARKET","Live market request failed",e)
        emptyList()
    }
}

fun fetchMarkets(){
    viewModelScope.launch(Dispatchers.IO){
        _uiState.value=UiState.Loading
        val live=loadLiveMarkets()
        val markets=if(live.isNotEmpty())live else (_uiState.value as? UiState.Success)?.markets.orEmpty()
        if(markets.isNotEmpty()){
            withContext(Dispatchers.Main){
                _uiState.value=UiState.Success(markets)
                if(_selectedMarket.value==null)_selectedMarket.value=markets.firstOrNull()
            }
        }else{
            withContext(Dispatchers.Main){_uiState.value=UiState.Error("No live markets received")}
        }
    }
}


private fun deltaFlowSymbol(rawSymbol:String):String {
    var s = rawSymbol.trim().uppercase(Locale.US)
    if (s.startsWith("B-")) s = s.removePrefix("B-")
    s = s.replace("_", "")
    s = s.removeSuffix("USDT").removeSuffix("USDC").removeSuffix("USD")
    // Delta public L2/trades endpoints use contract symbols such as BTCUSD.
    return if (s.isNotBlank()) "${s}USD" else ""
}

private suspend fun loadDeltaMarketFlow(rawSymbol:String):LiveMarketFlow? =
    withContext(Dispatchers.IO) {
        val symbol = deltaFlowSymbol(rawSymbol)
        if (symbol.isBlank()) return@withContext null

        try {
            val bookResponse = deltaPublicFlowApi.getL2OrderBook(symbol, 15)
            val tradesResponse = deltaPublicFlowApi.getTrades(symbol)

            // Delta responses may be returned directly or inside the standard
            // {success,result:{...}} envelope. Normalize both forms here.
            val bookJson = bookResponse.body()
            val bookRoot = bookJson?.let {
                if (it.has("result") && it.get("result").isJsonObject) it.getAsJsonObject("result") else it
            }
            val book = bookRoot?.let {
                runCatching { Gson().fromJson(it, DeltaL2OrderBook::class.java) }.getOrNull()
            }

            val tradesJson = tradesResponse.body()
            val tradesRoot = tradesJson?.let {
                if (it.has("result") && it.get("result").isJsonObject) it.getAsJsonObject("result") else it
            }
            val trades = tradesRoot?.let {
                runCatching { Gson().fromJson(it, DeltaTradesResponse::class.java) }.getOrNull()
            }?.trades.orEmpty()

            if (book == null && trades.isEmpty()) {
                Log.w("DELTA_FLOW", "$symbol: no public flow/orderbook data")
                return@withContext null
            }

            // Delta public trades explicitly identify the aggressor side:
            // side=buy means buyer aggressor; side=sell means seller aggressor.
            // Convert each trade to quote notional (size * price) so the AI
            // compares buyer/seller flow on the same monetary basis.
            var buyerVolume = 0.0
            var sellerVolume = 0.0
            var buyerTrades = 0L
            var sellerTrades = 0L

            for (trade in trades) {
                val size = trade.size.toDouble().coerceAtLeast(0.0)
                val price = trade.price.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                val notional = size * price
                if (trade.side.equals("buy", true)) {
                    buyerVolume += notional
                    buyerTrades++
                } else if (trade.side.equals("sell", true)) {
                    sellerVolume += notional
                    sellerTrades++
                }
            }

            // L2 size is contract/underlying size. Keep both raw book size
            // and price-weighted notional for AI analysis.
            val bidBookVolume = book?.buy.orEmpty().sumOf { it.size.toDouble().coerceAtLeast(0.0) }
            val askBookVolume = book?.sell.orEmpty().sumOf { it.size.toDouble().coerceAtLeast(0.0) }
            val bidBookNotional = book?.buy.orEmpty().sumOf {
                (it.price.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0) *
                    it.size.toDouble().coerceAtLeast(0.0)
            }
            val askBookNotional = book?.sell.orEmpty().sumOf {
                (it.price.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0) *
                    it.size.toDouble().coerceAtLeast(0.0)
            }

            val bookTotal = bidBookNotional + askBookNotional
            val bookImbalance = if (bookTotal > 0.0) {
                ((bidBookNotional - askBookNotional) / bookTotal * 100.0)
                    .coerceIn(-100.0, 100.0)
            } else 0.0

            val tradeTotal = buyerVolume + sellerVolume
            val flowBias = when {
                tradeTotal <= 0.0 && bookTotal <= 0.0 -> "UNKNOWN"
                tradeTotal > 0.0 && buyerVolume > sellerVolume * 1.05 -> "BUYER_DOMINANT"
                tradeTotal > 0.0 && sellerVolume > buyerVolume * 1.05 -> "SELLER_DOMINANT"
                bookImbalance >= 10.0 -> "BID_BOOK_DOMINANT"
                bookImbalance <= -10.0 -> "ASK_BOOK_DOMINANT"
                else -> "BALANCED"
            }

            LiveMarketFlow(
                symbol = symbol,
                buyerVolume = buyerVolume,
                sellerVolume = sellerVolume,
                buyerTrades = buyerTrades,
                sellerTrades = sellerTrades,
                bidBookVolume = bidBookVolume,
                askBookVolume = askBookVolume,
                bidBookNotional = bidBookNotional,
                askBookNotional = askBookNotional,
                bookImbalancePct = bookImbalance,
                flowBias = flowBias,
                updatedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w("DELTA_FLOW", "$symbol: ${e.message}")
            null
        }
    }

private suspend fun warmupMarketHistory(market: CryptoPrice) {
    val symbol = market.symbol.trim().uppercase(Locale.US)
    if (symbol.isBlank() || warmedSymbols.contains(symbol)) return

    // CoinDCX spot candle pair format used by the existing Chart code.
    val pair = if (market.market.contains("_") && market.market.startsWith("B-", true)) {
        market.market
    } else {
        "B-${symbol}_USDT"
    }

    warmupMutex.lock()
    try {
        if (warmedSymbols.contains(symbol)) return
        val candles = withContext(Dispatchers.IO) {
            try {
                candleApi.getCandles(
                    pair = pair,
                    interval = "1m",
                    limit = 200
                )
            } catch (e: Exception) {
                Log.w("CANDLE_WARMUP", "${market.symbol}: ${e.message}")
                emptyList()
            }
        }

        if (candles.size >= 50) {
            val ordered = candles.sortedBy { it.time }
            val closes = ordered.map { it.close }.filter { it > 0.0 && it.isFinite() }
            val highs = ordered.map { it.high }.filter { it > 0.0 && it.isFinite() }
            val lows = ordered.map { it.low }.filter { it > 0.0 && it.isFinite() }
            if (closes.size >= 50 && highs.size >= 50 && lows.size >= 50) {
                val usable = minOf(closes.size, highs.size, lows.size, 200)
                priceHistory[symbol] = closes.takeLast(usable).toMutableList()
                highHistory[symbol] = highs.takeLast(usable).toMutableList()
                lowHistory[symbol] = lows.takeLast(usable).toMutableList()
                warmedSymbols.add(symbol)
                Log.d("CANDLE_WARMUP", "${market.symbol}: ${closes.size} chronological candles loaded; using $usable")
            } else {
                Log.w("CANDLE_WARMUP", "${market.symbol}: only ${closes.size} usable candles; need 50")
            }
        }
    } finally {
        warmupMutex.unlock()
    }
}

private fun calculateRSI(prices:List<Double>,period:Int=14):Double{
    if(prices.size<=period)return 50.0
    var gain=0.0
    var loss=0.0
    for(i in prices.size-period until prices.size){
        val change=prices[i]-prices[i-1]
        if(change>0)gain+=change else loss+=-change
    }
    if(loss==0.0)return 100.0
    val rs=(gain/period)/(loss/period)
    return 100.0-(100.0/(1.0+rs))
}

private fun calculateEMA(prices:List<Double>,period:Int):Double{
    if(prices.isEmpty())return 0.0
    if(prices.size<period)return prices.average()
    val k=2.0/(period+1.0)
    var ema=prices.take(period).average()
    for(i in period until prices.size)ema=(prices[i]-ema)*k+ema
    return ema
}

private fun calculateMACD(prices:List<Double>):Pair<Double,Double>{
    if(prices.size<26)return 0.0 to 0.0
    val macd=calculateEMA(prices,12)-calculateEMA(prices,26)
    val history=mutableListOf<Double>()
    for(i in 26 until prices.size){
        val p=prices.subList(0,i+1)
        history.add(calculateEMA(p,12)-calculateEMA(p,26))
    }
    val signal=if(history.size>=9)calculateEMA(history,9) else macd
    return macd to signal
}

private fun calculateBollingerBands(prices:List<Double>,period:Int=20,multiplier:Double=2.0):Pair<Double,Double>{
    if(prices.size<period)return 0.0 to 0.0
    val recent=prices.takeLast(period)
    val sma=recent.average()
    val variance=recent.map{(it-sma)*(it-sma)}.average()
    val sd=sqrt(variance)
    return (sma+multiplier*sd) to (sma-multiplier*sd)
}

private fun calculateATR(highs:List<Double>,lows:List<Double>,closes:List<Double>,period:Int=14):Double{
    if(highs.size<2)return 0.0
    val tr=mutableListOf<Double>()
    for(i in 1 until highs.size){
        tr.add(maxOf(highs[i]-lows[i],abs(highs[i]-closes[i-1]),abs(lows[i]-closes[i-1])))
    }
    return if(tr.isEmpty())0.0 else tr.takeLast(period).average()
}

fun calculateIndicators(symbol:String,currentPrice:Double,high:Double=currentPrice,low:Double=currentPrice):IndicatorValues{
    val prices=priceHistory.getOrPut(symbol){mutableListOf()}
    val highs=highHistory.getOrPut(symbol){mutableListOf()}
    val lows=lowHistory.getOrPut(symbol){mutableListOf()}
    if (prices.lastOrNull() != currentPrice) {
        prices.add(currentPrice)
        highs.add(high)
        lows.add(low)
    } else if (prices.isNotEmpty()) {
        // Update the current bar instead of duplicating the same tick.
        highs[highs.lastIndex] = maxOf(highs.lastOrNull() ?: high, high)
        lows[lows.lastIndex] = minOf(lows.lastOrNull() ?: low, low)
    }
    while(prices.size>200){prices.removeAt(0);highs.removeAt(0);lows.removeAt(0)}
    val macd=calculateMACD(prices)
    val bb=calculateBollingerBands(prices)
    val result=IndicatorValues(
        rsi=calculateRSI(prices),
        macd=macd.first,
        macdSignal=macd.second,
        macdHistogram=macd.first-macd.second,
        ema9=calculateEMA(prices,9),
        ema21=calculateEMA(prices,21),
        ema50=calculateEMA(prices,50),
        ema200=calculateEMA(prices,200),
        bollingerUpper=bb.first,
        bollingerLower=bb.second,
        atr=calculateATR(highs,lows,prices)
    )
    _currentIndicators.value=result
    return result
}

private data class StrategyScore(
    val trend:Int,
    val momentum:Int,
    val meanReversion:Int,
    val breakout:Int,
    val volatility:Int,
    val total:Int,
    val reasons:List<String>
)

private fun buildProfessionalEnsemble(i:IndicatorValues,currentPrice:Double):StrategyScore{
    var trend=0; var momentum=0; var mean=0; var breakout=0; var volatility=0
    val reasons=mutableListOf<String>()

    // Trend-following: higher weight because the bot is intended to scan trending coins.
    if(i.ema9>i.ema21 && i.ema21>i.ema50 && i.ema50>i.ema200){ trend+=4; reasons += "Trend ↑ EMA stack" }
    else if(i.ema9<i.ema21 && i.ema21<i.ema50 && i.ema50<i.ema200){ trend-=4; reasons += "Trend ↓ EMA stack" }
    else if(i.ema9>i.ema21){ trend+=1 } else if(i.ema9<i.ema21){ trend-=1 }

    // Momentum: RSI + MACD agreement.
    if(i.rsi>=55 && i.rsi<=70) momentum+=2
    if(i.rsi<=45 && i.rsi>=30) momentum-=2
    if(i.macd>i.macdSignal && i.macdHistogram>0) momentum+=3
    if(i.macd<i.macdSignal && i.macdHistogram<0) momentum-=3

    // Mean reversion is deliberately capped so it cannot fight a strong trend too hard.
    if(i.bollingerLower>0 && currentPrice<i.bollingerLower) mean+=2
    if(i.bollingerUpper>0 && currentPrice>i.bollingerUpper) mean-=2

    // Breakout: price relative to Bollinger mid-band + EMA9/21 alignment.
    val mid=if(i.bollingerUpper>0 && i.bollingerLower>0)(i.bollingerUpper+i.bollingerLower)/2.0 else 0.0
    if(mid>0 && currentPrice>mid && i.ema9>i.ema21) breakout+=2
    if(mid>0 && currentPrice<mid && i.ema9<i.ema21) breakout-=2

    // Volatility guard: ATR is useful, but extreme ATR/price means "do not chase".
    if(i.atr>0 && currentPrice>0){
        val atrPct=i.atr/currentPrice
        when {
            atrPct>0.08 -> { volatility=-3; reasons += "Extreme ATR" }
            atrPct>0.04 -> { volatility=-1; reasons += "High volatility" }
            else -> volatility+=1
        }
    }

    if(currentPrice>i.ema200 && i.ema200>0) reasons += "Above EMA200"
    if(currentPrice<i.ema200 && i.ema200>0) reasons += "Below EMA200"

    // Profile-controlled ensemble weights. The selected profile changes the
    // scoring emphasis without bypassing the existing technical indicators.
    val cfg = _strategyConfig.value
    val (tw,mw,rw,bw,vw) = when(cfg.tradingViewStrategy) {
        TradingViewStrategy.COMBINED_CONFIRMATION -> when(cfg.profile) {
            StrategyProfile.BALANCED -> listOf(3,3,1,2,1)
            StrategyProfile.TREND_FOLLOWING -> listOf(5,2,0,2,1)
            StrategyProfile.MOMENTUM -> listOf(2,5,0,2,1)
            StrategyProfile.MEAN_REVERSION -> listOf(1,2,5,1,1)
            StrategyProfile.BREAKOUT -> listOf(2,2,1,5,1)
            StrategyProfile.CONSERVATIVE -> listOf(4,3,1,2,2)
        }
        TradingViewStrategy.EMA_CROSSOVER -> listOf(6,1,0,2,1)
        TradingViewStrategy.RSI_MOMENTUM -> listOf(1,6,1,1,1)
        TradingViewStrategy.MACD_CROSSOVER -> listOf(2,6,0,1,1)
        TradingViewStrategy.BOLLINGER_MEAN_REVERSION -> listOf(1,2,7,0,1)
        TradingViewStrategy.BOLLINGER_BREAKOUT -> listOf(3,2,1,7,2)
        TradingViewStrategy.ATR_BREAKOUT -> listOf(3,2,0,6,5)
        TradingViewStrategy.VOLUME_MOMENTUM -> listOf(3,6,0,3,1)
        TradingViewStrategy.EMA_TREND_PULLBACK -> listOf(7,3,2,2,1)
        TradingViewStrategy.CONSERVATIVE_CONFIRMATION -> listOf(5,4,1,2,4)
    }
    val total=(trend*tw)+(momentum*mw)+(mean*rw)+(breakout*bw)+(volatility*vw)
    return StrategyScore(trend,momentum,mean,breakout,volatility,total,reasons.distinct())
}

private fun riskGate(symbol:String,currentPrice:Double,score:StrategyScore):String?{
    if(currentPrice<=0.0) return "invalid price"
    val i=_currentIndicators.value
    if(i.atr<=0.0 || i.ema200<=0.0) return "indicators not warmed"
    val atrPct=i.atr/currentPrice
    if(atrPct>0.10) return "extreme volatility"
    val realized =
        _marketStats.value.totalProfit - _marketStats.value.totalLoss
    val equity=(_realBalance.value ?: _portfolioValue.value).coerceAtLeast(0.0)
    val cfg = _strategyConfig.value
    if(equity>0 && realized <= -(equity*(cfg.maxDrawdownPct/100.0))) return "daily/active drawdown guard"
    if(_botTrades.value.count{it.status.equals("OPEN",true)}>=cfg.maxOpenPositions) return "max open positions"
    return null
}

fun analyzeMarket(symbol:String,currentPrice:Double):TradingSignal{
    val i=calculateIndicators(symbol,currentPrice)
    if(i.ema200<=0.0 || i.atr<=0.0){
        _trendingSignal.value=TradingSignal.HOLD
        return TradingSignal.HOLD
    }
    val e=buildProfessionalEnsemble(i,currentPrice)
    val guard=riskGate(symbol,currentPrice,e)
    if(guard!=null){
        _trendingSignal.value=TradingSignal.HOLD
        return TradingSignal.HOLD
    }
    // Use the persisted strategy settings for signal strength and confidence.
    val cfg = _strategyConfig.value
    val buyThreshold = cfg.signalThreshold
    val sellThreshold = -buyThreshold
    val confidence=(kotlin.math.abs(e.total).toDouble()/28.0*100.0).coerceIn(0.0,100.0)
    val reason=e.reasons.joinToString(", ")
    val signal=when{
        e.total>=24 && confidence>=cfg.minConfidence.coerceAtLeast(75.0) -> TradingSignal.STRONG_BUY(reason,confidence)
        e.total>=buyThreshold && confidence>=cfg.minConfidence -> TradingSignal.BUY(reason,confidence)
        e.total<=-24 && confidence>=cfg.minConfidence.coerceAtLeast(75.0) -> TradingSignal.STRONG_SELL(reason,confidence)
        e.total<=sellThreshold && confidence>=cfg.minConfidence -> TradingSignal.SELL(reason,confidence)
        else -> TradingSignal.HOLD
    }
    _trendingSignal.value=signal
    return signal
}

private fun signalConfidence(signal:TradingSignal):Double=when(signal){
    is TradingSignal.STRONG_BUY->signal.confidence
    is TradingSignal.BUY->signal.confidence
    is TradingSignal.SELL->signal.confidence
    is TradingSignal.STRONG_SELL->signal.confidence
    TradingSignal.HOLD->0.0
}

private fun signalText(signal:TradingSignal):String=when(signal){
    is TradingSignal.STRONG_BUY->"STRONG_BUY"
    is TradingSignal.BUY->"BUY"
    TradingSignal.HOLD->"HOLD"
    is TradingSignal.SELL->"SELL"
    is TradingSignal.STRONG_SELL->"STRONG_SELL"
}

private fun canPlaceRealOrder():Boolean{
    return _orderMode.value==OrderMode.REAL && _liveMode.value && _isConnected.value
}


// ============================================================
// ADAPTIVE PROFIT TRAILING SELL
// No fixed profit percentage. The bot lets profit run and exits only after
// the market reverses by a volatility-based ATR distance from the highest
// price reached since this bot entry.
// ============================================================
private val highestProfitPrice = ConcurrentHashMap<String, Double>()

// Loss positions are NEVER sold by this rule.
// The target is recalculated from the persisted entry price on every
// cycle, so it survives Activity/service restarts.
// ============================================================
private suspend fun checkAdaptiveTrailingSell(
    market: CryptoPrice,
    indicators: IndicatorValues
): Boolean {
    if (!canPlaceRealOrder()) return false

    val symbol = market.symbol.trim().uppercase(Locale.US)
    val price = market.lastPrice
    if (symbol.isBlank() || price <= 0.0) return false

    if (pendingRealOrders.containsKey(symbol)) return false
    if (realOrderJobs[symbol]?.isActive == true) return false

    val localOpen = _botTrades.value.firstOrNull {
        it.symbol.equals(symbol, true) &&
            it.type.equals("BUY", true) &&
            it.status.equals("OPEN", true) &&
            it.entryPrice > 0.0 &&
            it.quantity > 0.0
    }

    val persisted = loadBotOwnedPositions().firstOrNull {
        it.exchange.equals("DeltaIndia", true) &&
            it.symbol.equals(symbol, true) &&
            it.side.equals("BUY", true) &&
            it.entryPrice > 0.0 &&
            it.quantity > 0.0
    }

    val entryPrice = localOpen?.entryPrice ?: persisted?.entryPrice ?: return false
    val quantityHint = localOpen?.quantity ?: persisted?.quantity ?: return false

    // PROFIT TARGET:
    // Never use this manager to sell a losing/flat position. Once gross P/L
    // reaches the requested 3% target, close the real position immediately.
    // Exchange fees/slippage mean realized/net P/L can be lower than 5%.
    val profitPercent = ((price - entryPrice) / entryPrice) * 100.0
    if (profitPercent <= 0.0) {
        highestProfitPrice.remove(symbol)
        return false
    }

    if (profitPercent >= targetProfitPct) {
        val exchange = autoExchangeName.value
        val credentials = loadRouterCredentials(exchange) ?: return false
        if (!reserveHourlyOrderSlot()) {
            _pipeline.value = _pipeline.value.copy(
                orderStatus = "⏸️ 60m order cap reached • $symbol SELL waits",
                lastUpdate = System.currentTimeMillis()
            )
            return false
        }

        _pipeline.value = _pipeline.value.copy(
            orderStatus = "🎯 ${targetProfitPct}% PROFIT TARGET • $symbol • profit=${"%.2f".format(Locale.US, profitPercent)}%",
            lastUpdate = System.currentTimeMillis()
        )

        val result = if (exchange.equals("DeltaIndia", true)) {
            ProfessionalExchangeRouter.closeDeltaBotPosition(
                credentials = credentials,
                symbol = symbol,
                quantityHint = quantityHint,
                clientOrderId = "NITINBOT-TP3-SELL-${System.currentTimeMillis()}"
            )
        } else {
            placeRealSell(
                market = market,
                quantity = quantityHint,
                price = market.lastPrice
            ).let { ok ->
                RouterOrderResult(
                    success = ok,
                    exchange = exchange,
                    orderId = lastRealOrderId,
                    status = if (ok) "closed" else "rejected",
                    message = if (ok) "3% target SELL" else "3% target SELL failed",
                    filled = ok
                )
            }
        }

        val filled = result.filled ||
            result.status.equals("closed", true) ||
            result.status.equals("filled", true)

        if (filled) {
            captureRealFill(result)
            if (localOpen != null) {
                closeTrade(
                    localOpen,
                    if (result.averageFillPrice > 0.0) result.averageFillPrice else market.lastPrice,
                    actualFilledSize = result.filledSize,
                    exitCommission = result.paidCommission
                )
            } else {
                forgetBotPosition(exchange, symbol)
            }
            highestProfitPrice.remove(symbol)
            pendingRealOrders.remove(symbol)
            publishPendingAutoOrders()
            _pipeline.value = _pipeline.value.copy(
                orderStatus = "🟢 ${targetProfitPct}% PROFIT SELL FILLED • $symbol • profit=${"%.2f".format(Locale.US, profitPercent)}%",
                lastUpdate = System.currentTimeMillis()
            )
            refreshAutoExchangeBalance()
            return true
        }

        _pipeline.value = _pipeline.value.copy(
            orderStatus = "⚠️ 3% PROFIT SELL NOT FILLED • $symbol • ${result.message}",
            lastUpdate = System.currentTimeMillis()
        )
        return false
    }

    val adaptiveExit = ProfessionalAdaptiveStrategyEngine.decide(
        price = price,
        indicators = indicators,
        flow = null,
        stats = _marketStats.value,
        existingPosition = true,
        positionPnlPct = profitPercent
    )
    val activationPct = adaptiveExit.dynamicExitActivationPct
    if (profitPercent < activationPct) return false

    val peak = highestProfitPrice.merge(symbol, price) { old, current -> maxOf(old, current) } ?: price
    val reversalPct = ((peak - price) / peak) * 100.0
    if (reversalPct < adaptiveExit.dynamicTrailPct) return false

    val exchange = autoExchangeName.value
    val credentials = loadRouterCredentials(exchange) ?: return false

    _pipeline.value = _pipeline.value.copy(
        orderStatus = "🎯 TRAILING REVERSAL • $symbol • peak=${"%.6f".format(Locale.US, peak)} • profit=${"%.2f".format(Locale.US, profitPercent)}%",
        lastUpdate = System.currentTimeMillis()
    )

    val result = if (exchange.equals("DeltaIndia", true)) {
        ProfessionalExchangeRouter.closeDeltaBotPosition(
            credentials = credentials,
            symbol = symbol,
            quantityHint = quantityHint,
            clientOrderId = "NITINBOT-TRAIL-SELL-${System.currentTimeMillis()}"
        )
    } else {
        placeRealSell(
            market = market,
            quantity = quantityHint,
            price = market.lastPrice
        ).let { ok ->
            RouterOrderResult(
                success = ok,
                exchange = exchange,
                orderId = lastRealOrderId,
                status = if (ok) "closed" else "rejected",
                message = if (ok) "adaptive trailing SELL" else "adaptive trailing SELL failed",
                filled = ok
            )
        }
    }

    val filled = result.filled || result.status.equals("closed", true) || result.status.equals("filled", true)

    if (filled) {
        captureRealFill(result)
        if (localOpen != null) {
            closeTrade(
                localOpen,
                if (result.averageFillPrice > 0.0) result.averageFillPrice else market.lastPrice,
                actualFilledSize = result.filledSize,
                exitCommission = result.paidCommission
            )
        } else {
            forgetBotPosition(exchange, symbol)
        }
        highestProfitPrice.remove(symbol)
        pendingRealOrders.remove(symbol)
        publishPendingAutoOrders()
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "🟢 TRAILING SELL FILLED • $symbol • profit=${"%.2f".format(Locale.US, profitPercent)}%",
            lastUpdate = System.currentTimeMillis()
        )
        refreshAutoExchangeBalance()
        return true
    }

    _pipeline.value = _pipeline.value.copy(
        orderStatus = "⚠️ TRAILING SELL NOT FILLED • $symbol • ${result.message}",
        lastUpdate = System.currentTimeMillis()
    )
    return false
}

// ============================================================
// BUY PASS-THROUGH
// A final BUY/STRONG_BUY decision is already produced by the technical
// + Master-AI pipeline above. Do not run a second 4/6, 5/6 or EMA/RSI
// veto here. That duplicate gate was the reason the UI could show BUY
// while Total Trades stayed at zero.
// ============================================================
// BUY volume confirmation: a coin is eligible for a new BUY when its
// observed 24h volume has increased by at least +5.0% versus the previous
// scanner observation. The first observation is accepted so the multi-coin
// scanner can warm up instead of blocking every symbol forever.
private val previousBuyVolume24h = ConcurrentHashMap<String, Double>()
private val BUY_VOLUME_SURGE_PCT = 5.0

private fun isHighQualityBuyCandidate(
    market:CryptoPrice,
    indicators:IndicatorValues,
    signal:TradingSignal
):Boolean {
    if (market.lastPrice <= 0.0) return false
    if (signal !is TradingSignal.BUY && signal !is TradingSignal.STRONG_BUY) return false

    val symbol = market.symbol.trim().uppercase(Locale.US)
    val currentVolume = market.volume24h
    if (symbol.isBlank() || currentVolume <= 0.0) return false

    val previousVolume = previousBuyVolume24h.put(symbol, currentVolume)
    if (previousVolume == null || previousVolume <= 0.0) {
        return true
    }

    val volumeChangePct = ((currentVolume - previousVolume) / previousVolume) * 100.0
    return volumeChangePct >= BUY_VOLUME_SURGE_PCT
}

private val lastBotPositionReconcileAt =
    ConcurrentHashMap<String,Long>()

/**
 * Reconcile only bot-owned Delta positions. Manual positions are never
 * removed/closed by this function.
 *
 * If Delta reports the bot-owned position as flat, remove the persisted
 * ownership record immediately so the position cannot remain in the bot UI
 * or be selected for another SELL.
 */
private suspend fun refreshLiveDeltaPositions() {
    if (!autoExchangeName.value.equals("DeltaIndia", true)) {
        _liveDeltaPositions.value = emptyList()
        _liveDeltaOpenOrders.value = emptyList()
        return
    }
    val credentials = loadRouterCredentials("DeltaIndia") ?: return
    val live = ProfessionalExchangeRouter.getDeltaLivePositions(credentials)
    val openOrders = ProfessionalExchangeRouter.getDeltaOpenOrders(credentials)

    // If the current market cache has a fresher mark price, use it only to
    // display current P/L. The position itself always comes from Delta.
    val markets = (_uiState.value as? UiState.Success)?.markets.orEmpty()
    val enriched = live.map { p ->
        val marketPrice = markets.firstOrNull {
            it.symbol.equals(p.symbol, true) || it.market.equals(p.symbol, true)
        }?.lastPrice ?: 0.0
        if (marketPrice <= 0.0) p else {
            val pnl = if (p.side.equals("LONG", true))
                (marketPrice - p.entryPrice) * p.quantity
            else
                (p.entryPrice - marketPrice) * p.quantity
            p.copy(markPrice = marketPrice, unrealizedPnl = pnl)
        }
    }

    _liveDeltaPositions.value = enriched.sortedBy { it.symbol }
    _liveDeltaOpenOrders.value = openOrders.sortedByDescending { it.createdAt }
}

fun refreshLiveDeltaPositionsNow() {
    viewModelScope.launch(Dispatchers.IO) {
        refreshLiveDeltaPositions()
    }
}

/**
 * Profit-only manager for every live Delta position visible in the account.
 * This is intentionally separate from bot-owned trade history: an exchange
 * position must not disappear from the dashboard just because the Activity
 * or bot was restarted. Loss/flat positions are never force-sold by this
 * manager.
 */
private suspend fun manageAllLiveDeltaPositionsForProfit() {
    if (!autoExchangeName.value.equals("DeltaIndia", true)) return
    if (!canPlaceRealOrder()) return

    val credentials = loadRouterCredentials("DeltaIndia") ?: return
    for (position in _liveDeltaPositions.value) {
        if (!currentCoroutineContext().isActive) return
        if (position.quantity <= 0.0 || position.entryPrice <= 0.0 || position.markPrice <= 0.0) continue

        val profitPct = if (position.side.equals("SHORT", true)) {
            ((position.entryPrice - position.markPrice) / position.entryPrice) * 100.0
        } else {
            ((position.markPrice - position.entryPrice) / position.entryPrice) * 100.0
        }
        if (!profitPct.isFinite() || profitPct < targetProfitPct) continue

        val symbol = position.symbol.trim().uppercase(Locale.US)
        if (pendingRealOrders.containsKey(symbol) || realOrderJobs[symbol]?.isActive == true) continue
        if (!reserveHourlyOrderSlot()) return

        _pipeline.value = _pipeline.value.copy(
            orderStatus = "🎯 ${targetProfitPct}% LIVE PROFIT SELL • $symbol • profit=${"%.2f".format(Locale.US, profitPct)}%",
            lastUpdate = System.currentTimeMillis()
        )

        val result = ProfessionalExchangeRouter.closeDeltaBotPosition(
            credentials = credentials,
            symbol = symbol,
            quantityHint = position.quantity,
            clientOrderId = "NITINBOT-LIVE-TP-${System.currentTimeMillis()}",
            productIdHint = position.productId
        )

        if (result.orderId.isNotBlank() &&
            result.status.lowercase(Locale.US) in setOf("open","pending")
        ) {
            pendingRealOrders[symbol] = PendingRealOrder(
                symbol = symbol,
                side = if (position.side.equals("SHORT", true)) "BUY" else "SELL",
                orderId = result.orderId,
                quantity = position.quantity,
                price = position.markPrice
            )
            publishPendingAutoOrders()
        }

        if (result.filled || result.status.equals("closed", true) || result.status.equals("filled", true)) {
            _pipeline.value = _pipeline.value.copy(
                orderStatus = "🟢 LIVE ${targetProfitPct}% PROFIT SELL FILLED • $symbol",
                lastUpdate = System.currentTimeMillis()
            )
            refreshAutoExchangeBalance()
        } else if (result.success) {
            _pipeline.value = _pipeline.value.copy(
                orderStatus = "⏳ LIVE PROFIT SELL PENDING • $symbol • order=${result.orderId}",
                lastUpdate = System.currentTimeMillis()
            )
        }
    }
}

private suspend fun reconcileBotOwnedDeltaPosition(symbol:String, marketPrice:Double) {
    val normalized = symbol.trim().uppercase(Locale.US)
    if (normalized.isBlank()) return
    if (!autoExchangeName.value.equals("DeltaIndia", true)) return

    val record = loadBotOwnedPositions().firstOrNull {
        it.exchange.equals("DeltaIndia", true) &&
            it.symbol.equals(normalized, true) &&
            it.quantity > 0.0
    } ?: return

    val now = System.currentTimeMillis()
    val last = lastBotPositionReconcileAt[normalized] ?: 0L
    if (now - last < 15_000L) return
    lastBotPositionReconcileAt[normalized] = now

    val credentials = loadRouterCredentials("DeltaIndia") ?: return
    val liveSize = ProfessionalExchangeRouter.getDeltaBotPositionSize(
        credentials = credentials,
        symbol = normalized
    ) ?: return

    if (liveSize <= 0.0) {
        forgetBotPosition("DeltaIndia", normalized)
        highestProfitPrice.remove(normalized)

        val localOpen = _botTrades.value.firstOrNull {
            it.symbol.equals(normalized, true) &&
                it.status.equals("OPEN", true)
        }
        if (localOpen != null) {
            // The exchange is already flat, but this path has no confirmed exit
            // fill price/fee. Never invent a P/L from the current market price.
            _botTrades.value = _botTrades.value.filterNot {
                it.symbol.equals(normalized, true) && it.time == localOpen.time
            }
        }

        pendingRealOrders.remove(normalized)
        publishPendingAutoOrders()
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "🧹 POSITION REMOVED • Delta flat • $normalized",
            lastUpdate = System.currentTimeMillis()
        )
        return
    }

    // Keep the persisted quantity synchronized with the exchange after
    // partial closes/adds. Entry price remains the bot's actual entry.
    if (kotlin.math.abs(liveSize - record.quantity) > 0.0000001) {
        val list = loadBotOwnedPositions().toMutableList()
        val idx = list.indexOfFirst {
            it.exchange.equals("DeltaIndia", true) &&
                it.symbol.equals(normalized, true)
        }
        if (idx >= 0) {
            list[idx] = list[idx].copy(quantity = liveSize)
            saveBotOwnedPositions(list)
        }
    }
}

/**
 * Returns the exact latest price used by the live bot scanner for a symbol.
 * The dashboard uses this instead of selecting the first generic market with
 * the same base symbol, which could show a wrong near-zero price and fake
 * -100% P/L on an open Delta position.
 */
fun liveBotPriceFor(symbol:String):Double {
    val key = symbol.trim().uppercase(Locale.US)
    if (key.isBlank()) return 0.0
    val live = priceHistory[key]?.lastOrNull() ?: 0.0
    if (live > 0.0 && live.isFinite()) return live
    val pipelineSymbol = _pipeline.value.candleStatus
        .substringAfter("LIVE ", "")
        .substringBefore(" ")
        .trim()
        .uppercase(Locale.US)
    return if (pipelineSymbol == key && _pipeline.value.lastPrice > 0.0)
        _pipeline.value.lastPrice
    else 0.0
}

private fun top3000AiContext(symbol:String):String {
    val rows=top3000AiMarkets.value
    if(rows.isEmpty()) return "Top-3000 AI market universe: loading"
    val target=symbol.trim().uppercase(Locale.US)
    val ranked=rows.indexOfFirst { it.symbol.trim().uppercase(Locale.US)==target } + 1
    val topVolume=rows.asSequence()
        .sortedByDescending { it.totalVolume ?: 0.0 }
        .take(12)
        .joinToString(", ") {
            "${it.symbol.uppercase(Locale.US)}:vol=${"%.0f".format(Locale.US,it.totalVolume ?: 0.0)}"
        }
    return if(ranked > 0) {
        "Top-3000 AI universe active • rank=$ranked/3000 • volume leaders=$topVolume"
    } else {
        "Top-3000 AI universe active • $target not in top-3000 • volume leaders=$topVolume"
    }
}

private suspend fun fetchDeltaRestTickerPrice(symbol: String): Double =
    withContext(Dispatchers.IO) {
        try {
            val base = symbol.trim().uppercase(Locale.US)
            if (base.isBlank()) return@withContext 0.0

            // Delta India perpetual product symbols use the USD contract
            val productSymbol = when {
                base.endsWith("USD") -> base
                base.endsWith("USDT") -> base.removeSuffix("USDT") + "USD"
                base.endsWith("USDC") -> base.removeSuffix("USDC") + "USD"
                base.contains("-") -> base.substringBefore("-") + "USD"
                base.contains("_") -> base.substringBefore("_") + "USD"
                else -> base + "USD"
            }.trim().uppercase(Locale.US)

            if (productSymbol.isBlank()) return@withContext 0.0

            val request = Request.Builder()
                .url("https://api.india.delta.exchange/v2/tickers/${java.net.URLEncoder.encode(productSymbol, "UTF-8")}")
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "NitinProTrading/1.0 Android")
                .build()

            val response = OkHttpClient.Builder()
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()

            response.use {
                if (!it.isSuccessful) return@withContext 0.0
                val body = it.body?.string().orEmpty()
                val root = org.json.JSONObject(body)
                if (!root.optBoolean("success", false)) return@withContext 0.0

                val result = root.optJSONObject("result") ?: return@withContext 0.0
                listOf(
                    result.optString("mark_price", "").toDoubleOrNull() ?: 0.0,
                    result.optString("last_price", "").toDoubleOrNull() ?: 0.0,
                    result.optString("spot_price", "").toDoubleOrNull() ?: 0.0
                ).firstOrNull { p -> p > 0.0 && p.isFinite() } ?: 0.0
            }
        } catch (e: Exception) {
            Log.w(
                "DELTA_REST_TICKER",
                "REST ticker fallback failed for $symbol: ${e.message}"
            )
            0.0
        }
    }

private suspend fun processTradingPipeline(sourceMarket:CryptoPrice, allowNewEntries:Boolean = true){
    val symbol = sourceMarket.symbol.trim().uppercase(Locale.US)
    if (symbol.isBlank() || sourceMarket.lastPrice <= 0.0) return

    // Delta decisions use Delta's own official public WebSocket price/flow.
    // Never substitute a cross-exchange price when Delta data is stale.
    val selectedExchange = autoExchangeName.value
    val deltaSnapshot = if (selectedExchange.equals("DeltaIndia", true)) {
        startDeltaLiveMarketFlow()
        deltaLiveMarketAdapter.ensureSymbol(symbol)
        deltaLiveMarketAdapter.latest(symbol)
    } else null

    val deltaWsFresh = if (selectedExchange.equals("DeltaIndia", true)) {
        deltaSnapshot != null &&
            deltaSnapshot.lastPrice > 0.0 &&
            deltaLiveMarketAdapter.isFresh(symbol)
    } else {
        true
    }

    /*
     * IMPORTANT BOT-OFF SELL FIX:
     * When the position manager is running with allowNewEntries=false, a
     * temporary Delta WebSocket outage must NOT block an existing-position
     * profit exit. Use Delta's official public REST ticker as the exit-price
     * fallback. New BUY entries remain blocked while WS data is stale.
     */
    val market = if (selectedExchange.equals("DeltaIndia", true) && !deltaWsFresh) {
        if (allowNewEntries) {
            _pipeline.value = _pipeline.value.copy(
                orderStatus = "⏳ $symbol BUY/SELL WAIT • Delta live data stale/missing",
                lastUpdate = System.currentTimeMillis()
            )
            return
        }

        val restPrice = fetchDeltaRestTickerPrice(symbol)
        if (restPrice <= 0.0) {
            _pipeline.value = _pipeline.value.copy(
                orderStatus = "⏳ $symbol SELL WAIT • Delta WS stale and REST price unavailable",
                lastUpdate = System.currentTimeMillis()
            )
            return
        }

        sourceMarket.copy(lastPrice = restPrice)
    } else {
        deltaSnapshot?.let { snapshot ->
            sourceMarket.copy(
                lastPrice = snapshot.lastPrice,
                priceChangePercentage24h = snapshot.market24hChangePct
            )
        } ?: sourceMarket
    }

    // Keep a small live stream on top of the historical warm-up.
    val prices = priceHistory.getOrPut(symbol) { mutableListOf() }
    val highs = highHistory.getOrPut(symbol) { mutableListOf() }
    val lows = lowHistory.getOrPut(symbol) { mutableListOf() }
    if (prices.lastOrNull() != market.lastPrice) {
        prices.add(market.lastPrice)
        highs.add(market.lastPrice)
        lows.add(market.lastPrice)
    }
    while (prices.size > 250) { prices.removeAt(0); if (highs.isNotEmpty()) highs.removeAt(0); if (lows.isNotEmpty()) lows.removeAt(0) }

    // When Delta is selected, prefer its official live 1-minute candle stream
    // for the newest indicator samples. The older warm-up remains intact, so
    // RSI/MACD/EMA/Bollinger/ATR are not calculated from a synthetic ticker.
    if (selectedExchange.equals("DeltaIndia", true)) {
        deltaSnapshot?.candles?.takeLast(30)?.forEach { candle ->
            if (candle.close > 0.0 && candle.high > 0.0 && candle.low > 0.0) {
                if (prices.lastOrNull() != candle.close) prices.add(candle.close)
                if (highs.lastOrNull() != candle.high) highs.add(candle.high)
                if (lows.lastOrNull() != candle.low) lows.add(candle.low)
            }
        }
        while (prices.size > 250) prices.removeAt(0)
        while (highs.size > 250) highs.removeAt(0)
        while (lows.size > 250) lows.removeAt(0)
    }

    val indicators = calculateIndicators(symbol, market.lastPrice, market.lastPrice, market.lastPrice)
    var signal = analyzeMarket(symbol, market.lastPrice)

    _pipelineStatus.value = if (prices.size >= 50) {
        "🟢 LIVE SCANNER • ${market.symbol}"
    } else {
        "🟡 WARMING • ${market.symbol} ${prices.size}/50"
    }

    _pipeline.value = _pipeline.value.copy(
        mode = _orderMode.value.name,
        candleStatus = if (prices.size >= 50) "🟢 LIVE ${market.symbol}" else "🟡 WARMUP ${prices.size}/50 ${market.symbol}",
        indicatorStatus = "${market.symbol} • RSI=${"%.2f".format(Locale.US, indicators.rsi)} MACD=${"%.6f".format(Locale.US, indicators.macd)} EMA200=${"%.4f".format(Locale.US, indicators.ema200)} ATR=${"%.6f".format(Locale.US, indicators.atr)}",
        signal = signalText(signal),
        confidence = signalConfidence(signal),
        lastPrice = market.lastPrice,
        lastUpdate = System.currentTimeMillis()
    )

    // Keep Delta-owned positions synchronized with the real exchange.
    // This runs before BUY/SELL decisions so a position closed externally
    // cannot remain as a stale bot position.
    reconcileBotOwnedDeltaPosition(symbol, market.lastPrice)

    // ============================================================
    // ADAPTIVE TRAILING SELL HAS PRIORITY OVER SIGNAL/Master HOLD.
    // A BUY position is held while flat/loss. Once profit is established,
    // the highest price is tracked and a volatility-based reversal closes it.
    // ============================================================
    if (checkAdaptiveTrailingSell(market, indicators)) {
        return
    }

    // New BUY/SELL signal generation still waits for the full technical warm-up.
    // Existing-position profit protection above does not.
    if (prices.size < 50) {
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "⏳ WARMUP • ${prices.size}/50",
            lastUpdate = System.currentTimeMillis()
        )
        return
    }

    // ============================================================
    // LIVE BUYER/SELLER PRESSURE + L2 ORDERBOOK
    // Official Delta WebSocket data is normalized by the universal engine.
    // ============================================================
    val liveFlowAnalysis = deltaSnapshot?.let {
        UniversalBuyerSellerFlowEngine.analyze(it)
    }

    // PROFESSIONAL ADAPTIVE STRATEGY: automatically re-evaluate RSI/MACD/EMA/
    // Bollinger/ATR plus authoritative buyer/seller flow on every live cycle.
    // The bot's own closed-trade statistics continuously tune the profile.
    val adaptiveOpen = _botTrades.value.any {
        it.symbol.equals(symbol, true) &&
            it.type.equals("BUY", true) &&
            it.status.equals("OPEN", true) &&
            it.entryPrice > 0.0
    }
    val adaptiveEntry = _botTrades.value.firstOrNull {
        it.symbol.equals(symbol, true) &&
            it.type.equals("BUY", true) &&
            it.status.equals("OPEN", true) &&
            it.entryPrice > 0.0
    }?.entryPrice ?: 0.0
    val adaptivePnl = if (adaptiveEntry > 0.0) {
        ((market.lastPrice - adaptiveEntry) / adaptiveEntry) * 100.0
    } else 0.0
    val adaptive = ProfessionalAdaptiveStrategyEngine.decide(
        price = market.lastPrice,
        indicators = indicators,
        flow = deltaSnapshot?.let { liveFlowFromSnapshot(it) },
        stats = _marketStats.value,
        existingPosition = adaptiveOpen,
        positionPnlPct = adaptivePnl
    )
    if (!adaptiveOpen) {
        when (adaptive.action) {
            ProfessionalAdaptiveStrategyEngine.Action.BUY ->
                signal = TradingSignal.BUY(adaptive.reason, adaptive.confidence)
            ProfessionalAdaptiveStrategyEngine.Action.SELL ->
                signal = TradingSignal.SELL(adaptive.reason, adaptive.confidence)
            ProfessionalAdaptiveStrategyEngine.Action.HOLD -> Unit
        }
    }
    _pipeline.value = _pipeline.value.copy(
        orderStatus = "🧠 ${adaptive.profile.name} AUTO-UPDATED • ${adaptive.reason}",
        lastUpdate = System.currentTimeMillis()
    )

    // ============================================================
    // FAST AI GATE SIGNAL
    // Keep these values available before the asynchronous AI gates below.
    // ============================================================
    val technicalTradeSignal =
        signal is TradingSignal.BUY ||
            signal is TradingSignal.STRONG_BUY ||
            signal is TradingSignal.SELL ||
            signal is TradingSignal.STRONG_SELL

    // ============================================================
    // AI #1: OPENAI MARKET AI MARKET + NEWS + REAL FLOW INTELLIGENCE
    // Scanner remains 1 second, but the network AI is cached per symbol so
    // the exchange scanner never waits for an LLM response on every tick.
    // ============================================================
    val technicalConfidence = signalConfidence(signal)
    val liveFlowForAi = deltaSnapshot?.let { liveFlowFromSnapshot(it) }
    val flowTotal = (liveFlowForAi?.buyerVolume ?: 0.0) + (liveFlowForAi?.sellerVolume ?: 0.0)
    val buyerRatio = if (flowTotal > 0.0) (liveFlowForAi!!.buyerVolume / flowTotal) * 100.0 else 0.0
    val sellerRatio = if (flowTotal > 0.0) (liveFlowForAi!!.sellerVolume / flowTotal) * 100.0 else 0.0
    val volumeDelta = (liveFlowForAi?.buyerVolume ?: 0.0) - (liveFlowForAi?.sellerVolume ?: 0.0)
    val flowScore = (((buyerRatio - sellerRatio) * 0.7) + ((liveFlowForAi?.bookImbalancePct ?: 0.0) * 0.3)).coerceIn(-100.0, 100.0)
    val marketAiCandidate = technicalTradeSignal
    val newsSummary = if (marketAiCandidate) runCatching { GlobalMarketIntelligence.latestGlobalNews().summary }.getOrDefault("News unavailable") else "News refresh deferred"
    val marketAiAnalysis = if (marketAiCandidate) runCatching {
        openAiMarketIntelligence.analyze(
            OpenAiMarketIntelligenceEngine.MarketInput(
                symbol = symbol, price = market.lastPrice, volume24h = market.volume24h,
                rsi = indicators.rsi, macd = indicators.macd, macdSignal = indicators.macdSignal,
                ema9 = indicators.ema9, ema21 = indicators.ema21, ema50 = indicators.ema50, ema200 = indicators.ema200,
                bollingerUpper = indicators.bollingerUpper, bollingerLower = indicators.bollingerLower, atr = indicators.atr,
                buyerVolume = liveFlowForAi?.buyerVolume ?: 0.0, sellerVolume = liveFlowForAi?.sellerVolume ?: 0.0,
                buyerRatioPct = buyerRatio, sellerRatioPct = sellerRatio,
                bidVolume = liveFlowForAi?.bidBookVolume ?: 0.0, askVolume = liveFlowForAi?.askBookVolume ?: 0.0,
                orderBookImbalancePct = liveFlowForAi?.bookImbalancePct ?: 0.0,
                volumeDelta = volumeDelta, flowScore = flowScore,
                flowBias = liveFlowForAi?.flowBias ?: "UNKNOWN", newsSummary = newsSummary, exchange = selectedExchange
            )
        )
    }.getOrNull() else null

    // ============================================================
    // OPENAI MASTER MIND — continuous independent strategy layer
    // Refresh every 20 seconds per symbol. Existing positions are also
    // evaluated while the bot is OFF by the persistent position manager.
    // ============================================================
    val normalizedAiSymbol = symbol.trim().uppercase(Locale.US)

    val localOpenForAi = _botTrades.value.firstOrNull {
        it.symbol.equals(normalizedAiSymbol, true) &&
            it.type.equals("BUY", true) &&
            it.status.equals("OPEN", true) &&
            it.entryPrice > 0.0 &&
            it.quantity > 0.0
    }
    val persistedForAi = loadBotOwnedPositions().firstOrNull {
        it.symbol.equals(normalizedAiSymbol, true) &&
            it.side.equals("BUY", true) &&
            it.quantity > 0.0 &&
            it.entryPrice > 0.0
    }
    val entryForAi = localOpenForAi?.entryPrice ?: persistedForAi?.entryPrice ?: 0.0
    val openPositionForAi = entryForAi > 0.0
    val highestForAi = maxOf(highestProfitPrice[normalizedAiSymbol] ?: entryForAi, market.lastPrice)
    val lastAiAt = lastOpenAiAnalysisAt[normalizedAiSymbol] ?: 0L
    val aiRefreshDue = System.currentTimeMillis() - lastAiAt >= openAiAnalysisIntervalMs
    val aiCandidate = technicalTradeSignal

    val openAiDecision = if (aiRefreshDue && (openPositionForAi || (allowNewEntries && aiCandidate))) {
        lastOpenAiAnalysisAt[normalizedAiSymbol] = System.currentTimeMillis()
        try {
            openAiMasterMind.evaluate(
                MasterMindAutoStrategyEngine.MarketInput(
                    symbol = normalizedAiSymbol,
                    market = market.market,
                    price = market.lastPrice,
                    change24hPct = market.priceChangePercentage24h,
                    volume24h = market.volume24h,
                    rsi = indicators.rsi,
                    macd = indicators.macd,
                    macdSignal = indicators.macdSignal,
                    ema9 = indicators.ema9,
                    ema21 = indicators.ema21,
                    ema50 = indicators.ema50,
                    ema200 = indicators.ema200,
                    bollingerUpper = indicators.bollingerUpper,
                    bollingerLower = indicators.bollingerLower,
                    atr = indicators.atr,
                    buyerVolume = liveFlowForAi?.buyerVolume ?: 0.0,
                    sellerVolume = liveFlowForAi?.sellerVolume ?: 0.0,
                    buyerRatioPct = buyerRatio,
                    sellerRatioPct = sellerRatio,
                    bidVolume = liveFlowForAi?.bidBookVolume ?: 0.0,
                    askVolume = liveFlowForAi?.askBookVolume ?: 0.0,
                    orderBookImbalancePct = liveFlowForAi?.bookImbalancePct ?: 0.0,
                    volumeDelta = volumeDelta,
                    flowScore = flowScore,
                    flowDataSufficient = liveFlowForAi != null && (flowTotal > 0.0),
                    buyerPressure = buyerRatio >= 55.0 || (liveFlowForAi?.bookImbalancePct ?: 0.0) >= 10.0,
                    sellerPressure = sellerRatio >= 55.0 || (liveFlowForAi?.bookImbalancePct ?: 0.0) <= -10.0,
                    newsSummary = newsSummary,
                    globalMarketSummary = _pipelineStatus.value +
                        " • OPENAI MARKET AI=${marketAiAnalysis?.bias?.name ?: "UNAVAILABLE"} " +
                        "confidence=${"%.0f".format(Locale.US, marketAiAnalysis?.confidence ?: 0.0)} " +
                        "score=${"%.0f".format(Locale.US, marketAiAnalysis?.marketScore ?: 0.0)} " +
                        "newsRisk=${marketAiAnalysis?.newsRisk ?: "UNKNOWN"} " +
                        "reason=${marketAiAnalysis?.reason ?: "AI1 unavailable"}" +
                        " • NEWS=${newsSummary.take(2500)}" +
                        " • ${top3000AiContext(normalizedAiSymbol)}" +
                        " • ${liveFlowForAi?.let {
                            "REAL_FLOW buyerVol=${"%.2f".format(Locale.US, it.buyerVolume)} " +
                            "sellerVol=${"%.2f".format(Locale.US, it.sellerVolume)} " +
                            "buyerTrades=${it.buyerTrades} sellerTrades=${it.sellerTrades} " +
                            "bidBook=${"%.2f".format(Locale.US, it.bidBookNotional)} " +
                            "askBook=${"%.2f".format(Locale.US, it.askBookNotional)} " +
                            "bookImbalance=${"%.1f".format(Locale.US, it.bookImbalancePct)}% " +
                            "bias=${it.flowBias}"
                        } ?: "REAL_FLOW unavailable"}" +
                        " • realStats trades=${_marketStats.value.totalTrades}" +
                        " winRate=${"%.1f".format(Locale.US, _marketStats.value.winRate)}%" +
                        " profitFactor=${"%.2f".format(Locale.US, _marketStats.value.profitFactor)}",
                    existingPosition = openPositionForAi,
                    entryPrice = entryForAi,
                    highestPrice = highestForAi,
                    positionPnlPct = if (entryForAi > 0.0)
                        ((market.lastPrice - entryForAi) / entryForAi) * 100.0
                    else 0.0,
                    minutesInPosition = persistedForAi?.let {
                        ((System.currentTimeMillis() - it.createdAt).coerceAtLeast(0L) / 60_000L)
                    } ?: 0L
                )
            )
        } catch (e:Exception) {
            Log.w("OPENAI_MASTER_MIND", "Dynamic strategy failed for $normalizedAiSymbol: ${e.message}")
            null
        }
    } else null

    if (openAiDecision?.strategy != null) {
        val flowStatus = liveFlowForAi?.let {
            " • FLOW ${it.flowBias} • B ${"%.0f".format(Locale.US, it.buyerVolume)} / S ${"%.0f".format(Locale.US, it.sellerVolume)}"
        }.orEmpty()
        _pipeline.value = _pipeline.value.copy(
            orderStatus =
                "🧠 OpenAI strategy ${openAiDecision.strategy.strategyId} • " +
                    "${openAiDecision.action.name} • " +
                    "${"%.0f".format(Locale.US, openAiDecision.confidence)}%" +
                    flowStatus,
            lastUpdate = System.currentTimeMillis()
        )
    } else if (aiRefreshDue) {
        val keyConfigured = AiKeyStore.get(getApplication<Application>(), AiKeyStore.PROVIDER_OPENAI).isNotBlank()
        _pipeline.value = _pipeline.value.copy(
            orderStatus = if (keyConfigured)
                "🧠 OpenAI Master Mind unavailable • check API key/network"
            else
                "🧠 OpenAI Master Mind waiting • API key not configured",
            lastUpdate = System.currentTimeMillis()
        )
    }

    /*
     * AI #2 = OpenAI GPT ORDER BRAIN.
     * OpenAI Market AI never submits an order. GPT returns an action, then the existing
     * authenticated exchange router performs the real order with its own
     * balance/signature/IP/risk checks. Opposite AI directions fail safe.
     */
    var signalForOrder: TradingSignal = signal
    val marketAiBuy = marketAiAnalysis?.bias == OpenAiMarketIntelligenceEngine.Bias.BUY && (marketAiAnalysis?.confidence ?: 0.0) >= 60.0
    val marketAiSell = marketAiAnalysis?.bias == OpenAiMarketIntelligenceEngine.Bias.SELL && (marketAiAnalysis?.confidence ?: 0.0) >= 60.0

    if (openPositionForAi && openAiDecision?.action == MasterMindAutoStrategyEngine.Action.SELL && openAiDecision.confidence >= 60.0) {
        signalForOrder = TradingSignal.SELL("GPT ORDER BRAIN • ${openAiDecision.reason}", openAiDecision.confidence)
    } else if (!openPositionForAi && openAiDecision?.action == MasterMindAutoStrategyEngine.Action.BUY &&
        openAiDecision.confidence >= 70.0 && !openAiDecision.riskBlocked && !marketAiSell) {
        signalForOrder = TradingSignal.BUY("GPT ORDER BRAIN • ${openAiDecision.reason}", openAiDecision.confidence)
    } else if (!openPositionForAi && marketAiBuy && technicalConfidence >= 60.0 && signal !is TradingSignal.STRONG_SELL) {
        signalForOrder = TradingSignal.BUY("OpenAI Market AI + technical market confirmation", minOf(marketAiAnalysis!!.confidence, technicalConfidence))
    } else if (openPositionForAi && marketAiSell && technicalConfidence >= 55.0 && signal !is TradingSignal.STRONG_BUY) {
        signalForOrder = TradingSignal.SELL("OpenAI Market AI seller-flow confirmation", minOf(marketAiAnalysis!!.confidence, technicalConfidence))
    }

    val aiStatus = when {
        marketAiAnalysis != null && openAiDecision != null -> "🧠 DUAL AI • OpenAI Market ${marketAiAnalysis.bias.name} ${"%.0f".format(Locale.US, marketAiAnalysis.confidence)}% → GPT ${openAiDecision.action.name} ${"%.0f".format(Locale.US, openAiDecision.confidence)}%"
        marketAiAnalysis != null -> "🧠 OPENAI MARKET AI LIVE • GPT waiting for candidate/API"
        openAiDecision != null -> "🧠 GPT LIVE • Market AI unavailable"
        else -> "🟡 AI waiting • scanner continues locally"
    }
    _pipeline.value = _pipeline.value.copy(
        signal = signalText(signalForOrder),
        confidence = signalConfidence(signalForOrder),
        orderStatus = aiStatus,
        lastUpdate = System.currentTimeMillis()
    )

    // REAL mode requires both live mode and a verified exchange connection.
    if (_orderMode.value == OrderMode.REAL && (!_liveMode.value || !_isConnected.value)) {
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ REAL BLOCKED • unauthenticated",
            lastUpdate = System.currentTimeMillis()
        )
        return
    }

    when(signalForOrder){
        is TradingSignal.STRONG_BUY,is TradingSignal.BUY->{
            // Background position-manager mode must NEVER create a new BUY.
            // It only manages existing bot-owned positions/pending orders.
            if (!allowNewEntries) return

            // BUY signals now reach the existing REAL order router directly.
            // Do not add another gate here: Master/Signal decision has already
            // been resolved above, and the existing order-safety checks below
            // still prevent duplicate or unauthenticated orders.

            val open=_botTrades.value.any{it.symbol.equals(symbol,true)&&it.status.equals("OPEN",true)}
            val persistedOpen=loadBotOwnedPositions().any{
                it.symbol.equals(symbol,true) &&
                    it.quantity > 0.0
            }
            val pending=pendingRealOrders.containsKey(symbol)
            val buyQualityOk = isHighQualityBuyCandidate(market, indicators, signalForOrder)
            if(!buyQualityOk){
                _pipeline.value = _pipeline.value.copy(
                    orderStatus = "🛡️ BUY skipped • strategy quality filter",
                    lastUpdate = System.currentTimeMillis()
                )
                return
            }
            if(!open && !persistedOpen && !pending && canPlaceRealOrder()){
                if (!reserveHourlyOrderSlot()) {
                    _pipeline.value = _pipeline.value.copy(
                        orderStatus = "⏸️ 60m order cap reached • $symbol BUY waits",
                        lastUpdate = System.currentTimeMillis()
                    )
                    return
                }
                val orderJob=viewModelScope.launch{
                    try {
                        if(!_isBotRunning.value) return@launch

                        _pipeline.value = _pipeline.value.copy(
                            orderStatus = "🧠 AI MASTER MIND ACTIVE • REAL BUY CHECK • $symbol",
                            lastUpdate = System.currentTimeMillis()
                        )
                        val accepted=placeRealBuy(market,market.lastPrice)

                        // If the exchange reports FILLED, record the real
                        // trade even when STOP was pressed during the request.
                        // If the order is only OPEN/PENDING, STOP will cancel
                        // it in cancelDeltaBotOrdersOnStop().
                        if(accepted){
                            executeTrade(
                                "BUY",
                                market.lastPrice,
                                symbol,
                                actualFillPrice = lastRealFillPrice,
                                actualFilledSize = lastRealFilledSize,
                                actualCommission = lastRealCommission,
                                actualContractValue = lastRealContractValue,
                                actualNotionalType = lastRealNotionalType
                            )
                        }

                        refreshAutoExchangeBalance()
                    } finally {
                        realOrderJobs.remove(symbol)
                    }
                }
                realOrderJobs[symbol]=orderJob
            } else if(pending) {
                _pipeline.value=_pipeline.value.copy(
                    orderStatus="⏳ REAL ORDER PENDING • Delta",
                    lastUpdate=System.currentTimeMillis()
                )
            }
        }
        is TradingSignal.STRONG_SELL,is TradingSignal.SELL->{
            /*
             * PERMANENT DELTA SELL FIX:
             * The foreground bot can run in a different Android process from
             * the Activity. In that case _botTrades is an in-memory list and
             * can be empty even though this bot has a persisted/open Delta
             * position. SELL must therefore use the persisted bot-position
             * registry and, for Delta, close the REAL exchange position by
             * reading Delta's live position size first.
             */
            val normalizedSymbol=symbol.trim().uppercase(Locale.US)
            val open=_botTrades.value.firstOrNull{
                it.symbol.equals(normalizedSymbol,true) &&
                    it.status.equals("OPEN",true)
            }

            val persistedBotPosition=loadBotOwnedPositions().firstOrNull{
                it.exchange.equals("DeltaIndia",true) &&
                    it.symbol.equals(normalizedSymbol,true) &&
                    it.quantity > 0.0
            }

            val knownEntryPrice = open?.entryPrice ?: persistedBotPosition?.entryPrice ?: 0.0
            val currentGrossPnlPct = if (knownEntryPrice > 0.0 && market.lastPrice > 0.0) {
                ((market.lastPrice - knownEntryPrice) / knownEntryPrice) * 100.0
            } else 0.0

            // FIXED SELL RULE:
            // Never close a bot-owned BUY position below +3.0% gross profit.
            // -3%, 0%, +1%, +2.99% => HOLD.
            // +3.0% or higher => the existing profit manager/router may SELL.
            if ((open != null || persistedBotPosition != null) &&
                currentGrossPnlPct < 3.0
            ) {
                _pipeline.value = _pipeline.value.copy(
                    orderStatus = "🛡️ SELL blocked • $normalizedSymbol • P/L ${"%.2f".format(Locale.US, currentGrossPnlPct)}% • target +3.00%",
                    lastUpdate = System.currentTimeMillis()
                )
                return
            }

            // A real SELL signal must be allowed to close a bot-owned position
            // even when it is below the profit target. Waiting for a fixed profit
            // target can leave a weakening position open indefinitely and was the
            // main reason the bot accumulated losing positions without SELLs.
            // The fixed 3% target is handled by the dedicated profit manager;
            // this branch handles genuine strategy/AI SELL signals.
            if (open!=null || persistedBotPosition!=null){
                if(canPlaceRealOrder() && realOrderJobs[normalizedSymbol]?.isActive != true){
                    if (!reserveHourlyOrderSlot()) {
                        _pipeline.value = _pipeline.value.copy(
                            orderStatus = "⏸️ 60m order cap reached • $normalizedSymbol SELL waits",
                            lastUpdate = System.currentTimeMillis()
                        )
                        return
                    }
                    val orderJob=viewModelScope.launch{
                        try{
                            if(!_isBotRunning.value && positionManagerJob?.isActive != true) return@launch

                            val exchange=autoExchangeName.value
                            val credentials=loadRouterCredentials(exchange)
                            if(credentials==null){
                                _pipeline.value=_pipeline.value.copy(
                                    orderStatus="❌ $exchange SELL blocked — credentials missing",
                                    lastUpdate=System.currentTimeMillis()
                                )
                                return@launch
                            }

                            val result=if(exchange.equals("DeltaIndia",true)){
                                /*
                                 * Delta derivative position close:
                                 * fetch the REAL signed position size and send
                                 * the opposite reduce-only market order.
                                 * Do not use the UI/local quantity as the
                                 * contract size.
                                 */
                                ProfessionalExchangeRouter.closeDeltaBotPosition(
                                    credentials=credentials,
                                    symbol=normalizedSymbol,
                                    quantityHint=persistedBotPosition?.quantity
                                        ?: open?.quantity
                                        ?: 0.0,
                                    clientOrderId="NITINBOT-AUTO-SELL-${System.currentTimeMillis()}"
                                )
                            }else{
                                val qty=open?.quantity
                                    ?: persistedBotPosition?.quantity
                                    ?: 0.0

                                if(qty>0.0){
                                    ProfessionalExchangeRouter.placeOrder(
                                        exchange,
                                        credentials,
                                        RouterOrderRequest(
                                            symbol=market.market,
                                            side="SELL",
                                            quantity=qty,
                                            price=market.lastPrice,
                                            quoteAmount=0.0,
                                            clientOrderId="NITINBOT-AUTO-SELL-${System.currentTimeMillis()}"
                                        )
                                    )
                                }else{
                                    RouterOrderResult(
                                        false,
                                        exchange,
                                        status="REJECTED",
                                        message="No bot-owned quantity available for SELL"
                                    )
                                }
                            }

                            val filled=result.success && (
                                result.filled ||
                                    result.status.equals("closed",true) ||
                                    result.status.equals("filled",true)
                            )

                            lastRealOrderId=result.orderId

                            if(filled){
                                captureRealFill(result)
                                if(open!=null){
                                    closeTrade(
                                        open,
                                        if(result.averageFillPrice > 0.0) result.averageFillPrice else market.lastPrice,
                                        actualFilledSize = result.filledSize,
                                        exitCommission = result.paidCommission
                                    )
                                }else{
                                    // Service-process recovery path: there is
                                    // no in-memory BotTrade, so remove only
                                    // this bot's persisted ownership record.
                                    forgetBotPosition(exchange,normalizedSymbol)
                                }

                                pendingRealOrders.remove(normalizedSymbol)
                                publishPendingAutoOrders()

                                _pipeline.value=_pipeline.value.copy(
                                    signal="SELL",
                                    confidence=signalConfidence(signal),
                                    orderStatus="🟢 SELL FILLED • $exchange",
                                    lastUpdate=System.currentTimeMillis()
                                )
                            }else{
                                _pipeline.value=_pipeline.value.copy(
                                    signal="SELL",
                                    confidence=signalConfidence(signal),
                                    orderStatus="❌ SELL REJECTED • $exchange",
                                    lastUpdate=System.currentTimeMillis()
                                )
                            }

                            refreshAutoExchangeBalance()
                        }catch(e:Exception){
                            Log.e("AUTO_SELL","REAL SELL failed for $normalizedSymbol",e)
                            _pipeline.value=_pipeline.value.copy(
                                signal="SELL",
                                orderStatus="❌ SELL ERROR • $normalizedSymbol",
                                lastUpdate=System.currentTimeMillis()
                            )
                        }finally{
                            realOrderJobs.remove(normalizedSymbol)
                        }
                    }

                    realOrderJobs[normalizedSymbol]=orderJob
                }
            }
        }
        TradingSignal.HOLD->{
            _pipeline.value=_pipeline.value.copy(orderStatus="⏸ HOLD • No order",lastUpdate=System.currentTimeMillis())
        }
    }
}

private fun startPriceTicker(){
    viewModelScope.launch{
        while(isActive){
            try{fetchMarkets()}catch(e:Exception){Log.e("MARKET","Ticker error",e)}
            delay(5000)
        }
    }
}

private fun startEquityRecorder(){
    viewModelScope.launch{
        while(isActive){
            delay(5000)
            val value=_realBalance.value?:_portfolioValue.value
            val list=_equityHistory.value.toMutableList()
            list.add(value)
            while(list.size>100)list.removeAt(0)
            _equityHistory.value=list
        }
    }
}

private fun startCoinDCXPoller(){
    viewModelScope.launch{
        while(isActive){
            delay(15000)
            // Never overwrite the selected Delta LIVE account value with
            // CoinDCX's legacy balance endpoint.
            if(_isConnected.value && autoExchangeName.value.equals("CoinDCX",true)) {
                refreshRealBalance()
            }
        }
    }
}

private suspend fun fetchLogos(){
    try{
        val map=_logoMap.value.toMutableMap()
        val aiUniverse=mutableListOf<GeckoCoin>()
        // Existing 16 pages provide up to 4000 CoinGecko rows. Keep the
        // market-cap-ranked top 3000 for Master Mind AI research.
        for(page in 1..16){
            val coins=geckoLogoApi.getCoins(page=page)
            coins.forEach{coin->
                if(coin.symbol.isNotBlank()&&coin.image.isNotBlank()) {
                    map[coin.symbol.uppercase(Locale.US)]=coin.image
                }
                if(aiUniverse.size < 3000 && coin.symbol.isNotBlank()) {
                    aiUniverse += coin
                }
            }
            if(aiUniverse.size >= 3000) break
            delay(250)
        }
        _logoMap.value=map
        top3000AiMarkets.value=aiUniverse.take(3000)
        Log.d("AI_UNIVERSE","Loaded top ${top3000AiMarkets.value.size} markets for Master Mind AI")
        Log.d("LOGO","Loaded ${map.size} live CoinGecko logos")
    }catch(e:Exception){Log.e("LOGO","CoinGecko logo/AI universe load failed",e)}
}

// ============================================================
// LOGO HELPER
// ============================================================

private fun getLogoForSymbol(
    symbol: String
): String {
    val normalized =
        symbol.trim()
            .lowercase(Locale.US)

    return _logoMap.value[
        normalized
    ] ?: ""
}

// ============================================================
// INIT
// ============================================================


// ============================================================
// REAL ORDER MODELS😇
// ============================================================

data class CoinDCXOrderResponse(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("order_id")
    val orderId: String? = null,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("error")
    val error: String? = null
)


// ============================================================
// LIVE MODE
// ============================================================


// ============================================================
// BOT TRADE
// ============================================================

fun addBotTrade(trade: BotTrade) {
    // Only OPEN trades belong in the position list.
    val current = _botTrades.value
        .filter { it.status.equals("OPEN", true) }
        .toMutableList()

    current.removeAll {
        it.symbol.equals(trade.symbol, true)
    }
    current.add(0, trade.copy(status = "OPEN"))

    while (current.size > 100) {
        current.removeAt(current.lastIndex)
    }

    _botTrades.value = current
    updateMarketStats(closedTradeHistory)
}

// ============================================================
// PERSISTED BOT-OWNED POSITION REGISTRY
// ============================================================

private val botPositionPrefsKey = "bot_owned_positions_v1"

private fun loadBotOwnedPositions():MutableList<BotPositionRecord> {
    val raw = prefs.getString(botPositionPrefsKey, "").orEmpty()
    if (raw.isBlank()) return mutableListOf()
    return try {
        val arr = org.json.JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val q = o.optDouble("quantity", 0.0)
                if (q <= 0.0) continue
                add(
                    BotPositionRecord(
                        exchange = o.optString("exchange", ""),
                        symbol = o.optString("symbol", ""),
                        side = o.optString("side", "BUY"),
                        quantity = q,
                        entryPrice = o.optDouble("entryPrice", 0.0),
                        orderId = o.optString("orderId", ""),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        contractValue = o.optDouble("contractValue", 0.0),
                        notionalType = o.optString("notionalType", "vanilla"),
                        entryCommission = o.optDouble("entryCommission", 0.0)
                    )
                )
            }
        }.toMutableList()
    } catch (e:Exception) {
        Log.w("BOT_POSITION", "Position registry parse failed: ${e.message}")
        mutableListOf()
    }
}

private fun saveBotOwnedPositions(list:List<BotPositionRecord>) {
    val arr = org.json.JSONArray()
    list.filter { it.symbol.isNotBlank() && it.quantity > 0.0 }.forEach { p ->
        arr.put(org.json.JSONObject().apply {
            put("exchange", p.exchange)
            put("symbol", p.symbol)
            put("side", p.side)
            put("quantity", p.quantity)
            put("entryPrice", p.entryPrice)
            put("orderId", p.orderId)
            put("createdAt", p.createdAt)
            put("contractValue", p.contractValue)
            put("notionalType", p.notionalType)
            put("entryCommission", p.entryCommission)
        })
    }
    prefs.edit().putString(botPositionPrefsKey, arr.toString()).apply()
}

private fun rememberBotPosition(
    exchange:String,
    symbol:String,
    side:String,
    quantity:Double,
    entryPrice:Double,
    orderId:String,
    contractValue:Double=0.0,
    notionalType:String="vanilla",
    entryCommission:Double=0.0
) {
    if (symbol.isBlank() || quantity <= 0.0) return
    val list = loadBotOwnedPositions()
    val key = symbol.trim().uppercase(Locale.US)
    val existing = list.indexOfFirst {
        it.exchange.equals(exchange, true) && it.symbol.equals(key, true)
    }
    val record = BotPositionRecord(
        exchange = exchange,
        symbol = key,
        side = side.uppercase(Locale.US),
        quantity = quantity,
        entryPrice = entryPrice,
        orderId = orderId,
        contractValue = contractValue,
        notionalType = notionalType,
        entryCommission = entryCommission
    )
    if (existing >= 0) list[existing] = record else list += record
    saveBotOwnedPositions(list)
}

private fun forgetBotPosition(exchange:String, symbol:String) {
    val key = symbol.trim().uppercase(Locale.US)
    saveBotOwnedPositions(
        loadBotOwnedPositions().filterNot {
            it.exchange.equals(exchange, true) && it.symbol.equals(key, true)
        }
    )
}

/**
 * Safe bot-stop cleanup. It closes only positions previously confirmed and
 * persisted as opened by this bot. It never calls Delta's close_all endpoint,
 * because that could close a user's manual position too.
 */
private suspend fun closeBotOwnedPositionsOnStop():Boolean {
    val records = loadBotOwnedPositions()
    if (records.isEmpty()) return true

    var allOk = true
    for (record in records.toList()) {
        val exchange = record.exchange.ifBlank { autoExchangeName.value }
        val credentials = loadRouterCredentials(exchange)
        if (credentials == null) {
            allOk = false
            _pipeline.value = _pipeline.value.copy(
                orderStatus = "⚠️ $exchange credentials unavailable • ${record.symbol} position not closed",
                lastUpdate = System.currentTimeMillis()
            )
            continue
        }

        val result = if (exchange.equals("DeltaIndia", true)) {
            ProfessionalExchangeRouter.closeDeltaBotPosition(
                credentials = credentials,
                symbol = record.symbol,
                quantityHint = record.quantity,
                clientOrderId = "NITINBOT-CLOSE-${System.currentTimeMillis()}"
            )
        } else {
            // For non-Delta spot adapters, an opposite market order closes the
            // bot-owned spot balance. Derivatives are intentionally not guessed.
            ProfessionalExchangeRouter.closeBotSpotPosition(
                exchangeName = exchange,
                credentials = credentials,
                record = record
            )
        }

        if (result.success && (result.filled || result.status.equals("closed", true) || result.status.equals("filled", true))) {
            forgetBotPosition(exchange, record.symbol)
            _pipeline.value = _pipeline.value.copy(
                orderStatus = "🟢 Bot position CLOSED • $exchange • ${record.symbol}",
                lastUpdate = System.currentTimeMillis()
            )
        } else {
            allOk = false
            _pipeline.value = _pipeline.value.copy(
                orderStatus = "❌ Bot position close failed • $exchange • ${record.symbol} • ${result.message}",
                lastUpdate = System.currentTimeMillis()
            )
        }
    }

    refreshAutoExchangeBalance()
    return allOk
}

private val closedTradeHistoryPrefsKey = "closed_bot_trade_history_v3_exchange_verified"

private fun loadClosedTradeHistory(): MutableList<BotTrade> {
    val raw = prefs.getString(closedTradeHistoryPrefsKey, "").orEmpty()
    if (raw.isBlank()) return mutableListOf()
    return try {
        val arr = org.json.JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val symbol = o.optString("symbol", "")
                val time = o.optString("time", "")
                if (symbol.isBlank() || time.isBlank()) continue
                add(BotTrade(
                    symbol = symbol,
                    type = o.optString("type", "BUY"),
                    entryPrice = o.optDouble("entryPrice", 0.0),
                    exitPrice = o.optDouble("exitPrice", 0.0),
                    quantity = o.optDouble("quantity", 0.0),
                    profitLoss = o.optDouble("profitLoss", 0.0),
                    time = time,
                    status = "CLOSED",
                    strategy = o.optString("strategy", "PRO"),
                    orderId = o.optString("orderId", ""),
                    exchange = o.optString("exchange", ""),
                    contractValue = o.optDouble("contractValue", 0.0),
                    notionalType = o.optString("notionalType", "vanilla"),
                    entryCommission = o.optDouble("entryCommission", 0.0),
                    exitCommission = o.optDouble("exitCommission", 0.0)
                ))
            }
        }.toMutableList()
    } catch (e:Exception) {
        Log.w("REAL_STATS", "Closed trade history parse failed: ${e.message}")
        mutableListOf()
    }
}

private fun saveClosedTradeHistory() {
    val arr = org.json.JSONArray()
    closedTradeHistory.take(5000).forEach { t ->
        arr.put(org.json.JSONObject().apply {
            put("symbol", t.symbol)
            put("type", t.type)
            put("entryPrice", t.entryPrice)
            put("exitPrice", t.exitPrice)
            put("quantity", t.quantity)
            put("profitLoss", t.profitLoss)
            put("time", t.time)
            put("status", "CLOSED")
            put("strategy", t.strategy)
            put("orderId", t.orderId)
            put("exchange", t.exchange)
            put("contractValue", t.contractValue)
            put("notionalType", t.notionalType)
            put("entryCommission", t.entryCommission)
            put("exitCommission", t.exitCommission)
        })
    }
    prefs.edit().putString(closedTradeHistoryPrefsKey, arr.toString()).apply()
}

private fun resetRealFill() {
    lastRealFillPrice = 0.0
    lastRealFilledSize = 0.0
    lastRealCommission = 0.0
    lastRealContractValue = 0.0
    lastRealNotionalType = "vanilla"
}

private fun captureRealFill(result: RouterOrderResult) {
    lastRealOrderId = result.orderId
    if (result.averageFillPrice > 0.0) lastRealFillPrice = result.averageFillPrice
    if (result.filledSize > 0.0) lastRealFilledSize = result.filledSize
    if (result.paidCommission.isFinite()) lastRealCommission = result.paidCommission
    if (result.contractValue > 0.0) lastRealContractValue = result.contractValue
    if (result.notionalType.isNotBlank()) lastRealNotionalType = result.notionalType
}

// ============================================================
// REAL TRADE RECORDING — exchange-confirmed only
// ============================================================

fun executeTrade(
    type: String,
    entryPrice: Double,
    symbol: String,
    actualFillPrice: Double = 0.0,
    actualFilledSize: Double = 0.0,
    actualCommission: Double = 0.0,
    actualContractValue: Double = 0.0,
    actualNotionalType: String = "vanilla"
): Boolean {
    if (symbol.isBlank() || entryPrice <= 0.0) return false

    // Never create a local trade without an exchange response.
    if (lastRealOrderId.isBlank()) {
        Log.w("REAL_ORDER", "Local trade rejected: no exchange order id")
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ Exchange order was not confirmed — local trade not created",
            lastUpdate = System.currentTimeMillis()
        )
        return false
    }

    val amount = _tradeAmount.value.toDoubleOrNull() ?: 0.0

    if (amount <= 0.0) {
        Log.w("REAL_ORDER", "Invalid/missing trade amount")
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ Trade amount missing — order skipped",
            lastUpdate = System.currentTimeMillis()
        )
        return false
    }

    val exchange = autoExchangeName.value
    val isDelta = exchange.equals("DeltaIndia", true)
    val realEntryPrice = if (actualFillPrice > 0.0) actualFillPrice else entryPrice
    val quantity = if (isDelta && actualFilledSize > 0.0) actualFilledSize else amount / realEntryPrice
    val contractValue = if (actualContractValue > 0.0) actualContractValue else lastRealContractValue
    val notionalType = actualNotionalType.ifBlank { lastRealNotionalType }
    val entryCommission = if (actualCommission.isFinite() && actualCommission != 0.0) actualCommission else lastRealCommission

    if (quantity <= 0.0 || !quantity.isFinite()) {
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ Invalid calculated quantity — order skipped",
            lastUpdate = System.currentTimeMillis()
        )
        return false
    }

    val normalizedType = type.trim().uppercase(Locale.US)

    val trade = BotTrade(
        symbol = symbol.trim().uppercase(Locale.US),
        type = normalizedType,
        entryPrice = realEntryPrice,
        exitPrice = 0.0,
        quantity = quantity,
        profitLoss = 0.0,
        time = System.currentTimeMillis().toString(),
        status = "OPEN",
        strategy = "PRO • RSI+MACD+EMA9/21/50/200+BB+ATR+Trend+Momentum",
        orderId = lastRealOrderId,
        exchange = exchange,
        contractValue = contractValue,
        notionalType = notionalType,
        entryCommission = entryCommission
    )

    addBotTrade(trade)
    rememberBotPosition(
        exchange = exchange,
        symbol = trade.symbol,
        side = trade.type,
        quantity = trade.quantity,
        entryPrice = trade.entryPrice,
        orderId = trade.orderId,
        contractValue = trade.contractValue,
        notionalType = trade.notionalType,
        entryCommission = trade.entryCommission
    )
    Log.d(
        "REAL_ORDER",
        "Exchange-confirmed trade recorded side=$normalizedType symbol=$symbol amount=$amount qty=$quantity orderId=${trade.orderId}"
    )
    return true
}

// ============================================================
// CLOSE TRADE
// ============================================================

fun closeTrade(
    trade: BotTrade,
    exitPrice: Double,
    actualFilledSize: Double = 0.0,
    exitCommission: Double = 0.0
) {
    if (exitPrice <= 0.0) return

    val current = _botTrades.value.firstOrNull {
        it.symbol.equals(trade.symbol, true) &&
            it.time == trade.time &&
            it.status.equals("OPEN", true)
    } ?: return

    val exchange = current.exchange.ifBlank { autoExchangeName.value }
    val quantityForPnl = if (actualFilledSize > 0.0) actualFilledSize else current.quantity
    val closeFee = if (exitCommission.isFinite() && exitCommission != 0.0) exitCommission else lastRealCommission

    val effectiveContractValue = if (current.contractValue > 0.0) current.contractValue else lastRealContractValue
    val effectiveNotionalType = current.notionalType.ifBlank { lastRealNotionalType }
    val grossPnl = if (exchange.equals("DeltaIndia", true) && effectiveContractValue > 0.0) {
        val cv = effectiveContractValue
        val longPnl = if (effectiveNotionalType.equals("inverse", true)) {
            quantityForPnl * cv * (exitPrice / current.entryPrice - 1.0)
        } else {
            quantityForPnl * cv * (exitPrice - current.entryPrice)
        }
        if (current.type.equals("BUY", true)) longPnl else -longPnl
    } else {
        when (current.type.uppercase(Locale.US)) {
            "BUY" -> (exitPrice - current.entryPrice) * quantityForPnl
            "SELL" -> (current.entryPrice - exitPrice) * quantityForPnl
            else -> 0.0
        }
    }

    val profitLoss = (grossPnl - current.entryCommission - closeFee)
        .let { if (it.isFinite()) it else 0.0 }

    val closed = current.copy(
        exitPrice = exitPrice,
        profitLoss = profitLoss,
        status = "CLOSED",
        exitCommission = closeFee,
        contractValue = effectiveContractValue,
        notionalType = effectiveNotionalType,
        time = System.currentTimeMillis().toString()
    )

    // Remove from the live-position list immediately. Keep the completed
    // trade in a separate history list so Total Profit/Trades remain intact.
    _botTrades.value = _botTrades.value.filterNot {
        it.symbol.equals(current.symbol, true) &&
            it.time == current.time
    }

    closedTradeHistory.add(0, closed)
    while (closedTradeHistory.size > 5000) {
        closedTradeHistory.removeAt(closedTradeHistory.lastIndex)
    }
    saveClosedTradeHistory()

    forgetBotPosition(exchange, trade.symbol)
    updateMarketStats(closedTradeHistory)

    // Refresh the REAL exchange wallet after a confirmed sell. Delta can
    // publish the realized cashflow a little after the order becomes closed,
    // so retry the official wallet endpoint without adding any local/fake P/L.
    viewModelScope.launch {
        repeat(5) { attempt ->
            delay(if (attempt == 0) 750L else 1_000L)
            refreshAutoExchangeBalance()
        }
    }
}

// ============================================================
// MARKET STATISTICS
// ============================================================

private fun calculateVerifiedClosedTradePnl(trade:BotTrade):Double? {
    if(!trade.status.equals("CLOSED",true)) return null
    if(trade.orderId.isBlank()) return null
    if(trade.exchange.isBlank()) return null
    if(!trade.entryPrice.isFinite() || trade.entryPrice<=0.0) return null
    if(!trade.exitPrice.isFinite() || trade.exitPrice<=0.0) return null
    if(!trade.quantity.isFinite() || trade.quantity<=0.0) return null

    val exchange=trade.exchange.trim()
    val gross=if(exchange.equals("DeltaIndia",true)) {
        if(!trade.contractValue.isFinite() || trade.contractValue<=0.0) return null

        val directional=
            if(trade.notionalType.equals("inverse",true)) {
                trade.quantity * trade.contractValue *
                    (trade.exitPrice / trade.entryPrice - 1.0)
            } else {
                trade.quantity * trade.contractValue *
                    (trade.exitPrice - trade.entryPrice)
            }

        if(trade.type.equals("BUY",true)) directional else -directional
    } else {
        val directional=when(trade.type.uppercase(Locale.US)) {
            "BUY" -> (trade.exitPrice-trade.entryPrice) * trade.quantity
            "SELL" -> (trade.entryPrice-trade.exitPrice) * trade.quantity
            else -> return null
        }
        directional
    }

    val fees=trade.entryCommission.coerceAtLeast(0.0) +
        trade.exitCommission.coerceAtLeast(0.0)

    val net=gross-fees
    return net.takeIf { it.isFinite() }
}

private fun adaptStrategyToVerifiedResults(verifiedClosed:List<Pair<BotTrade,Double>>) {
    // Auto-adaptation uses only exchange-confirmed closed trades.
    if(verifiedClosed.size < 5) return
    val recent=verifiedClosed.take(10)
    val wins=recent.count { it.second > 0.0 }
    val losses=recent.count { it.second < 0.0 }
    val current=_strategyConfig.value
    val lossPressure=losses >= 3 && losses > wins
    val strongPerformance=wins >= 5 && wins > losses
    val targetConfidence=when {
        lossPressure -> (current.minConfidence + 3.0).coerceAtMost(85.0)
        strongPerformance -> (current.minConfidence - 1.0).coerceAtLeast(60.0)
        else -> current.minConfidence
    }
    val targetThreshold=when {
        lossPressure -> (current.signalThreshold + 2).coerceAtMost(30)
        strongPerformance -> (current.signalThreshold - 1).coerceAtLeast(18)
        else -> current.signalThreshold
    }
    if(targetConfidence != current.minConfidence || targetThreshold != current.signalThreshold) {
        val updated=current.copy(minConfidence=targetConfidence, signalThreshold=targetThreshold)
        _strategyConfig.value=updated
        _strategyProfile.value=updated.profile
        prefs.edit()
            .putFloat("strategy_min_confidence", updated.minConfidence.toFloat())
            .putInt("strategy_signal_threshold", updated.signalThreshold)
            .apply()
        _pipeline.value=_pipeline.value.copy(
            orderStatus="🧠 AI ADAPTIVE STRATEGY • confidence=${updated.minConfidence.toInt()}% • threshold=${updated.signalThreshold}",
            lastUpdate=System.currentTimeMillis()
        )
    }
}

private fun updateMarketStats(
    trades:List<BotTrade>
) {
    // Only exchange-confirmed CLOSED trades are counted. P/L is rebuilt from
    // the recorded real fill prices/size/contract value instead of trusting an
    // old persisted profitLoss number, which prevents fake/inflated statistics.
    val verifiedClosed=trades.mapNotNull { trade ->
        calculateVerifiedClosedTradePnl(trade)?.let { pnl -> trade to pnl }
    }

    val winning=verifiedClosed.filter { it.second>0.0 }
    val losing=verifiedClosed.filter { it.second<0.0 }

    val totalProfit=winning.sumOf { it.second }
    val totalLoss=losing.sumOf { kotlin.math.abs(it.second) }
    val total=verifiedClosed.size

    val winRate=if(total>0) winning.size.toDouble()/total.toDouble()*100.0 else 0.0
    val lossRate=if(total>0) losing.size.toDouble()/total.toDouble()*100.0 else 0.0

    val profitFactor=when {
        totalLoss>0.0 -> totalProfit/totalLoss
        totalProfit>0.0 -> Double.POSITIVE_INFINITY
        else -> 0.0
    }

    // Count only exchange-confirmed trades. Each recorded trade represents
    // its filled entry side; when it is CLOSED, the opposite exit order is
    // also confirmed. This keeps BUY/SELL counts aligned with real fills.
    val allRecorded=trades + _botTrades.value
    val buyOrders=allRecorded.count { trade ->
        trade.type.equals("BUY", true)
    } + trades.count { trade ->
        trade.type.equals("SELL", true)
    }
    val sellOrders=allRecorded.count { trade ->
        trade.type.equals("SELL", true)
    } + trades.count { trade ->
        trade.type.equals("BUY", true)
    }

    val avgProfit=if(winning.isNotEmpty()) totalProfit/winning.size else 0.0
    val avgLoss=if(losing.isNotEmpty()) totalLoss/losing.size else 0.0

    adaptStrategyToVerifiedResults(verifiedClosed)

    _marketStats.value=MarketStats(
        totalTrades=total,
        winningTrades=winning.size,
        losingTrades=losing.size,
        buyOrders=buyOrders,
        sellOrders=sellOrders,
        totalProfit=if(totalProfit.isFinite()) totalProfit else 0.0,
        totalLoss=if(totalLoss.isFinite()) totalLoss else 0.0,
        winRate=winRate,
        lossRate=lossRate,
        profitFactor=if(profitFactor.isFinite()) profitFactor else profitFactor,
        avgProfit=if(avgProfit.isFinite()) avgProfit else 0.0,
        avgLoss=if(avgLoss.isFinite()) avgLoss else 0.0
    )
}

// ============================================================
// REAL AUTHENTICATED COINDCX
// ============================================================

private suspend fun authenticatedPost(
    path: String,
    body: String
): String? {
    return try {
        val apiKey = decrypt(
            prefs.getString("cdc_api_key", "") ?: ""
        ).trim()

        val secret = decrypt(
            prefs.getString("cdc_secret_key", "") ?: ""
        ).trim()

        if (apiKey.isBlank() || secret.isBlank()) {
            _isConnected.value = false
            _connectStatus.value = "❌ API key/secret missing"
            return null
        }

        val signature = javax.crypto.Mac
            .getInstance("HmacSHA256")
            .apply {
                init(
                    javax.crypto.spec.SecretKeySpec(
                        secret.toByteArray(Charsets.UTF_8),
                        "HmacSHA256"
                    )
                )
            }
            .doFinal(
                body.toByteArray(Charsets.UTF_8)
            )
            .joinToString("") {
                "%02x".format(it)
            }

        val requestBody =
            body.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request =
            okhttp3.Request.Builder()
                .url(
                    "https://api.coindcx.com$path"
                )
                .post(requestBody)
                .addHeader(
                    "Content-Type",
                    "application/json"
                )
                .addHeader(
                    "X-AUTH-APIKEY",
                    apiKey
                )
                .addHeader(
                    "X-AUTH-SIGNATURE",
                    signature
                )
                .build()

        httpClient
            .newCall(request)
            .execute()
            .use { response ->

                val responseText =
                    response.body?.string()
                        ?: ""

                if (!response.isSuccessful) {
                    Log.e(
                        "COINDCX_AUTH",
                        "HTTP ${response.code}: $responseText"
                    )

                    _connectStatus.value =
                        "❌ Exchange HTTP ${response.code}"

                    return null
                }

                responseText
            }

    } catch (e: Exception) {

        Log.e(
            "COINDCX_AUTH",
            "Authenticated request failed",
            e
        )

        _connectStatus.value =
            "❌ ${e.message ?: "Authentication error"}"

        null
    }
}

// ============================================================
// SERVER TIME
// ============================================================

private suspend fun getServerTime(): Long {
    return try {
        val request =
            okhttp3.Request.Builder()
                .url(
                    "https://api.coindcx.com/exchange/ticker"
                )
                .get()
                .build()

        httpClient
            .newCall(request)
            .execute()
            .use {
                System.currentTimeMillis()
            }

    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}

// ============================================================
// REAL BALANCE
// ============================================================

private suspend fun fetchRealBalances(): Boolean {

    val timestamp =
        getServerTime()

    val body =
        """{"timestamp":$timestamp}"""

    val response =
        authenticatedPost(
            "/exchange/v1/users/balances",
            body
        )
            ?: return false

    return try {

        val json =
            org.json.JSONArray(response)

        if (json.length() == 0) {
            _realBalance.value = 0.0
            return true
        }

        var totalBalance = 0.0
        val parsedBalances = mutableListOf<Balance>()

        for (i in 0 until json.length()) {
            val item=json.getJSONObject(i)
            val currency=item.optString("currency","")
            val balance=item.optDouble("balance",0.0)
            val locked=item.optDouble("locked_balance",0.0)
            parsedBalances += Balance(currency,balance,locked)
            Log.d("REAL_BALANCE","$currency balance=$balance locked=$locked")
            if(currency.equals("INR",ignoreCase=true)) totalBalance += balance
        }

        _balances.value=parsedBalances
        _realBalance.value=totalBalance
        _portfolioValue.value=totalBalance

        true

    } catch (e: Exception) {

        Log.e(
            "REAL_BALANCE",
            "Balance parse failed",
            e
        )

        false
    }
}

// ============================================================
// REAL USER AUTHENTICATION
// ============================================================

private suspend fun authenticateRealAccount(): Boolean {

    val timestamp =
        getServerTime()

    val body =
        """{"timestamp":$timestamp}"""

    val response =
        authenticatedPost(
            "/exchange/v1/users/info",
            body
        )
            ?: return false

    return try {

        val json =
            org.json.JSONArray(response)

        if (json.length() == 0) {
            _isConnected.value = false
            _connectStatus.value =
                "❌ User authentication failed"
            return false
        }

        val user =
            json.getJSONObject(0)

        val userId =
            user.optString(
                "coindcx_id",
                ""
            )

        if (userId.isBlank()) {
            _isConnected.value = false
            _connectStatus.value =
                "❌ Invalid account response"
            return false
        }

        Log.d(
            "COINDCX_AUTH",
            "Authenticated user=$userId"
        )

        true

    } catch (e: Exception) {

        Log.e(
            "COINDCX_AUTH",
            "User authentication parse error",
            e
        )

        false
    }
}

// ============================================================
// CONNECT REAL COINDCX ACCOUNT
// ============================================================


// ============================================================
// MARKET SYMBOL FOR COINDCX ORDER
// ============================================================

private fun getOrderMarket(
    market: CryptoPrice
): String {

    val raw =
        market.market
            .trim()
            .uppercase(Locale.US)

    return when {

        raw.startsWith("B-") ||
        raw.startsWith("I-") -> {

            raw.substringAfter("-")
                .replace("_", "")
        }

        else -> {

            raw.replace("-", "")
                .replace("_", "")
        }
    }
}

// ============================================================
// REAL ORDER RESPONSE
// ============================================================

private fun isOrderAccepted(response:String?):Boolean{
    if(response.isNullOrBlank())return false
    return try{
        val json=org.json.JSONObject(response)
        if(json.has("success")&&!json.optBoolean("success"))return false
        val error=json.optString("error","")
        if(error.isNotBlank())return false
        val status=json.optString("status","").lowercase(Locale.US)
        if(status=="rejected"||status=="cancelled"||status=="partially_cancelled")return false
        val id=json.optString("id",json.optString("order_id",""))
        id.isNotBlank() || status in setOf("init","open","partially_filled","filled")
    }catch(e:Exception){
        Log.e("REAL_ORDER","Invalid exchange order response",e)
        false
    }
}

// ============================================================
// REAL BUY
// ============================================================

private suspend fun placeRealBuy(
    market: CryptoPrice,
    price: Double
): Boolean {
    val exchange=autoExchangeName.value
    val c=loadRouterCredentials(exchange)
    if(c==null){
        _pipeline.value=_pipeline.value.copy(
            orderStatus="❌ $exchange credentials missing",
            lastUpdate=System.currentTimeMillis()
        )
        return false
    }

    val amount=_tradeAmount.value.toDoubleOrNull() ?: 0.0
    if(amount<=0.0){
        _pipeline.value=_pipeline.value.copy(
            orderStatus="❌ Trade amount missing",
            lastUpdate=System.currentTimeMillis()
        )
        return false
    }

    val qty=amount/price
    if(qty<=0.0 || !qty.isFinite()) return false

    val clientId="NITINBOT-${System.currentTimeMillis()}"
    resetRealFill()
    val result=ProfessionalExchangeRouter.placeOrder(
        exchange,
        c,
        RouterOrderRequest(
            symbol=market.market,
            side="BUY",
            quantity=qty,
            price=price,
            quoteAmount=amount,
            clientOrderId=clientId
        )
    )

    lastRealOrderId=result.orderId
    if(result.filled || result.status.equals("closed",true)) captureRealFill(result)

    if(exchange.equals("DeltaIndia",true) &&
        result.orderId.isNotBlank() &&
        result.status.lowercase(Locale.US) in setOf("open","pending")
    ) {
        pendingRealOrders[market.symbol.trim().uppercase(Locale.US)] =
            PendingRealOrder(
                symbol=market.symbol.trim().uppercase(Locale.US),
                side="BUY",
                orderId=result.orderId,
                quantity=qty,
                price=price
            )
        publishPendingAutoOrders()

        _pipeline.value=_pipeline.value.copy(
            orderStatus="⏳ BUY PENDING • DeltaIndia",
            lastUpdate=System.currentTimeMillis()
        )
        return false
    }

    val filled =
        if(exchange.equals("DeltaIndia",true))
            result.filled || result.status.equals("closed",true)
        else
            result.success

    _pipeline.value=_pipeline.value.copy(
        orderStatus=if(filled)
            "🟢 BUY FILLED • $exchange"
        else
            "❌ BUY REJECTED • $exchange • ${result.message.ifBlank { result.status.ifBlank { "unfilled" } }}",
        lastUpdate=System.currentTimeMillis()
    )

    return filled
}

// ============================================================
// REAL SELL
// ============================================================

private suspend fun placeRealSell(
    market: CryptoPrice,
    quantity: Double,
    price: Double
): Boolean {
    val exchange=autoExchangeName.value
    val c=loadRouterCredentials(exchange)
    if(c==null){
        _pipeline.value=_pipeline.value.copy(
            orderStatus="❌ $exchange credentials missing",
            lastUpdate=System.currentTimeMillis()
        )
        return false
    }

    if(quantity<=0.0 || price<=0.0) return false

    val clientId="NITINBOT-${System.currentTimeMillis()}"
    resetRealFill()
    val result=ProfessionalExchangeRouter.placeOrder(
        exchange,
        c,
        RouterOrderRequest(
            symbol=market.market,
            side="SELL",
            quantity=quantity,
            price=price,
            quoteAmount=0.0,
            clientOrderId=clientId
        )
    )

    lastRealOrderId=result.orderId
    if(result.filled || result.status.equals("closed",true)) captureRealFill(result)

    if(exchange.equals("DeltaIndia",true) &&
        result.orderId.isNotBlank() &&
        result.status.lowercase(Locale.US) in setOf("open","pending")
    ) {
        pendingRealOrders[market.symbol.trim().uppercase(Locale.US)] =
            PendingRealOrder(
                symbol=market.symbol.trim().uppercase(Locale.US),
                side="SELL",
                orderId=result.orderId,
                quantity=quantity,
                price=price
            )
        publishPendingAutoOrders()

        _pipeline.value=_pipeline.value.copy(
            orderStatus="⏳ REAL SELL PENDING • DeltaIndia • order=${result.orderId}",
            lastUpdate=System.currentTimeMillis()
        )
        return false
    }

    val filled =
        if(exchange.equals("DeltaIndia",true))
            result.filled || result.status.equals("closed",true)
        else
            result.success

    _pipeline.value=_pipeline.value.copy(
        orderStatus=if(filled)
            "🟢 REAL SELL FILLED • $exchange • order=${result.orderId}"
        else
            "❌ REAL SELL REJECTED/UNFILLED • $exchange • ${result.message}",
        lastUpdate=System.currentTimeMillis()
    )

    return filled
}

// ============================================================
// DELTA PENDING ORDER RECONCILIATION
// ============================================================

private suspend fun reconcilePendingRealOrders() {
    val exchange=autoExchangeName.value
    if(!exchange.equals("DeltaIndia",true)) return

    val c=loadRouterCredentials(exchange) ?: return

    // Recover NITINBOT orders created before an Activity/service restart so
    // a pending BUY/SELL is never lost just because the in-memory map vanished.
    ProfessionalExchangeRouter.findBotOwnedOpenOrders(c).forEach { recovered ->
        pendingRealOrders.putIfAbsent(recovered.symbol, recovered)
    }
    publishPendingAutoOrders()

    if(pendingRealOrders.isEmpty()) return

    for((symbol,pending) in pendingRealOrders.toMap()) {
        val result=ProfessionalExchangeRouter.getOrderStatus(
            exchange,
            c,
            pending.orderId
        )

        val state=result.status.lowercase(Locale.US)

        when {
            result.filled || state=="closed" -> {
                pendingRealOrders.remove(symbol,pending)
                publishPendingAutoOrders()
                captureRealFill(result.copy(orderId = pending.orderId))
                lastRealOrderId=pending.orderId

                if(pending.side.equals("BUY",true)) {
                    val alreadyOpen=_botTrades.value.any{
                        it.symbol.equals(symbol,true) &&
                        it.status.equals("OPEN",true)
                    }
                    if(!alreadyOpen) {
                        executeTrade(
                            "BUY",
                            pending.price,
                            symbol,
                            actualFillPrice = result.averageFillPrice,
                            actualFilledSize = result.filledSize,
                            actualCommission = result.paidCommission,
                            actualContractValue = result.contractValue,
                            actualNotionalType = result.notionalType
                        )
                    }
                } else {
                    val open=_botTrades.value.firstOrNull{
                        it.symbol.equals(symbol,true) &&
                        it.status.equals("OPEN",true)
                    }
                    if(open!=null) {
                        closeTrade(
                            open,
                            if(result.averageFillPrice > 0.0) result.averageFillPrice else pending.price,
                            actualFilledSize = result.filledSize,
                            exitCommission = result.paidCommission
                        )
                    } else {
                        // Service/process recovery: do not drop a real exchange
                        // SELL from Total Profit just because the Activity VM was recreated.
                        val persisted = loadBotOwnedPositions().firstOrNull {
                            it.symbol.equals(symbol,true) &&
                                it.exchange.equals("DeltaIndia",true) &&
                                it.quantity > 0.0
                        }
                        if (persisted != null) {
                            val recovered = BotTrade(
                                symbol = symbol,
                                type = "BUY",
                                entryPrice = persisted.entryPrice,
                                exitPrice = if(result.averageFillPrice > 0.0) result.averageFillPrice else pending.price,
                                quantity = if(result.filledSize > 0.0) result.filledSize else persisted.quantity,
                                profitLoss = 0.0,
                                time = persisted.createdAt.toString(),
                                status = "OPEN",
                                strategy = "PRO • exchange-confirmed recovery",
                                orderId = persisted.orderId,
                                exchange = persisted.exchange,
                                contractValue = if(persisted.contractValue > 0.0) persisted.contractValue else result.contractValue,
                                notionalType = persisted.notionalType,
                                entryCommission = persisted.entryCommission
                            )
                            _botTrades.value = listOf(recovered)
                            closeTrade(
                                recovered,
                                if(result.averageFillPrice > 0.0) result.averageFillPrice else pending.price,
                                actualFilledSize = result.filledSize,
                                exitCommission = result.paidCommission
                            )
                        } else {
                            forgetBotPosition("DeltaIndia", symbol)
                        }
                    }
                }

                _pipeline.value=_pipeline.value.copy(
                    orderStatus="🟢 Delta order FILLED • order=${pending.orderId}",
                    lastUpdate=System.currentTimeMillis()
                )
                refreshAutoExchangeBalance()
            }

            state=="cancelled" || state=="rejected" || state=="error" -> {
                pendingRealOrders.remove(symbol,pending)
                publishPendingAutoOrders()
                _pipeline.value=_pipeline.value.copy(
                    orderStatus="⚠️ Delta order ${state.uppercase(Locale.US)} • order=${pending.orderId}",
                    lastUpdate=System.currentTimeMillis()
                )
            }

            // open/pending/network failure: KEEP it tracked.
            else -> {
                _pipeline.value=_pipeline.value.copy(
                    orderStatus="⏳ Delta order ${pending.orderId} • state=${if(state.isBlank())"UNKNOWN" else state}",
                    lastUpdate=System.currentTimeMillis()
                )
            }
        }
    }
}

private suspend fun cancelDeltaBotOrdersOnStop(): Boolean {
    val exchange=autoExchangeName.value
    if(!exchange.equals("DeltaIndia",true)) return true

    val c=loadRouterCredentials(exchange) ?: run {
        _pipeline.value=_pipeline.value.copy(
            orderStatus="⚠️ Delta credentials unavailable — pending order not cancelled",
            lastUpdate=System.currentTimeMillis()
        )
        return false
    }

    var allOk=true

    for((symbol,pending) in pendingRealOrders.toMap()) {
        val result=ProfessionalExchangeRouter.cancelOrder(
            exchange,
            c,
            pending.orderId
        )

        if(result.success) {
            pendingRealOrders.remove(symbol,pending)
            publishPendingAutoOrders()
            _pipeline.value=_pipeline.value.copy(
                orderStatus="🟢 Delta pending order CANCELLED • order=${pending.orderId}",
                lastUpdate=System.currentTimeMillis()
            )
        } else {
            allOk=false
            _pipeline.value=_pipeline.value.copy(
                orderStatus="❌ Delta cancel failed • order=${pending.orderId} • ${result.message}",
                lastUpdate=System.currentTimeMillis()
            )
        }
    }

    // Also sweep bot-owned OPEN/PENDING orders left by a previous app
    // session or an in-flight request. Manual Delta orders are untouched.
    val sweep=ProfessionalExchangeRouter.cancelBotOwnedOpenOrders(
        exchange,
        c
    )
    if(!sweep.success) allOk=false

    refreshAutoExchangeBalance()
    return allOk
}


// ============================================================
// REAL EXCHANGE AUTHENTICATION / PERMISSION PREFLIGHT
// ============================================================
//
// This does NOT manufacture or grant exchange permissions.
// The user must create the API key on the exchange and enable the
// permissions required by that exchange. The app verifies that the
// credentials are actually accepted by the exchange's authenticated
// account endpoint before the bot can start.
//
// A successful balance call proves authenticated account access.
// Trade permission is additionally enforced by the exchange when the
// signed order endpoint is called. The app never reports a trade as
// accepted unless the exchange adapter returns success.
// ============================================================

private suspend fun verifyRealExchangeAccess(): Boolean {
    val exchange = autoExchangeName.value.trim()
    if (exchange.isBlank()) {
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ Select an exchange before starting REAL bot",
            lastUpdate = System.currentTimeMillis()
        )
        return false
    }

    val credentials = loadRouterCredentials(exchange)
    if (credentials == null ||
        credentials.apiKey.isBlank() ||
        credentials.secret.isBlank()
    ) {
        _isConnected.value = false
        _liveMode.value = false
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ $exchange API credentials missing",
            lastUpdate = System.currentTimeMillis()
        )
        return false
    }

    val exchangeType = ProfessionalExchangeRouter.normalizeExchange(exchange)
    if (exchangeType == null) {
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ Unsupported exchange: $exchange",
            lastUpdate = System.currentTimeMillis()
        )
        return false
    }

    // Passphrase is mandatory for exchanges whose API model requires it.
    val needsPassphrase = exchangeType in setOf(
        ProfessionalExchange.OKX,
        ProfessionalExchange.BITGET,
        ProfessionalExchange.KUCOIN
    )
    if (needsPassphrase && credentials.passphrase.isBlank()) {
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ $exchange API passphrase is required",
            lastUpdate = System.currentTimeMillis()
        )
        return false
    }

    _pipeline.value = _pipeline.value.copy(
        orderStatus = "🔐 Authenticating $exchange…",
        lastUpdate = System.currentTimeMillis()
    )

    val balance = ProfessionalExchangeRouter.readBalance(exchange, credentials)

    if (!balance.success) {
        _isConnected.value = false
        _liveMode.value = false
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ $exchange authentication failed • ${balance.message}",
            lastUpdate = System.currentTimeMillis()
        )
        return false
    }

    _isConnected.value = true
    _liveMode.value = true
    _realBalance.value = balance.total
    _portfolioValue.value = balance.total
    _balances.value = balance.holdings.map {
        Balance(it.first, it.second, 0.0)
    }
    _connectStatus.value = "🟢 LIVE • $exchange • authenticated"
    startUnifiedLiveBalancePolling()
    _pipeline.value = _pipeline.value.copy(
        mode = OrderMode.REAL.name,
        orderStatus = "🟢 $exchange authenticated • REAL trading armed",
        lastUpdate = System.currentTimeMillis()
    )

    Log.i(
        "REAL_AUTH",
        "$exchange authenticated successfully; balance endpoint accepted credentials"
    )
    return true
}

// ============================================================
// FINAL BOT START
// ============================================================

fun startBot() {
    if (_isBotRunning.value) return

    val configuredAmount = _tradeAmount.value.toDoubleOrNull()
    if (configuredAmount == null || configuredAmount <= 0.0) {
        _pipeline.value = _pipeline.value.copy(
            orderStatus = "❌ BOT NOT STARTED • Trade Amount is $0.00 — enter a REAL USD amount first",
            lastUpdate = System.currentTimeMillis()
        )
        return
    }

    // Persist ON BEFORE any coroutine starts. The foreground service is now
    // the lifecycle owner, so Activity/Compose destruction cannot turn it off.
    prefs.edit().putBoolean("auto_bot_enabled", true).apply()
    _isBotRunning.value = true

    TradingBotService.start(getApplication<Application>())
    TradingBotService.scheduleWatchdog(getApplication<Application>())

    if (botJob?.isActive != true) {
        launchBotLoop()
    }
}

// ============================================================
// STOP BOT
// ============================================================

fun stopBot() {
    // OFF disables NEW BUY entries only. Keep the persistent position manager
    // alive so pending orders and open bot positions can still be reconciled
    // and closed using exchange-confirmed fills.
    prefs.edit()
        .putBoolean("auto_bot_enabled", false)
        .putBoolean("position_manager_enabled", true)
        .commit()
    _isBotRunning.value=false
    botJob?.cancel()
    botJob=null

    startPositionManagerFromService()
    TradingBotService.startPositionManager(getApplication<Application>())

    _pipeline.value=_pipeline.value.copy(
        orderStatus="⏹ New BUY OFF • 🧠 background position manager ON • pending orders / profit exits protected",
        lastUpdate=System.currentTimeMillis()
    )
}

// ============================================================
// TOGGLE BOT
// ============================================================

fun toggleBot() {

    if (_isBotRunning.value) {
        stopBot()
    } else {
        startBot()
    }
}

// ============================================================
// LIVE MODE
// ============================================================


// ============================================================
// FINAL CLEANUP
// ============================================================

override fun onCleared() {
    stopDeltaLiveMarketFlow()
    positionManagerJob?.cancel()
    positionManagerJob = null
    balanceJob?.cancel()
    externalBalancePollingJob?.cancel()

    // Activity/ViewModel recreation must not switch a persistent foreground bot OFF.
    // Only an explicit stopBot() clears auto_bot_enabled and cancels the runner.
    if(!prefs.getBoolean("auto_bot_enabled",false)){
        _isBotRunning.value=false
        _liveMode.value=false
        botJob?.cancel()
        realOrderJobs.values.forEach{it.cancel()}
        realOrderJobs.clear()
        pendingRealOrders.clear()
        publishPendingAutoOrders()
        botJob=null
        externalBalancePollingJob=null
    }

    super.onCleared()
}

    // ============================================================
    // EXTERNAL EXCHANGE LIVE BALANCE BRIDGE
    // ============================================================
    // Repository/ExchangeRegistry se aane wali live balance ko existing
    // dashboard balance state me merge karta hai. Existing functions ko
    // delete/replace nahi karta.
    private var externalBalancePollingJob: Job? = null

    fun setExternalLiveBalance(
        exchangeName: String,
        snapshot: com.example.mycompose.hello.ui.LiveBalanceSnapshot
    ) {
        setAutoTradingExchange(exchangeName)
        if (!snapshot.total.isFinite() || snapshot.total < 0.0) return

        _realBalance.value = snapshot.total
        _portfolioValue.value = snapshot.total

        val externalBalances = snapshot.holdings
            .filter { it.second.isFinite() && it.second > 0.0 }
            .map { (currency, amount) ->
                Balance(
                    currency = currency,
                    balance = amount,
                    lockedBalance = 0.0
                )
            }

        _balances.value = externalBalances

        _isConnected.value = true
        _liveMode.value = true
        _connectStatus.value = "✅ LIVE • $exchangeName • REAL BALANCE"

        _pipelineStatus.value =
            "🟢 ${exchangeName} • LIVE"

        // Do NOT overwrite pipeline.orderStatus here.
        // A balance refresh must never hide a real order ACCEPTED/REJECTED message.
        // The live balance state is already exposed through _pipelineStatus and _connectStatus.

        Log.d(
            "EXTERNAL_LIVE_BALANCE",
            "$exchangeName total=${snapshot.total} ${snapshot.currency}, holdings=${snapshot.holdings.size}"
        )
    }

    fun startExternalBalancePolling(
        exchangeName: String,
        intervalMs: Long = 15_000L,
        fetcher: suspend () -> com.example.mycompose.hello.ui.LiveBalanceSnapshot?
    ) {
        externalBalancePollingJob?.cancel()

        externalBalancePollingJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val snapshot = fetcher()

                    if (snapshot != null) {
                        setExternalLiveBalance(exchangeName, snapshot)
                    } else {
                        Log.w(
                            "EXTERNAL_LIVE_BALANCE",
                            "$exchangeName live balance refresh returned null"
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(
                        "EXTERNAL_LIVE_BALANCE",
                        "$exchangeName polling failed",
                        e
                    )
                }

                delay(intervalMs.coerceAtLeast(5_000L))
            }
        }
    }

    fun stopExternalBalancePolling() {
        externalBalancePollingJob?.cancel()
        externalBalancePollingJob = null
    }

    /**
     * Service-owned bot bridge. The foreground service uses one application
     * scoped ViewModel instance, independent of the Activity lifecycle.
     */
    fun isBotActuallyRunning(): Boolean = botJob?.isActive == true && _isBotRunning.value

    internal fun stopBotFromService() {
        prefs.edit()
            .putBoolean("auto_bot_enabled", false)
            .putBoolean("position_manager_enabled", false)
            .commit()
        _isBotRunning.value = false
        botJob?.cancel()
        botJob = null
        stopPositionManagerFromService()
    }


    companion object {
        @Volatile
        private var serviceInstance: TradingViewModel? = null

        fun getServiceInstance(application: Application): TradingViewModel {
            return serviceInstance ?: synchronized(this) {
                serviceInstance ?: TradingViewModel(application).also {
                    serviceInstance = it
                }
            }
        }
    }


}
