// 📁 NEW FILE: app/.../ui/ExchangeRegistry.kt  (CHUNK 1/4)
package com.example.mycompose.hello.ui

import com.example.mycompose.hello.service.DeltaIpAutoSyncManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import android.content.Context
import androidx.compose.animation.*                 
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Ek exchange ki poori "passport" — host, auth-type, aur wo permissions jo key banate waqt ON karni hain.
data class ExchangeSpec(
    val name: String,        // display + connect key
    val region: String,      // "IN" ya "INTL"
    val host: String,        // base host (no https://)
    val style: String,       // auth-scheme code (dispatcher dekhta hai)
    val pass: Boolean,       // passphrase chahiye? (OKX/KuCoin/Bitget)
    val scopes: List<String>,// API-key permissions jo user ko enable karni hain
    val docs: String,        // official API docs URL
    val tag: String,         // 2-3 letter chip label
    val accent: Long         // brand color (chip + dot)
)

// 🔐 PERMISSION GUIDE — har exchange ke liye exact tick-marks. "all access permission allow" = ye list.
//    Connect karte waqt app yahi checklist dikhayega taaki user galat scope na chhode.
val ALL_EXCHANGES: List<ExchangeSpec> = listOf(
    // ───────────── 🇮🇳 INDIAN EXCHANGES ─────────────
    ExchangeSpec("CoinDCX","IN","api.coindcx.com","CDX",false, listOf("Spot Read","Spot Trade","(Futures alag app)"),"https://docs.coindcx.com","CDX",0xFF3D7EFF),
    ExchangeSpec("WazirX","IN","api.wazirx.com","WZ",false, listOf("Read Only","Spot Trade (Partner API)"),"https://docs.wazirx.com","WZX",0xFF3067F0),
    ExchangeSpec("ZebPay","IN","api.zebpay.com","ZEB",false, listOf("View Balance","Trade"),"https://www.zebpay.com/india/api-documentation","ZEB",0xFF1F8FFF),
    ExchangeSpec("CoinSwitch","IN","api.coinswitch.co","CSW",false, listOf("Read","Trade (Partner/Pro API)"),"https://coinswitch.co/","CSW",0xFF5B5BD6),
    ExchangeSpec("Giottus","IN","api.giottus.com","GIOT",false, listOf("Balance","Trade"),"https://www.giottus.com/","GIO",0xFFE8453C),
    ExchangeSpec("Bitbns","IN","api.bitbns.com","BNS",false, listOf("Account Read","Spot Trade"),"https://bitbns.com/","BNS",0xFF1FB8A0),
    ExchangeSpec("BuyUcoin","IN","api.buyucoin.com","BUYC",false, listOf("Read","Trade"),"https://www.buyucoin.com/","BUY",0xFF00B0FF),
    ExchangeSpec("Mudrex","IN","api.mudrex.com","MUD",false, listOf("Read","Trade (Bots)"),"https://mudrex.com/","MDX",0xFF6C5CE7),
    ExchangeSpec("DeltaIndia","IN","api.india.delta.exchange","DELTA",false, listOf("Read","Futures Trade"),"https://api-docs.delta.exchange/","DLT",0xFF00C2A8),
    ExchangeSpec("Unocoin","IN","api.unocoin.com","UNO",false, listOf("Read","Trade"),"https://www.unocoin.com/","UNO",0xFFF7931A),
    // ─────────────  INTERNATIONAL — TIER 1 ─────────────
    ExchangeSpec("Binance","INTL","api.binance.com","BN",false, listOf("Enable Reading","Enable Spot & Margin Trading"),"https://binance-docs.github.io/apidocs/spot/","BNB",0xFFF0B90B),
    ExchangeSpec("BinanceFutures","INTL","fapi.binance.com","BNF",false, listOf("Enable Reading","Enable Futures"),"https://binance-docs.github.io/apidocs/futures/","BNF",0xFFF0B90B),
    ExchangeSpec("BinanceUS","INTL","api.binance.us","BN",false, listOf("Enable Reading","Enable Spot Trading"),"https://docs.binance.us/","BUS",0xFFF0B90B),
    ExchangeSpec("Coinbase","INTL","api.coinbase.com","CB",false, listOf("wallet:accounts:read","wallet:trades","(Advanced Trade)"),"https://docs.cdp.coinbase.com/","COB",0xFF0052FF),
    ExchangeSpec("Kraken","INTL","api.kraken.com","KR",false, listOf("Query Funds","Query Open Orders","Trade"),"https://docs.kraken.com/api/","KRK",0xFF5741D9),
    ExchangeSpec("Bybit","INTL","api.bybit.com","BYBIT",false, listOf("Spot Read/Write","Derivatives Read/Write"),"https://bybit-exchange.github.io/docs/","BYB",0xFFF7A600),
    ExchangeSpec("OKX","INTL","www.okx.com","OKX",true, listOf("Read","Trade","(Passphrase zaroori)"),"https://www.okx.com/docs-v5/","OKX",0xFF1C1C1E),
    ExchangeSpec("KuCoin","INTL","api.kucoin.com","KC",true, listOf("General (Read)","Spot Trading","(Passphrase + IP-whitelist)"),"https://www.kucoin.com/docs/","KUC",0xFF24AE8F),
    ExchangeSpec("Gate.io","INTL","api.gateio.ws","GATE",false, listOf("Spot Read","Spot Trade","(IP-whitelist)"),"https://www.gate.io/docs/developers/apiv4/","GAT",0xFF2354E6),
    ExchangeSpec("Bitget","INTL","api.bitget.com","BG",true, listOf("Read","Spot Trade","(Passphrase + IP)"),"https://www.bitget.com/api-doc/","BGT",0xFF00F0A6),
    ExchangeSpec("MEXC","INTL","api.mexc.com","MEXC",false, listOf("Spot Read","Spot Trade","(IP-whitelist)"),"https://mexcdevelop.github.io/apidocs/","MEX",0xFF00B897),
    ExchangeSpec("HTX","INTL","api.huobi.pro","HTX",false, listOf("Read","Trade (Huobi/HTX)"),"https://www.htx.com/en-us/opend/","HTX",0xFF1B7CFF),
    // ───────────── 🌍 INTERNATIONAL — TIER 2 ─────────────
    ExchangeSpec("Bitfinex","INTL","api.bitfinex.com","BFX",false, listOf("Account:Read","Orders:Write"),"https://docs.bitfinex.com/","BFX",0xFF16B157),
    ExchangeSpec("Bitstamp","INTL","www.bitstamp.net","BST",false, listOf("Account balance","Buy & Sell"),"https://www.bitstamp.net/api/","BST",0xFF00C076),
    ExchangeSpec("CryptoCom","INTL","api.crypto.com","CCOM",false, listOf("spot_read","spot_trade"),"https://exchange-docs.crypto.com/","CDC",0xFF1199FA),
    ExchangeSpec("Gemini","INTL","api.gemini.com","GEM",false, listOf("Auditor (read)","Trader"),"https://docs.gemini.com/rest-api/","GEM",0xFF00DCFA),
    ExchangeSpec("Poloniex","INTL","api.poloniex.com","POLO",false, listOf("account:read","orders:create"),"https://api-docs.poloniex.com/","POL",0xFFF1A23B),
    ExchangeSpec("BingX","INTL","open-api.bingx.com","BINGX",false, listOf("Spot Read","Spot Trade","(IP-whitelist)"),"https://bingx-api.github.io/docs/","BGX",0xFF2B6BFF),
    ExchangeSpec("Phemex","INTL","api.phemex.com","PHEMEX",false, listOf("Read-Only","Spot/Futures Trade","(IP-bind)"),"https://phemex-docs.github.io/","PHX",0xFF8A8FF0),
    ExchangeSpec("BitMart","INTL","api-cloud.bitmart.com","BMART",false, listOf("Spot Read","Spot Trade","(Memo set karo)"),"https://developer-pro.bitmart.com/","BMT",0xFF00BFFF),
    ExchangeSpec("LBank","INTL","api.lbank.info","LBANK",false, listOf("subscribe_account","subscribe_order"),"https://www.lbank.com/apidoc","LBK",0xFF1E6BFF),
    ExchangeSpec("XT","INTL","sapi.xt.com","XT",false, listOf("spot_read","spot_trade"),"https://doc.xt.com/","XT",0xFF2D6BFF),
    ExchangeSpec("WhiteBIT","INTL","whitebit.com","WBIT",false, listOf("Spot read","Spot trade","(IP + nonce)"),"https://docs.whitebit.com/","WBT",0xFF1B5BFF),
    ExchangeSpec("CoinEx","INTL","api.coinex.com","COINEX",false, listOf("account:read","order:write"),"https://docs.coinex.com/api/v7/","CEX",0xFF2D6CF6),
    ExchangeSpec("Bitrue","INTL","openapi.bitrue.com","BITRUE",false, listOf("Spot Read","Spot Trade"),"https://github.com/Bitrue-exchange/","BTR",0xFF26A17B),
    ExchangeSpec("Pionex","INTL","api.pionex.com","PIONEX",false, listOf("balance","trade"),"https://www.pionex.com/en-US/apiDoc","PNX",0xFF009CFF),
    ExchangeSpec("AscendEX","INTL","api.ascendex.com","ASC",false, listOf("AccountInfo:Read","CashTrade:Write"),"https://ascendex.github.io/","ASC",0xFF13C2C2),
    // ───────────── 🌍 DERIVATIVES / SPECIAL ─────────────
    ExchangeSpec("Deribit","INTL","www.deribit.com","DERI",false, listOf("read","trade (token-auth /public/auth)"),"https://docs.deribit.com/","DRB",0xFF00C076),
    ExchangeSpec("dYdX","INTL","api.dydx.exchange","DYDX",false, listOf("read-only","trading (v3 HMAC; v4 STARK key alag)"),"https://docs.dydx.exchange/","DYD",0xFF6966FF),
    ExchangeSpec("BitMEX","INTL","www.bitmex.com","BMEX",false, listOf("order read","order write (expires header)"),"https://www.bitmex.com/app/apiKeys","BMX",0xFFE8A23A),
    ExchangeSpec("Hotcoin","INTL","api.hotcoin.com","HOT",false, listOf("spot_read","spot_trade"),"https://www.hotcoin.com/","HOT",0xFFFF6B00),
    ExchangeSpec("Toobit","INTL","api.toobit.com","TOOB",false, listOf("Spot Read","Spot Trade"),"https://www.toobit.com/","TOB",0xFF00C2FF),
    ExchangeSpec("BloFin","INTL","api.blofin.com","BLOF",false, listOf("Read","Trade"),"https://blofin.com/docs","BLO",0xFF00D1A4),
    ExchangeSpec("Deepcoin","INTL","openapi.deepcoin.com","DEEP",false, listOf("spot_read","spot_trade"),"https://www.deepcoin.com/docs","DEP",0xFF1E88E5),
    ExchangeSpec("HashKey","INTL","api.hashkey.com","HASH",false, listOf("Read","Trade (Pro)"),"https://hashkeypro-apidoc.readme.io/","HSK",0xFFD4AF37),
    ExchangeSpec("BTSE","INTL","api.btse.com","BTSE",false, listOf("readHistory","tradeHistory (nonce header)"),"https://www.btse.com/apiexplorer/","BTS",0xFF00C2A8)
)

