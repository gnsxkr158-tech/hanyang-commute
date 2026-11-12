package com.hyt.commute

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** 출근/퇴근 처리를 백그라운드에서 실행하는 포그라운드 서비스 */
class ClockService : Service() {

    companion object {
        @Volatile
        var running = false

        /**
         * action: 0=사이트 기록 동기화, 1=출근, 2=퇴근, 3=출근지 확인(사이트가 GPS로 출근지명을 채워 주는지 확인)
         * near : true 면 사이트가 '지정된 출근지 근처'로 인정(출근지명 표시)했을 때만 출퇴근을 찍음
         */
        fun start(
            c: Context, action: Int, skipIfDone: Boolean,
            gate: Boolean = true, retry: Boolean = false, remind: Int = 0,
            dry: Boolean = false, near: Boolean = false
        ) {
            val i = Intent(c, ClockService::class.java)
                .putExtra("action", action)
                .putExtra("skip", skipIfDone)
                .putExtra("gate", gate)
                .putExtra("retry", retry)
                .putExtra("remind", remind)
                .putExtra("dry", dry)
                .putExtra("near", near)
            ContextCompat.startForegroundService(c, i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getIntExtra("action", 0) ?: 0
        val skip = intent?.getBooleanExtra("skip", false) ?: false
        val gate = intent?.getBooleanExtra("gate", true) ?: true
        val retry = intent?.getBooleanExtra("retry", false) ?: false
        val remind = intent?.getIntExtra("remind", 0) ?: 0
        val dry = intent?.getBooleanExtra("dry", false) ?: false
        val near = intent?.getBooleanExtra("near", false) ?: false
        val label = when (action) {
            1 -> "출근"
            2 -> "퇴근"
            3 -> "출근지 확인"
            else -> "조회"
        }

        val nm = getSystemService(NotificationManager::class.java)
        Util.ensureChannels(nm)
        val n = Notification.Builder(this, Util.CH_RUN)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("$label 처리 중…")
            .build()
        try {
            // 사이트가 폰 GPS를 읽을 수 있도록, 위치 권한이 있으면 'location' 유형을 함께 선언
            var type = 0
            if (Build.VERSION.SDK_INT >= 29) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
            }
            ServiceCompat.startForeground(this, 1, n, type)
        } catch (e: Exception) {
            if (remind != 0) Remind.notifyIfNeeded(this, remind)
            if (action == 1 || action == 2) {
                Util.notify(this, "$label 실패", "백그라운드 실행이 제한되었어요. 앱을 열어 직접 눌러 주세요.")
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) return START_NOT_STICKY
        running = true

        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "commute:run")
        wl.acquire(12 * 60 * 1000L)

        // 모의 실행은 '출근지 확인'(action 3)으로 돌려서 ExecCommuteClick(출퇴근 버튼)은 절대 호출하지 않음
        val runAct = if (dry) 3 else action
        // 자동 출근: 사이트가 아직 출근지를 못 찾으면(아직 회사 앞에 도착 전) 30초 간격으로 다시 시도
        var attemptsLeft = if (retry && near && !dry) 8 else 0
        var dryLoc = ""
        var runIt: () -> Unit = {}
        val finishUp: (ClockRunner.Res) -> Unit = { res ->
            if (!dry && res.notNear && attemptsLeft > 0) {
                attemptsLeft--
                handler.postDelayed({ runIt() }, 30_000)
            } else {
                try {
                    if (dry) handleDry(label, res) else handle(action, label, res, remind)
                } finally {
                    if (wl.isHeld) wl.release()
                    running = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        runIt = { ClockRunner(applicationContext, runAct, skip, near && !dry, finishUp).start() }

        if (dry || !gate || near) {
            runIt()
        } else {
            checkLocation(if (retry) 20 else 1, runIt) { msg ->
                finishUp(ClockRunner.Res(false, false, msg))
            }
        }
        return START_NOT_STICKY
    }

    private val handler = Handler(Looper.getMainLooper())

    /** (예전 방식) 내가 등록한 좌표 반경 안일 때만 onOk 실행 */
    private fun checkLocation(tries: Int, onOk: () -> Unit, onFail: (String) -> Unit) {
        Gate.check(applicationContext) { ok, msg, fatal ->
            when {
                ok -> onOk()
                fatal || tries <= 1 -> onFail(msg)
                else -> handler.postDelayed({ checkLocation(tries - 1, onOk, onFail) }, 30_000)
            }
        }
    }

    /** 모의 실행 결과: 출퇴근 기록(inDate/outDate)은 건드리지 않고 사이트 기록과 출근지 상태만 반영 */
    private fun handleDry(label: String, res: ClockRunner.Res) {
        val c = applicationContext
        handle(3, label, res, 0)
        val name = res.place.ifEmpty { res.place2 }
        val locTxt = when {
            !res.ok -> "확인 못 함"
            name.isNotEmpty() -> "지정된 출근지 근처 ($name)"
            else -> "지정된 출근지 근처가 아님"
        }
        val core = if (res.ok) "로그인·화면·$label 버튼 정상 (실제로 찍지 않았어요)" else res.msg
        val msg = "[모의 실행] $core / 위치: $locTxt"
        Prefs.putS(c, "msg", "${Util.now().substring(11)} $msg")
        Util.notify(c, "$label 모의 실행 ${if (res.ok) "성공" else "실패"}", msg)
        Bus.onChange?.invoke()
    }

    private fun handle(action: Int, label: String, res: ClockRunner.Res, remind: Int) {
        val c = applicationContext
        if (res.place.isNotEmpty()) Prefs.putS(c, "placeName", res.place) // 사이트가 알려 준 출근지명
        if (action == 0 || action == 3) { // 사이트 기록 동기화 / 출근지 확인 (알림 없음)
            val t = Util.today()
            if (res.ok) {
                if (res.start.isNotEmpty()) {
                    Prefs.putS(c, "inText", res.start)
                    if (res.start.contains(t)) Prefs.putS(c, "inDate", t)
                } else if (Prefs.getS(c, "inDate") != t) {
                    Prefs.putS(c, "inText", "")
                }
                if (res.end.isNotEmpty()) {
                    Prefs.putS(c, "outText", res.end)
                    if (res.end.contains(t)) Prefs.putS(c, "outDate", t)
                } else if (Prefs.getS(c, "outDate") != t) {
                    Prefs.putS(c, "outText", "")
                }
                if (action == 0) Prefs.putS(c, "msg", "${Util.now().substring(11)} 사이트 기록과 동기화됨")
            } else {
                Prefs.putS(c, "msg", "확인 실패: ${res.msg}")
            }
            if (action == 3) {
                if (res.ok) {
                    // 사이트가 GPS로 출근지를 찾았으면 출근지명이 채워짐 → '근처'
                    Prefs.putS(c, "nearName", res.place.ifEmpty { res.place2 })
                    Prefs.putS(c, "nearAt", System.currentTimeMillis().toString())
                } else {
                    Prefs.putS(c, "nearAt", "0") // 확인 못 함(알 수 없음)
                }
            }
            Bus.onChange?.invoke()
            if (remind != 0) Remind.notifyIfNeeded(c, remind) // 동기화 후에도 안 찍었으면 알림
            return
        }
        val today = Util.today()
        if (res.ok) {
            if (action == 1) {
                Prefs.putS(c, "inDate", today)
                Prefs.putS(c, "inText", res.start.ifEmpty { Util.now() })
            } else {
                Prefs.putS(c, "outDate", today)
                Prefs.putS(c, "outText", res.end.ifEmpty { Util.now() })
            }
        }
        val stamp = Util.now().substring(11)
        Prefs.putS(c, "msg", "$stamp ${res.msg}")
        val shown = if (action == 1) res.start else res.end
        Util.notify(
            c,
            if (res.ok) "$label 완료" else "$label 실패",
            if (res.ok && shown.isNotEmpty()) "${res.msg} · $shown" else res.msg
        )
        Bus.onChange?.invoke()
    }
}
