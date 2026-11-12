package ru.svet.wifialert

import android.content.Context

object Prefs {
    private const val FILE = "svet_prefs"
    private const val KEY_SSID = "ssid"
    private const val KEY_ENABLED = "enabled"

    const val PHRASE = "Да, будет свет в вашем доме"

    fun ssid(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SSID, "") ?: ""

    fun setSsid(ctx: Context, value: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_SSID, value.trim()).apply()

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, value).apply()
}
