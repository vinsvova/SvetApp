package ru.svet.wifialert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Перезапускает слежение после перезагрузки телефона или обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.enabled(context) && Prefs.ssid(context).isNotEmpty()) {
            runCatching { WifiWatcherService.start(context) }
        }
    }
}
