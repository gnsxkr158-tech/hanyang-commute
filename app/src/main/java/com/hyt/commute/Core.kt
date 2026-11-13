package com.hyt.commute

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Site {
    const val MAIN = "https://gps.hyttech.co.kr:8181/View/Main.aspx"
}

/** 설정/기록 저장소. ID·비밀번호는 암호화되어 저장됩니다. */
object Prefs {
    @Volatile
    private var cached: SharedPreferences? = null

    private fun sp(c: Context): SharedPreferences {
        cached?.let { return it }
        val app = c.applicationContext
        val created = try {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app,
                "secure_commute",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 일부 기기의 키스토어 문제 대비 (평문 저장으로 대체)
            app.getSharedPreferences("plain_commute", Context.MODE_PRIVATE)
        }
        cached = created
        return created
    }

    fun getS(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun putS(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun getI(c: Context, k: String, d: Int): Int = sp(c).getInt(k, d)
    fun putI(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun getB(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun putB(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()

    fun hasCreds(c: Context) = getS(c, "uid").isNotEmpty() && getS(c, "pw").isNotEmpty()
}

/** 화면 갱신용 간단한 알림 통로 */
object Bus {
    @Volatile
    var onChange: (() -> Unit)? = null
}

object Util {
    const val CH = "commute_result"
    const val CH_RUN = "commute_run"

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
    fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())

    fun ensureChannels(nm: NotificationManager) {
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "출퇴근 결과", NotificationManager.IMPORTANCE_HIGH))
        }
        if (nm.getNotificationChannel(CH_RUN) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_RUN, "출퇴근 처리 중", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun notify(c: Context, title: String, text: String, id: Int = 2) {
        val nm = c.getSystemService(NotificationManager::class.java)
        ensureChannels(nm)
        val pi = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}

data class Spot(val name: String, val lat: Double, val lng: Double, val addr: String = "")

/**
 * 출근지 기억 장치.
 * 사이트가 폰 GPS로 '지정된 출근지 근처'라고 인정(출근지명 표시)했을 때의 폰 좌표를 출근지명과 함께 자동으로 저장합니다.
 * (사이트는 직원에게 출근지 좌표를 알려주지 않기 때문)
 */
object Spots {
    fun all(c: Context): List<Spot> {
        val out = ArrayList<Spot>()
        try {
            val arr = JSONArray(Prefs.getS(c, "spots", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Spot(o.getString("n"), o.getDouble("lat"), o.getDouble("lng"), o.optString("a")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 같은 이름이면 좌표를 평균내어 정확도를 높이고, 새 이름이면 추가. 새로 추가되면 true */
    fun learn(c: Context, name: String, lat: Double, lng: Double): Boolean {
        val arr = try {
            JSONArray(Prefs.getS(c, "spots", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("n") == name) {
                val n = o.optInt("c", 1)
                val k = minOf(n, 20)
                o.put("lat", (o.getDouble("lat") * k + lat) / (k + 1))
                o.put("lng", (o.getDouble("lng") * k + lng) / (k + 1))
                o.put("c", n + 1)
                Prefs.putS(c, "spots", arr.toString())
                return false
            }
        }
        if (arr.length() >= 50) return false
        arr.put(JSONObject().put("n", name).put("lat", lat).put("lng", lng).put("c", 1))
        Prefs.putS(c, "spots", arr.toString())
        return true
    }

    fun setAddr(c: Context, name: String, addr: String) {
        try {
            val arr = JSONArray(Prefs.getS(c, "spots", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getString("n") == name) {
                    o.put("a", addr)
                    Prefs.putS(c, "spots", arr.toString())
                    return
                }
            }
        } catch (_: Exception) {
        }
    }

    fun label(c: Context, name: String): String {
        val a = all(c).firstOrNull { it.name == name }?.addr.orEmpty()
        return if (a.isNotEmpty()) "$a - $name" else name
    }
}
