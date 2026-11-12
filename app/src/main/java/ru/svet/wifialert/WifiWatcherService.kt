package ru.svet.wifialert

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Фоновый сервис: каждые SCAN_INTERVAL_MS запускает поиск Wi-Fi сетей.
 * Когда нужная сеть ПОЯВЛЯЕТСЯ (раньше её не было) — показывает уведомление
 * и произносит фразу. Повторно сработает только после того, как сеть пропадёт
 * и появится снова.
 */
class WifiWatcherService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "SvetWatcher"
        private const val CH_SERVICE = "svc"
        private const val CH_ALERT = "alert2"
        private const val ID_SERVICE = 1
        private const val ID_ALERT = 2

        /** Как часто сканировать. Android разрешает ~4 скана за 2 минуты. */
        private const val SCAN_INTERVAL_MS = 30_000L

        /** Сколько сканов подряд сеть должна отсутствовать, чтобы считать её пропавшей. */
        private const val MISSES_TO_RESET = 3

        const val ACTION_TEST = "ru.svet.wifialert.TEST"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WifiWatcherService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WifiWatcherService::class.java))
        }

        fun test(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, WifiWatcherService::class.java).setAction(ACTION_TEST)
            )
        }
    }

    private lateinit var wifi: WifiManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak = false

    private var networkVisible = false
    private var misses = 0

    private val scanLoop = object : Runnable {
        override fun run() {
            try {
                @Suppress("DEPRECATION")
                val started = wifi.startScan()
                if (!started) checkResults() // скан отклонён (лимит) — берём последние известные результаты
            } catch (e: Exception) {
                Log.w(TAG, "startScan failed", e)
            }
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkResults()
    }

    override fun onCreate() {
        super.onCreate()
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        createChannels()
        goForeground()

        // Держим процессор активным при выключенном экране, чтобы сканирование не засыпало
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Svet::Watcher").apply {
            setReferenceCounted(false)
            acquire()
        }

        tts = TextToSpeech(this, this)

        ContextCompat.registerReceiver(
            this, scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED // системный broadcast
        )
        handler.post(scanLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_TEST) alert()
        return START_STICKY // система перезапустит сервис, если убьёт его
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(scanReceiver) }
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Логика обнаружения ----------

    @SuppressLint("MissingPermission")
    private fun checkResults() {
        val target = Prefs.ssid(this)
        if (target.isEmpty()) return

        val results = try {
            wifi.scanResults
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for scan results", e)
            return
        }

        val found = results.any { ssidOf(it).equals(target, ignoreCase = true) }

        if (found) {
            misses = 0
            if (!networkVisible) {
                networkVisible = true
                alert()
            }
        } else if (networkVisible) {
            misses++
            if (misses >= MISSES_TO_RESET) {
                networkVisible = false
                misses = 0
            }
        }
        updateServiceNotification()
    }

    private fun ssidOf(r: android.net.wifi.ScanResult): String {
        val raw = if (Build.VERSION.SDK_INT >= 33) {
            r.wifiSsid?.toString() ?: ""
        } else {
            @Suppress("DEPRECATION") r.SSID ?: ""
        }
        return raw.trim().removeSurrounding("\"")
    }

    // ---------- Срабатывание ----------

    private fun alert() {
        showAlertNotification()
        speak()
    }

    private fun speak() {
        if (!ttsReady) {
            pendingSpeak = true
            return
        }
        tts?.speak(Prefs.PHRASE, TextToSpeech.QUEUE_FLUSH, null, "svet")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed: $status")
            return
        }
        tts?.apply {
            val res = setLanguage(Locale("ru", "RU"))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Русский голос не установлен")
            }
            // Канал "будильник": фраза слышна даже при выключенном экране
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        ttsReady = true
        if (pendingSpeak) {
            pendingSpeak = false
            speak()
        }
    }

    // ---------- Уведомления ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Слежение за Wi-Fi", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Свет дали", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Уведомление о появлении Wi-Fi сети"
                enableVibration(true)
                setSound(null, null) // звук — это голосовая фраза
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildServiceNotification(): Notification {
        val ssid = Prefs.ssid(this)
        val state = if (networkVisible) "сеть рядом ✅" else "сети пока нет"
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_light)
            .setContentTitle("Жду Wi-Fi «$ssid»")
            .setContentText(state)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, ID_SERVICE, buildServiceNotification(), type)
    }

    private fun updateServiceNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(ID_SERVICE, buildServiceNotification())
        }
    }

    private fun showAlertNotification() {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_light)
            .setContentTitle("Свет")
            .setContentText(Prefs.PHRASE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(Prefs.PHRASE))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(ID_ALERT, n)
        }
        // Включаем экран на пару секунд, чтобы уведомление было видно
        @Suppress("DEPRECATION")
        runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Svet::Screen"
            ).acquire(5_000)
        }
    }
}
