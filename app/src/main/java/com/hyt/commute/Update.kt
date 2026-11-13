package com.hyt.commute

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Releases 의 최신 버전을 확인. 새 버전이 있으면 표시하고, 받기는 브라우저(다운로드)로 넘김 */
object Update {
    private const val REPO = "gnsxkr158-tech/hanyang-commute"

    fun newer(remote: String, local: String): Boolean {
        val a = remote.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val b = local.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 저장된 '새 버전' 정보가 현재 버전보다 높은지 */
    fun pending(act: Activity, current: String): Boolean {
        val tag = Prefs.getS(act, "updTag")
        return tag.isNotEmpty() && newer(tag, current)
    }

    /**
     * 새 버전 확인. 결과는 updTag/updUrl 에 저장하고 화면을 갱신.
     * ask=true 이면 새 버전이 있을 때 바로 업데이트 창을 띄움.
     */
    fun check(act: Activity, current: String, manual: Boolean, ask: Boolean = manual) {
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "HanyangCommute")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (c.responseCode != 200) {
                    if (manual) act.runOnUiThread {
                        Toast.makeText(act, "업데이트 확인 실패 (${c.responseCode})", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = j.optString("tag_name")
                val assets = j.optJSONArray("assets")
                var url = ""
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                val has = url.isNotEmpty() && newer(tag, current)
                Prefs.putS(act, "updTag", if (has) tag else "")
                Prefs.putS(act, "updUrl", if (has) url else "")
                act.runOnUiThread {
                    Bus.onChange?.invoke()
                    if (!has) {
                        if (manual) Toast.makeText(act, "최신 버전이에요 (v$current)", Toast.LENGTH_SHORT).show()
                    } else if (ask) {
                        prompt(act, current)
                    }
                }
            } catch (e: Exception) {
                if (manual) act.runOnUiThread { Toast.makeText(act, "업데이트 확인 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    fun prompt(act: Activity, current: String) {
        val tag = Prefs.getS(act, "updTag")
        val url = Prefs.getS(act, "updUrl")
        if (tag.isEmpty() || url.isEmpty()) {
            check(act, current, true)
            return
        }
        AlertDialog.Builder(act)
            .setTitle("새 버전 ${tag.removePrefix("v")}")
            .setMessage("지금 v$current 를 쓰고 있어요. 새 버전을 내려받을까요?\n\n내려받은 뒤 알림이나 다운로드 목록에서 파일을 눌러 설치해 주세요.")
            .setPositiveButton("내려받기") { _, _ ->
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton("나중에", null)
            .show()
    }
}
