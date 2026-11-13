package com.hyt.commute

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.location.Location
import android.os.Build
import java.util.Calendar

/** 출근/퇴근을 찍기 직전에 '지금 내 위치'가 회사 허용 반경 이내인지 확인 */
object Gate {
    /** 이보다 GPS 오차가 크면 위치를 신뢰하지 않음 */
    const val MAX_ACCURACY = 100f

    fun distance(lat: Double, lng: Double, loc: Location): Int {
        val r = FloatArray(1)
        Location.distanceBetween(lat, lng, loc.latitude, loc.longitude, r)
        return r[0].toInt()
    }

    /** 모의(가짜) 위치 앱으로 만든 좌표인지 */
    @Suppress("DEPRECATION")
    fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider

    /** cb(허용여부, 메시지, 재시도해도 소용없는 오류인지) */
    @SuppressLint("MissingPermission")
    fun check(c: Context, cb: (Boolean, String, Boolean) -> Unit) {
        val app = c.applicationContext
        val lat = Prefs.getS(app, "lat").toDoubleOrNull()
        val lng = Prefs.getS(app, "lng").toDoubleOrNull()
        if (lat == null || lng == null) {
            cb(false, "회사 위치가 등록되지 않았어요. 설정에서 먼저 등록해 주세요.", true)
            return
        }
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            cb(false, "위치 권한이 필요해요.", true)
            return
        }
        val limit = Prefs.getI(app, "radius", 50)
        LocationServices.getFusedLocationProviderClient(app)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    cb(false, "현재 위치를 확인하지 못했어요. GPS를 켜 주세요.", false)
                } else if (isMock(loc)) {
                    cb(false, "모의(가짜) 위치가 켜져 있어요. 실제 폰 GPS 위치로만 출퇴근할 수 있어요.", true)
                } else if (loc.accuracy > MAX_ACCURACY) {
                    cb(false, "GPS 정확도가 낮아요(오차 약 ${loc.accuracy.toInt()}m). 창가/야외에서 다시 시도해 주세요.", false)
                } else {
                    val d = distance(lat, lng, loc)
                    if (d <= limit) cb(true, "ok", false)
                    else cb(false, "회사에서 약 ${d}m 떨어져 있어요 (${limit}m 이내에서만 가능, GPS 오차 약 ${loc.accuracy.toInt()}m)", false)
                }
            }
            .addOnFailureListener { cb(false, "위치 확인 실패: ${it.message}", false) }
    }
}

object Geo {
    private fun pi(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c.applicationContext, 0,
        Intent(c.applicationContext, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    @SuppressLint("MissingPermission")
    fun register(c: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        val app = c.applicationContext
        val spots = Spots.all(app)
        if (spots.isEmpty()) {
            onResult?.invoke(false, "아직 알고 있는 출근지가 없어요. 출근지 근처에서 앱을 한 번 열면 자동으로 기억해요")
            return
        }
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onResult?.invoke(false, "위치 권한이 필요해요")
            return
        }
        // 지오펜스는 100m 미만이면 인식이 불안정해서 '깨우는 용도'로 100m 이상 사용.
        // 실제 출근 가능 여부는 사이트가 폰 GPS로 판단합니다.
        val radius = maxOf(Prefs.getI(app, "radius", 50), 100).toFloat()
        val builder = GeofencingRequest.Builder().setInitialTrigger(0) // 등록 시점에 이미 안이어도 바로 출근 안 찍음
        spots.take(90).forEachIndexed { i, sp ->
            builder.addGeofence(
                Geofence.Builder()
                    .setRequestId("spot$i")
                    .setCircularRegion(sp.lat, sp.lng, radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            )
        }
        val client = LocationServices.getGeofencingClient(app)
        client.removeGeofences(pi(app)).addOnCompleteListener {
            client.addGeofences(builder.build(), pi(app))
                .addOnSuccessListener { onResult?.invoke(true, "ok") }
                .addOnFailureListener { onResult?.invoke(false, it.message ?: "등록 실패") }
        }
    }

    fun unregister(c: Context) {
        val app = c.applicationContext
        LocationServices.getGeofencingClient(app).removeGeofences(pi(app))
    }
}

/** 회사 반경 진입/이탈 시 자동으로 출근/퇴근 처리 */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        if (!Prefs.getB(c, "auto", false)) return

        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (Prefs.getB(c, "skipWeekend", true) && (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)) return

        val today = Util.today()
        when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                if (Prefs.getS(c, "inDate") != today) {
                    val limit = Prefs.getI(c, "inDeadline", 8)
                    if (Prefs.getB(c, "lateNotifyOnly", true) && cal.get(Calendar.HOUR_OF_DAY) >= limit) {
                        Util.notify(c, "자동 출근 건너뜀", "${limit}시가 지나서 자동으로 찍지 않았어요. 앱에서 직접 확인해 주세요.")
                        return
                    }
                    // 사이트가 폰 GPS로 '지정된 출근지 근처'로 인정할 때까지 30초마다 최대 약 10분간 재확인
                    ClockService.start(c, 1, true, gate = false, retry = true, near = true)
                }
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                // 회사를 벗어난 상태라 50m 규칙과 충돌하므로, 사용자가 따로 켠 경우에만 동작
                if (Prefs.getB(c, "autoOut", false) && Prefs.getS(c, "outDate") != today) {
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val from = Prefs.getI(c, "exitHour", 17)
                    if (hour >= from) {
                        ClockService.start(c, 2, true, gate = false)
                    } else {
                        Util.notify(c, "자동 퇴근 건너뜀", "${from}시 이전이라 퇴근을 찍지 않았어요.")
                    }
                }
            }
        }
    }
}

/** 재부팅/앱 업데이트 후 지오펜스와 알림 알람을 다시 등록 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val a = intent.action
        if ((a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) &&
            Prefs.getB(c, "auto", false)
        ) {
            Geo.register(c)
        }
        Remind.scheduleAll(c)
        UpdateJob.schedule(c)
    }
}
