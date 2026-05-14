package com.mhxx.snipe.sysdvr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

private const val TAG = "SysDVRAudioPlayer"

// SysDVR audio format: PCM 16-bit stereo 48000Hz little-endian
private const val SAMPLE_RATE  = 48000
private const val CHANNELS     = AudioFormat.CHANNEL_OUT_STEREO
private const val ENCODING     = AudioFormat.ENCODING_PCM_16BIT

/**
 * SysDVR 音声プレイヤー
 *
 * SysDVR が送ってくる生 PCM (16bit / ステレオ / 48kHz) を
 * AudioTrack でリアルタイム再生する。
 */
class SysDVRAudioPlayer {

    private var audioTrack: AudioTrack? = null
    @Volatile private var isStarted = false

    fun start() {
        if (isStarted) return

        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        // 音声パケット最大サイズ 0xFF0 バイト × 4 パケット分を確保
        val bufSize = maxOf(minBuf, 0xFF0 * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNELS)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isStarted = true
        Log.d(TAG, "AudioPlayer started  sampleRate=$SAMPLE_RATE  buf=$bufSize")
    }

    /** 受信した PCM バイト列をそのまま AudioTrack に書き込む */
    fun write(data: ByteArray, size: Int = data.size) {
        if (!isStarted) return
        audioTrack?.write(data, 0, size)
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        Log.d(TAG, "AudioPlayer stopped")
    }
}
