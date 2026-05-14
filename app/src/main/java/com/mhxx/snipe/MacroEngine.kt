package com.mhxx.snipe

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * MacroEngine - MHXXお守りスナイプ用マクロエンジン (sys-botbase版)
 *
 * マクロ形式 (JSON配列):
 * [
 *   {"type":"press",  "button":"A", "duration":100},
 *   {"type":"wait",   "ms":500},
 *   {"type":"multi",  "buttons":["A","B"], "duration":100},
 *   {"type":"repeat", "count":10, "actions":[...]},
 *   {"type":"stick",  "side":"L",    "x":0.0, "y":-1.0, "duration":500},
 *   {"type":"stick",  "side":"left", "x":0.0, "y":-1.0, "duration":500},
 *   {"type":"comment","text":"マカ錬金開始"}
 * ]
 *
 * stick の side は "L"/"R"/"LEFT"/"RIGHT"/"left"/"right" すべて受け付ける
 */
class MacroEngine(private val context: Context) {

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var controller: SysBotBaseController? = null
    private var activeJob: Job? = null
    private var statusCallback: ((String, String) -> Unit)? = null

    val PRESETS = mapOf(
        "makaAlchemy"  to buildMakaAlchemyMacro(),
        "miningPickup" to buildMiningPickupMacro(),
        "comboAmmo"    to buildComboAmmoMacro(),
        "saveLoad"     to buildSaveLoadMacro()
    )

    fun setController(c: SysBotBaseController?) { controller = c }
    fun setStatusCallback(cb: (String, String) -> Unit) { statusCallback = cb }

    fun executeJson(jsonStr: String) {
        val actions = parseActions(JSONArray(jsonStr))
        runMacro(actions)
    }

    fun executePreset(name: String) {
        val actions = PRESETS[name] ?: run {
            statusCallback?.invoke("error", "プリセットが見つかりません: $name")
            return
        }
        runMacro(actions)
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        // スティックをニュートラルに戻す
        try { controller?.sendCommand("setStick LEFT 0 0")  } catch (_: Exception) {}
        try { controller?.sendCommand("setStick RIGHT 0 0") } catch (_: Exception) {}
        statusCallback?.invoke("stopped", "マクロ停止")
    }

    fun stopAll() { scope.cancel() }

    // ── 内部実行 ──────────────────────────────────────────────────────────
    private fun runMacro(actions: List<MacroAction>) {
        activeJob?.cancel()
        activeJob = scope.launch {
            statusCallback?.invoke("running", "マクロ実行中...")
            try {
                executeActions(actions)
                statusCallback?.invoke("done", "マクロ完了")
            } catch (e: CancellationException) {
                statusCallback?.invoke("stopped", "マクロ中止")
            } catch (e: Exception) {
                statusCallback?.invoke("error", "エラー: ${e.message}")
            }
        }
    }

    private suspend fun executeActions(actions: List<MacroAction>) {
        for (action in actions) {
            currentCoroutineContext().ensureActive()
            when (action) {
                is MacroAction.Press -> {
                    val btn = SysBotBaseController.BUTTON_MAP[action.button.uppercase()]
                        ?: action.button.uppercase()
                    controller?.sendCommand("press $btn")
                    delay(action.duration.toLong().coerceAtLeast(50))
                    controller?.sendCommand("release $btn")
                    delay(30)
                }
                is MacroAction.Multi -> {
                    val btns = action.buttons.map {
                        SysBotBaseController.BUTTON_MAP[it.uppercase()] ?: it.uppercase()
                    }
                    btns.forEach { controller?.sendCommand("press $it") }
                    delay(action.duration.toLong().coerceAtLeast(50))
                    btns.forEach { controller?.sendCommand("release $it") }
                    delay(30)
                }
                is MacroAction.Wait -> delay(action.ms.toLong())
                is MacroAction.Repeat -> {
                    for (i in 0 until action.count) {
                        currentCoroutineContext().ensureActive()
                        executeActions(action.actions)
                    }
                }
                is MacroAction.Stick -> {
                    // x,y は -1.0〜1.0 の正規化済み (snipe.html側も同じ)
                    val lx = (action.x.coerceIn(-1f, 1f) * 32767).toInt()
                    val ly = (action.y.coerceIn(-1f, 1f) * 32767).toInt()
                    // side: "L"/"R"/"left"/"right"/"LEFT"/"RIGHT" すべて受け付け
                    val side = resolveStickSide(action.side)
                    controller?.sendCommand("setStick $side $lx $ly")
                    delay(action.duration.toLong().coerceAtLeast(50))
                    controller?.sendCommand("setStick $side 0 0")
                    delay(30)
                }
                is MacroAction.Comment -> { /* skip */ }
            }
        }
    }