fun specFor(name: String): ExchangeSpec? = ALL_EXCHANGES.firstOrNull { it.name.equals(name, true) }

// ── low-level crypto helpers (file-private → purani file se koi collision nahi) ──
private fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
private fun h256(k: String, d: String): ByteArray {
    val m = javax.crypto.Mac.getInstance("HmacSHA256"); m.init(javax.crypto.spec.SecretKeySpec(k.toByteArray(), "HmacSHA256")); return m.doFinal(d.toByteArray())
}
private fun sha256(s: String) = hx(java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
private fun ue(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

// 📁 ExchangeRegistry.kt  (CHUNK 2/4 — chunk 1 ke NEECHE jodo)
// connectExchangeV3: har exchange ka signing scheme. Tier-1 = precise (public docs ke hisaab se).
// Long-tail = best-effort; 401 aaye toh server ka asli message + docs link (picker mein) se verify karo.
// "alive feedback": 200 pe Binance-family ka real asset-count read hota hai → proof ki READ scope kaam kar rahi.

private fun catBytes(vararg a: ByteArray): ByteArray {
    var n = 0; for (x in a) n += x.size
    val out = ByteArray(n); var o = 0; for (x in a) { System.arraycopy(x, 0, out, o, x.size); o += x.size }; return out
}

// read-only / safe "probe" endpoint per style (connect ke baad yahi hit hota hai)
private fun probeEndpoint(style: String): Pair<String, String> = when (style) {
    "BN", "BUS" -> "GET" to "/api/v3/account"
    "BNF" -> "GET" to "/fapi/v2/account"
    "CDX" -> "POST" to "/exchange/v1/users/balances"
    "WZ" -> "GET" to "/sapi/v1/funds"
    "ZEB" -> "GET" to "/v1/user/balance"
    "CSW" -> "GET" to "/api/v1/user/balance"
    "GIOT" -> "GET" to "/api/v1/balance"
    "BUYC" -> "GET" to "/api/v1/balance"
    "MUD" -> "GET" to "/api/v1/balance"
    "DELTA" -> "GET" to "/v2/wallet/balances"
    "UNO" -> "GET" to "/api/v1/balance"
    "BNS" -> "GET" to "/api/v1/balance"
    "CB" -> "GET" to "/v2/accounts"
    "KR" -> "GET" to "/0/private/Balance"
    "BYBIT" -> "GET" to "/v5/account/wallet-balance?accountType=UNIFIED"
    "OKX" -> "GET" to "/api/v5/account/balance"
    "KC" -> "GET" to "/api/v1/accounts"
    "GATE" -> "GET" to "/api/v4/spot/accounts"
    "BG" -> "GET" to "/api/v2/spot/account/assets"
    "MEXC" -> "GET" to "/api/v3/account"
    "HTX" -> "GET" to "/v1/account/accounts"
    "BFX" -> "POST" to "/v2/auth/r/wallets"
    "BST" -> "GET" to "/api/v2/balance/"
    "CCOM" -> "POST" to "/v2/private/get-account-summary"
    "GEM" -> "GET" to "/v1/balances"
    "POLO" -> "GET" to "/api/v1/account/balances"
    "BINGX" -> "GET" to "/openApi/spot/v1/account/balance"
    "PHEMEX" -> "GET" to "/accounts/accountPositions"
    "BMART" -> "GET" to "/spot/v1/account"
    "LBANK" -> "GET" to "/v2/user_info.do"
    "XT" -> "GET" to "/spot/v3/balance"
    "WBIT" -> "GET" to "/api/v4/trading-account/spot/balance"
    "COINEX" -> "GET" to "/v2/spot/account/balance"
    "BTR" -> "GET" to "/api/v1/account"
    "PIONEX" -> "GET" to "/v1/account/balances"
    "ASC" -> "GET" to "/api/pro/v1/cash/balance"
    "DERI" -> "GET" to "/api/v2/private/get-account-summary"
    "DYDX" -> "GET" to "/v3/accounts"
    "BMEX" -> "GET" to "/api/v1/user/margin"
    "HOT" -> "GET" to "/open/v2/spot/account"
    "TOOB" -> "GET" to "/api/v1/spot/account"
    "BLOF" -> "GET" to "/api/v1/spot/account"
    "DEP" -> "GET" to "/api/v1/spot/account"
    "HASH" -> "GET" to "/api/v1/spot/account"
    "BTS" -> "GET" to "/api/v4/user/balances"
    else -> "GET" to "/"
}

suspend fun connectExchangeV3(spec: ExchangeSpec, apiKey: String, secret: String, passphrase: String): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (apiKey.isBlank() || secret.isBlank()) return@withContext "⚠️ API Key + Secret dono bharo."
        if (spec.pass && passphrase.isBlank()) return@withContext "⚠️ ${spec.name} ko PASSPHRASE chahiye — neeche wala box bharo."
        try {
            val ts = System.currentTimeMillis().toString(); val rw = "5000"
            val ep = probeEndpoint(spec.style); val method = ep.first; val full = ep.second
            val p0 = full.substringBefore('?'); val q = full.substringAfter('?', "")
            val style = spec.style; val host = spec.host
            var url = ""; var body = ""; val H = mutableMapOf<String, String>()
            when (style) {
                // ── Binance-family + forks: signature query mein, key header mein ──
                "BN", "BNF", "BUS", "BTR", "HOT", "TOOB", "BLOF", "DEP", "XT", "WBIT", "ASC", "PIONEX", "BMART", "MEXC" -> {
                    val qq = (if (q.isEmpty()) "" else "$q&") + "timestamp=$ts&recvWindow=$rw"
                    url = "https://$host$p0?$qq&signature=" + hx(h256(secret, qq))
                    H[if (style == "MEXC") "X-MX-APIKEY" else "X-MBX-APIKEY"] = apiKey
                }
                "BYBIT" -> { val pl = ts + apiKey + rw + q + body; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["X-BAPI-API-KEY"] = apiKey; H["X-BAPI-SIGN"] = hx(h256(secret, pl)); H["X-BAPI-TIMESTAMP"] = ts; H["X-BAPI-RECV-WINDOW"] = rw }
                "OKX" -> { val rp = p0 + (if (q.isEmpty()) "" else "?$q"); val pl = ts + method + rp + body; url = "https://$host$rp"; H["OK-ACCESS-KEY"] = apiKey; H["OK-ACCESS-SIGN"] = b64(h256(secret, pl)); H["OK-ACCESS-TIMESTAMP"] = ts; H["OK-ACCESS-PASSPHRASE"] = passphrase }
                "KC" -> { val rp = p0 + (if (q.isEmpty()) "" else "?$q"); val pl = ts + method + rp + body; url = "https://$host$rp"; H["KC-API-KEY"] = apiKey; H["KC-API-SIGN"] = b64(h256(secret, pl)); H["KC-API-TIMESTAMP"] = ts; H["KC-API-PASSPHRASE"] = b64(h256(secret, passphrase)); H["KC-API-KEY-VERSION"] = "2" }
                "BG" -> { val rp = p0 + (if (q.isEmpty()) "" else "?$q"); val pl = ts + method + rp + body; url = "https://$host$rp"; H["ACCESS-KEY"] = apiKey; H["ACCESS-SIGN"] = b64(h256(secret, pl)); H["ACCESS-TIMESTAMP"] = ts; H["ACCESS-PASSPHRASE"] = passphrase }
                "GATE" -> { val hb = if (body.isEmpty()) "" else sha256(body); val pl = "$method\n$p0\n$q\n$hb\n$ts"; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["KEY"] = apiKey; H["SIGN"] = hx(h256(secret, pl)); H["Timestamp"] = ts }
                "HTX" -> { val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()); val aq = "AccessKeyId=" + ue(apiKey) + "&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=" + ue(iso); val qq = if (q.isEmpty()) aq else "$q&$aq"; val pl = "$method\n$host\n$p0\n$qq"; url = "https://$host$p0?$qq&Signature=" + ue(b64(h256(secret, pl))) }
                "CDX" -> { body = "{\"timestamp\":$ts}"; url = "https://$host$p0"; H["X-AUTH-APIKEY"] = apiKey; H["X-AUTH-SIGNATURE"] = hx(h256(secret, body)); H["Content-Type"] = "application/json" }
                "DELTA" -> { val tsec = (System.currentTimeMillis() / 1000).toString(); url = "https://$host$p0"; H["api-key"] = apiKey; H["timestamp"] = tsec; H["signature"] = hx(h256(secret, "GET" + tsec + p0)); H["Content-Type"] = "application/json" }
                "WZ" -> { val qq = (if (q.isEmpty()) "" else "$q&") + "recvWindow=$rw&timestamp=$ts&apiKey=" + ue(apiKey); url = "https://$host$p0?$qq&signature=" + hx(h256(secret, qq + body)) }
                "CB" -> { val rp = p0 + (if (q.isEmpty()) "" else "?$q"); val pl = ts + method + rp + body; url = "https://$host$rp"; H["CB-ACCESS-KEY"] = apiKey; H["CB-ACCESS-SIGN"] = hx(h256(secret, pl)); H["CB-ACCESS-TIMESTAMP"] = ts }
                "KR" -> { val raw = java.security.MessageDigest.getInstance("SHA-256").digest((ts + body).toByteArray()); val msg = catBytes(p0.toByteArray(), raw); val mac = javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256")); url = "https://$host$p0"; H["API-Key"] = apiKey; H["API-Sign"] = b64(mac.doFinal(msg)) }
                "GEM" -> { val pl = b64("{\"request\":\"$p0\",\"nonce\":$ts}".toByteArray()); url = "https://$host$p0"; H["X-GEMINI-APIKEY"] = apiKey; H["X-GEMINI-PAYLOAD"] = pl; H["X-GEMINI-SIGNATURE"] = hx(h256(secret, pl)) }
                "POLO" -> { val pl = "$ts\n$method\n$p0\n$q\n$body"; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["Key"] = apiKey; H["Sign"] = hx(h256(secret, pl)); H["Timestamp"] = ts; H["Nonce"] = ts }
                "BMEX" -> { val exp = ((System.currentTimeMillis() / 1000) + 60).toString(); val pl = method + p0 + exp + body; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["api-expires"] = exp; H["api-key"] = apiKey; H["api-signature"] = hx(h256(secret, pl)) }
                "BFX" -> { val pl = "/api" + p0 + ts + ts + body; url = "https://$host$p0"; H["bfx-apikey"] = apiKey; H["bfx-signature"] = hx(h256(secret, pl)); H["bfx-timestamp"] = ts; H["bfx-nonce"] = ts; H["Content-Type"] = "application/json" }
                "BST" -> { val pl = apiKey + "BITSTAMP" + apiKey + method + p0 + q + "" + ts + ts + "v2"; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["X-Auth"] = apiKey; H["X-Auth-Signature"] = hx(h256(secret, pl)); H["X-Auth-Nonce"] = ts; H["X-Auth-Timestamp"] = ts; H["X-Auth-Version"] = "v2" }
                "ZEB" -> { val pl = ts + method + p0 + body; url = "https://$host$p0"; H["X-ZP-APIKEY"] = apiKey; H["X-ZP-TIMESTAMP"] = ts; H["X-ZP-SIGNATURE"] = hx(h256(secret, pl)) }
                // ── generic header-HMAC (long-tail best-effort) ──
                "CSW", "GIOT", "BUYC", "MUD", "UNO", "BNS", "HASH", "BTS" -> { val pl = ts + method + p0 + body; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["X-API-KEY"] = apiKey; H["X-API-SIGNATURE"] = hx(h256(secret, pl)); H["X-API-TIMESTAMP"] = ts }
                // ── generic query-HMAC (long-tail best-effort) ──
                "BINGX", "PHEMEX", "LBANK", "COINEX", "CCOM" -> { val qq = (if (q.isEmpty()) "" else "$q&") + "apiKey=" + ue(apiKey) + "&recvWindow=$rw&timestamp=$ts"; url = "https://$host$p0?$qq&signature=" + hx(h256(secret, qq + body)) }
                // ── token / STARK auth — simple HMAC nahi ──
                "DERI", "DYDX" -> return@withContext "⚠️ ${spec.name} token/STARK-key auth chahiye (simple HMAC nahi). Docs: ${spec.docs}"
                else -> return@withContext "⚠️ ${spec.name} ka signing scheme registry mein nahi mila."
            }
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 12000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            H.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body.isNotEmpty()) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            var extra = ""
            if (code in 200..299 && style in setOf("BN", "BNF", "BUS")) {
                try {
                    val bs = org.json.JSONObject(resp).optJSONArray("balances")
                    if (bs != null) { var n = 0; for (i in 0 until bs.length()) { val f = bs.getJSONObject(i).optString("free", "0").toDoubleOrNull() ?: 0.0; if (f > 0) n++ }; if (n > 0) extra = " • $n assets live" }
                } catch (_: Exception) {}
            }
            return@withContext when {
                code in 200..299 -> "✅ ${spec.name} Connected$extra"
                code == 401 || code == 403 -> "❌ ${spec.name}: ${resp.take(90)}  • IP-whitelist / permissions (scopes) verify karo"
                else -> "⚠️ ${spec.name} HTTP $code • ${resp.take(50)}"
            }
        } catch (e: Exception) { return@withContext "❌ ${e.message?.take(60)}" }
    }
    
    // 📁 ExchangeRegistry.kt  (CHUNK 3/4 — chunk 2 ke NEECHE jodo)
