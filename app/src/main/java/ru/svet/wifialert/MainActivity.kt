package ru.svet.wifialert

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var ssidInput: EditText
    private lateinit var status: TextView
    private lateinit var toggle: Button

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCorePermissions()) startWatching() else
                toast("Без доступа к местоположению Android не показывает список Wi-Fi сетей")
            refresh()
        }

    private val bgLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ssidInput = findViewById(R.id.ssid)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        ssidInput.setText(Prefs.ssid(this))

        toggle.setOnClickListener {
            if (Prefs.enabled(this)) {
                Prefs.setEnabled(this, false)
                WifiWatcherService.stop(this)
                refresh()
            } else {
                val ssid = ssidInput.text.toString().trim()
                if (ssid.isEmpty()) {
                    ssidInput.error = "Введите имя сети"
                    return@setOnClickListener
                }
                Prefs.setSsid(this, ssid)
                requestPermissionsAndStart()
            }
        }

        findViewById<Button>(R.id.test).setOnClickListener {
            // Сервис со слежением за местоположением нельзя запустить без разрешений
            if (hasCorePermissions()) WifiWatcherService.test(this)
            else toast("Сначала нажмите «Начать слежение» и выдайте разрешения")
        }
        findViewById<Button>(R.id.testDelayed).setOnClickListener {
            if (hasCorePermissions()) {
                WifiWatcherService.test(this, 15_000)
                toast("Выключите экран кнопкой питания и ждите 15 секунд")
            } else toast("Сначала нажмите «Начать слежение» и выдайте разрешения")
        }
        findViewById<Button>(R.id.fullScreen).setOnClickListener { requestFullScreen() }
        findViewById<Button>(R.id.notifSettings).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.huawei).apply {
            if (isHuawei()) visibility = android.view.View.VISIBLE
            setOnClickListener { openHuaweiLaunchSettings() }
        }
        findViewById<Button>(R.id.battery).setOnClickListener { requestIgnoreBattery() }
        findViewById<Button>(R.id.bgLocation).setOnClickListener { requestBackgroundLocation() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasCorePermissions(): Boolean {
        val loc = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val nearby = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.NEARBY_WIFI_DEVICES)
        return loc && nearby
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filterNot(::granted)
        if (missing.isEmpty()) startWatching() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startWatching() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(lm)) {
            AlertDialog.Builder(this)
                .setMessage("Включите геолокацию — без неё Android не ищет Wi-Fi сети.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        Prefs.setEnabled(this, true)
        WifiWatcherService.start(this)
        if (!isIgnoringBattery()) requestIgnoreBattery()
        refresh()
    }

    private fun isIgnoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        if (isIgnoringBattery()) {
            toast("Уже разрешено")
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestBackgroundLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Сначала нажмите «Начать слежение»")
            return
        }
        if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            toast("Уже разрешено")
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Чтобы слежение включалось само после перезагрузки телефона, выберите «Разрешить в любом режиме» / «Всегда».")
            .setPositiveButton("OK") { _, _ ->
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .show()
    }

    private fun isHuawei() = Build.MANUFACTURER.lowercase().let { it.contains("huawei") || it.contains("honor") }

    /** Экран «Запуск приложений» в Huawei/Honor (у разных версий EMUI он называется по-разному). */
    private fun openHuaweiLaunchSettings() {
        toast("Найдите «Будет свет», отключите «Управлять автоматически» и включите все 3 переключателя")
        val candidates = listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        for (c in candidates) {
            val ok = runCatching { startActivity(Intent().setComponent(c)) }.isSuccess
            if (ok) return
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
    }

    private fun canFullScreen(): Boolean =
        Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun requestFullScreen() {
        if (Build.VERSION.SDK_INT >= 34 && !canFullScreen()) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        } else {
            // На старых Android разрешение даётся автоматически; на Huawei надо проверить уведомления
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun refresh() {
        val on = Prefs.enabled(this)
        toggle.text = if (on) "Остановить слежение" else "Начать слежение"
        val lines = mutableListOf<String>()
        lines += if (on) "▶ Слежение включено: «${Prefs.ssid(this)}»" else "⏸ Слежение выключено"
        lines += check("Доступ к Wi-Fi/местоположению", hasCorePermissions())
        if (Build.VERSION.SDK_INT >= 33)
            lines += check("Уведомления", granted(Manifest.permission.POST_NOTIFICATIONS))
        lines += check("Без ограничений батареи", isIgnoringBattery())
        lines += check("Включение экрана при оповещении", canFullScreen())
        lines += check("Автозапуск после перезагрузки", granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        status.text = lines.joinToString("\n")
        findViewById<TextView>(R.id.diag).text = Prefs.diagnostics(this)
    }

    private fun check(name: String, ok: Boolean) = (if (ok) "✅ " else "⚠️ ") + name

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
