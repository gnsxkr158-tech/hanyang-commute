package com.hyt.commute

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Releases 에 올라온 최신 APK 를 확인하고 내려받아 설치 화면을 띄움 (설치 확인은 사용자가 한 번 눌러야 함) */
object Update {
    private fun newer(remote: String, local: String): Boolean {
        val a = remote.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val b = local.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** repo: "아이디/저장소" */
    fun check(act: Activity, current: String, manual: Boolean) {
        val repo = Prefs.getS(act, "repo").trim()
        if (repo.isEmpty() || !repo.contains("/")) {
            if (manual) Toast.makeText(act, "설정 탭에서 업데이트 저장소(아이디/저장소)를 먼저 입력해 주세요", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "HanyangCommute")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (c.responseCode != 200) {
                    if (manual) act.runOnUiThread {
                        Toast.makeText(act, "업데이트 확인 실패 (${c.responseCode}). 저장소가 Public 인지, 이름이 맞는지 확인해 주세요", Toast.LENGTH_LONG).show()
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
                act.runOnUiThread {
                    if (url.isEmpty() || !newer(tag, current)) {
                        if (manual) Toast.makeText(act, "최신 버전이에요 (v$current)", Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(act)
                            .setTitle("새 버전 ${tag.removePrefix("v")}")
                            .setMessage("지금 v$current 를 쓰고 있어요. 새 버전을 받아서 설치할까요?")
                            .setPositiveButton("업데이트") { _, _ -> download(act, url) }
                            .setNegativeButton("나중에", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                if (manual) act.runOnUiThread { Toast.makeText(act, "업데이트 확인 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun download(act: Activity, url: String) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(act, "'이 앱에서 설치 허용'을 켠 뒤 다시 업데이트를 눌러 주세요", Toast.LENGTH_LONG).show()
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))
            )
            return
        }
        Toast.makeText(act, "내려받는 중… 잠시만 기다려 주세요", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val dir = File(act.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "update.apk")
                var u = url
                var conn: HttpURLConnection
                var hops = 0
                while (true) { // GitHub 은 다운로드 주소를 다른 서버로 넘기므로 리다이렉트를 직접 따라감
                    conn = URL(u).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "HanyangCommute")
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    val code = conn.responseCode
                    if (code in 301..303 || code == 307 || code == 308) {
                        u = conn.getHeaderField("Location") ?: break
                        conn.disconnect()
                        if (++hops > 5) break
                    } else break
                }
                if (conn.responseCode != 200) throw RuntimeException("다운로드 실패 (${conn.responseCode})")
                conn.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", f)
                act.runOnUiThread {
                    act.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, "업데이트 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
