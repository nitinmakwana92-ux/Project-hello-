package com.example.mycompose.hello.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mycompose.hello.viewmodel.TradingViewModel
import com.example.mycompose.hello.service.TradingBotService

private val StylishDarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFB388FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1A102B),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF2A1E4A),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFF0E6FF),
    secondary = androidx.compose.ui.graphics.Color(0xFF7C4DFF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF24164A),
    tertiary = androidx.compose.ui.graphics.Color(0xFF00D9FF),
    background = androidx.compose.ui.graphics.Color(0xFF08070C),
    surface = androidx.compose.ui.graphics.Color(0xFF100E16),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF211D2B),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF5F1FA),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF5F1FA),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBDB5C8)
)

object ThemeState {
    var isDark by mutableStateOf(true)
    private const val PREF = "trading_bot_prefs"
    fun init(ctx: android.content.Context) {
        isDark = true
    }
    fun toggle(ctx: android.content.Context) {
        // App theme is intentionally dark-only.
        isDark = true
        ctx.applicationContext.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("dark_mode", true).apply()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeState.init(this)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // Restore the persistent foreground bot whenever the UI process starts.
        // The ON flag is written only by the explicit bot ON/OFF actions, so
        // reopening the app does not silently turn an enabled bot off.
        val botPrefs = getSharedPreferences("trading_bot_prefs", MODE_PRIVATE)
        if (botPrefs.getBoolean("auto_bot_enabled", false)) {
            runCatching { TradingBotService.start(applicationContext) }
        }

        setContent {
            val viewModel: TradingViewModel = viewModel(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return TradingViewModel(application) as T
                    }
                }
            )
            MaterialTheme(colorScheme = StylishDarkColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TradingDashboard(viewModel)
                }
            }
        }
    }
}
