package com.example.mycompose.hello.ui

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView

class ChartBridge(
    private val webView: WebView,
    private val onReady: () -> Unit = {},
    private val onTimeframeChanged:
        (String) -> Unit = {},
    private val onSymbolChanged:
        (String) -> Unit = {}
) {

    // =============================================================
    // CHART READY
    // =============================================================

    @JavascriptInterface
    fun onChartReady() {

        Log.d(
            "PRO_CHART",
            "Lightweight Charts ready"
        )

        webView.post {

            onReady()
        }
    }

    // =============================================================
    // TIMEFRAME
    // =============================================================

    @JavascriptInterface
    fun onTimeframeChanged(
        timeframe: String
    ) {

        val value =
            timeframe
                .trim()
                .lowercase()

        Log.d(
            "PRO_CHART",
            "Timeframe changed: $value"
        )

        webView.post {

            onTimeframeChanged(
                value
            )
        }
    }

    // =============================================================
    // SYMBOL
    // =============================================================

    @JavascriptInterface
    fun onSymbolChanged(
        symbol: String
    ) {

        val value =
            symbol
                .trim()
                .uppercase()

        Log.d(
            "PRO_CHART",
            "Symbol changed: $value"
        )

        webView.post {

            onSymbolChanged(
                value
            )
        }
    }

    // =============================================================
    // SET SYMBOL
    // =============================================================

    fun setSymbol(
        symbol: String
    ) {

        if (
            symbol.isBlank()
        ) {
            return
        }

        val safe =
            symbol
                .trim()
                .uppercase()
                .replace(
                    "\\",
                    "\\\\"
                )
                .replace(
                    "\"",
                    "\\\""
                )

        webView.post {

            webView.evaluateJavascript(
                """
                if (
                    window.ChartApp &&
                    typeof window.ChartApp.setSymbol === "function"
                ) {
                    window.ChartApp.setSymbol(
                        "$safe"
                    );
                }
                """.trimIndent(),
                null
            )
        }
    }

    // =============================================================
    // UPDATE LIVE PRICE
    // =============================================================

    fun updatePrice(
        price: Double
    ) {

        if (
            !price.isFinite() ||
            price <= 0.0
        ) {
            return
        }

        webView.post {

            webView.evaluateJavascript(
                """
                if (
                    window.ChartApp &&
                    typeof window.ChartApp.updatePrice === "function"
                ) {
                    window.ChartApp.updatePrice(
                        $price
                    );
                }
                """.trimIndent(),
                null
            )
        }
    }

    // =============================================================
    // LOAD HISTORICAL CANDLES
    //
    // Future:
    // TradingViewModel -> real OHLC -> this function
    // =============================================================

    fun setHistoricalCandles(
        json: String
    ) {

        if (json.isBlank()) {
            return
        }

        val safe =
            json
                .replace(
                    "\\",
                    "\\\\"
                )
                .replace(
                    "\"",
                    "\\\""
                )
                .replace(
                    "\n",
                    ""
                )
                .replace(
                    "\r",
                    ""
                )

        webView.post {

            webView.evaluateJavascript(
                """
                if (
                    window.ChartApp &&
                    typeof window.ChartApp.setHistoricalCandles === "function"
                ) {
                    window.ChartApp.setHistoricalCandles(
                        "$safe"
                    );
                }
                """.trimIndent(),
                null
            )
        }
    }

    // =============================================================
    // CLEAR CHART
    // =============================================================

    fun clearChart() {

        webView.post {

            webView.evaluateJavascript(
                """
                if (
                    window.ChartApp &&
                    typeof window.ChartApp.clearChart === "function"
                ) {
                    window.ChartApp.clearChart();
                }
                """.trimIndent(),
                null
            )
        }
    }
}