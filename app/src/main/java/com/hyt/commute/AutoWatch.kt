package com.hyt.commute

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Calendar

/** 출근 마감 / 퇴근 시작 시각 설정과 '지금 자동으로 찍어야 하는지' 판단 */
object Sched {
    fun inLimit(c: Context): Int = Prefs.getI(c, "inLimitMin", 8 * 60)
    /** 오늘 고른 퇴근 시각이 있는지 (19시/22시 버튼) */
    fun picked(c: Context): Boolean = Prefs.getS(c, "outPickDate") == Util.today()
    fun outFrom(c: Context): Int = if (picked(c)) Prefs.getI(c, "outPickMin", 17 * 60) else 17 * 60
    fun fmt(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
    fun nowMin(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    fun weekendOff(c: Context): Boolean {
        val d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return Prefs.getB(c, "skipWeekend", true) && (d == Calendar.SATURDAY || d == Calendar.SUNDAY)
    }

    /** 지금 자동으로 찍을 동작: 0=없음, 1=출근, 2=퇴근 */
    fun due(c: Context): Int {
        if (weekendOff(c)) return 0
        val today = Util.today()
        val now = nowMin()
        if (Prefs.getB(c, "auto", false) && Prefs.getS(c, "inDate") != today) {
            val end = if (Prefs.getB(c, "lateNotifyOnly", true)) inLimit(c) else 12 * 60
            if (now >= 5 * 60 && now < end) return 1
        }
        if (Prefs.getB(c, "autoOut", false) && picked(c) && Prefs.getS(c, "inDate") == today &&
            Prefs.getS(c, "outDate") != today && now >= outFrom(c)
        ) return 2
        return 0
    }
}

/** 앱이 꺼져 있어도 주기적으로 '출근지/퇴근지명이 떴는지' 확인해서 바로 찍음 (달리는 차 안에서 지오펜스를 놓쳐도 보완) */
object AutoWatch {
    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 7003, Intent(c, AutoWatchReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(c: Context) {
        val app = c.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        if (!Prefs.getB(app, "auto", false) && !Prefs.getB(app, "autoOut", false)) {
            am.cancel(pi(app))
            return
        }
        var delay = if (Sched.due(app) != 0) 2 * 60_000L else 10 * 60_000L
        if (Prefs.getB(app, "autoOut", false) && Sched.picked(app)) {
            val untilOut = (Sched.outFrom(app) - Sched.nowMin()) * 60_000L
            if (untilOut in 1 until delay) delay = untilOut
        }
        val t = System.currentTimeMillis() + delay
        try {
            if (Remind.canExact(app)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(app))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(app))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(app))
        }
    }

    private fun go(app: Context, d: Int) {
        try {
            ClockService.start(app, d, true, gate = false, near = true, quiet = true)
        } catch (_: Throwable) {
        }
    }

    /** 확인할 때마다 웹뷰를 띄우면 배터리를 쓰므로, 알려진 출근지에서 3km 넘게 떨어져 있으면 건너뜀 */
    @android.annotation.SuppressLint("MissingPermission")
    fun tick(app: Context, done: () -> Unit) {
        val d = Sched.due(app)
        if (d == 0) {
            val now = System.currentTimeMillis()
            if (Prefs.getB(app, "autoOut", false) && Sched.picked(app) && !Sched.weekendOff(app) && Prefs.hasCreds(app) &&
                Sched.nowMin() >= Sched.outFrom(app) && Prefs.getS(app, "outDate") != Util.today() &&
                Prefs.getS(app, "inDate") != Util.today() && !ClockService.running &&
                now - (Prefs.getS(app, "tickSync").toLongOrNull() ?: 0L) > 30 * 60_000L
            ) {
                Prefs.putS(app, "tickSync", now.toString())
                try { ClockService.start(app, 0, true, gate = false) } catch (_: Throwable) { }
            }
            done()
            return
        }
        if (ClockService.running || !Prefs.hasCreds(app)) {
            done()
            return
        }
        val spots = Spots.all(app)
        if (spots.isEmpty() || app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            go(app, d)
            done()
            return
        }
        LocationServices.getFusedLocationProviderClient(app)
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { l ->
                if (l == null || spots.any { Gate.distance(it.lat, it.lng, l) < 3000 }) go(app, d)
                done()
            }
            .addOnFailureListener {
                go(app, d)
                done()
            }
    }
}

class AutoWatchReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val pr = goAsync()
        val app = c.applicationContext
        var finished = false
        val fin = {
            if (!finished) {
                finished = true
                try { AutoWatch.schedule(app) } catch (_: Throwable) { }
                try { pr.finish() } catch (_: Throwable) { }
            }
        }
        try {
            AutoWatch.tick(app, fin)
        } catch (_: Throwable) {
            fin()
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ fin() }, 15_000)
    }
}
