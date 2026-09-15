package com.example.mycompose.hello.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.mycompose.hello.viewmodel.CryptoPrice
import java.util.Locale

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProChartScreen(
    markets: List<CryptoPrice>,
    selectedMarket: CryptoPrice?,
    onMarketSelected: (CryptoPrice) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    var webViewRef by remember {
        mutableStateOf<WebView?>(null)
    }

    /*
     * ============================================================
     * TOP ASSET PRIORITY
     * ============================================================
     */
    val prioritySymbols = remember {
        listOf(
            "BTC",
            "ETH",
            "BNB",
            "SOL",
            "XRP",
            "XAU",
            "XAG",
            "WTI",
            "BRENT",
            "NG"
        )
    }

    val priorityMap = remember {
        prioritySymbols
            .mapIndexed { index, symbol ->
                symbol to index
            }
            .toMap()
    }

    /*
     * ============================================================
     * DEDUPLICATE + SORT MARKETS
     * ============================================================
     */
    val sortedMarkets = remember(markets) {

        markets
            .filter { market ->
                market.symbol.isNotBlank() &&
                    market.lastPrice > 0.0 &&
                    market.lastPrice.isFinite()
            }
            .groupBy { market ->
                market.symbol
                    .trim()
                    .uppercase(Locale.US)
            }
            .mapNotNull { entry ->
                entry.value.maxByOrNull { market ->
                    market.volume24h
                }
            }
            .sortedWith(
                compareBy<CryptoPrice> { market ->
                    priorityMap[
                        market.symbol
                            .trim()
                            .uppercase(Locale.US)
                    ] ?: 1000
                }.thenByDescending { market ->
                    market.volume24h
                }
            )
    }

    /*
     * ============================================================
     * MAIN SCREEN
     * ============================================================
     */
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                ComposeColor(
                    Color.rgb(10, 14, 18)
                )
            )
    ) {

        /*
         * ========================================================
         * HEADER
         * ========================================================
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 8.dp,
                    vertical = 5.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = "📊 PRO CHART",
                    color = ComposeColor.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Coins: ${sortedMarkets.size}",
                    color = ComposeColor.LightGray,
                    fontSize = 10.sp
                )
            }

            selectedMarket?.let { market ->

                Text(
                    text = market.symbol
                        .trim()
                        .uppercase(Locale.US),
                    color = ComposeColor.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (onClose != null) {

                TextButton(
                    onClick = onClose
                ) {

                    Text(
                        text = "×",
                        color = ComposeColor.White,
                        fontSize = 20.sp
                    )
                }
            }
        }

        /*
         * ========================================================
         * CHART AREA
         *
         * Left coin list is OVER the chart.
         * Therefore no Modifier.weight() is required.
         * ========================================================
         */
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {

            /*
             * ====================================================
             * WEBVIEW CHART
             * ====================================================
             */
            AndroidView(
                modifier = Modifier
                    .fillMaxSize(),

                factory = { context ->

                    WebView(context).apply {

                        setBackgroundColor(
                            Color.rgb(
                                10,
                                14,
                                18
                            )
                        )

                        settings.apply {

                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true

                            allowFileAccess = true
                            allowContentAccess = true

                            cacheMode =
                                WebSettings.LOAD_DEFAULT

                            builtInZoomControls = false
                            displayZoomControls = false

                            mediaPlaybackRequiresUserGesture =
                                false
                        }

                        webChromeClient =
                            WebChromeClient()

                        webViewClient =
                            WebViewClient()

                        addJavascriptInterface(
                            ChartBridge(
                                webView = this
                            ),
                            "AndroidChart"
                        )

                        loadUrl(
                            "file:///android_asset/pro_chart.html"
                        )

                        webViewRef = this
                    }
                },

                update = { view ->

                    webViewRef = view

                    val market =
                        selectedMarket
                            ?: return@AndroidView

                    val symbol =
                        market.market
                            .trim()
                            .uppercase(Locale.US)

                    val price =
                        market.lastPrice

                    if (
                        symbol.isBlank() ||
                        price <= 0.0 ||
                        !price.isFinite()
                    ) {
                        return@AndroidView
                    }

                    view.post {

                        view.evaluateJavascript(
                            """
                            if (window.ChartApp) {
                                ChartApp.setSymbol(
                                    ${jsString(symbol)}
                                );
                                ChartApp.updatePrice(
                                    $price
                                );
                            }
                            """.trimIndent(),
                            null
                        )
                    }
                }
            )

            /*
             * ====================================================
             * LEFT SMALL COIN PANEL
             * ====================================================
             */
            Column(
                modifier = Modifier
                    .width(78.dp)
                    .fillMaxHeight()
                    .background(
                        ComposeColor(
                            Color.rgb(
                                12,
                                17,
                                23
                            )
                        )
                    )
                    .padding(
                        start = 3.dp,
                        end = 3.dp
                    )
            ) {

                Text(
                    text = "${sortedMarkets.size} Coins",
                    color = ComposeColor.LightGray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        horizontal = 3.dp,
                        vertical = 4.dp
                    )
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement =
                        Arrangement.spacedBy(2.dp)
                ) {

                    items(
                        items = sortedMarkets,
                        key = { coin ->

                            coin.symbol
                                .trim()
                                .uppercase(Locale.US)
                        }
                    ) { coin ->

                        val selected =
                            coin.symbol.equals(
                                selectedMarket?.symbol,
                                ignoreCase = true
                            )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMarketSelected(coin)
                                }
                                .background(
                                    color =
                                        if (selected) {
                                            ComposeColor(
                                                Color.rgb(
                                                    35,
                                                    99,
                                                    235
                                                )
                                            )
                                        } else {
                                            ComposeColor(
                                                Color.rgb(
                                                    24,
                                                    30,
                                                    38
                                                )
                                            )
                                        },
                                    shape =
                                        RoundedCornerShape(
                                            4.dp
                                        )
                                )
                                .padding(
                                    horizontal = 4.dp,
                                    vertical = 4.dp
                                )
                        ) {

                            Column {

                                Text(
                                    text =
                                        coin.symbol
                                            .trim()
                                            .uppercase(
                                                Locale.US
                                            ),
                                    color =
                                        ComposeColor.White,
                                    fontSize = 9.sp,
                                    fontWeight =
                                        FontWeight.Bold,
                                    maxLines = 1
                                )

                                Text(
                                    text =
                                        formatChartPrice(
                                            coin.lastPrice
                                        ),
                                    color =
                                        ComposeColor.LightGray,
                                    fontSize = 7.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /*
     * ============================================================
     * SELECTED MARKET LIVE UPDATE
     * ============================================================
     */
    LaunchedEffect(
        selectedMarket?.market,
        selectedMarket?.lastPrice
    ) {

        val view =
            webViewRef
                ?: return@LaunchedEffect

        val market =
            selectedMarket
                ?: return@LaunchedEffect

        val symbol =
            market.market
                .trim()
                .uppercase(Locale.US)

        val price =
            market.lastPrice

        if (
            symbol.isBlank() ||
            price <= 0.0 ||
            !price.isFinite()
        ) {
            return@LaunchedEffect
        }

        view.post {

            view.evaluateJavascript(
                """
                if (window.ChartApp) {
                    ChartApp.setSymbol(
                        ${jsString(symbol)}
                    );
                    ChartApp.updatePrice(
                        $price
                    );
                }
                """.trimIndent(),
                null
            )
        }
    }

    /*
     * ============================================================
     * WEBVIEW CLEANUP
     * ============================================================
     */
    DisposableEffect(Unit) {

        onDispose {

            webViewRef?.apply {

                stopLoading()

                loadUrl(
                    "about:blank"
                )

                removeJavascriptInterface(
                    "AndroidChart"
                )

                destroy()
            }

            webViewRef = null
        }
    }
}

/*
 * ================================================================
 * UNIQUE PRICE FORMATTER
 *
 * IMPORTANT:
 * Do NOT create another formatPrice() in this file.
 * ================================================================
 */
private fun formatChartPrice(
    value: Double
): String {

    return when {

        value >= 1000.0 -> {
            String.format(
                Locale.US,
                "%.2f",
                value
            )
        }

        value >= 1.0 -> {
            String.format(
                Locale.US,
                "%.4f",
                value
            )
        }

        value > 0.0 -> {
            String.format(
                Locale.US,
                "%.8f",
                value
            )
        }

        else -> {
            "0"
        }
    }
}

/*
 * ================================================================
 * JAVASCRIPT STRING ESCAPER
 * ================================================================
 */
private fun jsString(
    value: String
): String {

    return "\"" +
        value
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
                "\\n"
            )
            .replace(
                "\r",
                "\\r"
            ) +
        "\""
}