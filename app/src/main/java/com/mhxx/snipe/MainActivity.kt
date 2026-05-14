package com.mhxx.snipe

import android.graphics.Color
import android.os.*
import android.view.*
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mhxx.snipe.sysdvr.SysDVRCaptureActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var switchController: SysBotBaseController? = null
    private var macroEngine: MacroEngine? = null
    private var currentSwitchIp: String? = null

    /** 現在開いている SysDVRCaptureActivity (操作録画フォワード用) */
    var activeCaptureActivity: SysDVRCaptureActivity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                setGeolocationEnabled(false)
            }
            setBackgroundColor(Color.parseColor("#0b0c10"))
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
        }

        macroEngine = MacroEngine(this)
        webView.addJavascriptInterface(
            WebAppInterface(this, webView, macroEngine!!),
            "AndroidBridge"
        )

        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    // ── 接続管理 ─────────────────────────────────────────────────────────
    fun connectToSwitch(ip: String, port: Int) {
        switchController?.disconnect()
        switchController = null
        currentSwitchIp = null

        if (ip.isBlank()) {
            sendToJs("onConnectionStatus", "'error'", "'IPアドレスを入力してください'")
            return
        }

        currentSwitchIp = ip

        switchController = SysBotBaseController(ip, port, object : SysBotBaseController.Listener {
            override fun onConnected() {
                macroEngine?.setController(switchController)
                runOnUiThread { sendToJs("onConnectionStatus", "'connected'", "'接続成功 ($ip:$port)'") }
            }
            override fun onDisconnected() {
                macroEngine?.setController(null)
                runOnUiThread { sendToJs("onConnectionStatus", "'disconnected'", "'切断されました'") }
            }
            override fun onError(msg: String) {
                macroEngine?.setController(null)
                runOnUiThread { sendToJs("onConnectionStatus", "'error'", "'${msg}'") }
            }
        })

        switchController?.connect()
        sendToJs("onConnectionStatus", "'connecting'", "'接続中... ($ip:$port)'")
    }

    fun disconnectFromSwitch() {
        switchController?.disconnect()
        switchController = null
        currentSwitchIp = null
        macroEngine?.setController(null)
    }

    fun isSwitchConnected(): Boolean = switchController?.isConnected() == true

    /** 現在の接続先 IP を返す (SysDVR 起動用) */
    fun getCurrentSwitchIp(): String? = currentSwitchIp

    // ── ボタン操作 ────────────────────────────────────────────────────────
    fun sendButton(button: String, durationMs: Int) {
        switchController?.pressButton(button, durationMs)
    }

    // ── スティック操作 ─────────────────────────────────────────────────────
    fun tiltStick(side: String, x: Float, y: Float, durationMs: Int) {
        val ctrl = switchController ?: return
        Thread {
            try {
                when (side.uppercase().trimStart('"').trimEnd('"')) {
                    "L", "LEFT"  -> {
                        ctrl.setLeftStick(x, y)
                        Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                        ctrl.resetLeftStick()
                    }
                    "R", "RIGHT" -> {
                        ctrl.setRightStick(x, y)
                        Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                        ctrl.resetRightStick()
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) { /* ignore */ }
        }.start()
    }

    fun moveStick(side: String, x: Int, y: Int) {
        val ctrl = switchController ?: return
        val xf = (x.toFloat() / 32767f).coerceIn(-1f, 1f)
        val yf = (y.toFloat() / 32767f).coerceIn(-1f, 1f)
        when (side.uppercase()) {
            "L" -> ctrl.setLeftStick(xf, yf)
            "R" -> ctrl.setRightStick(xf, yf)
        }
    }

    // ── JS通信 ───────────────────────────────────────────────────────────
    fun sendToJs(fn: String, vararg args: String) {
        val argStr = args.joinToString(",")
        webView.post { webView.evaluateJavascript("if(window.$fn) window.$fn($argStr);", null) }
    }

    override fun onDestroy() {
        super.onDestroy()
        switchController?.disconnect()
        macroEngine?.stopAll()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
