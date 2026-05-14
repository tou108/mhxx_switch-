package com.mhxx.snipe.sysdvr

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "SysDVRRecorder"

/**
 * SysDVR 録画モジュール
 *
 * H264 パケットをそのまま MP4 ファイルにマルチプレックスする。
 * デコーダーからの raw H264 ペイロードを受け取るだけでよい。
 */
class SysDVRRecorder(private val outputFile: File) {
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var isStarted = false
    private var firstTimestampUs = -1L

    // MediaFormat for H264 1280x720
    private val videoFormat = MediaFormat.createVideoFormat(
        MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720
    ).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(SWITCH_SPS))
        setByteBuffer("csd-1", ByteBuffer.wrap(SWITCH_PPS))
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0x54000)
        setFloat(MediaFormat.KEY_FRAME_RATE, 30f)
    }

    fun start() {
        if (isStarted) return
        try {
            outputFile.parentFile?.mkdirs()
            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            ).also { m ->
                videoTrack = m.addTrack(videoFormat)
                m.start()
            }
            isStarted = true
            firstTimestampUs = -1L
            Log.d(TAG, "Recording started: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start muxer", e)
            throw e
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        try {
            muxer?.stop()
            muxer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
        muxer = null
        Log.d(TAG, "Recording stopped")
    }

    fun writePacket(data: ByteArray, timestampUs: Long, isMultiNal: Boolean) {
        if (!isStarted) return
        val m = muxer ?: return

        // BUG FIX②: Switch から来るタイムスタンプが 0 や同一値の場合、
        // MediaMuxer が duration を設定できず0秒動画になる。
        // システムクロック(nanoTime)をベースにしたタイムスタンプにフォールバックする。
        val nowUs = System.nanoTime() / 1000L
        val effectiveTs = if (timestampUs > 0L) timestampUs else nowUs

        // Normalize timestamps to start from 0
        if (firstTimestampUs < 0) firstTimestampUs = effectiveTs
        val relativeUs = (effectiveTs - firstTimestampUs).coerceAtLeast(0)

        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            presentationTimeUs = relativeUs
            // Detect keyframe: H264 IDR NAL type = 0x65 (after start code 00 00 00 01)
            flags = if (isKeyFrame(data)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }

        try {
            m.writeSampleData(videoTrack, ByteBuffer.wrap(data), info)
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData error", e)
        }
    }

    private fun isKeyFrame(data: ByteArray): Boolean {
        // Scan for NAL start codes and check for IDR slice (type 5)
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte()) {
                val startAt = when {
                    data[i + 2] == 0x01.toByte() -> i + 3
                    data[i + 2] == 0x00.toByte() && data[i + 3] == 0x01.toByte() -> i + 4
                    else -> { i++; continue }
                }
                if (startAt < data.size) {
                    val nalType = (data[startAt].toInt() and 0x1F)
                    if (nalType == 5) return true  // IDR
                }
            }
            i++
        }
        return false
    }

    val isRecording get() = isStarted
    val outputPath: String get() = outputFile.absolutePath
}
