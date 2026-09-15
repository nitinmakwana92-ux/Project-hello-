package com.example.mycompose.hello.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.mycompose.hello.viewmodel.TradingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The permanent background owner of Auto Trading Bot.
 *
 * The Activity can be destroyed/swiped away without stopping this foreground
 * service. The service is recreated with START_STICKY after normal process
 * pressure. Only the explicit ACTION_STOP command clears auto_bot_enabled.
 *
 * IMPORTANT: this service never uses a SharedPreferences FALSE read as a
 * reason to self-stop. That avoids cross-process SharedPreferences cache
 * problems when the service is hosted in :bot.
 */
class TradingBotService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var viewModel: TradingViewModel? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        acquireWakeLock()

        // The service itself is the persistent owner of the bot.
        // Do NOT read auto_bot_enabled here to decide whether to start.
        //
        // SharedPreferences can be cached independently when Android runs
        // components in different processes. The old implementation could
        // therefore see a stale FALSE after the Activity had saved TRUE and
        // immediately stop the service when the app was closed.
        //
        // Explicit OFF is handled only by ACTION_STOP -> onStartCommand().
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Auto Trading Bot • starting")
        )

        // This ViewModel is manually owned by the foreground service. It is
        // NOT the Activity's Compose ViewModel and therefore is not cleared
        // when MainActivity is destroyed/swiped from Recents.
        viewModel = TradingViewModel.getServiceInstance(application)

        serviceScope.launch {
            delay(150)

            ensureRuntime()

            while (isActive) {
                // The foreground service remains alive for BOTH modes:
                // 1) new-entry bot ON, and
                // 2) position/pending-order manager ON after the UI bot is OFF.
                ensureRuntime()

                updateNotification(
                    when {
                        viewModel?.isBotActuallyRunning() == true ->
                            "Auto Trading Bot • LIVE • new entries + position manager ON"
                        else ->
                            "AI Position Manager • LIVE • pending orders / profit exits ON"
                    }
                )

                scheduleWatchdog(applicationContext)
                delay(WATCHDOG_LOOP_MS)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        // Cross-process OFF: disable NEW entries but keep the position manager
        // alive for existing positions and profit exits.
        if (intent?.action == ACTION_DISABLE_NEW_ENTRIES) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ENABLED, false)
                .putBoolean(PREF_MANAGER_ENABLED, true)
                .commit()
            // New entries are disabled through PREF_ENABLED=false above.
            // Keep the service/position manager alive so existing positions
            // can still be managed and profit exits can execute.
            return START_STICKY
        }

        // ONLY the user's explicit full-stop action is allowed to stop the bot.
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ENABLED, false)
                .putBoolean(PREF_MANAGER_ENABLED, false)
                .apply()

            // Existing shutdown path is used only here.
            viewModel?.stopBotFromService()

            cancelWatchdog(applicationContext)

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)

            return START_NOT_STICKY
        }

        // Normal start, Android recreation, watchdog restart, or Activity
        // reopening. Never turn the bot OFF because a preference read failed
        // or was stale in another process.
        ensureRuntime()
        scheduleWatchdog(applicationContext)

        // Android may recreate a killed service. On recreation, onCreate()
        // creates the service-owned ViewModel and starts the runner again.
        return START_STICKY
    }

    private fun ensureRuntime() {
        if (viewModel == null) {
            viewModel = TradingViewModel.getServiceInstance(application)
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val botEnabled = prefs.getBoolean(PREF_ENABLED, false)
        val managerEnabled = prefs.getBoolean(PREF_MANAGER_ENABLED, false)

        when {
            botEnabled -> {
                viewModel?.startBotFromService()
                viewModel?.startPositionManagerFromService()
            }
            managerEnabled -> {
                viewModel?.startPositionManagerFromService()
            }
            else -> {
                viewModel?.stopPositionManagerFromService()
                stopSelf()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from Recents is NOT an OFF command.
        // Keep the foreground service alive and schedule recovery.
        runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(
                    "Auto Trading Bot • LIVE • app closed, trading continues"
                )
            )
        }

        scheduleWatchdog(applicationContext)

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // IMPORTANT: never clear auto_bot_enabled here. Android/OEM may destroy
        // a service temporarily and recreate it; the persisted ON state survives.
        serviceScope.cancel()
        releaseWakeLock()
        viewModel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "hello:AutoTradingBot"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Trading Bot",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent Auto Trading Bot status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = openIntent?.let {
            PendingIntent.getActivity(
                this,
                7402,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Auto Trading Bot")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val CHANNEL_ID = "auto_trading_bot"
        private const val NOTIFICATION_ID = 7401
        private const val PREFS_NAME = "trading_bot_prefs"
        private const val PREF_ENABLED = "auto_bot_enabled"
        private const val PREF_MANAGER_ENABLED = "position_manager_enabled"
        private const val WATCHDOG_REQUEST_CODE = 7403
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val WATCHDOG_LOOP_MS = 30_000L

        const val ACTION_START = "com.example.mycompose.hello.bot.START"
        const val ACTION_STOP = "com.example.mycompose.hello.bot.STOP"
        const val ACTION_DISABLE_NEW_ENTRIES = "com.example.mycompose.hello.bot.DISABLE_NEW_ENTRIES"

        fun start(context: Context) {
            val appContext = context.applicationContext
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ENABLED, true)
                .putBoolean(PREF_MANAGER_ENABLED, true)
                .apply()
            val intent = Intent(appContext, TradingBotService::class.java)
                .setAction(ACTION_START)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            scheduleWatchdog(appContext)
        }

        fun startPositionManager(context: Context) {
            val appContext = context.applicationContext
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_MANAGER_ENABLED, true)
                .putBoolean(PREF_ENABLED, false)
                .apply()
            val intent = Intent(appContext, TradingBotService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            scheduleWatchdog(appContext)
        }

        fun disableNewEntries(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, TradingBotService::class.java)
                .setAction(ACTION_DISABLE_NEW_ENTRIES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ENABLED, false)
                .putBoolean(PREF_MANAGER_ENABLED, false)
                .apply()
            cancelWatchdog(appContext)
            appContext.stopService(Intent(appContext, TradingBotService::class.java).setAction(ACTION_STOP))
        }

        fun scheduleWatchdog(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(appContext, AutoBotBackgroundReceiver::class.java)
                .setAction(AutoBotBackgroundReceiver.ACTION_WATCHDOG)
            val pending = PendingIntent.getBroadcast(
                appContext,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pending
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pending
                )
            }
        }

        fun cancelWatchdog(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(appContext, AutoBotBackgroundReceiver::class.java)
                .setAction(AutoBotBackgroundReceiver.ACTION_WATCHDOG)
            val pending = PendingIntent.getBroadcast(
                appContext,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }
}
