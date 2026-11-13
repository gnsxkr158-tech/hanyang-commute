package com.hyt.commute

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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
    /** (HTTP 코드, 태그, apk 주소) — 실패하면 코드만 의미 있음 */
    @Volatile private var lastEtag = ""

    private fun request(etag: String = ""): Triple<Int, String, String> {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "HanyangCommute")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (etag.isNotEmpty()) c.setRequestProperty("If-None-Match", etag) // 바뀐 게 없으면 304 (횟수 제한에도 안 걸림)
        c.connectTimeout = 6_000
        c.readTimeout = 8_000
        if (c.responseCode != 200) return Triple(c.responseCode, "", "")
        lastEtag = c.getHeaderField("ETag") ?: ""
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val assets = j.optJSONArray("assets")
        var url = ""
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
        }
        return Triple(200, j.optString("tag_name"), url)
    }

    /** 앱이 꺼져 있어도 UpdateJob 이 주기적으로 호출. 새 버전이 처음 발견되면 알림 */
    fun backgroundCheck(c: Context) {
        try {
            val (code, tag, url) = request(Prefs.getS(c, "updEtag"))
            if (code != 200) return // 304 = 새 릴리스 없음
            Prefs.putS(c, "updEtag", lastEtag)
            val current = c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: return
            val has = url.isNotEmpty() && newer(tag, current)
            Prefs.putS(c, "updTag", if (has) tag else "")
            Prefs.putS(c, "updUrl", if (has) url else "")
            if (has && Prefs.getS(c, "updNotified") != tag) {
                Prefs.putS(c, "updNotified", tag)
                Util.notify(
                    c, "새 버전 ${tag.removePrefix("v")}이 나왔어요",
                    "앱을 열고 맨 위의 '업데이트가 필요함'을 눌러 받아 주세요.", 5
                )
            }
        } catch (_: Exception) {
        }
    }

    fun check(act: Activity, current: String, manual: Boolean, ask: Boolean = manual) {
        Thread {
            try {
                val (code, tag, url) = request()
                if (code != 200) {
                    if (manual) act.runOnUiThread {
                        Toast.makeText(act, "업데이트 확인 실패 ($code)", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
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
            .setMessage("지금 v$current 를 쓰고 있어요. 새 버전을 내려받을까요?\n\n내려받기가 끝나면 알림이 떠요. 그 알림을 눌러 설치해 주세요.")
            .setPositiveButton("내려받기") { _, _ -> download(act, url, tag) }
            .setNegativeButton("나중에", null)
            .show()
    }

    /** 안드로이드 다운로드 관리자로 받기: 진행 상황이 알림에 보이고, 끝나면 알림을 눌러 설치 */
    private fun download(act: Activity, url: String, tag: String) {
        try {
            val name = "HanyangCommute-$tag-${System.currentTimeMillis() / 1000}.apk"
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("한양 출퇴근 $tag")
                .setDescription("업데이트 파일 내려받는 중")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            act.getSystemService(DownloadManager::class.java).enqueue(req)
            Toast.makeText(act, "내려받기를 시작했어요. 알림창에서 진행 상황을 볼 수 있어요. 끝나면 알림을 눌러 설치해 주세요.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // 다운로드 관리자를 못 쓰면 브라우저로 넘김
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
