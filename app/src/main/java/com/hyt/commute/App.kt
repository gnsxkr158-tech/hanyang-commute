package com.hyt.commute

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/** 앱이 갑자기 꺼지면 원인(오류 내용)을 화면에 보여 줌 — 캡처해서 알려 주면 바로 고칠 수 있음 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":crash")) return
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
                startActivity(
                    Intent(this, CrashActivity::class.java)
                        .putExtra("t", "v$ver / Android ${Build.VERSION.RELEASE} / ${Build.MODEL}\n\n" + sw.toString().take(5000))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (_: Throwable) {
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