// readBalanceV3: connect ke baad READ scope ka asli istemaal. Har exchange ka response apne format mein
// aata hai → generic + targeted parser. USD-total jahan exchange khud de (Bybit/OKX/BNF…) wahi, warna
// top holdings live. Empty account → "read OK • 0". Crash-safe: parse fail = graceful message.
// permissionChecklist: picker mein connect ke upar exact tick-marks + safety lines.

private data class SignedResp(val code: Int, val body: String)
private data class BalanceInfo(val usd: Double?, val holdings: List<Pair<String, Double>>)

data class LiveBalanceSnapshot(
    val total: Double,
    val currency: String,
    val holdings: List<Pair<String, Double>> = emptyList()
)

// read-path signer — connect-path (chunk 2) ka mirror; same scheme ⇒ connect 200 to read bhi chalega.
private suspend fun signedCall(spec: ExchangeSpec, apiKey: String, secret: String, passphrase: String, method: String, full: String): SignedResp =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ts = System.currentTimeMillis().toString(); val rw = "5000"
        val p0 = full.substringBefore('?'); val q = full.substringAfter('?', "")
        val style = spec.style; val host = spec.host
        var url = ""; var body = ""; val H = mutableMapOf<String, String>()
        try {
            when (style) {
                "BN", "BNF", "BUS", "BTR", "HOT", "TOOB", "BLOF", "DEP", "XT", "WBIT", "ASC", "PIONEX", "BMART", "MEXC" -> {
                    val qq = (if (q.isEmpty()) "" else "$q&") + "timestamp=$ts&recvWindow=$rw"
                    url = "https://$host$p0?$qq&signature=" + hx(h256(secret, qq))
                    H[if (style == "MEXC") "X-MX-APIKEY" else "X-MBX-APIKEY"] = apiKey
                }
                "BYBIT" -> { val pl = ts + apiKey + rw + q + body; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["X-BAPI-API-KEY"] = apiKey; H["X-BAPI-SIGN"] = hx(h256(secret, pl)); H["X-BAPI-TIMESTAMP"] = ts; H["X-BAPI-RECV-WINDOW"] = rw }
                "OKX" -> { val rp = p0 + (if (q.isEmpty()) "" else "?$q"); val pl = ts + method + rp + body; url = "https://$host$rp"; H["OK-ACCESS-KEY"] = apiKey; H["OK-ACCESS-SIGN"] = b64(h256(secret, pl)); H["OK-ACCESS-TIMESTAMP"] = ts; H["OK-ACCESS-PASSPHRASE"] = passphrase }
                "KC" -> { val rp = p0 + (if (q.isEmpty()) "" else "?$q"); val pl = ts + method + rp + body; url = "https://$host$rp"; H["KC-API-KEY"] = apiKey; H["KC-API-SIGN"] = b64(h256(secret, pl)); H["KC-API-TIMESTAMP"] = ts; H["KC-API-PASSPHRASE"] = b64(h256(secret, passphrase)); H["KC-API-KEY-VERSION"] = "2" }
                "BG" -> { val rp = p0 + (if (q.isEmpty()) "" else "?$q"); val pl = ts + method + rp + body; url = "https://$host$rp"; H["ACCESS-KEY"] = apiKey; H["ACCESS-SIGN"] = b64(h256(secret, pl)); H["ACCESS-TIMESTAMP"] = ts; H["ACCESS-PASSPHRASE"] = passphrase }
                "GATE" -> { val hb = if (body.isEmpty()) "" else sha256(body); val pl = "$method\n$p0\n$q\n$hb\n$ts"; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["KEY"] = apiKey; H["SIGN"] = hx(h256(secret, pl)); H["Timestamp"] = ts }
                "HTX" -> { val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()); val aq = "AccessKeyId=" + ue(apiKey) + "&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=" + ue(iso); val qq = if (q.isEmpty()) aq else "$q&$aq"; val pl = "$method\n$host\n$p0\n$qq"; url = "https://$host$p0?$qq&Signature=" + ue(b64(h256(secret, pl))) }
                "CDX" -> { body = "{\"timestamp\":$ts}"; url = "https://$host$p0"; H["X-AUTH-APIKEY"] = apiKey; H["X-AUTH-SIGNATURE"] = hx(h256(secret, body)); H["Content-Type"] = "application/json" }
                "DELTA" -> { val tsec = (System.currentTimeMillis() / 1000).toString(); url = "https://$host$p0"; H["api-key"] = apiKey; H["timestamp"] = tsec; H["signature"] = hx(h256(secret, "GET" + tsec + p0)); H["Content-Type"] = "application/json" }
                "WZ" -> { val qq = (if (q.isEmpty()) "" else "$q&") + "recvWindow=$rw&timestamp=$ts&apiKey=" + ue(apiKey); url = "https://$host$p0?$qq&signature=" + hx(h256(secret, qq + body)) }
                "CB" -> { val rp = p0 + (if (q.isEmpty()) "" else "?$q"); val pl = ts + method + rp + body; url = "https://$host$rp"; H["CB-ACCESS-KEY"] = apiKey; H["CB-ACCESS-SIGN"] = hx(h256(secret, pl)); H["CB-ACCESS-TIMESTAMP"] = ts }
                "KR" -> { val raw = java.security.MessageDigest.getInstance("SHA-256").digest((ts + body).toByteArray()); val msg = catBytes(p0.toByteArray(), raw); val mac = javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256")); url = "https://$host$p0"; H["API-Key"] = apiKey; H["API-Sign"] = b64(mac.doFinal(msg)) }
                "GEM" -> { val pl = b64("{\"request\":\"$p0\",\"nonce\":$ts}".toByteArray()); url = "https://$host$p0"; H["X-GEMINI-APIKEY"] = apiKey; H["X-GEMINI-PAYLOAD"] = pl; H["X-GEMINI-SIGNATURE"] = hx(h256(secret, pl)) }
                "POLO" -> { val pl = "$ts\n$method\n$p0\n$q\n$body"; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["Key"] = apiKey; H["Sign"] = hx(h256(secret, pl)); H["Timestamp"] = ts; H["Nonce"] = ts }
                "BMEX" -> { val exp = ((System.currentTimeMillis() / 1000) + 60).toString(); val pl = method + p0 + exp + body; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["api-expires"] = exp; H["api-key"] = apiKey; H["api-signature"] = hx(h256(secret, pl)) }
                "BFX" -> { val pl = "/api" + p0 + ts + ts + body; url = "https://$host$p0"; H["bfx-apikey"] = apiKey; H["bfx-signature"] = hx(h256(secret, pl)); H["bfx-timestamp"] = ts; H["bfx-nonce"] = ts; H["Content-Type"] = "application/json" }
                "BST" -> { val pl = apiKey + "BITSTAMP" + apiKey + method + p0 + q + "" + ts + ts + "v2"; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["X-Auth"] = apiKey; H["X-Auth-Signature"] = hx(h256(secret, pl)); H["X-Auth-Nonce"] = ts; H["X-Auth-Timestamp"] = ts; H["X-Auth-Version"] = "v2" }
                "ZEB" -> { val pl = ts + method + p0 + body; url = "https://$host$p0"; H["X-ZP-APIKEY"] = apiKey; H["X-ZP-TIMESTAMP"] = ts; H["X-ZP-SIGNATURE"] = hx(h256(secret, pl)) }
                "CSW", "GIOT", "BUYC", "MUD", "UNO", "BNS", "HASH", "BTS" -> { val pl = ts + method + p0 + body; url = "https://$host$p0" + (if (q.isEmpty()) "" else "?$q"); H["X-API-KEY"] = apiKey; H["X-API-SIGNATURE"] = hx(h256(secret, pl)); H["X-API-TIMESTAMP"] = ts }
                "BINGX", "PHEMEX", "LBANK", "COINEX", "CCOM" -> { val qq = (if (q.isEmpty()) "" else "$q&") + "apiKey=" + ue(apiKey) + "&recvWindow=$rw&timestamp=$ts"; url = "https://$host$p0?$qq&signature=" + hx(h256(secret, qq + body)) }
                else -> return@withContext SignedResp(-1, "")
            }
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 12000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            H.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body.isNotEmpty()) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            SignedResp(code, resp)
        } catch (e: Exception) { SignedResp(-1, e.message ?: "") }
    }

