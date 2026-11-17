package com.hyt.commute

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * 퇴근 시각을 따로 정하지 않은 날: 17시 이후 퇴근지(알려진 출근지 근처)에서 머물다가 벗어나기 시작하면 바로 퇴근을 찍음.
 * 사이트는 퇴근지에서 약 100~200m 이내일 때만 퇴근을 받으므로, 앵커(머무른 위치)에서 조금만 벗어나도 즉시 시도함.
 * 앵커 위치 정확도가 낮으면 GPS 흔들림으로 오인하지 않도록 기준 거리를 자동으로 넓힘 (최소 10m).
 */
class LeaveWatchService : Service() {
    companion object {
        @Volatile
        var running = false

        fun start(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, LeaveWatchService::class.java))
        }
    }

    private var anchor: Location? = null
    private var farHits = 0
    private var noSpotHits = 0
    private var cb: LocationCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        Util.ensureChannels(nm)
        val n = Notification.Builder(this, Util.CH_RUN)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("퇴근지를 벗어나면 퇴근을 찍어요 (감시 중)")
            .build()
        try {
            var type = 0
            if (Build.VERSION.SDK_INT >= 29) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            ServiceCompat.startForeground(this, 2, n, type)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1000L).build()
        val c = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.lastLocation?.let { onFix(it) }
            }
        }
        cb = c
        LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(req, c, Looper.getMainLooper())
        return START_NOT_STICKY
    }

    private fun finish() {
        try { cb?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) } } catch (_: Throwable) { }
        cb = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onFix(l: Location) {
        if (!Sched.leaveDue(this)) { finish(); return } // 퇴근이 찍혔거나 감시 시간대가 끝남
        if (Gate.isMock(l) || l.accuracy > 60f) return
        val a = anchor
        if (a == null) {
            val near = Spots.all(this).any { Gate.distance(it.lat, it.lng, l) < 250 }
            if (near) {
                anchor = l
                noSpotHits = 0
            } else if (++noSpotHits >= 10) {
                finish() // 아는 출근지 근처가 아님 → 감시 종료 (2분 뒤 다시 확인)
            }
            return
        }
        val d = a.distanceTo(l)
        val thr = maxOf(10f, a.accuracy + l.accuracy)
        if (d <= thr) {
            farHits = 0
            if (l.accuracy < a.accuracy) anchor = l // 머무는 동안 가장 정확한 위치를 기준으로 삼음
            return
        }
        if (++farHits < 2) return // 흔들림이 아닌 실제 이동인지 연속 2회 확인
        // 하루 최대 3번까지만 시도
        val today = Util.today()
        val cnt = if (Prefs.getS(this, "leaveTryDate") == today) Prefs.getI(this, "leaveTryCnt", 0) else 0
        if (cnt >= 3) { finish(); return }
        Prefs.putS(this, "leaveTryDate", today)
        Prefs.putI(this, "leaveTryCnt", cnt + 1)
        try {
            ClockService.start(this, 2, true, gate = false, retry = true, near = true)
        } catch (_: Throwable) {
        }
        finish()
    }
}
