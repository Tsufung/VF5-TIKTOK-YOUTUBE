package com.vf5plus.tiktokyoutube

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

private const val TAG = "WebAudioEngine"

class WebAudioEngine private constructor(private val appContext: Context) {

    companion object {
        @Volatile
        private var instance: WebAudioEngine? = null

        fun getInstance(context: Context): WebAudioEngine {
            return instance ?: synchronized(this) {
                instance ?: WebAudioEngine(context.applicationContext).also { instance = it }
            }
        }

        const val MODE_TIKTOK = "tiktok"
        const val MODE_YOUTUBE = "youtube"
    }

    interface EngineListener {
        fun onMetadataUpdated(author: String, title: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    var listener: EngineListener? = null
    var currentMode: String = MODE_TIKTOK
        private set
    var currentAuthor: String = "TikTok"
        private set
    var currentTitle: String = "Dành Cho Bạn"
        private set
    var isPlaying: Boolean = false
        private set

    val webView: WebView = WebView(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        try {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VF5TikTokYouTube:WakeLock")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }
        setupWebView()
    }

    private fun setupWebView() {
        webView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                unmuteAndPlay()
            }
        }

        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")
        loadTikTokForYou()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun updateMetadata(author: String, desc: String) {
            mainHandler.post {
                val safeAuthor = if (author.isNotBlank()) author.trim() else if (currentMode == MODE_TIKTOK) "TikTok" else "YouTube"
                val safeTitle = if (desc.isNotBlank()) desc.trim() else "Đang phát âm thanh"
                currentAuthor = safeAuthor
                currentTitle = safeTitle
                listener?.onMetadataUpdated(safeAuthor, safeTitle)
            }
        }

        @JavascriptInterface
        fun onPlaybackState(playing: Boolean) {
            mainHandler.post {
                isPlaying = playing
                listener?.onPlaybackStateChanged(playing)
            }
        }
    }