// ── tolerant balance parser (generic + targeted) ──
private val SYM_KEYS = listOf("symbol", "asset", "coin", "currency", "ccy", "instrumentId", "market", "product")
private val AMT_KEYS = listOf("free", "available", "balance", "equity", "qty", "hold", "amount", "total")
private val USD_KEYS = listOf(
    "totalEquity", "totalEq", "totalWalletBalance", "totalMarginBalance",
    "walletBalance", "usdBalance", "totalUsd", "net_equity", "netEquity",
    "equity", "total_equity", "accountEquity", "totalAccountValue", "totalValue"
)
private val DENY = (SYM_KEYS + AMT_KEYS + USD_KEYS + listOf("state", "type", "id", "name", "timestamp", "ts", "time", "locked", "margin", "cross", "isolated", "accountType", "bizType")).toSet()
private val SYM_RE = Regex("^[A-Z0-9]{2,12}$")
private fun num(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }

private fun scanUsd(root: Any?, d: Int): Double? {
    if (d > 5) return null
    return when (root) {
        is org.json.JSONObject -> {
            for (k in USD_KEYS) { val n = num(root.opt(k)); if (n != null && n > 0) return n }
            val it = root.keys(); while (it.hasNext()) { val r = scanUsd(root.get(it.next()), d + 1); if (r != null) return r }; null
        }
        is org.json.JSONArray -> { for (i in 0 until root.length()) { val r = scanUsd(root.get(i), d + 1); if (r != null) return r }; null }
        else -> null
    }
}

