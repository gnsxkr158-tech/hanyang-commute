package com.hyt.commute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 앱이 꺼져 있어도 약 10분마다 새 버전을 확인 (알람이 울릴 때마다 확인하고 다음 알람을 다시 등록) */
object UpdateAlarm {
    const val INTERVAL_MS = 10 * 60 * 1000L

    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 7002, Intent(c, UpdateAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(c: Context) {
        val app = c.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val t = System.currentTimeMillis() + INTERVAL_MS
        try {
            if (Remind.canExact(app)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(app))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(app))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(app))
        }
    }
}

class UpdateAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val pr = goAsync()
        val app = c.applicationContext
        Thread {
            try {
                Update.backgroundCheck(app)
            } catch (_: Throwable) {
            } finally {
                try { UpdateAlarm.schedule(app) } catch (_: Throwable) { }
                pr.finish()
            }
        }.start()
    }
}
