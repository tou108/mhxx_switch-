package com.mhxx.snipe.sysdvr

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "SysDVRTcp"

// SysDVR 6.x TCP bridge ports
const val SYSDVR_VIDEO_PORT = 9911
const val SYSDVR_AUDIO_PORT = 9922

// Protocol constants (from SysDVR source)
private const val HANDSHAKE_MAGIC_REQ: Int = 0xAAAAAAAA.toInt()
private const val PACKET_MAGIC_RES: Int = 0xCCCCCCCC.toInt()
private const val HANDSHAKE_OK: Int = 6
private const val HEADER_SIZE = 18          // magic(4)+dataSize(4)+ts(8)+flags(1)+slot(1)
private const val HELLO_SIZE = 10           // "SysDVR|03\0"
private const val HANDSHAKE_REQ_SIZE = 16
private const val HANDSHAKE_RES_V2_SIZE = 4
private const val HANDSHAKE_RES_V3_SIZE = 72
private const val MAX_PAYLOAD = 0x54000     // VideoPayloadSize

// Flags
const val FLAG_IS_VIDEO: Byte = 0x01
const val FLAG_IS_AUDIO: Byte = 0x02
const val FLAG_IS_MULTI_NAL: Byte = 0x10

// Known-good SPS/PPS for Switch H264 output (1280x720 baseline)
val SWITCH_SPS = byteArrayOf(
    0x00, 0x00, 0x00, 0x01,
    0x67.toByte(), 0x64, 0x0C, 0x20,
    0xAC.toByte(), 0x2B, 0x40, 0x28,
    0x02, 0xDD.toByte(), 0x35, 0x01,
    0x0D, 0x01, 0xE0.toByte(), 0x80.toByte()
)
val SWITCH_PPS = byteArrayOf(
    0x00, 0x00, 0x00, 0x01,
    0x68.toByte(), 0xEE.toByte(), 0x3C, 0xB0.toByte()
)

data class VideoPacket(
    val data: ByteArray,
    val timestampUs: Long,
    val isMultiNal: Boolean
)

