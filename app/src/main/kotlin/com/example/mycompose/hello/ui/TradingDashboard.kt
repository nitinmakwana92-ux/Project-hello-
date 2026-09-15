package com.example.mycompose.hello.ui

import com.example.mycompose.hello.Ai.AiKeyStore
import kotlinx.coroutines.isActive

import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.VpnKey
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import android.widget.Toast
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalView
import androidx.core.view.drawToBitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mycompose.hello.viewmodel.*
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradingDashboard(viewModel: TradingViewModel = viewModel()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🧞 Pro Nitin Makwana", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
        // ✅ Bottom navigation puri tarah hata diya
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            CoinGeckoMarketScreen(viewModel)
        }
    }
}

private val AlgoBotCardBorder = BorderStroke(1.dp, Color.White)

@Composable
private fun algoBotCardColors() =
    CardDefaults.cardColors(containerColor = Color.Transparent)

@Composable
fun BotDashboardScreen(viewModel: TradingViewModel) {
    val context = LocalContext.current
    val isRunning by viewModel.isBotRunning.collectAsState()
    val trades by viewModel.botTrades.collectAsState()
    val liveDeltaPositions by viewModel.liveDeltaPositions.collectAsState()
    val liveDeltaOpenOrders by viewModel.liveDeltaOpenOrders.collectAsState()
    val liveDeltaBuyCount = liveDeltaPositions.count { it.side.equals("LONG", true) }
    val liveDeltaSellCount = liveDeltaPositions.count { it.side.equals("SHORT", true) }
    val stats by viewModel.marketStats.collectAsState()
    val indicators by viewModel.currentIndicators.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val strategy by viewModel.strategyProfile.collectAsState()
    val scanner by viewModel.scannerStatus.collectAsState()
    val pipeline by viewModel.pipeline.collectAsState()
    val logoMap by viewModel.logoMap.collectAsState()
    var tradeToClose by remember { mutableStateOf<BotTrade?>(null) }
    var chatTrade by remember { mutableStateOf<BotTrade?>(null) }
    var chartTrade by remember { mutableStateOf<BotTrade?>(null) }
    
    LaunchedEffect(Unit) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            viewModel.refreshLiveDeltaPositionsNow()
            kotlinx.coroutines.delay(15_000L)
        }
    }
    var tpSlTrade by remember { mutableStateOf<BotTrade?>(null) }

    fun currentPriceOf(symbol: String): Double {
        val list = (uiState as? UiState.Success)?.markets ?: emptyList()
        return list.firstOrNull { it.symbol == symbol }?.lastPrice ?: 0.0
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        run {
            val openPL = trades.filter { it.status == "OPEN" }.sumOf { t ->
                val cp = currentPriceOf(t.symbol).let { if (it > 0.0) it else t.entryPrice }
                if (t.type == "BUY") (cp - t.entryPrice) * t.quantity else (t.entryPrice - cp) * t.quantity
            }
            val realized = stats.totalProfit - stats.totalLoss
            val pval = viewModel.portfolioValue.value
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = algoBotCardColors(),
                border = AlgoBotCardBorder
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("🏦 Portfolio", fontSize = 14.sp, color = Color.Gray)
                            if (isRunning) {
                                Text(
                                    "● AUTO BOT • LIVE • RUNNING",
                                    color = Color(0xFF00E676),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    "○ AUTO BOT • OFF",
                                    color = Color.Gray,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "$${String.format(Locale.US, "%.2f", pval)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            )
                            if (isRunning) {
                                Text(
                                    "LIVE ORDER ENGINE",
                                    color = Color(0xFF00E676),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Open P/L", fontSize = 11.sp, color = Color.Gray)
                            Text("${if (openPL >= 0) "+" else ""}$${String.format(Locale.US, "%.2f", openPL)}", color = if (openPL >= 0) Color(0xFF00E676) else Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Realized P/L", fontSize = 11.sp, color = Color.Gray)
                            Text("${if (realized >= 0) "+" else ""}$${String.format(Locale.US, "%.2f", realized)}", color = if (realized >= 0) Color(0xFF00E676) else Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        val tradeAmount by viewModel.tradeAmount.collectAsState()
        var usdInrRate by remember { mutableStateOf<Double?>(null) }

        LaunchedEffect(Unit) {
            while (true) {
                usdInrRate = withContext(Dispatchers.IO) {
                    runCatching {
                        val connection = (URL("https://open.er-api.com/v6/latest/USD").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 7000
                            readTimeout = 7000
                        }
                        try {
                            if (connection.responseCode !in 200..299) return@runCatching null
                            val body = connection.inputStream.bufferedReader().use { it.readText() }
                            org.json.JSONObject(body)
                                .optJSONObject("rates")
                                ?.optDouble("INR", 0.0)
                                ?.takeIf { it > 0.0 }
                        } finally {
                            connection.disconnect()
                        }
                    }.getOrNull()?.takeIf { it > 0.0 && it.isFinite() }
                }
                kotlinx.coroutines.delay(60_000L)
            }
        }

        val amountUsd = tradeAmount.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: 0.0
        val amountInr = usdInrRate?.let { amountUsd * it }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = algoBotCardColors(),
            border = AlgoBotCardBorder
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text("Order Mode", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "🔴 REAL • LIVE EXCHANGE ORDERS",
                    color = Color(0xFFFF5252),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(5.dp))

                OutlinedTextField(
                    value = tradeAmount,
                    onValueChange = {
                        if (it.length <= 12 && it.all { ch -> ch.isDigit() || ch == '.' }) {
                            viewModel.setTradeAmount(it)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    label = { Text("Amount (USD)", fontSize = 11.sp) },
                    leadingIcon = { Text("$", fontWeight = FontWeight.Bold) },
                    placeholder = { Text("300.00", fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )

                Spacer(Modifier.height(5.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "LIVE USD  $${String.format(Locale.US, "%.2f", amountUsd)}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (amountInr != null)
                            "LIVE INR  ₹${String.format(Locale.US, "%.2f", amountInr)}"
                        else
                            "LIVE INR  --",
                        color = Color(0xFF00E676),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    if (usdInrRate != null)
                        "USD/INR live rate: ₹${String.format(Locale.US, "%.2f", usdInrRate)}"
                    else
                        "USD/INR live rate: loading…",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Text(
                    "⚠️ REAL uses the selected exchange API + trading permission.",
                    color = Color(0xFFFFB74D),
                    fontSize = 9.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleBot() },
            colors = algoBotCardColors(),
            border = AlgoBotCardBorder
        ) {
            Row(modifier = Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Trading Bot", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(text = if (isRunning) "🟢 Running • ${strategy.name} • multi-coin live scanner" else "⚪ Stopped", color = if (isRunning) Color(0xFF00E676) else Color.Gray)
                    Text(scanner, color = Color.Gray, fontSize = 11.sp)
                    Text("Signal: ${pipeline.signal} • ${String.format(Locale.US, "%.0f", pipeline.confidence)}% • ${pipeline.orderStatus}", color = Color(0xFFB8B8C2), fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = isRunning,
                    onCheckedChange = { viewModel.toggleBot() },
                    colors = SwitchDefaults.colors(
                        uncheckedThumbColor = Color(0xFFBDBDBD),
                        uncheckedTrackColor = Color.Transparent,
                        uncheckedBorderColor = Color.White,
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF00C853),
                        checkedBorderColor = Color.White
                    )
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                "Profit %",
                "${String.format(Locale.US, "%.1f", stats.winRate)}%",
                Modifier.weight(1f),
                Color(0xFF00E676)
            )
            StatCard(
                "Loss %",
                "${String.format(Locale.US, "%.1f", stats.lossRate)}%",
                Modifier.weight(1f),
                Color(0xFFFF5252)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Total Trades", "${stats.totalTrades}", Modifier.weight(1f))
            StatCard(
                "Winning / Losing",
                "${stats.winningTrades} / ${stats.losingTrades}",
                Modifier.weight(1f)
            )
        }

        Text("LIVE Delta position counts • BUY = LONG • SELL = SHORT", color = Color.Gray, fontSize = 9.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("BOT BUY Orders", "${liveDeltaBuyCount}", Modifier.weight(1f), Color(0xFF00E676))
            StatCard("BOT SELL Orders", "${liveDeltaSellCount}", Modifier.weight(1f), Color(0xFFFF5252))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Total Profit", "$${String.format(Locale.US, "%.2f", stats.totalProfit)}", Modifier.weight(1f), Color(0xFF00E676))
            StatCard("Total Loss", "$${String.format(Locale.US, "%.2f", stats.totalLoss)}", Modifier.weight(1f), Color(0xFFFF5252))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Profit Factor", String.format(Locale.US, "%.2f", stats.profitFactor), Modifier.weight(1f))
            StatCard("Avg Profit", "$${String.format(Locale.US, "%.2f", stats.avgProfit)}", Modifier.weight(1f), Color(0xFF00E676))
        }


        if (liveDeltaOpenOrders.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = algoBotCardColors(),
                border = AlgoBotCardBorder
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⏳ LIVE Delta Pending Orders", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${liveDeltaOpenOrders.size}", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    liveDeltaOpenOrders.take(50).forEach { o ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${o.symbol} • ${o.side} • ${o.state.uppercase(Locale.US)}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Qty ${String.format(Locale.US, "%.4f", o.quantity)} • Unfilled ${String.format(Locale.US, "%.4f", o.unfilledQuantity)} • Order ${o.orderId}",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            if (o.price > 0.0) {
                                Text(
                                    String.format(Locale.US, "%.6f", o.price),
                                    color = Color(0xFFFFA726),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    if (liveDeltaOpenOrders.size > 50) {
                        Text("+ ${liveDeltaOpenOrders.size - 50} more pending orders", color = Color.Gray, fontSize = 11.sp)
                    }
                    Text(
                        "LIVE exchange data • visible whether bot is ON or OFF",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = algoBotCardColors(),
            border = AlgoBotCardBorder
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("📊 Live Indicators", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                IndicatorRow("RSI (14)", String.format(Locale.US, "%.2f", indicators.rsi), if (indicators.rsi < 30) Color(0xFF00E676) else if (indicators.rsi > 70) Color(0xFFFF5252) else Color.Gray)
                IndicatorRow("MACD", String.format(Locale.US, "%.4f", indicators.macd), if (indicators.macd > indicators.macdSignal) Color(0xFF00E676) else Color(0xFFFF5252))
                IndicatorRow("MACD Signal", String.format(Locale.US, "%.4f", indicators.macdSignal), Color.Gray)
                IndicatorRow("EMA 9", String.format(Locale.US, "%.2f", indicators.ema9), Color(0xFF2196F3))
                IndicatorRow("EMA 21", String.format(Locale.US, "%.2f", indicators.ema21), Color(0xFFFF9800))
                IndicatorRow("EMA 50", String.format(Locale.US, "%.2f", indicators.ema50), Color(0xFF9C27B0))
                IndicatorRow("EMA 200", String.format(Locale.US, "%.2f", indicators.ema200), Color(0xFF607D8B))
                IndicatorRow("Bollinger Upper", String.format(Locale.US, "%.2f", indicators.bollingerUpper), Color(0xFF00BCD4))
                IndicatorRow("Bollinger Lower", String.format(Locale.US, "%.2f", indicators.bollingerLower), Color(0xFF00BCD4))
                IndicatorRow("ATR (14)", String.format(Locale.US, "%.4f", indicators.atr), Color.Gray)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = algoBotCardColors(),
            border = AlgoBotCardBorder
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("📈 Equity Curve", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    val eq = viewModel.equityHistory.value
                    val cur = eq.lastOrNull() ?: viewModel.portfolioValue.value
                    val first = eq.firstOrNull() ?: cur
                    val chg = if (first > 0.0) (cur - first) / first * 100.0 else 0.0
                    Text("${if (chg >= 0.0) "+" else ""}${String.format(Locale.US, "%.2f", chg)}%", color = if (chg >= 0.0) Color(0xFF00E676) else Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(105.dp)) {
                    val data = viewModel.equityHistory.value
                    if (data.size >= 2) {
                        val minV = data.min()
                        val maxV = data.max()
                        val range = if (maxV - minV > 0.0) maxV - minV else 1.0
                        val w = size.width
                        val h = size.height
                        val pad = 10f
                        val n = data.size
                        for (i in 1 until n) {
                            val x1 = ((i - 1).toFloat() / (n - 1)) * w
                            val x2 = (i.toFloat() / (n - 1)) * w
                            val y1 = h - pad - (((data[i - 1] - minV) / range) * (h - 2 * pad)).toFloat()
                            val y2 = h - pad - (((data[i] - minV) / range) * (h - 2 * pad)).toFloat()
                            drawLine(color = Color(0xFF00E676), start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 5f)
                        }
                    }
                }
                if (viewModel.equityHistory.value.size < 2) {
                    Text("Chart ban raha hai... Bot ON rakho 10-15 sec", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = algoBotCardColors(),
            border = AlgoBotCardBorder
        ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🟢 LIVE Delta Positions • EXCHANGE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${liveDeltaPositions.size}", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(5.dp))
                    liveDeltaPositions.take(50).forEach { p ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${p.symbol} • ${p.side}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Qty ${String.format(Locale.US, "%.4f", p.quantity)} • Entry ${String.format(Locale.US, "%.6f", p.entryPrice)} • Mark ${String.format(Locale.US, "%.6f", p.markPrice)}",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                String.format(Locale.US, "%+.4f", p.unrealizedPnl),
                                color = if (p.unrealizedPnl >= 0.0) Color(0xFF00E676) else Color(0xFFFF5252),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (liveDeltaPositions.size > 50) {
                        Text("+ ${liveDeltaPositions.size - 50} more live positions", color = Color.Gray, fontSize = 10.sp)
                    }
                    if (liveDeltaPositions.isEmpty()) {
                        Text("No live Delta positions", color = Color.Gray, fontSize = 10.sp)
                    }
                    Text(
                        "LIVE exchange data • display only • manual positions are not bot-owned",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }
        }
    if (chatTrade != null) {
        LiveChatDialog(
            trade = chatTrade!!,
            onDismiss = { chatTrade = null }
        )
    }

    if (chartTrade != null) {
        LivePriceChartDialog(
            trade = chartTrade!!,
            currentPriceOf = ::currentPriceOf,
            onDismiss = { chartTrade = null }
        )
    }

    if (tpSlTrade != null) {
        TpSlScreenshotDialog(
            trade = tpSlTrade!!,
            onDismiss = { tpSlTrade = null }
        )
    }

    if (tradeToClose != null) {
        val t = tradeToClose!!
        val curPrice = currentPriceOf(t.symbol).let { if (it > 0.0) it else t.entryPrice }
        val estimatedPL = when (t.type) {
            "BUY" -> (curPrice - t.entryPrice) * t.quantity
            "SELL" -> (t.entryPrice - curPrice) * t.quantity
            else -> 0.0
        }
        AlertDialog(
            onDismissRequest = { tradeToClose = null },
            title = { Text("Close ${t.type} ${t.symbol}?") },
            text = {
                Column {
                    Text("Entry Price: $${String.format(Locale.US, "%.4f", t.entryPrice)}", fontSize = 13.sp)
                    Text("Current Price: $${String.format(Locale.US, "%.4f", curPrice)}", fontSize = 13.sp)
                    Text("Quantity: ${String.format(Locale.US, "%.6f", t.quantity)}", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Estimated P/L: ${if (estimatedPL >= 0) "+" else ""}$${String.format(Locale.US, "%.2f", estimatedPL)}",
                        color = if (estimatedPL >= 0) Color(0xFF00E676) else Color(0xFFFF5252),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.closeTrade(t, curPrice); tradeToClose = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) { Text("SELL / CLOSE") }
            },
            dismissButton = {
                TextButton(onClick = { tradeToClose = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Shows the original live CoinGecko icon already loaded by TradingViewModel.logoMap.
 * No trading/order logic is changed here.
 */
private fun openTradingViewInChromeDirect(activity: Activity, symbol: String) {
    val url = "https://www.tradingview.com/chart/?symbol=" + Uri.encode(symbol)
    val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        setPackage("com.android.chrome")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        activity.startActivity(chromeIntent)
    } catch (_: Exception) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}

private fun deltaTradingViewSymbol(rawSymbol: String): String {
    val clean = rawSymbol
        .trim()
        .uppercase(Locale.US)
        .substringAfterLast(":")
        .removeSuffix(".P")
        .replace("-", "")
        .replace("_", "")
        .replace("/", "")

    val base = when {
        clean.endsWith("USDT") -> clean.removeSuffix("USDT")
        clean.endsWith("USDC") -> clean.removeSuffix("USDC")
        clean.endsWith("USD") -> clean.removeSuffix("USD")
        clean.endsWith("INR") -> clean.removeSuffix("INR")
        else -> clean
    }

    return if (base.isNotBlank()) "DELTAIN:${base}USD.P" else "BINANCE:BTCUSDT"
}

@Composable
private fun LiveCoinIcon(
    symbol: String,
    logoMap: Map<String, String>
) {
    val baseSymbol = remember(symbol) {
        symbol
            .trim()
            .uppercase(Locale.US)
            .substringAfterLast(":")
            .replace("/", "")
            .replace("-", "")
            .replace("_", "")
            .removeSuffix(".P")
            .let { raw ->
                when {
                    raw.endsWith("USDT") -> raw.removeSuffix("USDT")
                    raw.endsWith("USDC") -> raw.removeSuffix("USDC")
                    raw.endsWith("USD") -> raw.removeSuffix("USD")
                    raw.endsWith("INR") -> raw.removeSuffix("INR")
                    else -> raw
                }
            }
    }

    val logoUrl = remember(baseSymbol, logoMap) {
        logoMap[baseSymbol]
            ?: logoMap[baseSymbol.uppercase(Locale.US)]
            ?: logoMap[baseSymbol.lowercase(Locale.US)]
            ?: ""
    }

    // Keep the existing CoinGecko/logoMap source first.  If that cache has
    // no icon for a newly discovered live coin, use a public crypto-icon
    // fallback instead of rendering the old "L" placeholder.
    val fallbackUrl = remember(baseSymbol) {
        "https://assets.coincap.io/assets/icons/${baseSymbol.lowercase(Locale.US)}@2x.png"
    }

    var bitmap by remember(logoUrl, baseSymbol) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(logoUrl, baseSymbol) {
        bitmap = null
        val candidates = buildList {
            if (logoUrl.isNotBlank()) add(logoUrl)
            add(fallbackUrl)
        }
        bitmap = withContext(Dispatchers.IO) {
            candidates.firstNotNullOfOrNull { candidate ->
                runCatching {
                    (URL(candidate).openConnection() as? HttpURLConnection)?.apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        requestMethod = "GET"
                        instanceFollowRedirects = true
                    }?.let { connection ->
                        connection.connect()
                        connection.inputStream.use { input ->
                            BitmapFactory.decodeStream(input)
                        }.also {
                            connection.disconnect()
                        }
                    }
                }.getOrNull()
            }
        }
    }

    Box(
        modifier = Modifier.size(34.dp),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "${baseSymbol} live coin icon",
                modifier = Modifier.size(34.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun LiveChatDialog(
    trade: BotTrade,
    onDismiss: () -> Unit
) {
    var message by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(
            "Algo Bot: ${trade.symbol.uppercase(Locale.US)} is being monitored live.",
            "Status: ${trade.status.uppercase(Locale.US)} • ${trade.type.uppercase(Locale.US)}"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💬 Live Chat • ${trade.symbol.uppercase(Locale.US)}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    messages.forEach { msg ->
                        Text(
                            msg,
                            color = Color(0xFFE8E8EE),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Message") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = message.trim()
                    if (clean.isNotEmpty()) {
                        messages.add("You: $clean")
                        messages.add("Algo Bot: Message received for ${trade.symbol.uppercase(Locale.US)}.")
                        message = ""
                    }
                }
            ) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun LivePriceChartDialog(
    trade: BotTrade,
    currentPriceOf: (String) -> Double,
    onDismiss: () -> Unit
) {
    /*
     * Live TradingView chart.
     * The card's actual trade symbol is converted to the Delta India
     * perpetual TradingView symbol. No bot/order/position logic is changed.
     */
    val rawSymbol = trade.symbol
        .trim()
        .uppercase(Locale.US)

    val tvSymbol = remember(rawSymbol) {
        val clean = rawSymbol
            .substringAfterLast(":")
            .removeSuffix(".P")

        val base = when {
            clean.endsWith("USDT") -> clean.removeSuffix("USDT")
            clean.endsWith("USD") -> clean.removeSuffix("USD")
            clean.endsWith("USDC") -> clean.removeSuffix("USDC")
            else -> clean
        }

        // Delta India perpetuals are quoted as COINUSD.P on TradingView.
        if (base.isNotBlank()) {
            "DELTAIN:${base}USD.P"
        } else {
            "BINANCE:BTCUSDT"
        }
    }

    val latest = currentPriceOf(trade.symbol)
        .takeIf { it > 0.0 }
        ?: trade.entryPrice

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "📈 Live TradingView • ${trade.symbol.uppercase(Locale.US)}",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Live Price: $${String.format(Locale.US, "%.6f", latest)}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(10.dp))

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    url: String
                                ): Boolean {
                                    return false
                                }
                            }

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

                            // Load TradingView's actual chart page rather than the
                            // external widget script. This keeps the exchange symbol
                            // live and avoids the WebView widget-script failure.
                            val chartUrl =
                                "https://www.tradingview.com/chart/?symbol=" +
                                java.net.URLEncoder.encode(tvSymbol, "UTF-8")

                            loadUrl(chartUrl)
                        }
                    }
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    "Live TradingView • 5m • ${tvSymbol}",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun TpSlScreenshotDialog(
    trade: BotTrade,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    var takeProfit by remember { mutableStateOf("30") }
    var stopLoss by remember { mutableStateOf("") }

    fun saveScreenshot() {
        runCatching {
            val bitmap = view.rootView.drawToBitmap(Bitmap.Config.ARGB_8888)
            val resolver = context.contentResolver
            val fileName = "AlgoBot_${trade.symbol}_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AlgoBot")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create image")
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } ?: error("Unable to write image")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
            }
            bitmap.recycle()
            Toast.makeText(context, "Screenshot saved", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(
                context,
                "Screenshot failed: ${it.message ?: "unknown error"}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("+ TP/SL • ${trade.symbol.uppercase(Locale.US)}")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Position: ${trade.type.uppercase(Locale.US)} • ${trade.status.uppercase(Locale.US)}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = takeProfit,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() || c == '.' }) takeProfit = it },
                    label = { Text("Take Profit %") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = stopLoss,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() || c == '.' }) stopLoss = it },
                    label = { Text("Stop Loss % (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "📸 Screenshot captures the current Algo Bot screen.",
                    color = Color(0xFFFFB74D),
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            Button(onClick = { saveScreenshot() }) {
                Text("📸 Screenshot")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Card(
        modifier = modifier,
        colors = algoBotCardColors(),
        border = AlgoBotCardBorder
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 11.sp, color = Color.Gray, maxLines = 1, softWrap = false)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
        }
    }
}

@Composable
fun IndicatorRow(name: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, fontSize = 13.sp, color = Color.Gray)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun SettingsScreen(viewModel: TradingViewModel = viewModel()) {
    val strategy by viewModel.strategyConfig.collectAsState()
    var selectedProfile by remember(strategy.profile) { mutableStateOf(strategy.profile) }
    var selectedTradingViewStrategy by remember(strategy.tradingViewStrategy) { mutableStateOf(strategy.tradingViewStrategy) }
    var minConfidence by remember(strategy.minConfidence) { mutableStateOf(strategy.minConfidence.toString()) }
    var signalThreshold by remember(strategy.signalThreshold) { mutableStateOf(strategy.signalThreshold.toString()) }
    var maxOpenPositions by remember(strategy.maxOpenPositions) { mutableStateOf(strategy.maxOpenPositions.toString()) }
    var maxDrawdown by remember(strategy.maxDrawdownPct) { mutableStateOf(strategy.maxDrawdownPct.toString()) }

    // OpenAI Master Mind settings are persisted by TradingViewModel into
    // the existing "trading_bot_prefs" SharedPreferences store.
    val context = LocalContext.current
    val aiPrefs = remember {
        context.getSharedPreferences("trading_bot_prefs", android.content.Context.MODE_PRIVATE)
    }
    LaunchedEffect(Unit) {
        AiKeyStore.migrateLegacy(context)
    }
    var openAiKey by remember {
        mutableStateOf(AiKeyStore.get(context, AiKeyStore.PROVIDER_OPENAI))
    }
    var openAiModel by remember {
        mutableStateOf(aiPrefs.getString("openai_model", "gpt-5.6-luna") ?: "gpt-5.6-luna")
    }
    var openAiBaseUrl by remember {
        mutableStateOf(
            aiPrefs.getString(
                "openai_base_url",
                "https://api.openai.com/v1"
            ) ?: "https://api.openai.com/v1"
        )
    }
    var openAiEnabled by remember { mutableStateOf(true) }
    // Once an API key is saved/active, lock the whole Master Mind card.
    // The lock state itself is persistent across navigation/app restart.
    var openAiLocked by remember {
        mutableStateOf(
            aiPrefs.getBoolean("openai_master_mind_locked", openAiKey.isNotBlank())
        )
    }

    var message by remember { mutableStateOf("Strategy system ready") }
    var aiMessage by remember { mutableStateOf("OpenAI Master Mind ready") }
    var showProfiles by remember { mutableStateOf(false) }
    var showTradingViewStrategies by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ============================================================
        // OPENAI MASTER MIND
        // ============================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = BorderStroke(1.5.dp, Color(0xFF35BFFF)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🤖 OpenAI Master Mind",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color(0xFF35BFFF)
                        )
                        Text(
                            "Dynamic AI strategy • BUY • position monitoring • automatic SELL",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Button(
                            onClick = {
                                openAiLocked = !openAiLocked
                                aiPrefs.edit()
                                    .putBoolean("openai_master_mind_locked", openAiLocked)
                                    .commit()
                                aiMessage = if (openAiLocked) {
                                    "🔒 OpenAI Master Mind locked"
                                } else {
                                    "🔓 OpenAI Master Mind unlocked — edit allowed"
                                }
                            },
                            modifier = Modifier.height(38.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text(if (openAiLocked) "🔒 Locked" else "🔓 Unlock")
                        }

                        Spacer(Modifier.height(4.dp))

                        Switch(
                            checked = true,
                            enabled = false,
                            onCheckedChange = { }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = openAiKey,
                    onValueChange = {
                        if (!openAiLocked) openAiKey = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !openAiLocked,
                    label = { Text("OpenAI API Key") },
                    placeholder = { Text("Paste your OpenAI API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) }
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = openAiModel,
                    onValueChange = {
                        openAiModel = it
                        aiPrefs.edit()
                            .putString(
                                "openai_model",
                                it.trim().ifBlank { "gpt-5.6-luna" }
                            )
                            .commit()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !openAiLocked,
                    label = { Text("OpenAI Model") },
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = openAiBaseUrl,
                    onValueChange = {
                        openAiBaseUrl = it
                        aiPrefs.edit()
                            .putString(
                                "openai_base_url",
                                it.trim().trimEnd('/').ifBlank {
                                    "https://api.openai.com/v1"
                                }
                            )
                            .commit()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !openAiLocked,
                    label = { Text("OpenAI API Base URL") },
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Default: https://api.openai.com/v1",
                    fontSize = 9.sp,
                    color = Color.Gray
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    aiMessage,
                    fontSize = 10.sp,
                    color = Color(0xFF00E676)
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    enabled = !openAiLocked,
                    onClick = {
                        val key = openAiKey.trim()
                        val model = openAiModel.trim().ifBlank { "gpt-5.6-luna" }
                        val baseUrl = openAiBaseUrl.trim()
                            .ifBlank { "https://api.openai.com/v1" }

                        if (key.isBlank()) {
                            aiMessage = "❌ OpenAI API key required"
                            return@Button
                        }

                        if (!baseUrl.startsWith("https://")) {
                            aiMessage = "❌ API URL must use HTTPS"
                            return@Button
                        }

                        val saved = AiKeyStore.put(
                            context,
                            AiKeyStore.PROVIDER_OPENAI,
                            key
                        )
                        if (!saved) {
                            aiMessage = "❌ OpenAI API key could not be saved securely"
                            return@Button
                        }

                        aiPrefs.edit()
                            .remove("openai_api_key")
                            .putString("openai_model", model)
                            .putString("openai_base_url", baseUrl.trimEnd('/'))
                            .putBoolean("openai_master_mind_enabled", true)
                            .commit()

                        viewModel.setOpenAiMasterMindConfig(
                            apiKey = key,
                            model = model,
                            baseUrl = baseUrl,
                            enabled = true
                        )

                        openAiLocked = true
                        aiPrefs.edit()
                            .putBoolean("openai_master_mind_locked", true)
                            .commit()

                        aiMessage = if (openAiEnabled) {
                            "✅ OpenAI Master Mind saved & active"
                        } else {
                            "⏸ OpenAI Master Mind saved & disabled"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Text("🤖 Save & Activate OpenAI Master Mind")
                }

                Spacer(Modifier.height(5.dp))

                Text(
                    "🔐 API key is stored encrypted with Android Keystore and survives app close/restart.",
                    fontSize = 9.sp,
                    color = Color(0xFFFFB74D)
                )
                Text(
                    if (openAiLocked)
                        "🔒 Card locked • tap Unlock to edit settings"
                    else
                        "🔓 Card unlocked • Auto-save ON",
                    fontSize = 9.sp,
                    color = if (openAiLocked) Color(0xFFFFB74D) else Color(0xFF00E676)
                )
            }
        }

        // ============================================================
        // EXISTING STRATEGY SETTINGS — PRESERVED
        // ============================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = BorderStroke(1.5.dp, Color.White),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "🧠 Strategy System",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color(0xFFE8D9FF)
                )
                Text(
                    "Full strategy engine controls — RSI + MACD + EMA + Bollinger + ATR + AI gate",
                    fontSize = 10.sp, color = Color.Gray
                )
                Spacer(Modifier.height(14.dp))

                Text("Strategy Profile", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB388FF))
                Spacer(Modifier.height(6.dp))
                Box {
                    OutlinedButton(
                        onClick = { showProfiles = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedProfile.name.replace('_', ' '), modifier = Modifier.weight(1f))
                        Text("▼")
                    }
                    DropdownMenu(
                        expanded = showProfiles,
                        onDismissRequest = { showProfiles = false },
                        modifier = Modifier.fillMaxWidth(0.88f)
                    ) {
                        StrategyProfile.values().forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.name.replace('_', ' ')) },
                                onClick = {
                                    selectedProfile = profile
                                    showProfiles = false
                                }
                            )
                        }
                    }
                }
                Text(
                    when(selectedProfile) {
                        StrategyProfile.BALANCED -> "Trend + momentum + mean-reversion balanced"
                        StrategyProfile.TREND_FOLLOWING -> "EMA trend alignment gets highest weight"
                        StrategyProfile.MOMENTUM -> "RSI + MACD momentum gets highest weight"
                        StrategyProfile.MEAN_REVERSION -> "Bollinger mean-reversion gets highest weight"
                        StrategyProfile.BREAKOUT -> "Breakout confirmation gets highest weight"
                        StrategyProfile.CONSERVATIVE -> "Stronger trend confirmation and safer gating"
                    },
                    fontSize = 9.sp, color = Color(0xFFB8B0C8)
                )

                Spacer(Modifier.height(10.dp))
                Text("TradingView Strategy", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB388FF))
                Spacer(Modifier.height(6.dp))
                Box {
                    OutlinedButton(
                        onClick = { showTradingViewStrategies = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedTradingViewStrategy.name.replace('_', ' '), modifier = Modifier.weight(1f))
                        Text("▼")
                    }
                    DropdownMenu(
                        expanded = showTradingViewStrategies,
                        onDismissRequest = { showTradingViewStrategies = false },
                        modifier = Modifier.fillMaxWidth(0.88f)
                    ) {
                        TradingViewStrategy.values().forEach { strategyOption ->
                            DropdownMenuItem(
                                text = { Text(strategyOption.name.replace('_', ' ')) },
                                onClick = {
                                    selectedTradingViewStrategy = strategyOption
                                    showTradingViewStrategies = false
                                }
                            )
                        }
                    }
                }
                Text(
                    "Manual template selection • indicators are confirmed before REAL order execution",
                    fontSize = 9.sp, color = Color(0xFFB8B0C8)
                )

                Spacer(Modifier.height(10.dp))
                StrategyNumberField("Minimum Confidence %", minConfidence) { minConfidence = it }
                StrategyNumberField("Signal Threshold", signalThreshold) { signalThreshold = it }
                StrategyNumberField("Max Open Positions", maxOpenPositions) { maxOpenPositions = it }
                StrategyNumberField("Max Drawdown %", maxDrawdown) { maxDrawdown = it }

                Spacer(Modifier.height(8.dp))
                Text("Active: ${strategy.profile.name.replace('_', ' ')} • Confidence ${strategy.minConfidence.toInt()}% • Threshold ${strategy.signalThreshold}", fontSize = 10.sp, color = Color(0xFF00E676))
                Text(message, fontSize = 10.sp, color = Color(0xFFBDB5C9))

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val cfg = StrategyConfig(
                            profile = selectedProfile,
                            tradingViewStrategy = selectedTradingViewStrategy,
                            minConfidence = minConfidence.toDoubleOrNull() ?: strategy.minConfidence,
                            signalThreshold = signalThreshold.toIntOrNull() ?: strategy.signalThreshold,
                            maxOpenPositions = maxOpenPositions.toIntOrNull() ?: strategy.maxOpenPositions,
                            maxDrawdownPct = maxDrawdown.toDoubleOrNull() ?: strategy.maxDrawdownPct
                        )
                        viewModel.setStrategyConfig(cfg)
                        message = "✅ Strategy saved and active"
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) { Text("🧠 Save & Activate Strategy") }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.resetStrategySettings()
                        selectedProfile = StrategyProfile.BALANCED
                        selectedTradingViewStrategy = TradingViewStrategy.COMBINED_CONFIRMATION
                        minConfidence = "60.0"
                        signalThreshold = "18"
                        maxOpenPositions = "5"
                        maxDrawdown = "3.0"
                        message = "🔄 Strategy reset — BALANCED defaults restored"
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    border = BorderStroke(1.dp, Color.White)
                ) { Text("🔄 Strategy Reset", color = Color.White) }
            }
        }

        // Existing Strategy Engine card preserved.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = BorderStroke(1.dp, Color.White),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = "⚙️ Strategy Engine",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF35BFFF)
                )
                StrategyStatusRow("RSI (14)", "Momentum / overbought / oversold", Icons.Default.ShowChart, Color(0xFFFF4DCE))
                StrategyStatusRow("MACD", "Momentum confirmation", Icons.Default.ShowChart, Color(0xFFFFA000))
                StrategyStatusRow("EMA 9/21/50/200", "Trend structure", Icons.Default.ArrowUpward, Color(0xFF00D9FF))
                StrategyStatusRow("Bollinger Bands", "Mean-reversion + breakout", Icons.Default.ShowChart, Color(0xFFFFD400))
                StrategyStatusRow("ATR (14)", "Volatility guard", Icons.Default.Build, Color(0xFF9D6CFF))
                StrategyStatusRow("Master Mind AI", "Qwen3 + gpt-oss-20b + OpenAI dynamic strategy", Icons.Default.Settings, Color(0xFF00D9FF))
                StrategyStatusRow("REAL Order Gate", "Authenticated LIVE exchange required", Icons.Default.Lock, Color(0xFFFFD400))
            }
        }
    }
}

@Composable
private fun StrategyNumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() || c == '.' }) onValueChange(it) },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
        )
    )
}

@Composable
private fun StrategyStatusRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleColor: Color
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(Color.Transparent, RoundedCornerShape(50))
                .then(Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = titleColor,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = titleColor)
            Text(subtitle, fontSize = 9.sp, color = Color(0xFFBDB5C9))
        }
        Text("ACTIVE", fontSize = 9.sp, color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StartupStatusRow(
    title: String,
    subtitle: String,
    statusColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(statusColor, RoundedCornerShape(50))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White
            )
            Text(
                subtitle,
                fontSize = 9.sp,
                color = Color.Gray
            )
        }
        Text("READY", fontSize = 9.sp, color = statusColor, fontWeight = FontWeight.Bold)
    }
}
