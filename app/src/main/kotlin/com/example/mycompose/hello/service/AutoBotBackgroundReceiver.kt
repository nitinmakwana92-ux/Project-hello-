package com.example.mycompose.hello.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the persistent Auto Trading Bot only when the user has left it ON.
 * Used for device reboot/package replacement and the lightweight watchdog alarm.
 */
class AutoBotBackgroundReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_ENABLED, false)) return

        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_WATCHDOG -> {
                TradingBotService.start(context.applicationContext)
                TradingBotService.scheduleWatchdog(context.applicationContext)
            }
        }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.example.mycompose.hello.bot.WATCHDOG"
        private const val PREFS_NAME = "trading_bot_prefs"
        private const val PREF_ENABLED = "auto_bot_enabled"
    }
}
