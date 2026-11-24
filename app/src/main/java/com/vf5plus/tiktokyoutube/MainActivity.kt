package com.vf5plus.tiktokyoutube

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), WebAudioEngine.EngineListener {

    private lateinit var engine: WebAudioEngine
    private lateinit var phoneWebViewContainer: FrameLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvMetadata: TextView
    private lateinit var etOtpCode: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Start VF5MediaPlaybackService as foreground service
        val serviceIntent = Intent(this, VF5MediaPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        engine = WebAudioEngine.getInstance(this)
        engine.listener = this

        phoneWebViewContainer = findViewById(R.id.phoneWebViewContainer)
        tvStatus = findViewById(R.id.tvStatus)
        tvMetadata = findViewById(R.id.tvMetadata)
        etOtpCode = findViewById(R.id.etOtpCode)

        val btnTikTok: Button = findViewById(R.id.btnTikTok)
        val btnYouTube: Button = findViewById(R.id.btnYouTube)
        val btnTestNext: Button = findViewById(R.id.btnTestNext)
        val btnStopAll: Button = findViewById(R.id.btnStopAll)
        val btnInjectOtp: Button = findViewById(R.id.btnInjectOtp)
        val btnForceKeyboard: Button = findViewById(R.id.btnForceKeyboard)

        btnTikTok.setOnClickListener {
            engine.loadTikTokForYou()
            Toast.makeText(this, "Đã chuyển sang TikTok", Toast.LENGTH_SHORT).show()
        }

        btnYouTube.setOnClickListener {
            engine.loadYouTube()
            Toast.makeText(this, "Đã chuyển sang YouTube", Toast.LENGTH_SHORT).show()
        }

        btnTestNext.setOnClickListener {
            engine.skipNext()
            Toast.makeText(this, "Đã chuyển bài tiếp theo!", Toast.LENGTH_SHORT).show()
        }

        btnStopAll.setOnClickListener {
            engine.stopAndRelease()
            val stopIntent = Intent(this, VF5MediaPlaybackService::class.java)
            stopService(stopIntent)
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        // Nút gửi mã OTP vào ô nhập trên TikTok
        btnInjectOtp.setOnClickListener {
            val code = etOtpCode.text.toString().trim()
            if (code.isNotEmpty()) {
                engine.injectOtp(code)
                Toast.makeText(this, "Đã gửi mã $code vào ô xác minh!", Toast.LENGTH_SHORT).show()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etOtpCode.windowToken, 0)
            } else {
                Toast.makeText(this, "Vui lòng nhập mã xác minh trước!", Toast.LENGTH_SHORT).show()
            }
        }

        // Nút cưỡng chế bật bàn phím ảo
        btnForceKeyboard.setOnClickListener {
            engine.webView.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(engine.webView, InputMethodManager.SHOW_FORCED)
            Toast.makeText(this, "Đang mở bàn phím ảo...", Toast.LENGTH_SHORT).show()
        }

        // Gắn WebView vào màn hình điện thoại
        engine.attachToContainer(phoneWebViewContainer)

        // Cấu hình focus để khi bấm vào ô nhập trên web sẽ tự nảy bàn phím
        engine.webView.setOnTouchListener { v, _ ->
            if (!v.hasFocus()) {
                v.requestFocus()
            }
            false
        }

        updateUiState(engine.currentAuthor, engine.currentTitle, engine.isPlaying)
    }

    override fun onDestroy() {
        if (isFinishing) {
            engine.stopAndRelease()
            val stopIntent = Intent(this, VF5MediaPlaybackService::class.java)
            stopService(stopIntent)
        }
        super.onDestroy()
    }

    // ─── EngineListener ───────────────────────────────────────────────────────

    override fun onMetadataUpdated(author: String, title: String) {
        runOnUiThread {
            updateUiState(author, title, engine.isPlaying)
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            updateUiState(engine.currentAuthor, engine.currentTitle, isPlaying)
        }
    }

    private fun updateUiState(author: String, title: String, isPlaying: Boolean) {
        val mode = if (engine.currentMode == WebAudioEngine.MODE_TIKTOK) "TikTok" else "YouTube"
        tvStatus.text = if (isPlaying) "🔊 $mode trên xe VF5: Đang phát (Vô-lăng chuyển bài)" else "⏸ $mode trên xe VF5: Tạm dừng"
        tvMetadata.text = "$author • $title"
    }
}
