package com.hyt.commute

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra("t") ?: "오류 내용을 가져오지 못했어요"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply {
            this.text = "앱에 오류가 생겼어요. 이 화면을 캡처해서 보내 주세요."
            textSize = 16f
        })
        col.addView(Button(this).apply {
            this.text = "오류 내용 복사"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", text))
                Toast.makeText(this@CrashActivity, "복사했어요", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(Button(this).apply {
            this.text = "닫기"
            setOnClickListener { finishAffinity() }
        })
        val sv = ScrollView(this)
        sv.addView(TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
        })
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
    }
}