class SysDVRTcpReceiver(
    private val host: String,
    private val onPacket: (VideoPacket) -> Unit,
    private val onError: (String) -> Unit,
    private val onConnected: () -> Unit
) {
    private var socket: Socket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        job?.cancel()
        job = scope.launch {
            try {
                connectAndStream()
            } catch (e: CancellationException) {
                // Normal stop
            } catch (e: Exception) {
                Log.e(TAG, "Stream error", e)
                onError(e.message ?: "Unknown error")
            } finally {
                socket?.closeQuietly()
            }
        }
    }

    fun stop() {
        job?.cancel()
        socket?.closeQuietly()
    }

    private suspend fun connectAndStream() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Connecting to $host:$SYSDVR_VIDEO_PORT")
        val s = Socket(host, SYSDVR_VIDEO_PORT).also {
            it.tcpNoDelay = true
            it.receiveBufferSize = MAX_PAYLOAD + HEADER_SIZE
            it.soTimeout = 5000
            socket = it
        }

        val ins = s.getInputStream()

        // 1. Read hello packet "SysDVR|03\0" (10 bytes)
        val hello = readExact(ins, HELLO_SIZE)
        val helloStr = String(hello, Charsets.US_ASCII)
        Log.d(TAG, "Hello: $helloStr")

        val protoVer = if (helloStr.startsWith("SysDVR|")) {
            helloStr.substring(7, 9)
        } else {
            throw Exception("Invalid hello: $helloStr")
        }
        Log.d(TAG, "Protocol version: $protoVer")

        // 2. Send handshake request (16 bytes)
        val reqBuf = buildHandshakeRequest(protoVer)
        s.getOutputStream().write(reqBuf)
        s.getOutputStream().flush()

        // 3. Read handshake response
        val resSize = if (protoVer == "03") HANDSHAKE_RES_V3_SIZE else HANDSHAKE_RES_V2_SIZE
        val res = readExact(ins, resSize)
        val resCode = ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN).int
        if (resCode != HANDSHAKE_OK) {
            throw Exception("Handshake rejected: code=$resCode")
        }

        s.soTimeout = 3000
        Log.d(TAG, "Handshake OK, starting stream")
        onConnected()

        // 4. Stream packets
        val headerBuf = ByteArray(HEADER_SIZE)
        var inSync = true

        while (isActive) {
            if (!inSync) {
                // Re-sync: scan for 0xCC 0xCC 0xCC 0xCC
                inSync = resyncStream(ins)
                if (!inSync) continue
                // Read remaining header bytes (4 already consumed by resync)
                val rest = readExact(ins, HEADER_SIZE - 4)
                ByteBuffer.wrap(headerBuf).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    putInt(PACKET_MAGIC_RES)
                }
                System.arraycopy(rest, 0, headerBuf, 4, rest.size)
            } else {
                readExact(ins, HEADER_SIZE, headerBuf)
            }

            val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            val magic = bb.int
            val dataSize = bb.int
            val timestampNs = bb.long
            val flags = bb.get()
            // val replaySlot = bb.get()

            if (magic != PACKET_MAGIC_RES) {
                Log.w(TAG, "Bad magic 0x${magic.toUInt().toString(16)}, resyncing")
                inSync = false
                continue
            }

            if (dataSize <= 0 || dataSize > MAX_PAYLOAD) {
                Log.w(TAG, "Bad dataSize=$dataSize, resyncing")
                inSync = false
                continue
            }

            if ((flags and FLAG_IS_VIDEO) == 0.toByte()) {
                // Audio or other - skip
                skipExact(ins, dataSize)
                continue
            }

            val payload = readExact(ins, dataSize)
            val tsUs = timestampNs / 1000L

            onPacket(VideoPacket(payload, tsUs, (flags and FLAG_IS_MULTI_NAL) != 0.toByte()))
        }
    }

    private fun buildHandshakeRequest(version: String): ByteArray {
        // Version encoding: ushort = code[0] | (code[1] << 8)
        val v0 = version[0].code.toByte()
        val v1 = version[1].code.toByte()

        return ByteArray(HANDSHAKE_REQ_SIZE).also { b ->
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(HANDSHAKE_MAGIC_REQ)       // Magic (4)
            bb.put(v0)                            // Version low byte
            bb.put(v1)                            // Version high byte
            bb.put(0x01)                          // MetaFlags: IsVideoPacket bit0
            bb.put(0x02)                          // VideoFlags: InjectPPSSPS bit1
            bb.put(0)                             // AudioBatching
            bb.put(0)                             // FeatureFlags
            // Reserved[6] already zero
        }
    }

    private fun resyncStream(ins: InputStream): Boolean {
        var count = 0
        val magicByte = (PACKET_MAGIC_RES and 0xFF).toByte()
        val oneByte = ByteArray(1)
        while (true) {
            val read = ins.read(oneByte)
            if (read <= 0) return false
            if (oneByte[0] == magicByte) {
                if (++count == 4) return true
            } else {
                count = 0
            }
        }
    }

    private fun readExact(ins: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        readExact(ins, length, buf)
        return buf
    }

    private fun readExact(ins: InputStream, length: Int, buf: ByteArray, offset: Int = 0) {
        var remaining = length
        var off = offset
        while (remaining > 0) {
            val r = ins.read(buf, off, remaining)
            if (r <= 0) throw Exception("Stream ended unexpectedly")
            remaining -= r
            off += r
        }
    }

    private fun skipExact(ins: InputStream, length: Int) {
        var remaining = length.toLong()
        while (remaining > 0) {
            val skipped = ins.skip(remaining)
            if (skipped <= 0) {
                // fallback: read into discard buffer
                val buf = ByteArray(minOf(remaining, 4096).toInt())
                val r = ins.read(buf)
                if (r <= 0) throw Exception("Stream ended unexpectedly")
                remaining -= r
            } else {
                remaining -= skipped
            }
        }
    }

    private fun Socket.closeQuietly() {
        try { close() } catch (_: Exception) {}
    }
}
