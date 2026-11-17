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
 * 사이트는 퇴근지에서 약 100~200m 이내일 때만 퇴근을 받으므로, 머무른 위치(앵커)에서 50m 이상(GPS 오차가 크면 더 넓게) 연속으로 벗어날 때 시도함.
 * 10m 기준은 실내 GPS 흔들림만으로도 넘어서 책상에 앉아 있어도 퇴근이 찍히는 문제가 있어 50m로 넓힘.
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

    private var anchorAt = 0L
    private var firstFarAt = 0L
    private val startedAt = System.currentTimeMillis()

    private fun onFix(l: Location) {
        if (!Sched.leaveDue(this)) { finish(); return } // 퇴근이 찍혔거나 감시 시간대가 끝남
        val now = System.currentTimeMillis()
        // 한 번에 최대 20분만 감시 (배터리 보호). 아직 조건이 남아 있으면 2분 뒤 다시 켜짐
        if (now - startedAt > 20 * 60_000L) { finish(); return }
        // 실내에서 GPS가 크게 흔들려 '벗어났다'고 오인하는 것을 막기 위해 정확도가 좋은 위치만 사용
        if (Gate.isMock(l) || l.accuracy > 30f) return
        val a = anchor
        if (a == null) {
            if (Spots.all(this).any { Gate.distance(it.lat, it.lng, l) < 250 }) {
                anchor = l
                anchorAt = now
                noSpotHits = 0
                farHits = 0
            } else if (++noSpotHits >= 10) {
                finish() // 아는 출근지 근처가 아님 → 감시 종료 (2분 뒤 다시 확인)
            }
            return
        }
        val d = a.distanceTo(l)
        // 처음 20초는 기준 위치를 안정시키는 시간 (이 동안은 퇴근을 찍지 않음)
        if (now - anchorAt < 20_000L) {
            if (d < 30f && l.accuracy < a.accuracy) anchor = l
            return
        }
        val thr = maxOf(50f, 2f * (a.accuracy + l.accuracy))
        if (d <= thr) {
            farHits = 0
            firstFarAt = 0L
            if (d < 15f && l.accuracy < a.accuracy) anchor = l // 머무는 동안 가장 정확한 위치를 기준으로 삼음
            return
        }
        if (farHits == 0) firstFarAt = now
        farHits++
        // 기준에서 계속 멀어지는 상태가 연속 3회, 6초 이상 이어질 때만 '벗어남'으로 판단
        if (farHits < 3 || now - firstFarAt < 6_000L) return
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
