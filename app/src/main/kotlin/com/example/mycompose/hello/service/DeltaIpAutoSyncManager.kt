package com.example.mycompose.hello.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Keeps the app's view of the current public egress IP synchronized.
 *
 * Important:
 * - Delta may see IPv4 OR IPv6. We therefore discover both.
 * - If Delta returns client_ip in an IP-whitelist error, that exact IP is
 *   treated as authoritative and stored.
 * - The app can automatically refresh/re-authenticate its own state.
 * - Delta's API documentation does NOT expose a public endpoint for changing
 *   an API-key whitelist. Therefore this class never pretends it can mutate
 *   the exchange whitelist. The user must add the newly observed IP in Delta
 *   API Management when the key is IP restricted.
 */
class DeltaIpAutoSyncManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DeltaIpAutoSync"
        private const val PREFS = "exchange_ip_sync_v2"
        private const val KEY_IPV4 = "public_ipv4"
        private const val KEY_IPV6 = "public_ipv6"
        private const val KEY_DELTA_SEEN = "delta_seen_client_ip"
        private const val KEY_LAST_SYNC = "last_sync_ms"
        private const val SYNC_INTERVAL_MS = 60 * 1000L

        // These endpoints return only the caller's address and do not require
        // exchange credentials.
        private const val IPV4_URL = "https://api4.ipify.org"
        private const val IPV6_URL = "https://api6.ipify.org"

        // Fixed IPv4 whitelist candidates requested for Delta integrations.
        // They are always retained in the local whitelist candidate list.
        // The app does not silently mutate an exchange-side API whitelist.
        const val ALGOTEST_IPV4 = "146.190.8.95"
        const val TRADETRON_IPV4 = "103.216.94.212"

        val FIXED_WHITELIST_IPV4: List<String> = listOf(
            ALGOTEST_IPV4,
            TRADETRON_IPV4
        )
    }

    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var job: Job? = null

    fun start(onSnapshot: (Snapshot) -> Unit = {}) {
        if (job?.isActive == true) return

        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val snapshot = syncNow()
                    withContext(Dispatchers.Main.immediate) { onSnapshot(snapshot) }
                }.onFailure { Log.w(TAG, "IP sync failed: ${it.message}") }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun syncNow(): Snapshot {
        val ipv4 = fetchText(IPV4_URL)
            .takeIf(::isIpv4)

        val ipv6 = fetchText(IPV6_URL)
            .takeIf(::isIpv6)

        val old4 = prefs.getString(KEY_IPV4, "").orEmpty()
        val old6 = prefs.getString(KEY_IPV6, "").orEmpty()

        // Never overwrite a valid previously detected address with an empty
        // value when one IP-family endpoint is temporarily unavailable.
        // This keeps IPv4 + IPv6 whitelist candidates stable across renewals.
        val activeIpv4 = ipv4 ?: old4.takeIf(::isIpv4).orEmpty()
        val activeIpv6 = ipv6 ?: old6.takeIf(::isIpv6).orEmpty()

        prefs.edit()
            .putString(KEY_IPV4, activeIpv4)
            .putString(KEY_IPV6, activeIpv6)
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply()

        if (activeIpv4.isNotBlank() && activeIpv4 != old4) {
            Log.i(TAG, "IPv4 changed: $old4 -> $activeIpv4")
        }
        if (activeIpv6.isNotBlank() && activeIpv6 != old6) {
            Log.i(TAG, "IPv6 changed: $old6 -> $activeIpv6")
        }

        return Snapshot(
            ipv4 = activeIpv4,
            ipv6 = activeIpv6,
            deltaSeenIp = prefs.getString(KEY_DELTA_SEEN, "").orEmpty(),
            changed = ipv4 != old4 || ipv6 != old6
        )
    }

    /**
     * Call this with the raw Delta error response after an authenticated
     * request fails. Delta's documented error contains:
     * {"error":{"code":"ip_not_whitelisted_for_api_key",
     *            "context":{"client_ip":"..."}}}
     */
    fun recordDeltaResponse(raw: String): String? {
        val ip = parseDeltaClientIp(raw) ?: return null

        prefs.edit()
            .putString(KEY_DELTA_SEEN, ip)
            .apply()

        Log.w(TAG, "Delta authoritative client_ip=$ip")
        return ip
    }

    fun currentIpv4(): String =
        prefs.getString(KEY_IPV4, "").orEmpty()

    fun currentIpv6(): String =
        prefs.getString(KEY_IPV6, "").orEmpty()

    fun lastDeltaSeenIp(): String =
        prefs.getString(KEY_DELTA_SEEN, "").orEmpty()

    /**
     * Returns every currently known address in a Delta-whitelist-ready,
     * comma-separated form. IPv4 and IPv6 are kept together instead of
     * selecting only one family.
     */
    fun whitelistIpList(includeAlgoProviders: Boolean = true): List<String> {
        val ips = linkedSetOf<String>()
        currentIpv4().takeIf(::isIpv4)?.let(ips::add)
        currentIpv6().takeIf(::isIpv6)?.let(ips::add)
        lastDeltaSeenIp()
            .trim()
            .takeIf { isValidIp(it) }
            ?.let(ips::add)

        if (includeAlgoProviders) {
            FIXED_WHITELIST_IPV4
                .filter(::isIpv4)
                .forEach(ips::add)
        }
        return ips.toList()
    }

    fun whitelistIpCsv(includeAlgoProviders: Boolean = true): String =
        whitelistIpList(includeAlgoProviders).joinToString(",")

    fun whitelistHint(): String {
        val ips = whitelistIpList(includeAlgoProviders = true)
        return if (ips.isEmpty()) "Delta IP not detected yet" else ips.joinToString(", ")
    }

    data class Snapshot(
        val ipv4: String,
        val ipv6: String,
        val deltaSeenIp: String,
        val changed: Boolean
    )

    private fun fetchText(endpoint: String): String {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            useCaches = false
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "HelloTradingBot/1.0 Android")
        }

        return try {
            if (c.responseCode in 200..299) {
                c.inputStream.bufferedReader().use { it.readText().trim() }
            } else {
                ""
            }
        } finally {
            c.disconnect()
        }
    }

    private fun parseDeltaClientIp(raw: String): String? {
        if (raw.isBlank()) return null

        return runCatching {
            val root = JSONObject(raw)
            val error = root.optJSONObject("error")
            val code = error?.optString("code").orEmpty()

            if (!code.equals("ip_not_whitelisted_for_api_key", true) &&
                !code.equals("ip_not_whitelisted", true)
            ) {
                return@runCatching null
            }

            error?.optJSONObject("context")
                ?.optString("client_ip")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
            ?: Regex(
                "\"client_ip\"\\s*:\\s*\"([^\"]+)\"",
                RegexOption.IGNORE_CASE
            ).find(raw)?.groupValues?.getOrNull(1)
    }

    private fun isIpv4(value: String): Boolean =
        Regex(
            "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}" +
                "(25[0-5]|2[0-4]\\d|1?\\d?\\d)$"
        ).matches(value.trim())

    private fun isIpv6(value: String): Boolean =
        value.contains(":") &&
            value.length <= 80 &&
            value.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' || it == '-' }

    private fun isValidIp(value: String): Boolean =
        isIpv4(value) || isIpv6(value)
}