private fun scanHold(root: Any?, d: Int, out: MutableList<Pair<String, Double>>) {
    if (d > 5) return
    when (root) {
        is org.json.JSONArray -> { for (i in 0 until root.length()) scanHold(root.get(i), d + 1, out) }
        is org.json.JSONObject -> {
            var sym: String? = null; for (sk in SYM_KEYS) { val v = root.opt(sk); if (v is String && v.isNotBlank()) { sym = v.uppercase(); break } }
            var amt: Double? = null; for (ak in AMT_KEYS) { val n = num(root.opt(ak)); if (n != null) { amt = n; break } }
            if (sym != null && amt != null) { out.add(sym to amt); return }
            val keys = mutableListOf<String>(); val it = root.keys(); while (it.hasNext()) keys.add(it.next())
            for (k in keys) {
                val v = root.get(k); val ku = k.uppercase()
                if (k !in DENY && ku.matches(SYM_RE)) {
                    when (v) {
                        is String -> { val n = num(v); if (n != null) out.add(ku to n) }
                        is org.json.JSONObject -> { var a: Double? = null; for (ak in AMT_KEYS) { val n = num(v.opt(ak)); if (n != null) { a = n; break } }; if (a != null) out.add(ku to a) }
                    }
                }
                scanHold(v, d + 1, out)
            }
        }
    }
}

private fun parseBalance(body: String): BalanceInfo {
    val root: Any? = try { val s = body.trim(); if (s.startsWith("[")) org.json.JSONArray(s) else org.json.JSONObject(s) } catch (_: Exception) { return BalanceInfo(null, emptyList()) }
    val usd = scanUsd(root, 0)
    val raw = mutableListOf<Pair<String, Double>>(); scanHold(root, 0, raw)
    val ded = raw.filter { it.second > 0 }.groupBy({ it.first }, { it.second }).map { it.key to (it.value.maxOrNull() ?: 0.0) }
    return BalanceInfo(usd, ded)
}