    fun attachToContainer(container: ViewGroup) {
        mainHandler.post {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            container.addView(
                webView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            webView.requestFocus()
            unmuteAndPlay()
        }
    }

    fun detachFromContainer() {
        mainHandler.post {
            (webView.parent as? ViewGroup)?.removeView(webView)
        }
    }

    // ─── AudioFocus & WakeLock ────────────────────────────────────────────────

    fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(audioAttributes)
                        .setOnAudioFocusChangeListener { focusChange ->
                            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                                pause()
                            }
                        }
                        .build()
                }
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            pause()
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(3 * 60 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestAudioFocus error: ${e.message}")
        }
    }

    fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "abandonAudioFocus error: ${e.message}")
        }
    }

    // ─── Điều Hướng Nguồn Phát ────────────────────────────────────────────────

    fun loadTikTokForYou() {
        mainHandler.post {
            currentMode = MODE_TIKTOK
            requestAudioFocus()
            currentAuthor = "TikTok"
            currentTitle = "TikTok — Dành Cho Bạn"
            listener?.onMetadataUpdated(currentAuthor, currentTitle)
            webView.loadUrl("https://www.tiktok.com/foryou")
        }
    }

    fun loadYouTube() {
        mainHandler.post {
            currentMode = MODE_YOUTUBE
            requestAudioFocus()
            currentAuthor = "YouTube"
            currentTitle = "YouTube — Trang Chủ"
            listener?.onMetadataUpdated(currentAuthor, currentTitle)
            webView.loadUrl("https://m.youtube.com")
        }
    }

    // ─── Điều Khiển Chuyển Bài Bằng Vô Lăng (Steering Wheel) ──────────────────

    fun skipNext() {
        mainHandler.post {
            requestAudioFocus()
            if (currentMode == MODE_YOUTUBE) {
                val js = """
                    (function() {
                        var nextBtn = document.querySelector('.ytp-next-button, button.slim-video-action-bar-next-button, [aria-label*="Next" i], [aria-label*="tiếp" i]');
                        if (nextBtn) {
                            nextBtn.click();
                        } else {
                            var v = document.querySelector('video');
                            if (v && v.duration) { v.currentTime = v.duration - 0.5; }
                        }
                        setTimeout(function() {
                            var v = document.querySelector('video');
                            if (v) { v.muted = false; v.play(); }
                            var title = (document.querySelector('h1.title, .slim-video-metadata-title, h2.title')?.textContent || document.title || '').trim();
                            var author = (document.querySelector('.ytm-channel-name, .ytd-channel-name, #channel-name')?.textContent || 'YouTube').trim();
                            if (window.AndroidBridge) {
                                window.AndroidBridge.updateMetadata(author, title);
                            }
                        }, 800);
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            } else {
                // TikTok Next
                val js = """
                    (function() {
                        var selectors = [
                            'button[data-e2e="arrow-right"]',
                            'button[aria-label*="next" i]',
                            'button[aria-label*="tiếp" i]',
                            'button[data-e2e="feed-next"]',
                            '[data-e2e="arrow-right"]',
                            'button[aria-label="Next video"]',
                            'button[aria-label="Video tiếp theo"]'
                        ];
                        var clicked = false;
                        for (var i = 0; i < selectors.length; i++) {
                            var btn = document.querySelector(selectors[i]);
                            if (btn) {
                                btn.click();
                                clicked = true;
                                break;
                            }
                        }
                        if (!clicked) {
                            try {
                                var ev = new KeyboardEvent('keydown', {key: 'ArrowDown', code: 'ArrowDown', keyCode: 40, which: 40, bubbles: true});
                                document.dispatchEvent(ev);
                                window.dispatchEvent(ev);
                            } catch(e) {}
                            window.scrollBy({top: window.innerHeight, behavior: 'smooth'});
                        }
                        setTimeout(function() {
                            var vids = document.querySelectorAll('video');
                            for (var i = 0; i < vids.length; i++) {
                                vids[i].muted = false;
                                vids[i].volume = 1.0;
                                vids[i].play();
                            }
                            var author = (document.querySelector('[data-e2e="video-author-uniqueid"]')?.textContent || '').trim();
                            var desc = (document.querySelector('[data-e2e="browse-video-desc"]')?.textContent || '').trim();
                            if (!author && !desc) { desc = document.title; }
                            if (window.AndroidBridge) {
                                window.AndroidBridge.updateMetadata(author || 'TikTok Video', desc || 'Đang phát TikTok');
                            }
                        }, 500);
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
            isPlaying = true
            listener?.onPlaybackStateChanged(true)
        }
    }

    fun skipPrevious() {
        mainHandler.post {
            requestAudioFocus()
            if (currentMode == MODE_YOUTUBE) {
                webView.evaluateJavascript("history.back();", null)
            } else {
                val js = """
                    (function() {
                        var selectors = [
                            'button[data-e2e="arrow-left"]',
                            'button[aria-label*="prev" i]',
                            'button[aria-label*="trước" i]',
                            '[data-e2e="arrow-left"]'
                        ];
                        var clicked = false;
                        for (var i = 0; i < selectors.length; i++) {
                            var btn = document.querySelector(selectors[i]);
                            if (btn) {
                                btn.click();
                                clicked = true;
                                break;
                            }
                        }
                        if (!clicked) {
                            try {
                                var ev = new KeyboardEvent('keydown', {key: 'ArrowUp', code: 'ArrowUp', keyCode: 38, which: 38, bubbles: true});
                                document.dispatchEvent(ev);
                                window.dispatchEvent(ev);
                            } catch(e) {}
                            window.scrollBy({top: -window.innerHeight, behavior: 'smooth'});
                        }
                        setTimeout(function() {
                            var vids = document.querySelectorAll('video');
                            for (var i = 0; i < vids.length; i++) {
                                vids[i].muted = false;
                                vids[i].volume = 1.0;
                                vids[i].play();
                            }
                        }, 500);
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
            isPlaying = true
            listener?.onPlaybackStateChanged(true)
        }
    }

    fun play() {
        mainHandler.post {
            requestAudioFocus()
            unmuteAndPlay()
            isPlaying = true
            listener?.onPlaybackStateChanged(true)
        }
    }

    fun pause() {
        mainHandler.post {
            val js = """
                (function() {
                    var mediaEls = document.querySelectorAll('video, audio');
                    for (var i = 0; i < mediaEls.length; i++) {
                        try {
                            mediaEls[i].pause();
                            mediaEls[i].muted = true;
                            mediaEls[i].volume = 0;
                        } catch(e) {}
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
            abandonAudioFocus()
            isPlaying = false
            listener?.onPlaybackStateChanged(false)
        }
    }

    fun unmuteAndPlay() {
        val js = """
            (function() {
                var vids = document.querySelectorAll('video');
                for (var i = 0; i < vids.length; i++) {
                    vids[i].muted = false;
                    vids[i].volume = 1.0;
                    vids[i].play();
                }
                var muteBtns = document.querySelectorAll('button[data-e2e="video-sound-mute"], [aria-label*="unmute" i], [aria-label*="bật âm" i], [aria-label*="Bật âm" i]');
                for (var j = 0; j < muteBtns.length; j++) {
                    muteBtns[j].click();
                }
                var author = (document.querySelector('[data-e2e="video-author-uniqueid"]')?.textContent || '').trim();
                var desc = (document.querySelector('[data-e2e="browse-video-desc"]')?.textContent || '').trim();
                if (!author && !desc) { desc = document.title; }
                if (window.AndroidBridge) {
                    window.AndroidBridge.updateMetadata(author || 'Đang phát', desc || '');
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ─── Trợ Thủ Nhập Mã Xác Minh OTP TikTok ─────────────────────────────────

    fun injectOtp(code: String) {
        val safeCode = code.trim().replace("'", "\\'")
        val js = """
            (function() {
                var otp = '$safeCode';
                var inputs = document.querySelectorAll('input[type="text"], input[type="number"], input[type="tel"], input[name*="code" i], input[placeholder*="code" i], input[placeholder*="xác minh" i], input[placeholder*="mã" i]');
                if (inputs.length === 1) {
                    inputs[0].focus();
                    inputs[0].value = otp;
                    inputs[0].dispatchEvent(new Event('input', { bubbles: true }));
                    inputs[0].dispatchEvent(new Event('change', { bubbles: true }));
                } else if (inputs.length >= 4) {
                    for (var i = 0; i < Math.min(otp.length, inputs.length); i++) {
                        inputs[i].focus();
                        inputs[i].value = otp[i];
                        inputs[i].dispatchEvent(new Event('input', { bubbles: true }));
                        inputs[i].dispatchEvent(new Event('change', { bubbles: true }));
                    }
                } else {
                    var el = document.activeElement;
                    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                        el.value = otp;
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                }
            })();
        """.trimIndent()
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun stopAndRelease() {
        mainHandler.post {
            pause()
            abandonAudioFocus()
            wakeLock?.let { if (it.isHeld) it.release() }
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
            } catch (e: Exception) {}
        }
    }
}
