package com.hyt.commute

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 화면에 보이지 않는 WebView로 회사 사이트를 열어
 * (필요하면) 로그인 → 출근/퇴근 버튼(ExecCommuteClick) 실행 → 결과 시간 읽기 를 수행합니다.
 * 반드시 메인 스레드에서 start() 를 호출해야 합니다.
 */
class ClockRunner(
    private val app: Context,
    private val action: Int, // 1 = 출근, 2 = 퇴근
    private val skipIfDone: Boolean,
    private val requireNear: Boolean, // true: 사이트가 '지정된 출근지 근처'로 인정(출근지명 표시)할 때만 진행
    private val done: (Res) -> Unit
) {
    data class Res(
        val ok: Boolean,
        val skipped: Boolean,
        val msg: String,
        val start: String = "",
        val end: String = "",
        val place: String = "",
        val place2: String = "",
        val notNear: Boolean = false
    )

    private data class St(val s: String, val e: String, val state: String, val p: String = "", val q: String = "")

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wv: WebView
    private var finished = false
    private var clicking = false
    private var loginTries = 0
    private var otherCount = 0
    private var mainWait = 0
    private var readTries = 0
    private val alerts = mutableListOf<String>()
    private val stepRunnable = Runnable { step() }

    fun start() {
        wv = WebView(app)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.setGeolocationEnabled(true)
        wv.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                if (!message.isNullOrBlank()) alerts.add(message)
                result?.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm() // "출근하시겠습니까?" 같은 확인창은 '확인'
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                schedule(1200)
            }
        }
        h.postDelayed({ finish(Res(false, false, "시간 초과 (네트워크/사이트 상태를 확인하세요)")) }, 75_000)
        wv.loadUrl(Site.MAIN)
    }

    private fun schedule(ms: Long) {
        if (finished) return
        h.removeCallbacks(stepRunnable)
        h.postDelayed(stepRunnable, ms)
    }

    private fun finish(r: Res) {
        if (finished) return
        finished = true
        h.removeCallbacksAndMessages(null)
        try {
            wv.stopLoading()
            wv.destroy()
        } catch (_: Exception) {
        }
        done(r)
    }

    private fun unq(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            JSONTokener(raw).nextValue().toString()
        } catch (e: Exception) {
            raw
        }
    }

    private fun js(script: String, cb: (String) -> Unit) {
        if (finished) return
        wv.evaluateJavascript(script) { raw -> if (!finished) cb(unq(raw)) }
    }

    private fun parse(t: String): St = try {
        val o = JSONObject(t)
        St(o.optString("s"), o.optString("e"), o.optString("st"), o.optString("p"), o.optString("q"))
    } catch (e: Exception) {
        St("", "", "")
    }

    private fun step() {
        if (finished || clicking) return
        js(DETECT) { r ->
            when (r) {
                "login" -> doLogin()
                "main" -> {
                    markLogin(true)
                    onMain()
                }
                else -> {
                    otherCount++
                    when {
                        otherCount == 4 -> {
                            wv.loadUrl(Site.MAIN)
                        }
                        otherCount > 10 -> finish(Res(false, false, "로그인/출퇴근 화면을 찾지 못했어요. 설정의 '사이트 직접 열기'로 확인해 주세요."))
                        else -> schedule(1500)
                    }
                }
            }
        }
    }

    /** 로그인 성공/실패를 기록해서 앱 화면에 표시 */
    private fun markLogin(ok: Boolean) {
        Prefs.putS(app, "loginState", if (ok) "ok" else "fail")
        Prefs.putS(app, "loginAt", Util.now().substring(11, 16))
        Bus.onChange?.invoke()
    }

    private fun doLogin() {
        val uid = Prefs.getS(app, "uid")
        val pw = Prefs.getS(app, "pw")
        if (uid.isEmpty() || pw.isEmpty()) {
            markLogin(false)
            finish(Res(false, false, "저장된 로그인 정보가 없어요. 설정에서 ID/비밀번호를 저장하세요."))
            return
        }
        if (loginTries >= 2) {
            markLogin(false)
            finish(Res(false, false, "자동 로그인 실패. ID/비밀번호를 확인하거나 '사이트 직접 열기'로 한 번 로그인해 보세요."))
            return
        }
        loginTries++
        val script = LOGIN.replace("__U__", JSONObject.quote(uid)).replace("__P__", JSONObject.quote(pw))
        js(script) { _ -> schedule(4000) }
    }

    private var nearTries = 0

    /** 사이트가 폰 GPS로 '지정된 출근지'를 찾아 출근지명을 채울 때까지 기다림 (최대 약 40초) */
    private fun waitNear(cb: (St) -> Unit) {
        js(READ) { raw ->
            val st = parse(raw)
            if (st.p.isNotEmpty() || st.q.isNotEmpty() || nearTries >= 20) {
                cb(st)
            } else {
                nearTries++
                h.postDelayed({ waitNear(cb) }, 2000)
            }
        }
    }

    private fun onMain() {
        js(READY) { r ->
            if (r != "ready") {
                mainWait++
                if (mainWait > 25) finish(Res(false, false, "출퇴근 화면 로딩 실패"))
                else schedule(1000)
                return@js
            }
            if (clicking) return@js
            clicking = true
            val reader: ((St) -> Unit) -> Unit = { cb ->
                if (action == 3 || requireNear) waitNear(cb) else js(READ) { raw -> cb(parse(raw)) }
            }
            reader { before ->
                if (action == 0) { // 조회 전용: 버튼은 누르지 않고 사이트의 오늘 기록만 읽어옴
                    finish(Res(true, true, "동기화 완료", before.s, before.e, before.p, before.q))
                    return@reader
                }
                if (action == 3) { // 출근지 확인: 사이트가 출근지명을 보여주면 '근처'
                    val near = before.p.isNotEmpty() || before.q.isNotEmpty()
                    val detail = alerts.filter { it.isNotBlank() }.joinToString(" / ")
                    finish(Res(true, true, if (near) "근처" else "근처 아님 $detail".trim(), before.s, before.e, before.p, before.q, !near))
                    return@reader
                }
                if (requireNear && before.p.isEmpty() && before.q.isEmpty()) {
                    finish(Res(false, false, "지정된 출근지 근처가 아니에요 (사이트가 출근지를 찾지 못했어요)", before.s, before.e, "", "", true))
                    return@reader
                }
                val today = Util.today()
                val already = if (action == 1) before.s.contains(today) else before.e.contains(today)
                if (skipIfDone && already) {
                    finish(Res(true, true, "이미 처리되어 있어요", before.s, before.e, before.p, before.q))
                    return@reader
                }
                val click = "(function(){try{var w=document.querySelector('iframe').contentWindow;" +
                        "w.ExecCommuteClick($action);return 'ok';}catch(e){return 'err:'+e;}})()"
                js(click) { res ->
                    if (res != "ok") {
                        finish(Res(false, false, "버튼 실행 실패: $res"))
                    } else {
                        h.postDelayed({ readAfter(before) }, 4500)
                    }
                }
            }
        }
    }

    private fun readAfter(before: St) {
        js(READ) { raw ->
            val after = parse(raw)
            val today = Util.today()
            val ok = if (action == 1)
                after.s.isNotEmpty() && (after.s != before.s || after.s.contains(today))
            else
                after.e.isNotEmpty() && (after.e != before.e || after.e.contains(today))
            if (ok) {
                val label = if (action == 1) "출근" else "퇴근"
                val extra = if (alerts.isNotEmpty()) " (${alerts.joinToString(" / ")})" else ""
                finish(Res(true, false, "$label 완료$extra", after.s, after.e, after.p, after.q))
            } else if (readTries < (if (alerts.isNotEmpty()) 3 else 8)) {
                // 사이트가 GPS 위치를 받아온 뒤 처리하므로(실내에서는 10~20초 걸릴 수 있음) 넉넉히 기다림
                readTries++
                h.postDelayed({ readAfter(before) }, 3000)
            } else {
                val detail = (alerts + listOf(after.state)).filter { it.isNotBlank() }.joinToString(" / ")
                finish(Res(false, false, "처리 결과를 확인하지 못했어요. 사이트에서 직접 확인해 주세요. $detail".trim(), after.s, after.e))
            }
        }
    }

    companion object {
        private const val DETECT =
            "(function(){if(document.querySelector('input[type=password]'))return 'login';" +
                    "var f=document.querySelector('iframe');" +
                    "if(f&&f.src&&f.src.indexOf('CommuteManager')>=0)return 'main';return 'other';})()"

        private const val READY =
            "(function(){try{var w=document.querySelector('iframe').contentWindow;" +
                    "return (w.document.readyState==='complete'&&typeof w.ExecCommuteClick==='function')?'ready':'wait';" +
                    "}catch(e){return 'err';}})()"

        private const val READ =
            "(function(){try{var f=document.querySelector('iframe');var d=f.contentDocument,w=f.contentWindow;" +
                    "function v(k){var e=d.querySelector('input[id*=\"'+k+'_I\"]');return e?e.value:'';}" +
                    // 사이트가 GPS로 찾아 채워 주는 출근지/퇴근지 이름 읽기: 선택된 값, 없으면 목록 전체
                    "function pl(nm){var r='';try{var c=w.ASPxClientControl.GetControlCollection().GetByName(nm);" +
                    "if(c){var t=c.GetText();if(t&&t.trim()){r=t.trim();}else{var a=[];" +
                    "for(var i=0;i<c.GetItemCount();i++)a.push(c.GetItem(i).text);r=a.join(' / ');}}}catch(x){}return r;}" +
                    "return JSON.stringify({s:v('txtStartTime'),e:v('txtEndTime'),st:v('txtState')," +
                    "p:pl('CbPnlCommute_BootstrapFormLayout1_cboTitle'),q:pl('CbPnlCommute_BootstrapFormLayout1_cboTitle2')});" +
                    "}catch(x){return '{}';}})()"

        // 로그인 화면 자동 입력 (비밀번호 입력칸 바로 앞의 텍스트 입력칸을 ID로 간주)
        private const val LOGIN =
            "(function(u,p){" +
                    // 1) 한양 출퇴근 사이트 로그인 화면에 정확히 맞춘 입력 (안내문구가 값처럼 들어 있는 DevExpress 입력칸)
                    "function sv(el,val){var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;" +
                    "s.call(el,val);el.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "el.dispatchEvent(new Event('change',{bubbles:true}));}" +
                    "var ue=document.getElementById('txtUserId_I'),pe=document.getElementById('txtPassword_I')," +
                    "fk=document.getElementById('txtPassword_I_CLND'),be=document.getElementById('btnLogin');" +
                    "if(ue&&pe&&be){" +
                    "try{ue.focus();}catch(e){}" +
                    "sv(ue,u);" +
                    "try{var c=ASPxClientControl.GetControlCollection().GetByName('txtUserId');if(c&&c.SetText)c.SetText(u);}catch(e){}" +
                    "try{if(fk)fk.focus();}catch(e){}" +
                    "pe.style.display='';if(fk)fk.style.display='none';sv(pe,p);" +
                    "ue.classList.remove('input-placeholder');pe.classList.remove('input-placeholder');" +
                    "var rm=document.getElementById('chkRemember');if(rm&&!rm.checked)rm.click();" +
                    "setTimeout(function(){be.click();},300);return 'clicked';}" +
                    // 2) 그 외 일반적인 로그인 화면 (예비)
                    "var all=[].slice.call(document.querySelectorAll('input'));" +
                    "var pw=document.querySelector('input[type=password]');if(!pw)return 'nopw';" +
                    "var pi=all.indexOf(pw);var user=null;" +
                    "for(var k=pi-1;k>=0;k--){var t=all[k].type;" +
                    "if(t==='text'||t==='email'||t==='tel'||t==='number'||t===''){user=all[k];break;}}" +
                    "if(!user)return 'nouser';" +
                    "function setv(el,val){var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;" +
                    "s.call(el,val);el.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "el.dispatchEvent(new Event('change',{bubbles:true}));}" +
                    "setv(user,u);setv(pw,p);" +
                    "var btn=null;" +
                    "var c=[].slice.call(document.querySelectorAll('button,input[type=submit],input[type=button],input[type=image],a'));" +
                    "for(var i=0;i<c.length;i++){var tx=((c[i].innerText||c[i].value||c[i].alt||'')+'').replace(/\\s/g,'');" +
                    "if(/로그인|login|signin/i.test(tx)){btn=c[i];break;}}" +
                    "if(!btn)btn=document.querySelector('input[type=submit],button[type=submit]');" +
                    "if(btn){setTimeout(function(){btn.click();},300);return 'clicked';}" +
                    "if(pw.form){pw.form.submit();return 'submitted';}return 'nobtn';" +
                    "})(__U__,__P__)"
    }
}