suspend fun readLiveBalanceV3(
    spec: ExchangeSpec,
    apiKey: String,
    secret: String,
    passphrase: String
): LiveBalanceSnapshot? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (apiKey.isBlank() || secret.isBlank()) return@withContext null
    if (spec.pass && passphrase.isBlank()) return@withContext null

    val ep = probeEndpoint(spec.style)
    val r = signedCall(spec, apiKey, secret, passphrase, ep.first, ep.second)
    if (r.code !in 200..299) {
        android.util.Log.e("EXCHANGE_BALANCE", "${spec.name} HTTP ${r.code}: ${r.body.take(200)}")
        return@withContext null
    }

    val info = parseBalance(r.body)
    val total = info.usd ?: run {
        // Safe fallback: only fiat/stable holdings are used as a dashboard total.
        info.holdings.firstOrNull {
            it.first.equals("INR", true) ||
            it.first.equals("USD", true) ||
            it.first.equals("USDT", true) ||
            it.first.equals("USDC", true)
        }?.second ?: 0.0
    }

    if (!total.isFinite() || total < 0.0) return@withContext null

    val currency = if (info.usd != null) "USD" else {
        info.holdings.firstOrNull {
            it.first.equals("INR", true) ||
            it.first.equals("USD", true) ||
            it.first.equals("USDT", true) ||
            it.first.equals("USDC", true)
        }?.first ?: "USD"
    }

    LiveBalanceSnapshot(total, currency, info.holdings)
}

suspend fun readBalanceV3(spec: ExchangeSpec, apiKey: String, secret: String, passphrase: String): String {
    if (spec.style == "DERI" || spec.style == "DYDX") {
        return "⚠️ ${spec.name}: token/STARK authentication required • Docs: ${spec.docs}"
    }

    val snapshot = readLiveBalanceV3(spec, apiKey, secret, passphrase)
        ?: return "⚠️ ${spec.name}: live balance read failed — API permissions/IP/signature verify karo"

    val top = snapshot.holdings
        .sortedByDescending { it.second }
        .take(5)
        .joinToString(", ") { it.first }

    val n = snapshot.holdings.size
    return "💰 ${java.lang.String.format(java.util.Locale.US, "%,.8f", snapshot.total).trimEnd('0').trimEnd('.')} ${snapshot.currency} • $n holdings live" +
        if (top.isNotEmpty()) "  ▸ $top" else ""
}

fun permissionChecklist(spec: ExchangeSpec): List<String> = buildList {
    addAll(spec.scopes)
    add("🛡️ Withdraw / Transfer = OFF rakho (safety — read+trade kaafi)")
    add("🌐 IP-whitelist: jahan required ho, app ka detected IP add karo")
}// 📁 ExchangeRegistry.kt  (CHUNK 4/4 — LAST. chunk 3 ke NEECHE jodo)
// Polished picker. Same name+signature as old dialog ⇒ TopBar() ka call site bina edit ke naye wale se bind hoga.
// Purana AccountApiDialogV2 CoinGeckoScreen.kt se DELETE karna (neeche steps). Ambient sweep + pulse + sliding tab + animated ring = living UI.

private fun visibleExchanges(region: String, query: String): List<ExchangeSpec> {
    val filtered = ALL_EXCHANGES.filter { 
        it.region == region && (query.isBlank() || 
            it.name.contains(query, ignoreCase = true) || 
            it.host.contains(query, ignoreCase = true) ||
            it.tag.contains(query, ignoreCase = true))
    }
    return filtered
}

@Composable
private fun ExRow(ex: ExchangeSpec, isSel: Boolean, onPick: () -> Unit) {
    val bg by animateColorAsState(if (isSel) Color(ex.accent).copy(alpha = 0.16f) else Color(0xFF050505), label = "rowbg")
    val bd by animateColorAsState(if (isSel) Color(ex.accent) else Color(0xFF2A313C), label = "rowbd")
    Surface(onClick = onPick, shape = RoundedCornerShape(14.dp), color = bg, border = BorderStroke(1.dp, bd)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(ex.accent)), contentAlignment = Alignment.Center) { Text(ex.tag, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ex.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (ex.region == "IN") "🇮 India" else "🌍 Intl", color = Color(0xFF848E9C), fontSize = 9.sp); Text("  •  ", color = Color(0xFF3A3F47), fontSize = 9.sp); Text(ex.style, color = Color(ex.accent), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
            if (isSel) Box(Modifier.size(18.dp).clip(CircleShape).background(Color(ex.accent)), contentAlignment = Alignment.Center) { Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            else Box(Modifier.size(18.dp).clip(CircleShape).background(Color.Transparent).then(Modifier.background(Color(0xFF2A313C), CircleShape)))
        }
    }
}



private const val EXCHANGE_PREFS = "exchange_credentials_v4"
private const val EXCHANGE_KEYSTORE = "AndroidKeyStore"
private const val EXCHANGE_KEY_ALIAS = "ProNitinExchangeVault"

private fun exchangePrefs(ctx: Context): android.content.SharedPreferences =
    ctx.getSharedPreferences(EXCHANGE_PREFS, Context.MODE_PRIVATE)

private fun exchangePrefKey(spec: ExchangeSpec, suffix: String): String =
    "${spec.name.lowercase(java.util.Locale.US)}_$suffix"

private fun vaultCipher(mode: Int, iv: ByteArray? = null): javax.crypto.Cipher {
    val ks = java.security.KeyStore.getInstance(EXCHANGE_KEYSTORE).apply { load(null) }
    if (!ks.containsAlias(EXCHANGE_KEY_ALIAS)) {
        val generator = javax.crypto.KeyGenerator.getInstance(
            "AES",
            EXCHANGE_KEYSTORE
        )
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                EXCHANGE_KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        generator.generateKey()
    }
    val key = ks.getKey(EXCHANGE_KEY_ALIAS, null) as java.security.Key
    return javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").also { cipher ->
        if (mode == javax.crypto.Cipher.ENCRYPT_MODE) {
            cipher.init(mode, key)
        } else {
            cipher.init(
                mode,
                key,
                javax.crypto.spec.GCMParameterSpec(128, requireNotNull(iv))
            )
        }
    }
}

private fun vaultEncrypt(value: String): String {
    if (value.isBlank()) return ""
    return try {
        val cipher = vaultCipher(javax.crypto.Cipher.ENCRYPT_MODE)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val all = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, all, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, all, cipher.iv.size, encrypted.size)
        android.util.Base64.encodeToString(all, android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        android.util.Log.e("EXCHANGE_VAULT", "Encryption failed", e)
        ""
    }
}

private fun vaultDecrypt(value: String): String {
    if (value.isBlank()) return ""
    return try {
        val all = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
        if (all.size <= 12) return ""
        val iv = all.copyOfRange(0, 12)
        val encrypted = all.copyOfRange(12, all.size)
        val cipher = vaultCipher(javax.crypto.Cipher.DECRYPT_MODE, iv)
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    } catch (_: Exception) {
        // Backward compatibility with the previous plaintext local vault.
        value
    }
}

