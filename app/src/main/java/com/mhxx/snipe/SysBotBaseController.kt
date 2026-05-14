package com.mhxx.snipe

import android.os.Handler
import android.os.Looper
import java.io.OutputStream
import java.net.Socket

/**
 * Nintendo Switch sys-botbase TCP/IP コントローラー
 *
 * sys-botbase プロトコル:
 *   - Switch に sys-botbase をインストールし、同一WiFiに接続
 *   - TCP接続: SwitchのIP, ポート6000 (デフォルト)
 *   - コマンドはASCII + 改行で送信
 *
 * 対応コマンド:
 *   click   BUTTON        - ボタンを1回押して離す
 *   press   BUTTON        - ボタンを押し続ける
 *   release BUTTON        - ボタンを離す
 *   setStick LEFT  x y   - 左スティック (-32768 〜 32767)
 *   setStick RIGHT x y   - 右スティック (-32768 〜 32767)
 *
 * ボタン名: A B X Y L R ZL ZR PLUS MINUS HOME CAPTURE
 *           LSTICK RSTICK DUP DDOWN DLEFT DRIGHT
 */
class SysBotBaseController(
    private val ip: String,
    private val port: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(msg: String)
    }

    companion object {
        // sys-botbase ボタン名マッピング (MHXXSnipeApp 内部名 → sys-botbase名)
        val BUTTON_MAP = mapOf(
            "A"       to "A",
            "B"       to "B",
            "X"       to "X",
            "Y"       to "Y",
            "L"       to "L",
            "R"       to "R",
            "ZL"      to "ZL",
            "ZR"      to "ZR",
            "PLUS"    to "PLUS",
            "MINUS"   to "MINUS",
            "HOME"    to "HOME",
            "CAPTURE" to "CAPTURE",
            "UP"      to "DUP",
            "DOWN"    to "DDOWN",
            "LEFT"    to "DLEFT",
            "RIGHT"   to "DRIGHT",
            "LS"      to "LSTICK",
            "RS"      to "RSTICK"
        )
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    @Volatile private var isConnected = false
    private val handler = Handler(Looper.getMainLooper())

    fun connect() {
        Thread {
            try {
                val s = Socket(ip, port)
                s.soTimeout = 0 // 受信タイムアウトなし
                socket = s
                outputStream = s.getOutputStream()
                isConnected = true
                listener.onConnected()
            } catch (e: Exception) {
                isConnected = false
                listener.onError("接続失敗: ${e.message}")
            }
        }.start()
    }

    /**
     * ボタンを durationMs ミリ秒間押してから離す
     */
    fun pressButton(buttonName: String, durationMs: Int) {
        val btn = BUTTON_MAP[buttonName.uppercase()] ?: buttonName.uppercase()
        Thread {
            try {
                sendCommand("press $btn")
                Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                sendCommand("release $btn")
            } catch (e: Exception) {
                handleError(e)
            }
        }.start()
    }

    /**
     * 複数ボタン同時押し
     */
    fun pressButtons(buttons: List<String>, durationMs: Int) {
        Thread {
            try {
                val mapped = buttons.map { BUTTON_MAP[it.uppercase()] ?: it.uppercase() }
                mapped.forEach { sendCommand("press $it") }
                Thread.sleep(durationMs.toLong().coerceAtLeast(50))
                mapped.forEach { sendCommand("release $it") }
            } catch (e: Exception) {
                handleError(e)
            }
        }.start()
    }

    /**
     * ボタンをクリック（press + release 一括）
     */
    fun clickButton(buttonName: String) {
        val btn = BUTTON_MAP[buttonName.uppercase()] ?: buttonName.uppercase()
        Thread {
            try {
                sendCommand("click $btn")
            } catch (e: Exception) {
                handleError(e)
            }
        }.start()
    }

    /**
     * 左スティックを設定 (x,y: -1.0 〜 1.0 → -32767 〜 32767)
     */
    fun setLeftStick(x: Float, y: Float) {
        val lx = (x.coerceIn(-1f, 1f) * 32767).toInt()
        val ly = (y.coerceIn(-1f, 1f) * 32767).toInt()
        Thread {
            try {
                sendCommand("setStick LEFT $lx $ly")
            } catch (e: Exception) {
                handleError(e)
            }
        }.start()
    }

    /**
     * 右スティックを設定 (x,y: -1.0 〜 1.0 → -32767 〜 32767)
     */
    fun setRightStick(x: Float, y: Float) {
        val rx = (x.coerceIn(-1f, 1f) * 32767).toInt()
        val ry = (y.coerceIn(-1f, 1f) * 32767).toInt()
        Thread {
            try {
                sendCommand("setStick RIGHT $rx $ry")
            } catch (e: Exception) {
                handleError(e)
            }
        }.start()
    }

    /**
     * スティックをニュートラル位置に戻す
     */
    fun resetLeftStick() {
        Thread {
            try { sendCommand("setStick LEFT 0 0") } catch (e: Exception) { handleError(e) }
        }.start()
    }

    fun resetRightStick() {
        Thread {
            try { sendCommand("setStick RIGHT 0 0") } catch (e: Exception) { handleError(e) }
        }.start()
    }

    /**
     * sys-botbase にコマンドを送信 (ASCII + newline)
     */
    @Synchronized
    fun sendCommand(cmd: String) {
        val out = outputStream ?: throw RuntimeException("未接続")
        out.write("$cmd\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    fun disconnect() {
        isConnected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
        listener.onDisconnected()
    }

    fun isConnected() = isConnected

    private fun handleError(e: Exception) {
        if (isConnected) {
            isConnected = false
            handler.post { listener.onError("エラー: ${e.message}") }
        }
    }
}
