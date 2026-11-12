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

    const val PHRASE = "Да, будет свет в вашем доме"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun ssid(ctx: Context): String = sp(ctx).getString(KEY_SSID, "") ?: ""
    fun setSsid(ctx: Context, value: String) = sp(ctx).edit().putString(KEY_SSID, value.trim()).apply()

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, value: Boolean) = sp(ctx).edit().putBoolean(KEY_ENABLED, value).apply()

    fun logScanRequest(ctx: Context, started: Boolean) =
        sp(ctx).edit().putLong(KEY_LAST_SCAN, System.currentTimeMillis()).putBoolean(KEY_SCAN_OK, started).apply()

    fun logResults(ctx: Context, names: List<String>, found: Boolean) =
        sp(ctx).edit()
            .putLong(KEY_LAST_RESULTS, System.currentTimeMillis())
            .putString(KEY_LAST_NAMES, names.take(12).joinToString(", "))
            .putBoolean(KEY_LAST_FOUND, found)
            .apply()

    fun logAlert(ctx: Context) = sp(ctx).edit().putLong(KEY_LAST_ALERT, System.currentTimeMillis()).apply()

    fun logError(ctx: Context, msg: String) =
        sp(ctx).edit().putString(KEY_LAST_ERROR, "${fmt(System.currentTimeMillis())} $msg").apply()

    fun diagnostics(ctx: Context): String {
        val p = sp(ctx)
        val lines = mutableListOf<String>()
        lines += "Последний запрос скана: ${fmt(p.getLong(KEY_LAST_SCAN, 0))}" +
            if (p.getLong(KEY_LAST_SCAN, 0) > 0 && !p.getBoolean(KEY_SCAN_OK, true)) " (система ограничила)" else ""
        lines += "Последний список сетей: ${fmt(p.getLong(KEY_LAST_RESULTS, 0))}"
        val names = p.getString(KEY_LAST_NAMES, "") ?: ""
        lines += "Видны сети: ${names.ifEmpty { "—" }}"
        lines += "Нужная сеть найдена: ${if (p.getBoolean(KEY_LAST_FOUND, false)) "да" else "нет"}"
        lines += "Последнее оповещение: ${fmt(p.getLong(KEY_LAST_ALERT, 0))}"
        p.getString(KEY_LAST_ERROR, null)?.let { lines += "Ошибка: $it" }
        return lines.joinToString("\n")
    }

    private fun fmt(t: Long): String =
        if (t == 0L) "—" else SimpleDateFormat("dd.MM HH:mm:ss", Locale("ru")).format(Date(t))
}
