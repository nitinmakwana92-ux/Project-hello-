package com.example.mycompose.hello.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Power-efficient background trading support.
 *
 * Android does not allow an app to silently force the device-wide Battery
 * Saver mode off/on or silently grant itself an unrestricted battery policy.
 * For continuous trading the correct model is a foreground service plus,
 * when the user grants it, exemption from battery optimization for this app.
 */
object PowerEfficiencyManager {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestOptimizationExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
