package com.example.mycompose.hello.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-screen TradingView Advanced Chart.
 *
 * Features:
 * - Real TradingView market data
 * - 1h default
 * - TradingView timeframe controls
 * - Zoom / pan / crosshair
 * - TradingView symbol search/change
 * - Dark theme
 * - TradingView chart settings
 * - XAU -> OANDA:XAUUSD
 * - Crypto -> BINANCE:<COIN>USDT
 *
 * Replace the existing CryptoChartActivity.kt with this file.
 */
class CryptoChartActivity : Activity() {

    private lateinit var webView: WebView

    private val inputSymbol: String by lazy {
        intent.getStringExtra(EXTRA_SYMBOL).orEmpty()
    }

    private val positionSide: String by lazy {
        intent.getStringExtra(EXTRA_SIDE).orEmpty().ifBlank { "BUY" }
    }

    private val positionEntry: Double by lazy {
        intent.getDoubleExtra(EXTRA_ENTRY_PRICE, 0.0)
    }

    private val positionQuantity: Double by lazy {
        intent.getDoubleExtra(EXTRA_QUANTITY, 0.0)
    }

    private val positionCurrent: Double by lazy {
        intent.getDoubleExtra(EXTRA_CURRENT_PRICE, 0.0)
    }

    private val positionUpl: Double by lazy {
        intent.getDoubleExtra(EXTRA_UPL, 0.0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(19, 23, 34)
        window.navigationBarColor = Color.rgb(19, 23, 34)

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(19, 23, 34))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                builtInZoomControls = false
                displayZoomControls = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = userAgentString + " Chrome/120.0.0.0 Mobile"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    view.evaluateJavascript(
                        "window.__tvNativeError && window.__tvNativeError(" +
                            "'" + (description ?: "WebView error").replace("'", "\\'") + "'" +
                        ");", null
                    )
                }
            }
            webChromeClient = WebChromeClient()
        }

        setContentView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        resolveTradingViewSymbol(inputSymbol) { resolved ->
            runOnUiThread { loadTradingViewChart(resolved) }
        }
    }

    /**
     * Resolves Binance symbols correctly.
     *
     * Binance spot uses BINANCE:BTCUSDT.
     * Binance perpetuals use BINANCE:BTCUSDT.P on TradingView.
     * Several newer/derivative-only coins do not have a Binance spot feed,
     * which was the reason the old code showed "This symbol doesn't exist".
     *
     * We check Binance spot first, then Binance futures. For known coins that
     * are not carried by Binance, use a TradingView exchange that actually
     * publishes that market.
     */
    private fun resolveTradingViewSymbol(value: String, done: (String) -> Unit) {
        val normalized = toTradingViewSymbol(value)

        // Explicit non-Binance symbols and commodities should never be rewritten.
        if (normalized.startsWith("OANDA:") ||
            normalized.startsWith("OKX:") ||
            normalized.startsWith("BYBIT:") ||
            normalized.startsWith("WHITEBIT:") ||
            normalized.startsWith("BITFINEX:") ||
            normalized.startsWith("GATEIO:") ||
            normalized.startsWith("MEXC:")
        ) {
            done(normalized)
            return
        }

        val basePair = normalized.substringAfter(":", normalized)
            .removeSuffix(".P")
            .uppercase(Locale.US)

        if (!normalized.startsWith("BINANCE:")) {
            done(normalized)
            return
        }

        // TradingView's feed availability is not identical to Binance's
        // exchangeInfo listing. Some pairs exist on Binance but only have a
        // TradingView perpetual/alternate-exchange feed. Resolve those known
        // pairs BEFORE trusting Binance REST exchangeInfo; otherwise the chart
        // can incorrectly open BINANCE:PAIR and show "This symbol doesn't exist".
        val known = knownTradingViewSymbol(basePair)
        if (known != null) {
            done(known)
            return
        }

        Thread {
            val resolved = try {
                val spot = binanceSymbolExists(
                    "https://api.binance.com/api/v3/exchangeInfo?symbol=$basePair"
                )
                if (spot) {
                    "BINANCE:$basePair"
                } else {
                    val futures = binanceSymbolExists(
                        "https://fapi.binance.com/fapi/v1/exchangeInfo?symbol=$basePair"
                    )
                    if (futures) {
                        "BINANCE:$basePair.P"
                    } else {
                        alternateTradingViewSymbol(basePair)
                    }
                }
            } catch (_: Exception) {
                // Network failure: use the deterministic mappings for the
                // Binance symbols known to be futures-only / alternate-feed.
                alternateTradingViewSymbol(basePair)
            }
            done(resolved)
        }.start()
    }

    private fun binanceSymbolExists(urlString: String): Boolean {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            useCaches = false
        }
        return try {
            connection.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            connection.disconnect()
        }
    }

    /**
     * TradingView has different exchange symbols for some coins.
     * These are real feeds and are used only when Binance has no spot/futures
     * market for the requested pair.
     */
    private fun knownTradingViewSymbol(pair: String): String? {
        return when (pair) {
            // Delta Exchange India publishes these perpetuals on TradingView
            // under the DELTAIN feed and USD perpetual naming.
            "HYPEUSDT" -> "DELTAIN:HYPEUSD.P"
            "XMRUSDT" -> "DELTAIN:XMRUSD.P"
            "IOUSDT", "IOUSD" -> "DELTAIN:IOUSD.P"

            // Keep exchange-specific feeds where TradingView has a known feed.
            "WBTUSDT" -> "WHITEBIT:WBTUSDT"
            "LEOUSDT" -> "OKX:LEOUSDT"
            else -> null
        }
    }

    private fun alternateTradingViewSymbol(pair: String): String {
        // When Binance does not expose a TradingView feed for the requested
        // pair, prefer Delta Exchange India's USD perpetual feed. This avoids
        // constructing a fake BINANCE:<pair>.P symbol that TradingView rejects.
        val base = pair
            .removeSuffix("USDT")
            .removeSuffix("USD")
            .removeSuffix("USDC")
            .trim()

        return if (base.isNotBlank()) {
            "DELTAIN:${base}USD.P"
        } else {
            "DELTAIN:BTCUSD.P"
        }
    }

    private fun loadTradingViewChart(tvSymbol: String) {
        webView.loadDataWithBaseURL(
            "https://www.tradingview.com/",
            buildHtml(tvSymbol, positionSide, positionEntry, positionQuantity, positionCurrent, positionUpl),
            "text/html",
            "UTF-8",
            "https://www.tradingview.com/"
        )
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SYMBOL =
            "com.example.mycompose.hello.ui.CRYPTO_CHART_SYMBOL"
        const val EXTRA_SIDE =
            "com.example.mycompose.hello.ui.CRYPTO_CHART_SIDE"
        const val EXTRA_ENTRY_PRICE =
            "com.example.mycompose.hello.ui.CRYPTO_CHART_ENTRY_PRICE"
        const val EXTRA_QUANTITY =
            "com.example.mycompose.hello.ui.CRYPTO_CHART_QUANTITY"
        const val EXTRA_CURRENT_PRICE =
            "com.example.mycompose.hello.ui.CRYPTO_CHART_CURRENT_PRICE"
        const val EXTRA_UPL =
            "com.example.mycompose.hello.ui.CRYPTO_CHART_UPL"

        fun open(
            context: Context,
            symbol: String,
            side: String = "BUY",
            entryPrice: Double = 0.0,
            quantity: Double = 0.0,
            currentPrice: Double = 0.0,
            unrealizedPnl: Double = 0.0
        ) {
            context.startActivity(
                Intent(context, CryptoChartActivity::class.java).apply {
                    putExtra(EXTRA_SYMBOL, symbol)
                    putExtra(EXTRA_SIDE, side)
                    putExtra(EXTRA_ENTRY_PRICE, entryPrice)
                    putExtra(EXTRA_QUANTITY, quantity)
                    putExtra(EXTRA_CURRENT_PRICE, currentPrice)
                    putExtra(EXTRA_UPL, unrealizedPnl)
                }
            )
        }

        /**
         * Converts the selected app coin/pair into the exact TradingView symbol.
         * Examples:
         * BTC -> BINANCE:BTCUSDT
         * BTC/USDT -> BINANCE:BTCUSDT
         * B-BTC_USDT -> BINANCE:BTCUSDT
         * XAU -> OANDA:XAUUSD
         * XAU/USD -> OANDA:XAUUSD
         */
        private fun toTradingViewSymbol(value: String): String {
            var raw = value.trim().uppercase(Locale.US)

            if (raw.isBlank()) return "BINANCE:BTCUSDT"

            // If the caller already supplied an exchange-qualified TradingView symbol,
            // keep the exchange and only normalize the pair.
            if (raw.contains(":")) {
                val exchange = raw.substringBefore(":").trim()
                var pair = raw.substringAfter(":").trim()

                // CoinDCX-style prefix can sometimes arrive as B-BTC_USDT.
                pair = pair
                    .replace("/", "")
                    .replace("-", "")
                    .replace("_", "")
                    .replace(".", "")

                if (exchange.isNotBlank() && pair.isNotBlank()) {
                    return "$exchange:$pair"
                }
            }

            // Normalize CoinDCX symbols first.
            // Examples:
            // B-BTC_USDT -> BTCUSDT
            // B-ETH_USDT -> ETHUSDT
            // B-BNB_USDT -> BNBUSDT
            raw = raw
                .trim()
                .removePrefix("B-")
                .removePrefix("B_")
                .removePrefix("B/")
                .replace("/", "")
                .replace("-", "")
                .replace("_", "")
                .replace(".", "")

            // Extra protection for inputs that have already lost the separator:
            // BBTCUSDT -> BTCUSDT.
            // Do NOT strip a leading B from normal BNB/BCH/etc.
            if (raw.startsWith("BBTC") && raw.endsWith("USDT")) {
                raw = raw.removePrefix("B")
            } else if (raw.startsWith("BETH") && raw.endsWith("USDT")) {
                raw = raw.removePrefix("B")
            } else if (raw.startsWith("BSOL") && raw.endsWith("USDT")) {
                raw = raw.removePrefix("B")
            }

            // Precious metals.
            if (raw == "XAU" || raw == "XAUUSD" || raw == "GOLD" || raw == "XAUUSDT") {
                return "OANDA:XAUUSD"
            }
            if (raw == "XAG" || raw == "XAGUSD" || raw == "SILVER" || raw == "XAGUSDT") {
                return "OANDA:XAGUSD"
            }

            // If the selected pair already has a supported quote, preserve the base.
            val base = when {
                raw.endsWith("USDT") -> raw.removeSuffix("USDT")
                raw.endsWith("USDC") -> raw.removeSuffix("USDC")
                raw.endsWith("USD") -> raw.removeSuffix("USD")
                raw.endsWith("INR") -> raw.removeSuffix("INR")
                else -> raw
            }

            if (base.isBlank()) return "BINANCE:BTCUSDT"

            return "BINANCE:${base}USDT"
        }

        private fun buildHtml(
            symbol: String,
            side: String,
            entryPrice: Double,
            quantity: Double,
            currentPrice: Double,
            unrealizedPnl: Double
        ): String {
            val safeSymbol = symbol
                .replace("\\", "\\\\")
                .replace("'", "\\'")
            val safeSide = side.replace("'", "\\'")
            val hasPosition = entryPrice > 0.0 && quantity > 0.0
            val safeEntry = if (entryPrice.isFinite()) entryPrice else 0.0
            val safeQty = if (quantity.isFinite()) quantity else 0.0
            val safeCurrent = if (currentPrice.isFinite()) currentPrice else 0.0
            val safeUpl = if (unrealizedPnl.isFinite()) unrealizedPnl else 0.0

            return """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
html,body,#tv_chart_container{margin:0;width:100%;height:100%;overflow:hidden;background:#131722;}
body{font-family:Arial,sans-serif;}
#loading{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;background:#131722;color:#9aa4b2;font-size:14px;z-index:10;}
</style>
</head>
<body>
<div id="loading">Loading live TradingView chart…</div>
<div id="tv_chart_container"></div>
<script>
(function(){
  var currentSymbol = '$safeSymbol';
  var botSide = '$safeSide';
  var botEntry = $safeEntry;
  var botQty = $safeQty;
  var botCurrent = $safeCurrent;
  var botUpl = $safeUpl;
  var hasBotPosition = ${hasPosition};
  var loaded = false;

  window.__tvNativeError = function(msg){
    var e=document.getElementById('loading');
    if(e && !loaded){
      e.innerHTML='TradingView chart failed to load.<br><small>'+String(msg || 'Network/WebView error')+'</small>';
      e.style.color='#ef5350';
    }
  };

  function hideLoading(){
    loaded=true;
    var e=document.getElementById('loading');
    if(e) e.style.display='none';
  }

  function addPositionOverlay(){
    // The external TradingView widget does not expose the chart object to the
    // host page, so keep the position information visible without breaking
    // chart initialization.
    if(!hasBotPosition || botEntry <= 0) return;
    var badge=document.createElement('div');
    badge.style.cssText='position:fixed;left:10px;top:10px;z-index:20;padding:7px 10px;border-radius:6px;background:rgba(19,23,34,.92);font:12px Arial;color:' + (botUpl>=0?'#26a69a':'#ef5350') + ';pointer-events:none;';
    badge.textContent='BOT '+botSide+' • Qty '+botQty+' • Entry '+botEntry+(botCurrent>0?' • Mark '+botCurrent:'')+' • UPL '+(botUpl>=0?'+':'')+Number(botUpl).toFixed(2);
    document.body.appendChild(badge);
  }

  function loadWidget(){
    var container=document.getElementById('tv_chart_container');
    if(!container) return;

    var script=document.createElement('script');
    script.type='text/javascript';
    script.src='https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js';
    script.async=true;
    script.onerror=function(){ window.__tvNativeError('TradingView widget script could not be downloaded'); };
    script.appendChild(document.createTextNode(JSON.stringify({
      autosize:true,
      width:'100%',
      height:'100%',
      symbol:currentSymbol,
      interval:'60',
      timezone:'Asia/Kolkata',
      theme:'dark',
      style:'1',
      locale:'en',
      withdateranges:true,
      hide_side_toolbar:false,
      allow_symbol_change:true,
      hide_top_toolbar:false,
      hide_legend:false,
      hide_volume:false,
      details:true,
      hotlist:false,
      calendar:false,
      save_image:false,
      support_host:'https://www.tradingview.com',
      studies:[
        'MAExp@tv-basicstudies',
        'MAExp@tv-basicstudies',
        'MAExp@tv-basicstudies',
        'MAExp@tv-basicstudies',
        'RSI@tv-basicstudies'
      ]
    })));
    container.appendChild(script);

    // Give the widget enough time to initialize, but never leave the user
    // stuck on the loading screen forever.
    setTimeout(function(){
      var iframe=container.querySelector('iframe');
      if(iframe){ hideLoading(); addPositionOverlay(); }
    },1200);
    setTimeout(function(){
      var iframe=container.querySelector('iframe');
      if(iframe){ hideLoading(); }
      else if(!loaded){ window.__tvNativeError('No TradingView frame was created'); }
    },10000);
  }

  if(document.readyState === 'loading'){
    document.addEventListener('DOMContentLoaded',loadWidget,{once:true});
  }else{
    loadWidget();
  }
})();
</script>
</body>
</html>
""".trimIndent()
        }
    }
}
