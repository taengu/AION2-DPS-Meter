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
        val data: ByteArray
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
                val decodedPayloads = decodePayloads(cap)
                for (decoded in decodedPayloads) {
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

    private fun decodePayloads(cap: CapturedPayload): List<DecodedPayload> {
        val encapsulatedPackets = parseEncapsulatedTcp(cap.data)
        if (encapsulatedPackets.isNotEmpty()) {
            return encapsulatedPackets.map { packet ->
                DecodedPayload(
                    srcPort = packet.srcPort,
                    dstPort = packet.dstPort,
                    data = packet.payload
                )
            }
        }
        return listOf(
            DecodedPayload(
                srcPort = cap.srcPort,
                dstPort = cap.dstPort,
                data = cap.data
            )
        )
    }

    private data class EncapsulatedTcp(
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    private fun parseEncapsulatedTcp(data: ByteArray): List<EncapsulatedTcp> {
        if (data.size < 20) return emptyList()
        val results = mutableListOf<EncapsulatedTcp>()
        var offset = 0
        while (offset <= data.size - 20) {
            val startOffset = findIpv4HeaderOffset(data, offset) ?: break
            val versionIhl = data[startOffset].toInt() and 0xff
            val ipHeaderLength = (versionIhl and 0x0f) * 4
            if (startOffset + ipHeaderLength > data.size) {
                offset = startOffset + 1
                continue
            }
            val totalLength =
                ((data[startOffset + 2].toInt() and 0xff) shl 8) or (data[startOffset + 3].toInt() and 0xff)
            val packetEnd = startOffset + totalLength
            if (totalLength <= ipHeaderLength || packetEnd > data.size) {
                offset = startOffset + 1
                continue
            }
            val protocol = data[startOffset + 9].toInt() and 0xff
            if (protocol != 0x06) {
                offset = packetEnd
                continue
            }

            val tcpOffset = startOffset + ipHeaderLength
            if (tcpOffset + 20 > data.size) {
                offset = startOffset + 1
                continue
            }
            val tcpHeaderLength = ((data[tcpOffset + 12].toInt() and 0xf0) shr 4) * 4
            val payloadStart = tcpOffset + tcpHeaderLength
            val payloadEnd = minOf(packetEnd, data.size)
            if (payloadStart >= payloadEnd || payloadStart > data.size) {
                offset = packetEnd
                continue
            }
            val srcPort = ((data[tcpOffset].toInt() and 0xff) shl 8) or (data[tcpOffset + 1].toInt() and 0xff)
            val dstPort = ((data[tcpOffset + 2].toInt() and 0xff) shl 8) or (data[tcpOffset + 3].toInt() and 0xff)
            val payload = data.copyOfRange(payloadStart, payloadEnd)
            if (payload.isNotEmpty()) {
                results.add(EncapsulatedTcp(srcPort, dstPort, payload))
            }
            offset = packetEnd
        }
        return results
    }

    private fun findIpv4HeaderOffset(data: ByteArray, startIndex: Int): Int? {
        val maxIndex = minOf(data.size - 20, startIndex + 64)
        for (idx in startIndex..maxIndex) {
            if (data[idx] != 0x45.toByte()) continue
            if (idx + 20 > data.size) continue
            val versionIhl = data[idx].toInt() and 0xff
            if (versionIhl shr 4 != 4) continue
            val ihl = (versionIhl and 0x0f) * 4
            if (ihl < 20 || ihl > 60) continue
            val totalLength =
                ((data[idx + 2].toInt() and 0xff) shl 8) or (data[idx + 3].toInt() and 0xff)
            if (totalLength <= ihl || idx + totalLength > data.size) continue
            return idx
        }
        return null
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