private fun saveExchangeCredentialsLocal(
    ctx: Context,
    spec: ExchangeSpec,
    apiKey: String,
    secret: String,
    passphrase: String
): Boolean {
    if (apiKey.isBlank() || secret.isBlank()) return false
    val encryptedKey = vaultEncrypt(apiKey.trim())
    val encryptedSecret = vaultEncrypt(secret.trim())
    val encryptedPass = if (passphrase.isBlank()) "" else vaultEncrypt(passphrase.trim())
    if (encryptedKey.isBlank() || encryptedSecret.isBlank()) return false
    return exchangePrefs(ctx).edit()
        .putString(exchangePrefKey(spec, "api"), encryptedKey)
        .putString(exchangePrefKey(spec, "secret"), encryptedSecret)
        .putString(exchangePrefKey(spec, "pass"), encryptedPass)
        .putBoolean(exchangePrefKey(spec, "saved"), true)
        .putString("selected_exchange", spec.name)
        .putString("selected_region", spec.region)
        .commit()
}

private fun loadExchangeCredentialsLocal(
    ctx: Context,
    spec: ExchangeSpec
): Triple<String, String, String>? {
    val p = exchangePrefs(ctx)
    if (!p.getBoolean(exchangePrefKey(spec, "saved"), false)) return null
    val key = vaultDecrypt(p.getString(exchangePrefKey(spec, "api"), "").orEmpty())
    val secret = vaultDecrypt(p.getString(exchangePrefKey(spec, "secret"), "").orEmpty())
    val storedPass = p.getString(exchangePrefKey(spec, "pass"), "").orEmpty()
    val pass = if (storedPass.isBlank()) "" else vaultDecrypt(storedPass)
    if (key.isBlank() || secret.isBlank()) return null
    return Triple(key, secret, pass)
}

private fun savedExchangeName(ctx: Context): String =
    exchangePrefs(ctx).getString("selected_exchange", "").orEmpty()

private fun savedExchangeRegion(ctx: Context): String =
    exchangePrefs(ctx).getString("selected_region", "").orEmpty()

@Composable
fun AccountApiDialogV2(viewModel: com.example.mycompose.hello.viewmodel.TradingViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val restoredName = remember { savedExchangeName(ctx) }
    val restoredRegion = remember { savedExchangeRegion(ctx) }
    var region by remember { mutableStateOf(if (restoredRegion == "INTL") "INTL" else "IN") }
    var sel by remember {
        mutableStateOf(
            ALL_EXCHANGES.firstOrNull { it.name.equals(restoredName, ignoreCase = true) }
                ?: ALL_EXCHANGES.first { it.region == region }
        )
    }
    var apiKey by remember { mutableStateOf(regKey.value) }
    var secretKey by remember { mutableStateOf(regSecret.value) }
    var passphrase by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf("idle") }
    var result by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var myIp by remember { mutableStateOf("") }
    
    val scope = rememberCoroutineScope()
    val ipSync = remember { DeltaIpAutoSyncManager(ctx, scope) }
    val connectStatus by viewModel.connectStatus.collectAsState()
    
    val inf = rememberInfiniteTransition(label = "vault")
    val sweep by inf.animateFloat(0f, 360f, animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "sweep")
    val ring by inf.animateFloat(0f, 360f, animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), repeatMode = RepeatMode.Restart), label = "ring")
    val pulse by inf.animateFloat(0.4f, 1f, animationSpec = infiniteRepeatable(tween(700), repeatMode = RepeatMode.Reverse), label = "pulse")
    
    val list = remember(region) { visibleExchanges(region, "") }
    
    LaunchedEffect(region) {
        val v = visibleExchanges(region, "")
        if (v.isNotEmpty() && sel !in v) {
            val savedName = savedExchangeName(ctx)
            sel = v.firstOrNull { it.name.equals(savedName, ignoreCase = true) } ?: v.first()
        }
    }
    
    DisposableEffect(Unit) {
        ipSync.start { snapshot ->
            myIp = snapshot.deltaSeenIp.ifBlank { snapshot.ipv4.ifBlank { snapshot.ipv6 } }
        }
        onDispose { ipSync.stop() }
    }

    // Restore the selected exchange credentials every time this dialog is recreated.
    // After app process death, the credentials are loaded and the official exchange
    // endpoint is verified again; the app does not fake a LIVE state.
    LaunchedEffect(sel.name) {
        val saved = loadExchangeCredentialsLocal(ctx, sel)
        if (saved != null) {
            apiKey = saved.first
            secretKey = saved.second
            passphrase = saved.third
            phase = "connecting"
            result = "🔄 Restoring saved ${sel.name} credentials…"
            val r = connectExchangeV3(sel, saved.first, saved.second, saved.third)
            ipSync.recordDeltaResponse(r)?.let { myIp = it }
            connected = r.startsWith("✅")
            result = if (connected) {
                val snapshot = readLiveBalanceV3(sel, saved.first, saved.second, saved.third)
                if (snapshot != null) {
                    viewModel.setExternalLiveBalance(sel.name, snapshot)
                    viewModel.startExternalBalancePolling(sel.name) {
                        readLiveBalanceV3(sel, saved.first, saved.second, saved.third)
                    }
                }
                r + "\n💾 Saved credentials restored • LIVE re-authenticated\n" +
                    (if (snapshot != null) {
                        "💰 LIVE ${snapshot.total} ${snapshot.currency} • ${snapshot.holdings.size} holdings"
                    } else {
                        "⚠️ Connected, but live balance read failed"
                    })
            } else {
                "❌ Saved ${sel.name} credentials found, but re-authentication failed.\n" + r
            }
            phase = "done"
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .background(Color(0xFF050505)),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF050505))
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                // ── Header ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).rotate(ring).background(Brush.sweepGradient(listOf(Color(0xFFFF1744), Color(0xFFFFEA00), Color(0xFF00E676), Color(0xFF2979FF), Color(0xFFFF1744))), CircleShape), contentAlignment = Alignment.Center) { 
                        Box(Modifier.size(20.dp).background(Color(0xFF0E1116), CircleShape), contentAlignment = Alignment.Center) { Text("🔐", fontSize = 11.sp) } 
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SECURE VAULT • ${ALL_EXCHANGES.size} EXCHANGES", color = Color(0xFFFF8A1E), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                        Text("Connect Exchange", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Surface(shape = RoundedCornerShape(20.dp), color = if (connected) Color(0xFF0ECB81).copy(alpha = 0.18f) else Color(0xFFFFB300).copy(alpha = 0.16f * pulse)) { 
                        Text(if (connected) "● LIVE • ${sel.name}" else "● ${connectStatus.take(14)}", color = if (connected) Color(0xFF0ECB81) else Color(0xFFFFB300), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) 
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("✖", color = Color(0xFF848E9C), fontSize = 20.sp, modifier = Modifier.clickable { onDismiss() })
                }
                Spacer(Modifier.height(8.dp))
                
                // ── Tabs ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF050505), RoundedCornerShape(12.dp))
                        .padding(4.dp)
                ) {
                    listOf("IN" to "🇮🇳 India", "INTL" to " International").forEach { (k, lab) ->
                        val isSelected = region == k
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { region = k }
                                .background(Color(0xFF050505), RoundedCornerShape(4.dp))
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(lab, color = if (isSelected) Color.White else Color(0xFF848E9C), fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(1.dp))
                
                // ─ Exchange List Section ──
                ExchangeListSection(
                    list = list,
                    selected = sel,
                    onExchangeSelected = { ex -> 
                        sel = ex
                        phase = "idle"
                        result = ""
                    }
                )
                
                Spacer(Modifier.height(1.dp))
                
                // ── Detail Panel Section ──
                ExchangeDetailPanel(
                    sel = sel,
                    apiKey = apiKey,
                    secretKey = secretKey,
                    passphrase = passphrase,
                    phase = phase,
                    result = result,
                    myIp = myIp,
                    pulse = pulse,
                    ring = ring,
                    onApiKeyChange = { apiKey = it },
                    onSecretKeyChange = { secretKey = it },
                    onPassphraseChange = { passphrase = it },
                    onConnectClick = {
                        phase = "connecting"
                        result = ""
                        connected = false
                        scope.launch {
                            val r = connectExchangeV3(sel, apiKey.trim(), secretKey.trim(), passphrase.trim())
                            ipSync.recordDeltaResponse(r)?.let { myIp = it }
                            connected = r.startsWith("✅")
                            if (connected) {
                                // Persist credentials for this exact exchange before the dialog closes.
                                val saved = saveExchangeCredentialsLocal(
                                    ctx = ctx,
                                    spec = sel,
                                    apiKey = apiKey.trim(),
                                    secret = secretKey.trim(),
                                    passphrase = passphrase.trim()
                                )
                                if (sel.name.equals("CoinDCX", ignoreCase = true)) {
                                    viewModel.saveApiKeys(apiKey.trim(), secretKey.trim())
                                }
                                regKey.value = apiKey.trim()
                                regSecret.value = secretKey.trim()
                                if (myIp.isNotBlank()) viewModel.saveIPAddress(myIp)
                                val saveText = if (saved) "\n💾 Saved — auto reconnect enabled" else "\n⚠️ Could not persist credentials"
                                val snapshot = readLiveBalanceV3(sel, apiKey.trim(), secretKey.trim(), passphrase.trim())
                                if (snapshot != null) {
                                    viewModel.setExternalLiveBalance(sel.name, snapshot)
                                    viewModel.startExternalBalancePolling(sel.name) {
                                        readLiveBalanceV3(sel, apiKey.trim(), secretKey.trim(), passphrase.trim())
                                    }
                                }
                                result = r + saveText + "\n" +
                                    (if (snapshot != null) {
                                        "💰 LIVE ${snapshot.total} ${snapshot.currency} • ${snapshot.holdings.size} holdings"
                                    } else {
                                        "⚠️ Connected, but live balance read failed"
                                    })
                            } else {
                                result = r
                            }
                            phase = "done"
                        }
                    },
                    ctx = ctx
                )
                
                Spacer(Modifier.height(4.dp))
                Text("${ALL_EXCHANGES.size} exchanges  •  read + trade scopes  •  withdraw OFF  •  IP-aware", color = Color(0xFF5E6673), fontSize = 9.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

// ==========================================
// FUNCTION 2: Exchange List Section
// ==========================================
@Composable
fun ExchangeListSection(
    list: List<ExchangeSpec>,
    selected: ExchangeSpec,
    onExchangeSelected: (ExchangeSpec) -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().fillMaxHeight(0.45f)
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp)
        ) {
            if (list.isEmpty()) {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(20.dp), 
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) { 
                    androidx.compose.material3.Text("koi exchange nahi mila", color = androidx.compose.ui.graphics.Color(0xFF848E9C), fontSize = 10.sp) 
                }
            }
            list.forEach { ex -> 
                ExRow(ex, ex == selected) { onExchangeSelected(ex) } 
            }
        }
    }
}

