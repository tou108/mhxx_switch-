package com.mhxx.snipe

import android.content.Context
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.mhxx.snipe.sysdvr.SysDVRCaptureActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript ↔ Android ブリッジ
 *
 * 追加メソッド:
 *   AndroidBridge.launchCapture()           → 現在の接続先IPで画面キャプチャ起動
 *   AndroidBridge.launchCaptureWithIp(ip)   → 指定IPで画面キャプチャ起動
 */
class WebAppInterface(
    private val activity: MainActivity,
    private val webView: WebView,
    private val macroEngine: MacroEngine
) {

    @JavascriptInterface
    fun connectSwitch(ip: String, port: Int) {
        activity.runOnUiThread { activity.connectToSwitch(ip, port) }
    }

    @JavascriptInterface
    fun disconnectSwitch() {
        activity.runOnUiThread { activity.disconnectFromSwitch() }
    }

    @JavascriptInterface
    fun pressButton(button: String, durationMs: Int) {
        activity.activeCaptureActivity?.notifyButtonPress(button, durationMs)
        activity.sendButton(button, durationMs)
    }

    @JavascriptInterface
    fun pressButtons(buttonsJson: String, durationMs: Int) {
        val arr  = JSONArray(buttonsJson)
        val list = (0 until arr.length()).map { arr.getString(it) }
        list.forEach { btn -> activity.activeCaptureActivity?.notifyButtonPress(btn, durationMs) }
        val json = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "multi")
                put("buttons", JSONArray(list))
                put("duration", durationMs)
            })
        }
        macroEngine.executeJson(json.toString())
    }

    @JavascriptInterface
    fun tiltStick(side: String, x: Float, y: Float, durationMs: Int) {
        activity.activeCaptureActivity?.notifyStickMove(side, x, y)
        activity.tiltStick(side, x, y, durationMs)
    }

    @JavascriptInterface
    fun moveStick(side: String, x: Int, y: Int) {
        val xf = (x.toFloat() / 32767f).coerceIn(-1f, 1f)
        val yf = (y.toFloat() / 32767f).coerceIn(-1f, 1f)
        activity.activeCaptureActivity?.notifyStickMove(side, xf, yf)
        activity.moveStick(side, x, y)
    }

    @JavascriptInterface
    fun setStick(side: String, x: Int, y: Int) {
        val xf = (x.toFloat() / 32767f).coerceIn(-1f, 1f)
        val yf = (y.toFloat() / 32767f).coerceIn(-1f, 1f)
        activity.activeCaptureActivity?.notifyStickMove(side, xf, yf)
        activity.moveStick(side, x, y)
    }

    @JavascriptInterface
    fun runMacro(jsonStr: String) {
        macroEngine.setStatusCallback { status, msg ->
            activity.sendToJs("onMacroStatus", "'$status'", "'$msg'")
        }
        macroEngine.executeJson(jsonStr)
    }

    @JavascriptInterface
    fun runPreset(presetName: String) {
        macroEngine.setStatusCallback { status, msg ->
            activity.sendToJs("onMacroStatus", "'$status'", "'$msg'")
        }
        macroEngine.executePreset(presetName)
    }

    @JavascriptInterface
    fun stopMacro() { macroEngine.stop() }

    // ── SysDVR ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun launchCapture() {
        val ip = activity.getCurrentSwitchIp()
        if (ip.isNullOrBlank()) {
            activity.sendToJs("onCaptureStatus", "'error'", "'Switch IPを先に設定してください'")
            return
        }
        activity.runOnUiThread { SysDVRCaptureActivity.launch(activity, ip) }
    }

    @JavascriptInterface
    fun launchCaptureWithIp(ip: String) {
        if (ip.isBlank()) {
            activity.sendToJs("onCaptureStatus", "'error'", "'IP が空です'")
            return
        }
        activity.runOnUiThread { SysDVRCaptureActivity.launch(activity, ip) }
    }

    // ── Utility ─────────────────────────────────────────────────────────

    @JavascriptInterface
    fun vibrate(ms: Int) {
        val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        @Suppress("DEPRECATION")
        v?.vibrate(ms.toLong().coerceIn(10, 500))
    }

    @JavascriptInterface fun getPresetList(): String = macroEngine.PRESETS.keys.joinToString(",")
    @JavascriptInterface fun isAndroid(): Boolean = true
    @JavascriptInterface fun getAppVersion(): String = "2.2.0-MHXX-SysDVR"
    @JavascriptInterface fun isConnected(): Boolean = activity.isSwitchConnected()
}
