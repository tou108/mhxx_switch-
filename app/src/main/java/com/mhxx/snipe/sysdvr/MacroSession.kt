package com.mhxx.snipe.sysdvr

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "MacroSession"

/**
 * マクロセッション - 操作録画 & 再生 (フィードバックループ)
 *
 * 録画開始 → ボタン/スティック操作がすべてタイムスタンプ付きで記録される
 * 録画停止 → 動画ファイルと同名の .json ファイルに保存
 * インポート → .json ファイルを読み込んで再生可能に
 * 再生 → 記録した操作を同じタイミングで SysBotBaseController 経由で実行
 */
class MacroSession {
    data class InputEvent(
        val relTimeMs: Long,         // 録画開始からの経過 ms
        val type: String,            // "button" | "stick"
        val button: String = "",     // type=button: ボタン名
        val durationMs: Int = 0,     // type=button: 押下時間
        val side: String = "",       // type=stick: L/R
        val x: Float = 0f,           // type=stick: -1.0〜1.0
        val y: Float = 0f            // type=stick: -1.0〜1.0
    )

    private val events = mutableListOf<InputEvent>()
    private var recordingStartMs = -1L
    var isRecording = false
        private set

    // ── 録画 ──────────────────────────────────────────────────────────────

    fun startRecording() {
        events.clear()
        recordingStartMs = System.currentTimeMillis()
        isRecording = true
        Log.d(TAG, "Recording started")
    }

    fun stopRecording() {
        isRecording = false
        Log.d(TAG, "Recording stopped: ${events.size} events")
    }

    fun recordButton(button: String, durationMs: Int) {
        if (!isRecording) return
        val rel = System.currentTimeMillis() - recordingStartMs
        events.add(InputEvent(rel, "button", button = button, durationMs = durationMs))
    }

    fun recordStick(side: String, x: Float, y: Float) {
        if (!isRecording) return
        val rel = System.currentTimeMillis() - recordingStartMs
        events.add(InputEvent(rel, "stick", side = side, x = x, y = y))
    }

    // ── 保存 & 読み込み ───────────────────────────────────────────────────

    fun saveToFile(jsonFile: File) {
        val arr = JSONArray()
        for (e in events) {
            val obj = JSONObject().apply {
                put("t", e.relTimeMs)
                put("type", e.type)
                when (e.type) {
                    "button" -> {
                        put("button", e.button)
                        put("dur", e.durationMs)
                    }
                    "stick" -> {
                        put("side", e.side)
                        put("x", e.x.toDouble())
                        put("y", e.y.toDouble())
                    }
                }
            }
            arr.put(obj)
        }
        jsonFile.parentFile?.mkdirs()
        jsonFile.writeText(arr.toString(2))
        Log.d(TAG, "Saved ${events.size} events to ${jsonFile.absolutePath}")
    }

    fun loadFromFile(jsonFile: File) {
        events.clear()
        val arr = JSONArray(jsonFile.readText())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val type = obj.getString("type")
            val e = when (type) {
                "button" -> InputEvent(
                    relTimeMs = obj.getLong("t"),
                    type = "button",
                    button = obj.getString("button"),
                    durationMs = obj.optInt("dur", 100)
                )
                "stick" -> InputEvent(
                    relTimeMs = obj.getLong("t"),
                    type = "stick",
                    side = obj.getString("side"),
                    x = obj.getDouble("x").toFloat(),
                    y = obj.getDouble("y").toFloat()
                )
                else -> null
            }
            e?.let { events.add(it) }
        }
        Log.d(TAG, "Loaded ${events.size} events from ${jsonFile.absolutePath}")
    }

    fun hasEvents() = events.isNotEmpty()
    fun eventCount() = events.size

    // ── 再生 ──────────────────────────────────────────────────────────────

    /**
     * 録画した操作をリプレイする
     * @param controller  sys-botbase コントローラー
     * @param onProgress  進捗コールバック (done/total)
     * @param onFinished  完了コールバック
     */
    suspend fun replay(
        controller: com.mhxx.snipe.SysBotBaseController,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onFinished: () -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) {
            onFinished()
            return@withContext
        }
        Log.d(TAG, "Replay started: ${events.size} events")

        val startMs = System.currentTimeMillis()
        var idx = 0

        while (isActive && idx < events.size) {
            val ev = events[idx]
            val elapsed = System.currentTimeMillis() - startMs
            val waitMs = ev.relTimeMs - elapsed

            if (waitMs > 0) {
                delay(waitMs)
            }

            try {
                when (ev.type) {
                    "button" -> controller.pressButton(ev.button, ev.durationMs)
                    "stick" -> {
                        controller.setLeftStick(ev.x, ev.y).takeIf { ev.side.uppercase() == "L" }
                            ?: controller.setRightStick(ev.x, ev.y)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Replay event error: ${e.message}")
            }

            onProgress(++idx, events.size)
        }

        Log.d(TAG, "Replay finished")
        onFinished()
    }
}
