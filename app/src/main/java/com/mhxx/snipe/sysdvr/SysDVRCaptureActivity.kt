package com.mhxx.snipe.sysdvr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.view.WindowInsetsController
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mhxx.snipe.R
import com.mhxx.snipe.SysBotBaseController
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * SysDVR 画面キャプチャ Activity
 *
 * 機能:
 *  ① SysDVR TCP 接続 → Switch の画面をリアルタイム表示 (停止ボタンまで無限)
 *  ② 120fps 表示モード要求
 *  ③ 録画 (H264 → MP4, /Movies/SysDVR/ に保存)
 *  ④ 操作録画 (録画中のボタン/スティック操作をタイムスタンプ付きで記録)
 *  ⑤ フィードバックループ再生 (録画した操作を同じタイミングで再実行)
 *  ⑥ 動画インポート (ストレージから .mp4 を選択 → 対応 .json 操作データを読み込む)
 */
class SysDVRCaptureActivity : AppCompatActivity(), SurfaceHolder.Callback {

    // UI
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvRecTime: TextView
    private lateinit var tvRecFile: TextView
    private lateinit var tvFileInfo: TextView
    private lateinit var tvReplayProgress: TextView
    private lateinit var recordingIndicator: View
    private lateinit var replayIndicator: View
    private lateinit var btnStop: Button
    private lateinit var btnRecord: Button
    private lateinit var btnImport: Button
    private lateinit var btnReplay: Button
    private lateinit var btnFullscreen: Button

    // Core
    private var decoder: SysDVRDecoder? = null
    private var tcpReceiver: SysDVRTcpReceiver? = null
    private var recorder: SysDVRRecorder? = null
    private var macroSession = MacroSession()
    private var controller: SysBotBaseController? = null

    // State
    private var isCapturing = false
    private var isRecordingActive = false
    private var isFullscreen = false
    private var recStartTime = 0L
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Replay coroutine
    private var replayJob: Job? = null

    // Handler for periodic UI updates
    private val uiHandler = Handler(Looper.getMainLooper())
    private val fpsUpdater = object : Runnable {
        override fun run() {
            if (isCapturing) {
                decoder?.let { tvFps.text = "%.1f fps".format(it.currentFps) }
                if (isRecordingActive) updateRecordingTimer()
                uiHandler.postDelayed(this, 500)
            }
        }
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DVR_PORT = "dvrPort"

        /** MainActivity から呼ぶ起動ヘルパー */
        fun launch(activity: Activity, host: String, sysBotPort: Int = 6000) {
            activity.startActivity(
                Intent(activity, SysDVRCaptureActivity::class.java).apply {
                    putExtra(EXTRA_HOST, host)
                    putExtra(EXTRA_PORT, sysBotPort)
                    putExtra(EXTRA_DVR_PORT, SYSDVR_VIDEO_PORT)
                }
            )
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sysdvr)

        bindViews()
        request120fps()
        setupButtons()

        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val sysBotPort = intent.getIntExtra(EXTRA_PORT, 6000)

        // Optional: attach to existing SysBot controller for replay
        // controller will be set after handshake via companion object pattern
        // For now, create a new one using the same host
        if (host.isNotBlank()) {
            controller = SysBotBaseController(host, sysBotPort,
                object : SysBotBaseController.Listener {
                    override fun onConnected() {}
                    override fun onDisconnected() {}
                    override fun onError(msg: String) {}
                }
            )
        }

        surfaceView.holder.addCallback(this)
        tvStatus.text = "SurfaceView 準備中..."
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        scope.cancel()
    }

    override fun onBackPressed() {
        if (isRecordingActive) {
            AlertDialog.Builder(this)
                .setTitle("録画中")
                .setMessage("録画を停止して終了しますか？")
                .setPositiveButton("停止して終了") { _, _ ->
                    stopRecording()
                    finish()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            stopCapture()
            super.onBackPressed()
        }
    }

    // ── SurfaceHolder.Callback ──────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return
        if (host.isBlank()) {
            tvStatus.text = "エラー: Switch IP 未設定"
            return
        }
        startCapture(holder.surface, host)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopCapture()
    }

    // ── Capture Control ──────────────────────────────────────────────────