// ==========================================
// FUNCTION 3: Exchange Detail Panel
// ==========================================
@Composable
fun ExchangeDetailPanel(
    sel: ExchangeSpec,
    apiKey: String,
    secretKey: String,
    passphrase: String,
    phase: String,
    result: String,
    myIp: String,
    pulse: Float,
    ring: Float,
    onApiKeyChange: (String) -> Unit,
    onSecretKeyChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    ctx: android.content.Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF050505))
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔐 ${sel.name} — permissions to enable", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Surface(onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(sel.docs))) }, shape = RoundedCornerShape(8.dp), color = Color(sel.accent).copy(alpha = 0.2f)) { 
                    Text("📖 Docs", color = Color(sel.accent), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) 
                }
            }
            permissionChecklist(sel).forEach { item -> 
                Row(verticalAlignment = Alignment.Top) { 
                    Box(Modifier.size(15.dp).clip(CircleShape).background(Color(sel.accent)), contentAlignment = Alignment.Center) { 
                        Text("✓", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) 
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(item, color = Color(0xFFC7CCD4), fontSize = 11.sp, modifier = Modifier.weight(1f)) 
                } 
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { 
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF222A35)) { 
                    Text("host: ${sel.host}", color = Color(0xFF848E9C), fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)) 
                }
                Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF222A35)) { 
                    Text("auth: ${sel.style}", color = Color(0xFF848E9C), fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)) 
                } 
            }
            OutlinedTextField(
                value = apiKey, onValueChange = { onApiKeyChange(it) }, label = { Text("🔑 API Key", fontSize = 11.sp) }, 
                modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF3A3F47), unfocusedBorderColor = Color(0xFF3A3F47))
            )
            OutlinedTextField(
                value = secretKey, onValueChange = { onSecretKeyChange(it) }, label = { Text("🗝️ Secret Key", fontSize = 11.sp) }, 
                modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF3A3F47), unfocusedBorderColor = Color(0xFF3A3F47))
            )
            if (sel.pass) {
                OutlinedTextField(
                    value = passphrase, onValueChange = { onPassphraseChange(it) }, label = { Text(" Passphrase", fontSize = 11.sp) }, 
                    modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF3A3F47), unfocusedBorderColor = Color(0xFF3A3F47))
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) { 
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (myIp.isNotBlank()) Color(0xFF0ECB81) else Color(0xFFFFB300).copy(alpha = pulse)))
                Spacer(Modifier.width(6.dp))
                Text(if (myIp.isNotBlank()) "🌐 current egress IP $myIp • Delta whitelist status" else "🔄 IP detect ho raha hai…", color = if (myIp.isNotBlank()) Color(0xFF0ECB81) else Color(0xFFFFB300), fontSize = 9.sp) 
            }
            when (phase) {
                "connecting" -> Row(verticalAlignment = Alignment.CenterVertically) { 
                    Box(Modifier.size(16.dp).rotate(ring).background(Brush.sweepGradient(listOf(Color(0xFFFF8A1E), Color.Transparent)), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("Signing with ${sel.name}…", color = Color(0xFFFF8A1E), fontSize = 11.sp) 
                }
                "done" -> Text(result, color = if (result.startsWith("✅")) Color(0xFF0ECB81) else if (result.startsWith("")) Color(0xFFF6465D) else Color(0xFFFFB300), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = phase != "connecting", 
                onClick = { onConnectClick() }, 
                modifier = Modifier.fillMaxWidth().height(46.dp), 
                shape = RoundedCornerShape(12.dp), 
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8A1E), contentColor = Color.Black, disabledContainerColor = Color(0xFF3A3F47))
            ) { 
                Text(if (phase == "connecting") "Connecting…" else "Save & Connect  •  ${sel.name}", fontWeight = FontWeight.Bold, fontSize = 13.sp) 
            }
        }
    }
}

private val regKey = androidx.compose.runtime.mutableStateOf("")
private val regSecret = androidx.compose.runtime.mutableStateOf("")
