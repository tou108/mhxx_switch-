package com.mhxx.snipe.sysdvr

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

private const val TAG = "SysDVRDecoder"

/**
 * SysDVR H264 ハードウェアデコーダー
 *
 * - SPS/PPS をコーデックに注入して初期化
 * - VideoPacket を受け取ってデコードし Surface に描画
 * - 録画用に生 H264 データをコールバックで渡す
 */
class SysDVRDecoder(
    private val surface: Surface,
    private val onDecodedFrame: ((ByteArray, Long, Boolean) -> Unit)? = null  // for recorder
) {
    private var codec: MediaCodec? = null
    private var isStarted = false
    private var spsInjected = false

    // Running stats
    private var frameCount = 0L
    private var lastFpsTime = System.nanoTime()
    var currentFps = 0f
        private set

    fun start() {
        if (isStarted) return
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                1280, 720
            ).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(SWITCH_SPS))
                setByteBuffer("csd-1", ByteBuffer.wrap(SWITCH_PPS))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0x54000 + 64)
                setFloat(MediaFormat.KEY_FRAME_RATE, 120f)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { c ->
                c.configure(format, surface, null, 0)
                c.start()
            }
            isStarted = true
            spsInjected = true
            Log.d(TAG, "Decoder started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start decoder", e)
            throw e
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        Log.d(TAG, "Decoder stopped")
    }

    fun decodePacket(packet: VideoPacket) {
        val c = codec ?: return
        if (!isStarted) return

        try {
            // If InjectPPSSPS wasn't honored, inject manually
            if (!spsInjected) {
                feedSpsAndPps(c)
                spsInjected = true
            }

            feedData(c, packet)
            drainOutput(c)
            updateFps()

            // Forward raw data to recorder if active
            onDecodedFrame?.invoke(packet.data, packet.timestampUs, packet.isMultiNal)

        } catch (e: Exception) {
            Log.e(TAG, "Decode error", e)
        }
    }

    private fun feedSpsAndPps(c: MediaCodec) {
        val combined = SWITCH_SPS + SWITCH_PPS
        queueInput(c, combined, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
    }

    private fun feedData(c: MediaCodec, packet: VideoPacket) {
        val data = packet.data
        queueInput(c, data, packet.timestampUs, 0)
    }

    private fun queueInput(c: MediaCodec, data: ByteArray, tsUs: Long, flags: Int) {
        val inIdx = c.dequeueInputBuffer(5000L)
        if (inIdx < 0) return

        val inBuf: ByteBuffer = c.getInputBuffer(inIdx) ?: return
        inBuf.clear()
        val toCopy = minOf(data.size, inBuf.remaining())
        inBuf.put(data, 0, toCopy)
        c.queueInputBuffer(inIdx, 0, toCopy, tsUs, flags)
    }

    private fun drainOutput(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0L)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIdx >= 0 -> {
                    // render=true → push to Surface immediately
                    c.releaseOutputBuffer(outIdx, true)
                    frameCount++
                }
                else -> break
            }
        }
    }

    private fun updateFps() {
        val now = System.nanoTime()
        val elapsed = now - lastFpsTime
        if (elapsed >= 1_000_000_000L) {
            currentFps = frameCount * 1_000_000_000f / elapsed
            frameCount = 0
            lastFpsTime = now
        }
    }
}
