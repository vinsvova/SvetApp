package ru.svet.wifialert

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private const val FILE = "svet_prefs"
    private const val KEY_SSID = "ssid"
    private const val KEY_ENABLED = "enabled"

    // Диагностика
    private const val KEY_LAST_SCAN = "last_scan"
    private const val KEY_SCAN_OK = "scan_ok"
    private const val KEY_LAST_RESULTS = "last_results"
    private const val KEY_LAST_NAMES = "last_names"
    private const val KEY_LAST_FOUND = "last_found"
    private const val KEY_LAST_ALERT = "last_alert"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_AGE = "scan_age"
    private const val KEY_CONN = "conn"
    private const val KEY_EVENTS = "events"

    private const val KEY_STATE = "state"       // -1 неизвестно, 0 нет, 1 есть
    private const val KEY_LOST_AT = "lost_at"   // когда сеть пропала (время, мс)
    private const val KEY_OFF_SCANS = "off_scans"
    private const val KEY_OFF_LAST = "off_last"
    private const val KEY_SCREEN_OFF_AT = "screen_off_at"

    const val PHRASE = "Да, будет свет в вашем доме"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun ssid(ctx: Context): String = sp(ctx).getString(KEY_SSID, "") ?: ""
    fun setSsid(ctx: Context, value: String) = sp(ctx).edit().putString(KEY_SSID, value.trim()).apply()

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, value: Boolean) = sp(ctx).edit().putBoolean(KEY_ENABLED, value).apply()

    fun state(ctx: Context): Int = sp(ctx).getInt(KEY_STATE, -1)
    fun lostAt(ctx: Context): Long = sp(ctx).getLong(KEY_LOST_AT, 0)
    fun saveState(ctx: Context, state: Int, lostAt: Long) =
        sp(ctx).edit().putInt(KEY_STATE, state).putLong(KEY_LOST_AT, lostAt).apply()
    fun resetState(ctx: Context) = saveState(ctx, -1, 0)

    fun screenOff(ctx: Context) = sp(ctx).edit()
        .putLong(KEY_SCREEN_OFF_AT, System.currentTimeMillis()).putInt(KEY_OFF_SCANS, 0).apply()

    fun countScreenOffScan(ctx: Context) {
        val p = sp(ctx)
        p.edit().putInt(KEY_OFF_SCANS, p.getInt(KEY_OFF_SCANS, 0) + 1)
            .putLong(KEY_OFF_LAST, System.currentTimeMillis()).apply()
    }

    fun logScanRequest(ctx: Context, started: Boolean) =
        sp(ctx).edit().putLong(KEY_LAST_SCAN, System.currentTimeMillis()).putBoolean(KEY_SCAN_OK, started).apply()

    fun logResults(ctx: Context, names: List<String>, found: Boolean, ageSec: Long) =
        sp(ctx).edit()
            .putLong(KEY_AGE, ageSec)
            .putLong(KEY_LAST_RESULTS, System.currentTimeMillis())
            .putString(KEY_LAST_NAMES, names.take(12).joinToString(", "))
            .putBoolean(KEY_LAST_FOUND, found)
            .apply()

    fun logConnection(ctx: Context, msg: String) =
        sp(ctx).edit().putString(KEY_CONN, "${fmt(System.currentTimeMillis())} $msg").apply()

    /** Последние 6 событий: появление/пропадание сети. */
    fun logEvent(ctx: Context, msg: String) {
        val old = sp(ctx).getString(KEY_EVENTS, "") ?: ""
        val lines = (listOf("${fmt(System.currentTimeMillis())} $msg") + old.lines().filter { it.isNotBlank() }).take(6)
        sp(ctx).edit().putString(KEY_EVENTS, lines.joinToString("\n")).apply()
    }

    fun logAlert(ctx: Context) = sp(ctx).edit().putLong(KEY_LAST_ALERT, System.currentTimeMillis()).apply()

    fun logError(ctx: Context, msg: String) =
        sp(ctx).edit().putString(KEY_LAST_ERROR, "${fmt(System.currentTimeMillis())} $msg").apply()

    fun diagnostics(ctx: Context): String {
        val p = sp(ctx)
        val lines = mutableListOf<String>()
        lines += "Последний запрос скана: ${fmt(p.getLong(KEY_LAST_SCAN, 0))}" +
            if (p.getLong(KEY_LAST_SCAN, 0) > 0 && !p.getBoolean(KEY_SCAN_OK, true)) " (система ограничила)" else ""
        val age = p.getLong(KEY_AGE, -1)
        lines += "Последний список сетей: ${fmt(p.getLong(KEY_LAST_RESULTS, 0))}" +
            if (age >= 0) " (данным $age с)" else ""
        val names = p.getString(KEY_LAST_NAMES, "") ?: ""
        lines += "Видны сети (свежие): ${names.ifEmpty { "—" }}"
        lines += "Нужная сеть найдена: ${if (p.getBoolean(KEY_LAST_FOUND, false)) "да" else "нет"}"
        val offAt = p.getLong(KEY_SCREEN_OFF_AT, 0)
        if (offAt > 0) {
            lines += "Экран выключали: ${fmt(offAt)}, новых сканов в темноте: ${p.getInt(KEY_OFF_SCANS, 0)}" +
                (p.getLong(KEY_OFF_LAST, 0).takeIf { it > offAt }?.let { " (последний ${fmt(it)})" } ?: "")
        }
        lines += "Последнее оповещение: ${fmt(p.getLong(KEY_LAST_ALERT, 0))}"
        lines += "Wi-Fi подключение: ${p.getString(KEY_CONN, null) ?: "—"}"
        p.getString(KEY_EVENTS, null)?.takeIf { it.isNotBlank() }?.let { lines += "События:\n$it" }
        p.getString(KEY_LAST_ERROR, null)?.let { lines += "Ошибка: $it" }
        return lines.joinToString("\n")
    }

    private fun fmt(t: Long): String =
        if (t == 0L) "—" else SimpleDateFormat("dd.MM HH:mm:ss", Locale("ru")).format(Date(t))
}
