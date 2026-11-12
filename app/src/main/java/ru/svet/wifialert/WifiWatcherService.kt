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
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Фоновый сервис: регулярно ищет Wi-Fi сети. Когда нужная сеть ПОЯВЛЯЕТСЯ —
 * будит экран (полноэкранное оповещение, как у будильника), показывает уведомление
 * и проигрывает звук res/raw/svet_sound.mp3.
 *
 * Для прошивок, которые усыпляют фон (Huawei, Xiaomi…), используются сразу три механизма:
 *  - partial wake lock: процессор не засыпает;
 *  - Wi-Fi lock: Wi-Fi не отключается при выключенном экране;
 *  - AlarmManager.setAlarmClock: будит планшет раз в минуту, как приложение «Часы»
 *    (на Huawei обычные таймеры в глубоком сне не срабатывают).
 */
class WifiWatcherService : Service() {

    companion object {
        private const val TAG = "SvetWatcher"
        private const val CH_SERVICE = "svc"
        private const val CH_ALERT = "alarm3"
        private const val ID_SERVICE = 1
        const val ID_ALERT = 2

        /** Android разрешает 4 скана за 2 минуты — держимся чуть реже. */
        private const val SCAN_INTERVAL_MS = 35_000L
        private const val MIN_SCAN_GAP_MS = 30_000L
        /** Результат скана старше этого считается устаревшим (кэш). */
        private const val FRESH_MS = 45_000L
        /** Если сеть вернулась быстрее — это не отключение света, а помеха. */
        private const val MIN_OUTAGE_MS = 60_000L
        private const val ALARM_INTERVAL_MS = 60_000L
        private const val MISSES_TO_RESET = 2

        const val ACTION_TEST = "ru.svet.wifialert.TEST"
        const val ACTION_STOP_SOUND = "ru.svet.wifialert.STOP_SOUND"
        const val ACTION_TICK = "ru.svet.wifialert.TICK"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WifiWatcherService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WifiWatcherService::class.java))
        }

        fun test(ctx: Context, delayMs: Long = 0) {
            if (delayMs > 0) {
                // Через системный будильник — сработает и в глубоком сне
                AlarmReceiver.schedule(ctx, AlarmReceiver.ACTION_TEST, delayMs)
            } else {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, WifiWatcherService::class.java).setAction(ACTION_TEST)
                )
            }
        }

        fun stopSound(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, WifiWatcherService::class.java).setAction(ACTION_STOP_SOUND))
            }
        }
    }

    private lateinit var wifi: WifiManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** -1 — ещё не знаем, 0 — сети нет, 1 — сеть есть. Хранится в Prefs, переживает перезапуск. */
    private var state = -1
    private val networkVisible get() = state == 1
    private var misses = 0
    private var lostAt = 0L            // когда сеть пропала (System.currentTimeMillis)
    private var lastScanAttempt = 0L
    private var lastFreshScanTs = 0L   // время самого свежего результата скана, мкс
    private var connectedTarget: Network? = null

    private lateinit var connectivity: ConnectivityManager

    /** Второй способ: планшет сам подключился к нужной (сохранённой) сети. */
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = checkConnection(network)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            checkConnection(network)

        override fun onLost(network: Network) {
            if (network == connectedTarget) {
                connectedTarget = null
                Prefs.logConnection(this@WifiWatcherService, "отключился")
                handler.post { onAbsent("отключение") }
            }
        }
    }

    private val scanLoop = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            requestScan()
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkResults()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) Prefs.screenOff(context)
        }
    }

    // ---------- Жизненный цикл ----------

    override fun onCreate() {
        super.onCreate()
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        state = Prefs.state(this)
        lostAt = Prefs.lostAt(this)
        createChannels()
        goForeground()

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlarmReceiver.HUAWEI_SAFE_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Svet::Wifi").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        ContextCompat.registerReceiver(
            this, scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED // системный broadcast
        )
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_EXPORTED
        )
        connectivity = getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                netCallback
            )
        }
        scheduleTick()
        handler.post(scanLoop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_TEST -> alert()
            ACTION_TICK -> {
                // Разбудил системный будильник: держим процессор, пока придут результаты скана
                (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlarmReceiver.HUAWEI_SAFE_TAG)
                    .acquire(20_000)
                scheduleTick()
                handler.post(scanLoop)
            }
            ACTION_STOP_SOUND -> {
                stopSound()
                getSystemService(NotificationManager::class.java).cancel(ID_ALERT)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(scanReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
        AlarmReceiver.cancelTick(this)
        stopSound()
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Будильник-страховка ----------

    private fun scheduleTick() {
        if (Prefs.enabled(this)) AlarmReceiver.schedule(this, AlarmReceiver.ACTION_TICK, ALARM_INTERVAL_MS)
    }

    // ---------- Сканирование ----------

    private fun requestScan() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAttempt < MIN_SCAN_GAP_MS) return // не тратим лимит сканов зря
        lastScanAttempt = now
        try {
            @Suppress("DEPRECATION")
            val started = wifi.startScan()
            Prefs.logScanRequest(this, started)
            if (!started) checkResults() // лимит сканов: берём последние известные результаты
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed", e)
            Prefs.logError(this, "scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkResults() {
        val target = Prefs.ssid(this)
        if (target.isEmpty()) return

        val results = try {
            wifi.scanResults
        } catch (e: SecurityException) {
            Prefs.logError(this, "нет разрешения на список сетей")
            return
        }
        // Время результатов — в микросекундах с момента загрузки. Берём только свежие.
        val nowUs = SystemClock.elapsedRealtime() * 1000
        val newest = results.maxOfOrNull { it.timestamp } ?: 0L
        val fresh = results.filter { nowUs - it.timestamp < FRESH_MS * 1000 }
        val names = fresh.map { ssidOf(it) }.filter { it.isNotEmpty() }.distinct()
        val found = names.any { it.equals(target, ignoreCase = true) }
        val ageSec = if (newest > 0) (nowUs - newest) / 1_000_000 else -1
        Prefs.logResults(this, names, found, ageSec)

        if (found) {
            onPresent("скан")
        } else if (newest > lastFreshScanTs && fresh.isNotEmpty()) {
            // Пришёл НОВЫЙ скан, и сети в нём нет
            misses++
            // Пока планшет подключён к нужной сети, она точно есть
            if (misses >= MISSES_TO_RESET && connectedTarget == null) onAbsent("скан")
        }
        if (newest > lastFreshScanTs) {
            if (lastFreshScanTs > 0 && !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) {
                Prefs.countScreenOffScan(this) // новый скан при выключенном экране
            }
            lastFreshScanTs = newest
        }
        updateServiceNotification()
    }

    @Suppress("DEPRECATION")
    private fun checkConnection(network: Network) {
        val target = Prefs.ssid(this)
        if (target.isEmpty()) return
        val ssid = runCatching { wifi.connectionInfo?.ssid }.getOrNull()
            ?.trim()?.removeSurrounding("\"") ?: return
        if (ssid.equals(target, ignoreCase = true)) {
            if (connectedTarget != network) {
                connectedTarget = network
                Prefs.logConnection(this, "подключён к «$ssid»")
                handler.post { onPresent("подключение") }
            }
        }
    }

    private fun onPresent(source: String) {
        misses = 0
        if (state == 1) return
        val wasUnknown = state == -1
        state = 1
        Prefs.saveState(this, state, lostAt)
        if (wasUnknown) {
            Prefs.logEvent(this, "сеть уже есть при запуске — без оповещения")
        } else {
            val shortBlip = lostAt != 0L && System.currentTimeMillis() - lostAt < MIN_OUTAGE_MS
            Prefs.logEvent(this, "сеть появилась ($source)" + if (shortBlip) ", кратковременно — без оповещения" else "")
            if (!shortBlip) alertViaAlarm()
        }
        updateServiceNotification()
    }

    private fun onAbsent(source: String) {
        misses = 0
        if (state == 0) return
        val wasUnknown = state == -1
        state = 0
        lostAt = if (wasUnknown) 0L else System.currentTimeMillis()
        Prefs.saveState(this, state, lostAt)
        Prefs.logEvent(this, if (wasUnknown) "сети нет при запуске — жду" else "сеть пропала ($source)")
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

    private var alertPending = false

    /**
     * Оповещение через системный будильник (+1 с): только так Android/Huawei
     * разрешают открыть окно поверх экрана блокировки из фона — тот же путь, что у теста.
     * Если будильник почему-то не сработал за 6 с — оповещаем напрямую.
     */
    private fun alertViaAlarm() {
        alertPending = true
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlarmReceiver.HUAWEI_SAFE_TAG)
            .acquire(15_000)
        AlarmReceiver.schedule(this, AlarmReceiver.ACTION_TEST, 1_000)
        handler.postDelayed({
            if (alertPending) {
                Prefs.logEvent(this, "будильник не сработал — прямое оповещение")
                alert()
            }
        }, 6_000)
    }

    private fun alert() {
        alertPending = false
        Prefs.logAlert(this)
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlarmReceiver.HUAWEI_SAFE_TAG)
            .acquire(30_000)
        wakeScreen()
        showAlertNotification()
        playSound()
        // Прямой запуск экрана оповещения (Huawei и Android < 10 это обычно разрешают)
        runCatching {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "Svet::Screen"
            ).acquire(15_000)
        }
    }

    private fun playSound() {
        stopSound()
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        // Если громкость будильника почти на нуле — поднимаем до 80%, иначе ничего не услышать
        runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) < max / 3) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.8).toInt().coerceAtLeast(1), 0)
            }
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        player = try {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                resources.openRawResourceFd(R.raw.svet_sound).use { afd ->
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                setWakeMode(this@WifiWatcherService, PowerManager.PARTIAL_WAKE_LOCK)
                setOnCompletionListener { stopSound() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "mp3 failed", e)
            Prefs.logError(this, "звук: ${e.message}")
            playFallback(attrs)
            null
        }
    }

    private fun playFallback(attrs: AudioAttributes) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = attrs
                play()
                handler.postDelayed({ stop() }, 6_000)
            }
        }
    }

    private fun stopSound() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.release()
        }
        player = null
    }

    // ---------- Уведомления ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Слежение за Wi-Fi", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Свет дали", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Оповещение о появлении Wi-Fi сети"
                enableVibration(true)
                setSound(null, null) // звук играет сервис
                setBypassDnd(true)
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
        val text = if (networkVisible) "сеть рядом ✅" else "сети пока нет"
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_light)
            .setContentTitle("Жду Wi-Fi «$ssid»")
            .setContentText(text)
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
        val fullScreen = PendingIntent.getActivity(
            this, 1, Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, WifiWatcherService::class.java).setAction(ACTION_STOP_SOUND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_light)
            .setContentTitle("💡 Свет")
            .setContentText(Prefs.PHRASE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(Prefs.PHRASE))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true) // будит экран, как будильник
            .setContentIntent(fullScreen)
            .addAction(0, "OK", stop)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ID_ALERT, n) }
    }
}