    private fun startCapture(surface: android.view.Surface, host: String) {
        if (isCapturing) return
        isCapturing = true
        tvStatus.text = "接続中..."

        decoder = SysDVRDecoder(surface) { data, ts, multi ->
            // Forward raw H264 to recorder if active
            recorder?.writePacket(data, ts, multi)
        }
        decoder!!.start()

        tcpReceiver = SysDVRTcpReceiver(
            host = host,
            onPacket = { packet ->
                decoder?.decodePacket(packet)
            },
            onConnected = {
                runOnUiThread {
                    tvStatus.text = "配信中 ✓"
                    tvStatus.setTextColor(0xFF28a745.toInt())
                    btnRecord.isEnabled = true
                    btnStop.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(0xFFdc3545.toInt())
                }
            },
            onError = { msg ->
                runOnUiThread {
                    tvStatus.text = "エラー: $msg"
                    tvStatus.setTextColor(0xFFdc3545.toInt())
                }
            }
        )
        tcpReceiver!!.start()
        uiHandler.post(fpsUpdater)
    }

    private fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false

        if (isRecordingActive) stopRecording()

        uiHandler.removeCallbacks(fpsUpdater)
        tcpReceiver?.stop()
        tcpReceiver = null
        decoder?.stop()
        decoder = null
    }

    // ── Recording ────────────────────────────────────────────────────────

    private fun startRecording() {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "SysDVR"
        )
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val mp4 = File(dir, "capture_$ts.mp4")
        val jsonFile = File(dir, "capture_$ts.json")

        try {
            recorder = SysDVRRecorder(mp4).also { it.start() }
        } catch (e: Exception) {
            Toast.makeText(this, "録画開始エラー: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        macroSession.startRecording()
        isRecordingActive = true
        recStartTime = System.currentTimeMillis()

        btnRecord.text = "⏹ 停止"
        btnRecord.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFdc3545.toInt())
        recordingIndicator.visibility = View.VISIBLE
        tvRecFile.text = mp4.name
        tvFileInfo.text = "保存先: ${mp4.absolutePath}"

        // Save macro session alongside video when recording stops
        // Store jsonFile reference
        btnRecord.tag = jsonFile
    }

    private fun stopRecording() {
        if (!isRecordingActive) return
        isRecordingActive = false

        macroSession.stopRecording()
        recorder?.stop()
        recorder = null

        // Save operation JSON
        val jsonFile = btnRecord.tag as? File
        if (jsonFile != null && macroSession.hasEvents()) {
            try {
                macroSession.saveToFile(jsonFile)
                tvFileInfo.text = "保存: ${jsonFile.name} (${macroSession.eventCount()} 操作)"
            } catch (e: Exception) {
                tvFileInfo.text = "JSON 保存エラー: ${e.message}"
            }
        }

        btnRecord.text = "⏺ 録画"
        btnRecord.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF252830.toInt())
        recordingIndicator.visibility = View.GONE

        // Enable replay if we have events
        if (macroSession.hasEvents()) {
            btnReplay.isEnabled = true
            btnReplay.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF20c997.toInt())
        }

        // Notify media scanner
        recorder?.let {
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                Uri.fromFile(File(it.outputPath))))
        }
    }

    private fun updateRecordingTimer() {
        if (!isRecordingActive) return
        val elapsed = (System.currentTimeMillis() - recStartTime) / 1000
        val mm = elapsed / 60
        val ss = elapsed % 60
        tvRecTime.text = "● REC  %02d:%02d".format(mm, ss)
    }

    // ── Replay (Feedback Loop) ────────────────────────────────────────────

    private fun startReplay() {
        val ctrl = controller ?: run {
            Toast.makeText(this, "Switchに接続してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (!macroSession.hasEvents()) {
            Toast.makeText(this, "再生データがありません", Toast.LENGTH_SHORT).show()
            return
        }

        replayJob?.cancel()
        replayJob = scope.launch {
            replayIndicator.visibility = View.VISIBLE
            btnReplay.isEnabled = false
            btnReplay.text = "再生中..."

            try {
                ctrl.connect()
                delay(500)
                macroSession.replay(
                    controller = ctrl,
                    onProgress = { done, total ->
                        runOnUiThread {
                            tvReplayProgress.text = "$done / $total"
                        }
                    },
                    onFinished = {
                        runOnUiThread {
                            replayIndicator.visibility = View.GONE
                            btnReplay.isEnabled = true
                            btnReplay.text = "▶ 再生"
                            Toast.makeText(this@SysDVRCaptureActivity,
                                "リプレイ完了 (${macroSession.eventCount()} 操作)",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } catch (e: CancellationException) {
                // OK
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SysDVRCaptureActivity,
                        "リプレイエラー: ${e.message}", Toast.LENGTH_LONG).show()
                    replayIndicator.visibility = View.GONE
                    btnReplay.isEnabled = true
                    btnReplay.text = "▶ 再生"
                }
            }
        }
    }

    // ── File Import ──────────────────────────────────────────────────────

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importVideoOperations(uri)
    }

    private fun importVideoOperations(uri: Uri) {
        // Try to find companion .json with same base name
        val displayName = getDisplayName(uri)
        if (displayName == null) {
            Toast.makeText(this, "ファイル名を取得できませんでした", Toast.LENGTH_SHORT).show()
            return
        }

        val baseName = displayName.substringBeforeLast(".")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "SysDVR"
        )
        val jsonFile = File(dir, "$baseName.json")

        if (!jsonFile.exists()) {
            // Also check same directory as the video
            val uriPath = uri.path
            if (uriPath != null) {
                val sameDir = File(uriPath).parentFile
                val altJson = sameDir?.let { File(it, "$baseName.json") }
                if (altJson?.exists() == true) {
                    loadJsonFile(altJson)
                    return
                }
            }
            Toast.makeText(
                this,
                "操作データ ($baseName.json) が見つかりません\n/Movies/SysDVR/ に配置してください",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        loadJsonFile(jsonFile)
    }

    private fun loadJsonFile(jsonFile: File) {
        try {
            macroSession.loadFromFile(jsonFile)
            btnReplay.isEnabled = true
            btnReplay.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF20c997.toInt())
            tvFileInfo.text = "読み込み完了: ${jsonFile.name} (${macroSession.eventCount()} 操作)"
            Toast.makeText(this,
                "操作データ読み込み完了 (${macroSession.eventCount()} 操作)",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "読み込みエラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return uri.lastPathSegment
    }

    // ── UI helpers ───────────────────────────────────────────────────────

    private fun bindViews() {
        surfaceView     = findViewById(R.id.surfaceView)
        tvStatus        = findViewById(R.id.tvStatus)
        tvFps           = findViewById(R.id.tvFps)
        tvRecTime       = findViewById(R.id.tvRecTime)
        tvRecFile       = findViewById(R.id.tvRecFile)
        tvFileInfo      = findViewById(R.id.tvFileInfo)
        tvReplayProgress= findViewById(R.id.tvReplayProgress)
        recordingIndicator = findViewById(R.id.recordingIndicator)
        replayIndicator = findViewById(R.id.replayIndicator)
        btnStop         = findViewById(R.id.btnStop)
        btnRecord       = findViewById(R.id.btnRecord)
        btnImport       = findViewById(R.id.btnImport)
        btnReplay       = findViewById(R.id.btnReplay)
        btnFullscreen   = findViewById(R.id.btnFullscreen)
        btnRecord.isEnabled = false
    }

    private fun setupButtons() {
        btnStop.setOnClickListener {
            stopCapture()
            finish()
        }

        btnRecord.setOnClickListener {
            if (isRecordingActive) stopRecording() else startRecording()
        }

        btnImport.setOnClickListener {
            pickVideo.launch("video/mp4")
        }

        btnReplay.setOnClickListener {
            if (replayJob?.isActive == true) {
                replayJob?.cancel()
                replayIndicator.visibility = View.GONE
                btnReplay.isEnabled = true
                btnReplay.text = "▶ 再生"
            } else {
                startReplay()
            }
        }

        btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }
    }

    private fun request120fps() {
        // Request 120Hz display mode if supported (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.also { attrs ->
                attrs.preferredRefreshRate = 120f
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val topBar = findViewById<View>(R.id.topBar)

        if (isFullscreen) {
            bottomBar.visibility = View.GONE
            topBar.visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.apply {
                    hide(android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars())
                    systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        } else {
            bottomBar.visibility = View.VISIBLE
            topBar.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // ── Public API for WebAppInterface ───────────────────────────────────

    /** 録画中かつ MacroSession が録画中の時に JS ブリッジからの操作イベントを転送 */
    fun notifyButtonPress(button: String, durationMs: Int) {
        if (isRecordingActive) macroSession.recordButton(button, durationMs)
    }

    fun notifyStickMove(side: String, x: Float, y: Float) {
        if (isRecordingActive) macroSession.recordStick(side, x, y)
    }
}
