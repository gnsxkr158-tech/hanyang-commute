package com.hyt.commute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** 출근 10분 전(07:50) / 퇴근(17:00) 알림 */
object Remind {
    const val IN = 1
    const val OUT = 2

    // 출근 마감 08:00 의 10분 전, 퇴근 17:00 정각
    private const val IN_H = 7
    private const val IN_M = 50
    private const val OUT_H = 17
    private const val OUT_M = 0

    private fun pi(c: Context, kind: Int): PendingIntent = PendingIntent.getBroadcast(
        c.applicationContext, 100 + kind,
        Intent(c.applicationContext, RemindReceiver::class.java).putExtra("kind", kind),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun canExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun scheduleAll(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (!Prefs.getB(c, "remind", true)) {
            am.cancel(pi(c, IN))
            am.cancel(pi(c, OUT))
            return
        }
        schedule(c, IN)
        schedule(c, OUT)
    }

    fun schedule(c: Context, kind: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        val h = if (kind == IN) IN_H else OUT_H
        val m = if (kind == IN) IN_M else OUT_M
        val t = nextTime(c, h, m)
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, kind))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, kind)) // 정확도 낮음(수 분 지연 가능)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, kind))
        }
    }

    private fun isWeekend(cal: Calendar): Boolean {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }

    /** 다음 알림 시각 (주말 제외 설정이면 주말은 건너뜀) */
    private fun nextTime(c: Context, h: Int, m: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (Prefs.getB(c, "skipWeekend", true)) {
            while (isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun doneToday(c: Context, kind: Int): Boolean =
        Prefs.getS(c, if (kind == IN) "inDate" else "outDate") == Util.today()

    /** 아직 안 찍었으면 알림을 띄움 */
    fun notifyIfNeeded(c: Context, kind: Int) {
        if (doneToday(c, kind)) return
        if (kind == IN) {
            Util.notify(
                c, "⏰ 출근 10분 전이에요",
                "08:00까지 출근을 찍어야 해요. 회사 ${Prefs.getI(c, "radius", 50)}m 이내에서 앱의 [출근]을 눌러 주세요.", 3
            )
        } else {
            Util.notify(
                c, "⏰ 퇴근 시간이에요",
                "퇴근을 찍어야 해요. 회사 ${Prefs.getI(c, "radius", 50)}m 이내에서 앱의 [퇴근]을 눌러 주세요.", 4
            )
        }
    }
}

class RemindReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val kind = intent.getIntExtra("kind", 0)
        if (kind != Remind.IN && kind != Remind.OUT) return
        // 다음 날 알람을 먼저 예약
        Remind.schedule(c, kind)
        if (!Prefs.getB(c, "remind", true)) return

        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (Prefs.getB(c, "skipWeekend", true) && (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)) return

        val key = if (kind == Remind.IN) "inDate" else "outDate"
        if (Prefs.getS(c, key) == Util.today()) return

        // 크롬/PC에서 이미 찍었을 수 있으니 사이트 기록을 먼저 확인한 뒤 알림 (실패하면 바로 알림)
        if (Prefs.hasCreds(c) && !ClockService.running) {
            try {
                ClockService.start(c, 0, true, gate = false, remind = kind)
                return
            } catch (_: Exception) {
            }
        }
        Remind.notifyIfNeeded(c, kind)
    }
}