    /** "L"/"R"/"left"/"right"/"LEFT"/"RIGHT" → "LEFT" or "RIGHT" */
    private fun resolveStickSide(side: String): String {
        return when (side.uppercase()) {
            "R", "RIGHT" -> "RIGHT"
            else          -> "LEFT"
        }
    }

    // ── JSON パース ────────────────────────────────────────────────────────
    private fun parseActions(arr: JSONArray): List<MacroAction> {
        val result = mutableListOf<MacroAction>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result += when (obj.getString("type")) {
                "press"   -> MacroAction.Press(
                    obj.getString("button"),
                    obj.optInt("duration", 100)
                )
                "multi"   -> MacroAction.Multi(
                    (0 until obj.getJSONArray("buttons").length()).map {
                        obj.getJSONArray("buttons").getString(it)
                    },
                    obj.optInt("duration", 100)
                )
                "wait"    -> MacroAction.Wait(obj.optInt("ms", 100))
                "repeat"  -> MacroAction.Repeat(
                    obj.getInt("count"),
                    parseActions(obj.getJSONArray("actions"))
                )
                "stick"   -> MacroAction.Stick(
                    obj.optString("side", "L"),
                    obj.optDouble("x", 0.0).toFloat(),
                    obj.optDouble("y", 0.0).toFloat(),
                    obj.optInt("duration", 500)
                )
                "comment" -> MacroAction.Comment(obj.optString("text", ""))
                else      -> MacroAction.Comment("unknown: ${obj.optString("type")}")
            }
        }
        return result
    }

    // ── プリセットビルダー ────────────────────────────────────────────────
    private fun buildMakaAlchemyMacro(): List<MacroAction> = listOf(
        MacroAction.Comment("マカ錬金お守りスナイプシーケンス"),
        MacroAction.Press("A", 100),
        MacroAction.Wait(3000),
        MacroAction.Stick("L", -1f, 0f, 800),
        MacroAction.Wait(300),
        MacroAction.Press("A", 100),
        MacroAction.Wait(1500),
        MacroAction.Press("A", 100),
        MacroAction.Wait(500),
        MacroAction.Press("A", 100),
        MacroAction.Wait(500),
        MacroAction.Press("A", 100),
        MacroAction.Wait(2000),
        MacroAction.Press("A", 100),
        MacroAction.Wait(500)
    )

    private fun buildMiningPickupMacro(): List<MacroAction> = listOf(
        MacroAction.Comment("炭鉱採掘お守り取得シーケンス"),
        MacroAction.Press("A", 100),
        MacroAction.Wait(2500),
        MacroAction.Press("A", 100),
        MacroAction.Wait(800),
        MacroAction.Press("A", 100),
        MacroAction.Wait(500)
    )

    private fun buildComboAmmoMacro(): List<MacroAction> = listOf(
        MacroAction.Comment("調合スナイプ: Lv2通常弾カウント用マクロ"),
        MacroAction.Press("X", 100),
        MacroAction.Wait(500),
        MacroAction.Press("DOWN", 80),
        MacroAction.Wait(150),
        MacroAction.Press("A", 100),
        MacroAction.Wait(300),
        MacroAction.Press("A", 100),
        MacroAction.Wait(300),
        MacroAction.Repeat(
            count = 10,
            actions = listOf(
                MacroAction.Press("A", 80),
                MacroAction.Wait(600)
            )
        )
    )

    private fun buildSaveLoadMacro(): List<MacroAction> = listOf(
        MacroAction.Comment("セーブ&ロード シーケンス"),
        MacroAction.Press("X", 100),
        MacroAction.Wait(600),
        MacroAction.Press("UP", 80),
        MacroAction.Wait(150),
        MacroAction.Press("A", 100),
        MacroAction.Wait(300),
        MacroAction.Press("A", 100),
        MacroAction.Wait(2000),
        MacroAction.Press("HOME", 100),
        MacroAction.Wait(500),
        MacroAction.Press("HOME", 100),
        MacroAction.Wait(1500)
    )
}

// ── アクション型定義 ──────────────────────────────────────────────────────
sealed class MacroAction {
    data class Press(val button: String, val duration: Int) : MacroAction()
    data class Multi(val buttons: List<String>, val duration: Int) : MacroAction()
    data class Wait(val ms: Int) : MacroAction()
    data class Repeat(val count: Int, val actions: List<MacroAction>) : MacroAction()
    data class Stick(val side: String, val x: Float, val y: Float, val duration: Int) : MacroAction()
    data class Comment(val text: String) : MacroAction()
}
