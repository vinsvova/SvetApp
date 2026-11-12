package ru.svet.wifialert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Принимает системные будильники (AlarmManager.setAlarmClock). Это единственный механизм,
 * который гарантированно будит Huawei/Honor из глубокого сна — им пользуется приложение «Часы».
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TICK = "ru.svet.wifialert.ALARM_TICK"
        const val ACTION_TEST = "ru.svet.wifialert.ALARM_TEST"

        /** Имя wake lock, которое прошивка Huawei не отбирает у приложений. */
        const val HUAWEI_SAFE_TAG = "LocationManagerService"

        private fun pending(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun schedule(ctx: Context, action: String, delayMs: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val code = if (action == ACTION_TEST) 21 else 20
            val op = pending(ctx, action, code)
            val at = System.currentTimeMillis() + delayMs
            val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            runCatching {
                if (canExact) {
                    val show = PendingIntent.getActivity(
                        ctx, 22, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                    )
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
                }
            }
        }

        fun cancelTick(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            runCatching { am.cancel(pending(ctx, ACTION_TICK, 20)) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Держим процессор, пока сервис не проснётся и не запустит скан
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, HUAWEI_SAFE_TAG).acquire(20_000)

        if (intent.action == ACTION_TEST) {
            // Право открыть окно из фона есть именно сейчас, пока обрабатывается будильник
            runCatching {
                context.startActivity(
                    Intent(context, AlarmActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                )
            }
        }
        val serviceAction = when (intent.action) {
            ACTION_TEST -> WifiWatcherService.ACTION_TEST
            ACTION_TICK -> {
                if (!Prefs.enabled(context)) return
                WifiWatcherService.ACTION_TICK
            }
            else -> return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context, Intent(context, WifiWatcherService::class.java).setAction(serviceAction)
            )
        }
    }
}
