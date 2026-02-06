package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.windows.WindowTitleDetector
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

class CaptureDispatcher(
    private val channel: Channel<CapturedPayload>,
    dataStorage: DataStorage
) {
    private val logger = LoggerFactory.getLogger(CaptureDispatcher::class.java)

    private val sharedDataStorage = dataStorage
    private var lastWindowCheckMs = 0L
    private var isAionRunning = false

    // One assembler per (portA, portB) pair so streams don't mix
    private val assemblers = mutableMapOf<Pair<Int, Int>, StreamAssembler>()

    // raw magic detector for "lock" logging (but we do NOT filter yet)
    private val MAGIC = byteArrayOf(0x06.toByte(), 0x00.toByte(), 0x36.toByte())
    private val TLS_CONTENT_TYPES = setOf(0x14, 0x15, 0x16, 0x17)
    private val TLS_VERSIONS = setOf(0x00, 0x01, 0x02, 0x03, 0x04)

    suspend fun run() {
        for (cap in channel) {
            if (!ensureAionRunning()) {
                continue
            }
            val a = minOf(cap.srcPort, cap.dstPort)
            val b = maxOf(cap.srcPort, cap.dstPort)
            val key = a to b

            val assembler = assemblers.getOrPut(key) {
                StreamAssembler(StreamProcessor(sharedDataStorage))
            }

            // "Lock" is informational for now; don't filter until parsing confirmed stable
            if (CombatPortDetector.currentPort() == null && isUnencryptedCandidate(cap.data)) {
                // Choose srcPort for now (since magic typically comes from the sender)
                CombatPortDetector.registerCandidate(cap.srcPort, cap.deviceName)
                logger.info(
                    "Magic seen on flow {}-{} (src={}, dst={}, device={})",
                    a,
                    b,
                    cap.srcPort,
                    cap.dstPort,
                    cap.deviceName
                )
            }

            val parsed = assembler.processChunk(cap.data)
            if (parsed && CombatPortDetector.currentPort() == null) {
                CombatPortDetector.confirmCandidate(cap.srcPort, cap.dstPort, cap.deviceName)
            }
        }
    }

    private fun ensureAionRunning(): Boolean {
        val now = System.currentTimeMillis()
        val intervalMs = if (isAionRunning) WINDOW_CHECK_RUNNING_INTERVAL_MS else WINDOW_CHECK_STOPPED_INTERVAL_MS
        if (now - lastWindowCheckMs >= intervalMs) {
            lastWindowCheckMs = now
            val running = WindowTitleDetector.findAion2WindowTitle() != null
            if (!running && isAionRunning) {
                CombatPortDetector.reset()
                assemblers.clear()
            }
            isAionRunning = running
        }
        return isAionRunning
    }

    private fun isUnencryptedCandidate(data: ByteArray): Boolean {
        return !looksLikeTlsPayload(data) && contains(data, MAGIC)
    }

    private fun looksLikeTlsPayload(data: ByteArray): Boolean {
        if (data.size < 3) return false
        val contentType = data[0].toInt() and 0xff
        val major = data[1].toInt() and 0xff
        val minor = data[2].toInt() and 0xff
        return contentType in TLS_CONTENT_TYPES && major == 0x03 && minor in TLS_VERSIONS
    }

    private fun contains(data: ByteArray, needle: ByteArray): Boolean {
        if (data.size < needle.size) return false
        for (i in 0..data.size - needle.size) {
            var ok = true
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }

    fun getParsingBacklog(): Int {
        synchronized(assemblers) {
            return assemblers.values.sumOf { it.bufferedBytes() }
        }
    }

    companion object {
        private const val WINDOW_CHECK_STOPPED_INTERVAL_MS = 10_000L
        private const val WINDOW_CHECK_RUNNING_INTERVAL_MS = 60_000L
    }
}
