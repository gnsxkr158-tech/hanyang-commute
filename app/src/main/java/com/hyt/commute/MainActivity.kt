package com.hyt.commute

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var inTv: TextView
    private lateinit var outTv: TextView
    private lateinit var msgTv: TextView
    private val panels = arrayOfNulls<LinearLayout>(4)
    private val tabBtns = arrayOfNulls<Button>(4)
    private var openPanel = -1
    private lateinit var lateSw: Switch
    private lateinit var updTv: TextView
    private lateinit var idEt: EditText
    private lateinit var pwEt: EditText
    private lateinit var radiusEt: EditText
    private lateinit var hourEt: EditText
    private lateinit var locTv: TextView
    private lateinit var autoSw: Switch
    private lateinit var weekendSw: Switch
    private lateinit var autoOutSw: Switch
    private lateinit var remindSw: Switch
    private lateinit var loginTv: TextView
    private lateinit var distTv: TextView
    private lateinit var inSub: TextView
    private lateinit var outSub: TextView
    private lateinit var dot: View
    private lateinit var placeTv: TextView
    private lateinit var addrEt: EditText
    private lateinit var inBtn: Button
    private lateinit var outBtn: Button
    private var lastLoc: Location? = null
    private val loopH = Handler(Looper.getMainLooper())
    private var resumed = false
    private var lastLearnAt = 0L
    private var locCb: LocationCallback? = null
    private var askedLoc = false
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var afterPerm: (() -> Unit)? = null
    private var wantBg = true

    private val navy = Color.parseColor("#2B3A9F")
    private val green = Color.parseColor("#16B27E")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val bgGray = Color.parseColor("#F4F6FB")
    private val ink = Color.parseColor("#1B1F2A")
    private val subGray = Color.parseColor("#7A8194")
    private val author = "Claude와 함께 제작"

    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0, w: Int = -1, h: Int = -2) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 18)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        elevation = dp(2).toFloat()
    }

    private fun filledBtn(text: String, color: Int = navy, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color, 12)
        setOnClickListener { onClick() }
    }

    private fun outlineBtn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(navy)
        background = rounded(Color.WHITE, 12, Color.parseColor("#C9CFE6"))
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(t: String) =
        label(t, 13f, subGray, true).apply { setPadding(0, dp(16), 0, dp(4)) }

    @Suppress("DEPRECATION")
    private fun insetTop(i: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) i.getInsets(WindowInsets.Type.systemBars()).top else i.systemWindowInsetTop

    @Suppress("DEPRECATION")
    private fun insetBottom(i: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) i.getInsets(WindowInsets.Type.systemBars()).bottom else i.systemWindowInsetBottom

    /** "주소 - 위치명" */
    private fun placeLine(): String {
        val addr = Prefs.getS(this, "addr")
        val name = Prefs.getS(this, "placeName")
        return when {
            addr.isNotEmpty() && name.isNotEmpty() -> "$addr - $name"
            addr.isNotEmpty() -> addr
            else -> name
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** 입력한 주소를 폰의 주소 검색(Geocoder)으로 좌표로 변환해 회사 위치로 등록 */
    @Suppress("DEPRECATION")
    private fun findAddress() {
        val q = addrEt.text.toString().trim()
        if (q.isEmpty()) {
            toast("회사 주소를 입력해 주세요")
            return
        }
        if (!Geocoder.isPresent()) {
            toast("이 폰은 주소 검색을 지원하지 않아요. '지금 위치를 회사로 등록'을 사용해 주세요")
            return
        }
        toast("주소를 찾는 중…")
        Thread {
            try {
                val r = Geocoder(this, Locale.KOREA).getFromLocationName(q, 1)
                runOnUiThread {
                    if (r.isNullOrEmpty()) {
                        toast("주소를 찾지 못했어요. 도로명 주소로 자세히 입력해 보세요")
                    } else {
                        Prefs.putS(this, "lat", r[0].latitude.toString())
                        Prefs.putS(this, "lng", r[0].longitude.toString())
                        Prefs.putS(this, "addr", q)
                        saveSettings(false)
                        refresh()
                        toast("회사 위치를 등록했어요")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("주소 검색에 실패했어요. 인터넷 연결을 확인해 주세요") }
            }
        }.start()
    }

    /** 좌표 → 주소 글자 (현재 위치로 등록했을 때 화면에 주소를 표시하기 위함) */
    @Suppress("DEPRECATION")
    private fun reverseAddress(lat: Double, lng: Double) {
        if (!Geocoder.isPresent()) return
        Thread {
            try {
                val r = Geocoder(this, Locale.KOREA).getFromLocation(lat, lng, 1)
                val line = r?.firstOrNull()?.getAddressLine(0)
                if (!line.isNullOrBlank()) {
                    Prefs.putS(this, "addr", line.removePrefix("대한민국").trim())
                    runOnUiThread { refresh() }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** "2026-11-12 오전 8:55:04" → ("오전 8:55:04", "2026-11-12") */
    private fun splitTime(s: String): Pair<String, String> {
        if (s.isEmpty()) return "—" to ""
        val m = Regex("(오전|오후|AM|PM)?\\s*\\d{1,2}:\\d{2}(:\\d{2})?").find(s) ?: return s to ""
        return m.value.trim() to s.replace(m.value, "").trim()
    }

    private fun bg(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun label(text: String, size: Float, color: Int = Color.parseColor("#222222"), bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun field(hint: String, pwd: Boolean = false, number: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setSingleLine()
        inputType = when {
            pwd -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
    }

    private fun smallBtn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
        if (!Prefs.hasCreds(this)) showPanel(1)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        askExactAlarmOnce()
    }

    /** 07:50 / 17:00 알림을 정확한 시각에 받으려면 '알람 및 리마인더' 허용이 필요 (Android 12+) */
    private fun askExactAlarmOnce() {
        if (!Prefs.getB(this, "remind", true) || Remind.canExact(this)) return
        if (Prefs.getB(this, "askedExact", false)) return
        Prefs.putB(this, "askedExact", true)
        AlertDialog.Builder(this)
            .setMessage("출근 10분 전(07:50)과 퇴근(17:00)에 정확한 시각으로 알림을 받으려면 '알람 및 리마인더' 권한을 허용해 주세요.")
            .setPositiveButton("설정 열기") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        Bus.onChange = { runOnUiThread { refresh() } }
        refresh()
        Remind.scheduleAll(this)
        val nowMs = System.currentTimeMillis()
        if (nowMs - (Prefs.getS(this, "updChk").toLongOrNull() ?: 0L) > 30 * 60_000L) {
            Prefs.putS(this, "updChk", nowMs.toString())
            Update.check(this, versionName(), false) // 30분에 한 번 새 버전 확인 (표시만)
        }
        // 앱이 열려 있는 동안 GPS로 회사까지 거리를 계속 확인 → 허용 반경 이내일 때만 버튼 활성화
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            startLocationUpdates()
        } else if (!askedLoc) {
            askedLoc = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3
            )
        }
        resumed = true // 앱이 열려 있는 동안 1분마다 사이트에 '지정된 출근지 근처인지' 확인
        loopH.removeCallbacks(loopRun)
        loopH.post(loopRun)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (locCb != null || !has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                lastLoc = r.lastLocation
                updateRange()
            }
        }
        locCb = cb
        fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locCb?.let { fused.removeLocationUpdates(it) }
        locCb = null
    }

    private fun setBtns(on: Boolean) {
        listOf(inBtn, outBtn).forEach {
            it.isEnabled = on
            it.alpha = if (on) 1f else 0.35f
        }
    }

    private fun setDot(color: Int) {
        (dot.background as GradientDrawable).setColor(color)
    }

    private val loopRun = object : Runnable {
        override fun run() {
            if (!resumed) return
            syncNow(false)
            loopH.postDelayed(this, 60_000)
        }
    }

    /**
     * 출근지 판단은 회사 사이트가 합니다. 사이트는 폰의 GPS로 '지정된 출근지 근처'일 때 출근지명을 자동으로 채워 줍니다.
     * 앱은 그 결과(출근지명이 떴는지)를 읽어서 이름을 보여주고 출근/퇴근 버튼을 켭니다.
     */
    private fun updateRange() {
        val loc = lastLoc
        val nearAt = Prefs.getS(this, "nearAt").toLongOrNull() ?: 0L
        val name = Prefs.getS(this, "nearName")
        val fresh = nearAt > 0 && System.currentTimeMillis() - nearAt < 180_000
        val gray = Color.parseColor("#B8BECF")
        val amber = Color.parseColor("#F2A600")
        val red = Color.parseColor("#D64545")
        val acc = if (loc != null) " · GPS 오차 약 ${loc.accuracy.toInt()}m" else ""
        when {
            !has(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                placeTv.text = "위치 권한 필요"
                distTv.text = "위치 권한을 허용해 주세요 (폰 GPS 기준으로만 출퇴근할 수 있어요)"
                setDot(gray); setBtns(false)
            }
            loc != null && Gate.isMock(loc) -> {
                placeTv.text = "모의(가짜) 위치 감지"
                distTv.text = "모의 위치가 켜져 있어요. 실제 GPS로만 출퇴근할 수 있어요"
                setDot(red); setBtns(false)
            }
            !Prefs.hasCreds(this) -> {
                placeTv.text = "로그인 정보 없음"
                distTv.text = "설정에서 회사 사이트 아이디/비밀번호를 먼저 입력해 주세요"
                setDot(gray); setBtns(false)
            }
            fresh && name.isNotEmpty() -> {
                placeTv.text = Spots.label(this, name)
                distTv.text = "지정된 출근지 근처예요 · 출근/퇴근을 찍을 수 있어요$acc"
                setDot(Color.parseColor("#14A37F")); setBtns(true)
            }
            fresh -> {
                placeTv.text = "지정된 출근지 밖"
                val near = if (loc != null) Spots.all(this).minByOrNull { Gate.distance(it.lat, it.lng, loc) } else null
                distTv.text = if (near != null && loc != null)
                    "지정된 출근지 근처가 아니에요 · 가장 가까운 곳: ${near.name} 약 ${Gate.distance(near.lat, near.lng, loc)}m$acc"
                else "지정된 출근지 근처가 아니에요. 출근지 가까이 가면 자동으로 켜져요$acc"
                setDot(amber); setBtns(false)
            }
            else -> {
                placeTv.text = "출근지 확인 중…"
                distTv.text = "사이트가 폰 GPS로 지정된 출근지를 찾는 중이에요 (최대 40초)$acc"
                setDot(gray); setBtns(false)
            }
        }
    }

    /** 사이트가 '근처'로 인정한 순간의 폰 좌표를 출근지명과 함께 기억 (자동 출근 알림/지오펜스에 사용) */
    private fun learnIfNear() {
        val nearAt = Prefs.getS(this, "nearAt").toLongOrNull() ?: 0L
        val name = Prefs.getS(this, "nearName")
        val loc = lastLoc ?: return
        if (name.isEmpty() || nearAt <= lastLearnAt) return
        if (Gate.isMock(loc) || loc.accuracy > 50f) return
        lastLearnAt = nearAt
        if (Spots.learn(this, name, loc.latitude, loc.longitude)) {
            reverseSpotAddress(name, loc.latitude, loc.longitude)
            if (Prefs.getB(this, "auto", false)) Geo.register(this)
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseSpotAddress(name: String, lat: Double, lng: Double) {
        if (!Geocoder.isPresent()) return
        Thread {
            try {
                val r = Geocoder(this, Locale.KOREA).getFromLocation(lat, lng, 1)
                val line = r?.firstOrNull()?.getAddressLine(0)
                if (!line.isNullOrBlank()) {
                    Spots.setAddr(this, name, line.removePrefix("대한민국").trim())
                    runOnUiThread { refresh() }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private var lastSync = 0L

    private fun syncNow(force: Boolean) {
        if (!Prefs.hasCreds(this) || ClockService.running) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < 45_000) return
        lastSync = now
        if (force) msgTv.text = "로그인·출근지 확인 중… (최대 40초)"
        ClockService.start(this, 3, true, gate = false)
    }

    override fun onPause() {
        super.onPause()
        Bus.onChange = null
        resumed = false
        loopH.removeCallbacks(loopRun)
        stopLocationUpdates()
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgGray)
            isFillViewport = true
            addView(col)
        }
        setContentView(scroll)

        // ---------- 헤더 (흰색 바탕) ----------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
            elevation = dp(4).toFloat()
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 회사 로고: app/src/main/res/drawable-nodpi/company_logo.png (없으면 회사명 글자로 표시)
        val logoId = resources.getIdentifier("company_logo", "drawable", packageName)
        if (logoId != 0) {
            top.addView(
                ImageView(this).apply {
                    setImageResource(logoId)
                    adjustViewBounds = true
                    maxHeight = dp(40)
                },
                LinearLayout.LayoutParams(-2, -2)
            )
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        } else {
            top.addView(label("한양기술공업", 18f, navy, true), LinearLayout.LayoutParams(0, -2, 1f))
        }
        val verBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        verBox.addView(label("v${versionName()}", 12f, navy).apply {
            background = rounded(Color.parseColor("#EEF1FA"), 12)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        updTv = label("업데이트가 필요함", 10.5f, Color.parseColor("#D64545"), true).apply {
            visibility = View.GONE
            setPadding(0, dp(3), dp(2), 0)
            setOnClickListener { Update.prompt(this@MainActivity, versionName()) }
        }
        verBox.addView(updTv)
        top.addView(verBox)
        header.addView(top, lp())
        header.addView(label("출퇴근", 14f, subGray), lp(22))
        header.addView(
            label(SimpleDateFormat("M월 d일 EEEE", Locale.KOREA).format(Date()), 26f, ink, true),
            lp(2)
        )
        loginTv = label("", 12.5f, subGray, true).apply {
            background = rounded(Color.parseColor("#F1F3F9"), 14)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        header.addView(loginTv, lp(14, -2))
        col.addView(header, lp())

        scroll.setOnApplyWindowInsetsListener { _, ins ->
            header.setPadding(dp(20), insetTop(ins) + dp(20), dp(20), dp(24))
            col.setPadding(0, 0, 0, insetBottom(ins))
            ins
        }
        scroll.requestApplyInsets()

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }
        col.addView(body, lp())

        // ---------- 오늘의 기록 ----------
        val rec = card()
        rec.addView(label("오늘의 기록", 13f, subGray, true))
        val recRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun block(title: String, timeTv: TextView, dateTv: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 13f, subGray))
            addView(timeTv)
            addView(dateTv)
        }
        inTv = label("—", 24f, navy, true)
        inSub = label("", 12f, subGray)
        outTv = label("—", 24f, green, true)
        outSub = label("", 12f, subGray)
        recRow.addView(block("출근", inTv, inSub), LinearLayout.LayoutParams(0, -2, 1f))
        recRow.addView(block("퇴근", outTv, outSub), LinearLayout.LayoutParams(0, -2, 1f))
        rec.addView(recRow, lp(10))
        body.addView(rec, lp())

        // ---------- 위치 상태 ----------
        val locCard = card()
        placeTv = label("", 13f, navy, true)
        locCard.addView(placeTv, lp())
        val locRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this).apply { background = rounded(Color.parseColor("#B8BECF"), 6) }
        locRow.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) })
        distTv = label("", 14f, ink)
        locRow.addView(distTv, LinearLayout.LayoutParams(0, -2, 1f))
        locCard.addView(locRow, lp(8))
        body.addView(locCard, lp(12))

        // ---------- 출근 / 퇴근 버튼 ----------
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun big(text: String, color: Int, action: Int) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(color, 20)
            stateListAnimator = null
            setOnClickListener { manual(action) }
        }
        inBtn = big("출근", navy, 1)
        outBtn = big("퇴근", green, 2)
        row.addView(inBtn, LinearLayout.LayoutParams(0, dp(110), 1f).apply { rightMargin = dp(6) })
        row.addView(outBtn, LinearLayout.LayoutParams(0, dp(110), 1f).apply { leftMargin = dp(6) })
        body.addView(row, lp(14))
        setBtns(false) // 회사 허용 반경 이내로 확인되기 전까지는 비활성

        msgTv = label("", 13f, subGray).apply { gravity = Gravity.CENTER }
        body.addView(msgTv, lp(12))

        // ---------- 4개 탭 (눌러서 펼치기/접기) ----------
        val names = arrayOf("로그인·기록 확인", "회사 아이디", "위치 확인", "설정")
        val tabGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0..1) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0..1) {
                val i = r * 2 + k
                val b = outlineBtn(names[i]) { showPanel(i) }
                tabBtns[i] = b
                line.addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    if (k == 0) rightMargin = dp(6) else leftMargin = dp(6)
                })
            }
            tabGrid.addView(line, lp(if (r == 0) 0 else 10))
        }
        body.addView(tabGrid, lp(10))
        for (i in 0..3) {
            val pnl = card().apply { visibility = View.GONE }
            panels[i] = pnl
            body.addView(pnl, lp(12))
        }

        // ----- 탭 1: 로그인·기록 확인 -----
        val p0 = panels[0]!!
        p0.addView(label("로그인 · 기록 확인", 13f, subGray, true))
        p0.addView(label("회사 사이트에 로그인해서 오늘의 출근/퇴근 기록과 출근지를 읽어와요. 출퇴근은 찍지 않아요.", 12f, subGray))
        p0.addView(filledBtn("지금 확인하기") { syncNow(true) }, lp(10, -1, dp(48)))
        p0.addView(outlineBtn("사이트 직접 열기 (수동 로그인/확인)") {
            startActivity(Intent(this, WebActivity::class.java))
        }, lp(10, -1, dp(46)))
        p0.addView(sectionTitle("점검 (실제로 찍지 않아요)"))
        p0.addView(label("로그인 · 화면 · 버튼 · 위치 확인까지만 하고, 출퇴근 기록은 남기지 않아요.", 12f, subGray))
        val dry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dry.addView(outlineBtn("출근 모의 실행") { dryRun(1) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
        dry.addView(outlineBtn("퇴근 모의 실행") { dryRun(2) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        p0.addView(dry, lp(6))

        // ----- 탭 2: 회사 아이디 -----
        val p1 = panels[1]!!
        p1.addView(label("회사 사이트 로그인", 13f, subGray, true))
        p1.addView(label("이 폰 안에 암호화되어 저장돼요", 12f, subGray))
        idEt = field("아이디")
        pwEt = field("비밀번호 (바꿀 때만 입력)", pwd = true)
        p1.addView(idEt)
        p1.addView(pwEt)
        p1.addView(filledBtn("저장하고 로그인 확인") {
            saveSettings(true)
            syncNow(true) // 저장한 계정으로 로그인이 되는지 바로 확인
        }, lp(14, -1, dp(48)))

        // ----- 탭 3: 위치 확인 -----
        val p2 = panels[2]!!
        p2.addView(label("출근지 · 위치", 13f, subGray, true))
        p2.addView(label("출근지는 사이트가 인정해 준 위치에서 앱이 자동으로 기억해요. 아래 직접 등록은 선택이에요.", 12f, subGray))
        locTv = label("", 13f, ink)
        p2.addView(locTv, lp(8))
        p2.addView(outlineBtn("지금 위치를 출근지로 직접 등록(선택)") { registerOffice() }, lp(8, -1, dp(46)))
        addrEt = field("회사 주소 (예: 경기도 ○○시 ○○로 12)")
        p2.addView(addrEt, lp(6))
        p2.addView(outlineBtn("주소로 회사 위치 찾기") { findAddress() }, lp(6, -1, dp(46)))
        radiusEt = field("출퇴근 허용 반경(m) — 기본 50", number = true)
        p2.addView(radiusEt, lp(8))
        p2.addView(filledBtn("저장") { saveSettings(true) }, lp(10, -1, dp(46)))

        // ----- 탭 4: 설정 -----
        val p3 = panels[3]!!
        p3.addView(label("자동 출퇴근 · 알림", 13f, subGray, true))
        hourEt = field("자동 퇴근 인식 시작 시각(시) — 기본 17", number = true)
        autoSw = Switch(this).apply { text = "회사 도착 시 자동 출근" }
        lateSw = Switch(this).apply { text = "08시 이후 도착하면 자동 출근 대신 알림만" }
        weekendSw = Switch(this).apply { text = "주말에는 자동 출퇴근·알림 끄기" }
        remindSw = Switch(this).apply { text = "알림: 출근 10분 전(07:50) · 퇴근(17:00)" }
        autoOutSw = Switch(this).apply { text = "회사를 벗어날 때 자동 퇴근 (이 경우만 거리 규칙 미적용)" }
        p3.addView(autoSw, lp(10))
        p3.addView(lateSw, lp(6))
        p3.addView(autoOutSw, lp(6))
        p3.addView(hourEt, lp(6))
        p3.addView(weekendSw, lp(6))
        p3.addView(remindSw, lp(6))
        p3.addView(sectionTitle("앱 업데이트"))
        p3.addView(label("새 버전이 있으면 화면 맨 위 버전 아래에 '업데이트가 필요함'이 표시돼요.", 12f, subGray))
        p3.addView(outlineBtn("지금 업데이트 확인") {
            Update.check(this, versionName(), true)
        }, lp(8, -1, dp(46)))
        p3.addView(filledBtn("저장") { saveSettings(true) }, lp(14, -1, dp(46)))

        // ---------- 푸터 ----------
        body.addView(
            label("한양기술공업 출퇴근 · v${versionName()}\n제작: $author", 12f, subGray).apply {
                gravity = Gravity.CENTER
            },
            lp(28)
        )

        // 저장된 값 반영 (스위치 리스너는 반영 후에 연결)
        idEt.setText(Prefs.getS(this, "uid"))
        addrEt.setText(Prefs.getS(this, "addr"))
        radiusEt.setText(Prefs.getI(this, "radius", 50).toString())
        hourEt.setText(Prefs.getI(this, "exitHour", 17).toString())
        autoSw.isChecked = Prefs.getB(this, "auto", false)
        weekendSw.isChecked = Prefs.getB(this, "skipWeekend", true)
        autoOutSw.isChecked = Prefs.getB(this, "autoOut", false)
        autoOutSw.setOnCheckedChangeListener { _, on -> Prefs.putB(this, "autoOut", on) }
        lateSw.isChecked = Prefs.getB(this, "lateNotifyOnly", true)
        lateSw.setOnCheckedChangeListener { _, on -> Prefs.putB(this, "lateNotifyOnly", on) }
        autoSw.setOnCheckedChangeListener { _, on -> onAutoToggled(on) }
        weekendSw.setOnCheckedChangeListener { _, on ->
            Prefs.putB(this, "skipWeekend", on)
            Remind.scheduleAll(this)
        }
        remindSw.isChecked = Prefs.getB(this, "remind", true)
        remindSw.setOnCheckedChangeListener { _, on ->
            Prefs.putB(this, "remind", on)
            Remind.scheduleAll(this)
            if (on) {
                Prefs.putB(this, "askedExact", false)
                askExactAlarmOnce()
            }
        }
    }

    private fun showPanel(i: Int) {
        openPanel = if (openPanel == i) -1 else i
        for (k in 0..3) {
            val on = k == openPanel
            panels[k]?.visibility = if (on) View.VISIBLE else View.GONE
            tabBtns[k]?.apply {
                background = rounded(if (on) navy else Color.parseColor("#EEF1FA"), 16)
                setTextColor(if (on) Color.WHITE else navy)
            }
        }
    }

    /** 모의 실행: 로그인·화면·버튼·위치를 확인하되 ExecCommuteClick 은 호출하지 않음 */
    private fun dryRun(action: Int) {
        if (!Prefs.hasCreds(this)) {
            Toast.makeText(this, "먼저 아이디/비밀번호를 저장해 주세요", Toast.LENGTH_LONG).show()
            return
        }
        if (ClockService.running) {
            Toast.makeText(this, "다른 작업을 처리 중이에요. 잠시 후 다시 눌러 주세요", Toast.LENGTH_LONG).show()
            return
        }
        msgTv.text = "모의 실행 중… (실제로 찍지 않아요)"
        ClockService.start(this, action, true, gate = false, dry = true)
    }

    private fun refresh() {
        val (inT, inD) = splitTime(Prefs.getS(this, "inText"))
        val (outT, outD) = splitTime(Prefs.getS(this, "outText"))
        inTv.text = inT
        inSub.text = inD
        outTv.text = outT
        outSub.text = outD
        msgTv.text = Prefs.getS(this, "msg")
        val at = Prefs.getS(this, "loginAt")
        when (Prefs.getS(this, "loginState")) {
            "ok" -> {
                loginTv.text = "● 회사 사이트 로그인됨 · $at"
                loginTv.setTextColor(Color.parseColor("#0E8A5F"))
            }
            "fail" -> {
                loginTv.text = "● 로그인 실패 · $at (설정에서 계정 확인)"
                loginTv.setTextColor(Color.parseColor("#C62828"))
            }
            else -> {
                loginTv.text = if (Prefs.hasCreds(this)) "● 로그인 확인 전" else "● 로그인 정보 없음 (설정에서 입력)"
                loginTv.setTextColor(subGray)
            }
        }
        updateRange()
        val lat = Prefs.getS(this, "lat")
        val lng = Prefs.getS(this, "lng")
        learnIfNear()
        updTv.visibility = if (Update.pending(this, versionName())) View.VISIBLE else View.GONE
        if (!addrEt.hasFocus()) addrEt.setText(Prefs.getS(this, "addr"))
        val spots = Spots.all(this)
        locTv.text = if (spots.isEmpty()) "알고 있는 출근지: 아직 없음 (출근지 근처에서 앱을 열면 자동으로 기억해요)"
        else "알고 있는 출근지 ${spots.size}곳\n" + spots.joinToString("\n") { (if (it.addr.isNotEmpty()) it.addr + " - " else "") + it.name }
    }

    // ---------------- 수동 출퇴근 ----------------
    private fun manual(action: Int) {
        if (!Prefs.hasCreds(this)) {
            Toast.makeText(this, "먼저 '회사 아이디' 탭에서 아이디/비밀번호를 저장해 주세요", Toast.LENGTH_LONG).show()
            showPanel(1)
            return
        }
        val name = if (action == 1) "출근" else "퇴근"
        val doneToday = Prefs.getS(this, if (action == 1) "inDate" else "outDate") == Util.today()
        ensureLocationPerms(needBackground = false) {
            if (doneToday) {
                AlertDialog.Builder(this)
                    .setMessage("오늘 이미 $name 기록이 있어요. 다시 찍을까요?")
                    .setPositiveButton("다시 찍기") { _, _ -> runAction(action) }
                    .setNegativeButton("취소", null)
                    .show()
            } else {
                runAction(action)
            }
        }
    }

    private fun runAction(action: Int) {
        if (ClockService.running) {
            Toast.makeText(this, "다른 작업을 처리 중이에요. 10~20초 뒤에 다시 눌러 주세요", Toast.LENGTH_LONG).show()
            return
        }
        msgTv.text = "출근지 확인 후 처리 중… (5~45초)"
        // 사이트가 폰 GPS로 '지정된 출근지 근처'로 인정(출근지명 표시)했을 때만 실제로 찍음
        ClockService.start(this, action, false, gate = false, near = true)
    }

    // ---------------- 설정 ----------------
    private fun saveSettings(toast: Boolean) {
        Prefs.putS(this, "uid", idEt.text.toString().trim())
        val pw = pwEt.text.toString()
        if (pw.isNotEmpty()) {
            Prefs.putS(this, "pw", pw)
            Prefs.putS(this, "loginState", "") // 계정이 바뀌었으니 다시 확인
        }
        pwEt.setText("")
        Prefs.putI(this, "radius", (radiusEt.text.toString().toIntOrNull() ?: 50).coerceIn(20, 1000))
        Prefs.putI(this, "exitHour", (hourEt.text.toString().toIntOrNull() ?: 17).coerceIn(0, 23))
        radiusEt.setText(Prefs.getI(this, "radius", 50).toString())
        hourEt.setText(Prefs.getI(this, "exitHour", 17).toString())
        if (Prefs.getB(this, "auto", false)) Geo.register(this)
        if (toast) Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
    }

    private fun onAutoToggled(on: Boolean) {
        if (!on) {
            Prefs.putB(this, "auto", false)
            Geo.unregister(this)
            return
        }
        saveSettings(false)
        ensureLocationPerms {
            Prefs.putB(this, "auto", true)
            Geo.register(this) { ok, m ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) "자동 출근을 켰어요" else "자동 출근을 켰어요. $m",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerOffice() {
        ensureLocationPerms {
            Toast.makeText(this, "현재 위치를 확인하는 중…", Toast.LENGTH_SHORT).show()
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc == null) {
                        Toast.makeText(this, "위치를 가져오지 못했어요. GPS를 켜고 다시 시도해 주세요", Toast.LENGTH_LONG).show()
                    } else {
                        Prefs.putS(this, "lat", loc.latitude.toString())
                        Prefs.putS(this, "lng", loc.longitude.toString())
                        Prefs.putS(this, "addr", "")
                        reverseAddress(loc.latitude, loc.longitude)
                        saveSettings(false)
                        refresh()
                        Toast.makeText(this, "회사 위치를 등록했어요 (정확도 약 ${loc.accuracy.toInt()}m)", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "위치 확인 실패: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    // ---------------- 권한 ----------------
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPerms(needBackground: Boolean = true, next: () -> Unit) {
        wantBg = needBackground
        val needBg = needBackground && Build.VERSION.SDK_INT >= 29 &&
            !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (has(Manifest.permission.ACCESS_FINE_LOCATION) && !needBg) {
            next()
            return
        }
        afterPerm = next
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        } else {
            askBackground()
        }
    }

    private fun askBackground() {
        AlertDialog.Builder(this)
            .setMessage("앱을 닫아도 회사 도착을 인식하려면 위치 권한을 '항상 허용'으로 해야 해요.")
            .setPositiveButton("확인") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            }
            .setNegativeButton("취소") { _, _ -> afterPerm = null }
            .show()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        when (code) {
            1 -> {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    if (wantBg && Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) askBackground()
                    else afterPerm?.invoke().also { afterPerm = null }
                } else {
                    Toast.makeText(this, "위치 권한이 필요해요", Toast.LENGTH_LONG).show()
                    afterPerm = null
                }
            }
            2 -> {
                if (has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    afterPerm?.invoke()
                } else {
                    Toast.makeText(
                        this,
                        "'항상 허용'이 아니면 앱을 닫았을 때 자동 출퇴근이 동작하지 않을 수 있어요",
                        Toast.LENGTH_LONG
                    ).show()
                    afterPerm?.invoke() // 최소한 현재 위치 등록 등은 진행
                }
                afterPerm = null
            }
        }
    }
}
