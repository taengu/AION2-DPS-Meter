package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.logging.CrashLogWriter
import com.tbread.windows.WindowTitleDetector
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

class CaptureDispatcher(
    private val channel: Channel<CapturedPayload>,
    dataStorage: DataStorage
) {
    private data class DecodedPayload(
        val srcPort: Int,
        val dstPort: Int,
        val data: ByteArray,
        val encapsulated: Boolean
    )

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
            try {
                if (!ensureAionRunning()) {
                    continue
                }
                val lockedDevice = CombatPortDetector.currentDevice()
                if (lockedDevice != null && !deviceMatches(lockedDevice, cap.deviceName)) {
                    continue
                }
                val decoded = decodePayload(cap)
                val a = minOf(decoded.srcPort, decoded.dstPort)
                val b = maxOf(decoded.srcPort, decoded.dstPort)
                val key = a to b

                val assembler = assemblers.getOrPut(key) {
                    StreamAssembler(StreamProcessor(sharedDataStorage))
                }

                // "Lock" is informational for now; don't filter until parsing confirmed stable
                if (CombatPortDetector.currentPort() == null && isUnencryptedCandidate(decoded.data)) {
                    // Choose srcPort for now (since magic typically comes from the sender)
                    CombatPortDetector.registerCandidate(decoded.srcPort, key, cap.deviceName)
                    logger.info(
                        "Magic seen on flow {}-{} (src={}, dst={}, device={})",
                        a,
                        b,
                        decoded.srcPort,
                        decoded.dstPort,
                        cap.deviceName
                    )
                }

                if (looksLikeTlsPayload(decoded.data)) {
                    continue
                }
                val parsed = assembler.processChunk(decoded.data)
                if (parsed && CombatPortDetector.currentPort() == null) {
                    CombatPortDetector.confirmCandidate(decoded.srcPort, decoded.dstPort, cap.deviceName)
                }
                if (parsed) {
                    CombatPortDetector.markPacketParsed()
                }
            } catch (e: Exception) {
                CrashLogWriter.log(
                    "Parser stopped while processing ${cap.deviceName} ${cap.srcPort}-${cap.dstPort}",
                    e
                )
                throw e
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

    private fun deviceMatches(lockedDevice: String, packetDevice: String?): Boolean {
        val trimmedPacket = packetDevice?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return trimmedPacket.equals(lockedDevice, ignoreCase = true)
    }

    private fun decodePayload(cap: CapturedPayload): DecodedPayload {
        val encapsulated = parseEncapsulatedTcp(cap.data)
        if (encapsulated != null) {
            return DecodedPayload(
                srcPort = encapsulated.srcPort,
                dstPort = encapsulated.dstPort,
                data = encapsulated.payload,
                encapsulated = true
            )
        }
        return DecodedPayload(
            srcPort = cap.srcPort,
            dstPort = cap.dstPort,
            data = cap.data,
            encapsulated = false
        )
    }

    private data class EncapsulatedTcp(
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    private fun parseEncapsulatedTcp(data: ByteArray): EncapsulatedTcp? {
        if (data.size < 20) return null
        val startOffset = when {
            data.size >= 24 &&
                data[0] == 0x02.toByte() &&
                data[1] == 0x00.toByte() &&
                data[2] == 0x00.toByte() &&
                data[3] == 0x00.toByte() &&
                data[4] == 0x45.toByte() -> 4
            data[0] == 0x45.toByte() -> 0
            else -> return null
        }
        if (startOffset + 20 > data.size) return null
        val versionIhl = data[startOffset].toInt() and 0xff
        if (versionIhl shr 4 != 4) return null
        val ipHeaderLength = (versionIhl and 0x0f) * 4
        if (startOffset + ipHeaderLength > data.size) return null
        val totalLength =
            ((data[startOffset + 2].toInt() and 0xff) shl 8) or (data[startOffset + 3].toInt() and 0xff)
        if (totalLength <= ipHeaderLength) return null
        val protocol = data[startOffset + 9].toInt() and 0xff
        if (protocol != 0x06) return null

        val tcpOffset = startOffset + ipHeaderLength
        if (tcpOffset + 20 > data.size) return null
        val srcPort = ((data[tcpOffset].toInt() and 0xff) shl 8) or (data[tcpOffset + 1].toInt() and 0xff)
        val dstPort = ((data[tcpOffset + 2].toInt() and 0xff) shl 8) or (data[tcpOffset + 3].toInt() and 0xff)
        val tcpHeaderLength = ((data[tcpOffset + 12].toInt() and 0xf0) shr 4) * 4
        val payloadStart = tcpOffset + tcpHeaderLength
        val payloadEnd = minOf(startOffset + totalLength, data.size)
        if (payloadStart >= payloadEnd || payloadStart > data.size) return null
        val payload = data.copyOfRange(payloadStart, payloadEnd)
        if (payload.isEmpty()) return null
        return EncapsulatedTcp(srcPort, dstPort, payload)
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
