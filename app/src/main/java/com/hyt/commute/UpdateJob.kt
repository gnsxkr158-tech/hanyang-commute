package com.hyt.commute

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** 앱이 꺼져 있어도 약 1시간마다(인터넷 연결 시) 새 버전을 확인해서 알림을 보냄 */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            Update.backgroundCheck(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val ID = 7001

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.allPendingJobs.any { it.id == ID }) return
            js.schedule(
                JobInfo.Builder(ID, ComponentName(c, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(60 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
