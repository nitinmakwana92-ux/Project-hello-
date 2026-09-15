package com.example.mycompose.hello.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.util.Locale

/**
 * Opens the TradingView live chart in the real Chrome browser.
 *
 * This is intentionally NOT a WebView. The previous WebView/TradingView
 * implementations can remain untouched. When the user taps "Open Live Chart",
 * this Activity sends the TradingView URL to Chrome.
 *
 * Intent extra:
 *   TradingViewChromeActivity.EXTRA_SYMBOL
 *
 * Example:
 *   BINANCE:BTCUSDT
 */
class TradingViewChromeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawSymbol = intent.getStringExtra(EXTRA_SYMBOL)
            ?.trim()
            .orEmpty()

        val symbol = normalizeSymbol(rawSymbol)

        openChrome(symbol)
        finish()
    }

    private fun openChrome(symbol: String) {
        val url = buildTradingViewUrl(symbol)

        // First choice: the actual Google Chrome package.
        val chromeIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(url)
        ).apply {
            setPackage(CHROME_PACKAGE)
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(chromeIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // Chrome is not installed/available. Fall through to the
            // user's normal browser instead of failing the chart button.
        }

        val browserIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(url)
        ).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        try {
            startActivity(browserIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No web browser is installed",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildTradingViewUrl(symbol: String): String {
        return Uri.Builder()
            .scheme("https")
            .authority("www.tradingview.com")
            .path("/chart/")
            .appendQueryParameter("symbol", symbol)
            .appendQueryParameter("interval", "15")
            .appendQueryParameter("theme", "dark")
            .appendQueryParameter("timezone", "Asia/Kolkata")
            .appendQueryParameter("hide_side_toolbar", "0")
            .appendQueryParameter("withdateranges", "1")
            .appendQueryParameter("hide_volume", "0")
            .appendQueryParameter("allow_symbol_change", "1")
            .build()
            .toString()
    }

    private fun normalizeSymbol(value: String): String {
        val cleaned = value
            .replace("\\", "")
            .replace("\"", "")
            .replace("'", "")
            .trim()
            .uppercase(Locale.US)

        if (cleaned.isBlank()) return DEFAULT_SYMBOL

        // If the caller already supplied EXCHANGE:PAIR, keep it.
        if (cleaned.contains(":")) return cleaned

        val compact = cleaned
            .replace("-", "")
            .replace("_", "")
            .replace("/", "")

        val base = when {
            compact.endsWith("USDT") -> compact.removeSuffix("USDT")
            compact.endsWith("USDC") -> compact.removeSuffix("USDC")
            compact.endsWith("USD") -> compact.removeSuffix("USD")
            compact.endsWith("INR") -> compact.removeSuffix("INR")
            else -> compact
        }

        if (base.isBlank()) return DEFAULT_SYMBOL

        // TradingView has broad Binance spot coverage.
        return "BINANCE:${base}USDT"
    }

    companion object {
        const val EXTRA_SYMBOL =
            "com.example.mycompose.hello.ui.TRADINGVIEW_SYMBOL"

        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val DEFAULT_SYMBOL = "BINANCE:BTCUSDT"

        /**
         * Helper for the existing Compose screen.
         *
         * Replace the old onChart action with:
         *
         * openTradingViewInChrome(context, tvSymbol)
         */
        fun openTradingViewInChrome(
            activity: Activity,
            symbol: String
        ) {
            val intent = Intent(
                activity,
                TradingViewChromeActivity::class.java
            ).apply {
                putExtra(EXTRA_SYMBOL, symbol)
            }

            activity.startActivity(intent)
        }
    }
}
