package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.logging.UnifiedLogger
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
    private var skipLogWindowStartMs = 0L
    private var skipLogCountInWindow = 0

    suspend fun run() {
        for (cap in channel) {
            try {
                if (!ensureAionRunning()) {
                    logUnlockedPacketSkip(cap, "AION window not detected")
                    continue
                }
                val lockedDevice = CombatPortDetector.currentDevice()
                if (lockedDevice != null && !deviceMatches(lockedDevice, cap.deviceName)) {
                    continue
                }
                val a = minOf(cap.srcPort, cap.dstPort)
                val b = maxOf(cap.srcPort, cap.dstPort)
                val key = a to b

                val assembler = assemblers.getOrPut(key) {
                    StreamAssembler(StreamProcessor(sharedDataStorage))
                }

                val payload = extractGamePayload(cap.data)
                if (payload.isEmpty()) {
                    logUnlockedPacketSkip(cap, "Empty payload after loopback/IP normalization")
                    continue
                }

                val unlocked = CombatPortDetector.currentPort() == null
                val tlsPayload = looksLikeTlsPayload(payload)

                if (unlocked && tlsPayload) {
                    logUnlockedPacketSkip(cap, "TLS payload ignored while waiting for combat lock")
                    continue
                }

                val hasCombatMagic = if (tlsPayload) false else contains(payload, MAGIC)
                if (unlocked && !hasCombatMagic) {
                    val reason = when {
                        payload.isEmpty() -> "Empty payload while waiting for combat signature"
                        payload.size < MAGIC.size -> "Payload too short for combat signature while waiting for lock"
                        else -> "No combat signature (06 00 36) while waiting for combat lock"
                    }
                    logUnlockedPacketSkip(cap, reason)
                    continue
                }

                if (unlocked && hasCombatMagic) {
                    // Choose srcPort for now (since magic typically comes from the sender)
                    CombatPortDetector.registerCandidate(cap.srcPort, key, cap.deviceName)
                    logger.info(
                        "Magic seen on flow {}-{} (src={}, dst={}, device={})",
                        a,
                        b,
                        cap.srcPort,
                        cap.dstPort,
                        cap.deviceName
                    )
                }

                val parsed = assembler.processChunk(payload)
                if (parsed && CombatPortDetector.currentPort() == null) {
                    CombatPortDetector.confirmCandidate(cap.srcPort, cap.dstPort, cap.deviceName)
                }
                if (parsed) {
                    CombatPortDetector.markPacketParsed()
                } else {
                    val reason = if (unlocked) {
                        "Combat-signature candidate rejected or incomplete by assembler"
                    } else {
                        "Locked-flow chunk rejected or incomplete by assembler"
                    }
                    logUnlockedPacketSkip(cap, reason)
                }
            } catch (e: Exception) {
                UnifiedLogger.crash(
                    "Parser stopped while processing ${cap.deviceName} ${cap.srcPort}-${cap.dstPort}",
                    e
                )
                throw e
            }
        }
    }

    private fun logUnlockedPacketSkip(cap: CapturedPayload, reason: String) {
        if (!UnifiedLogger.isDebugEnabled()) return
        if (CombatPortDetector.currentPort() != null || CombatPortDetector.currentDevice() != null) return

        val now = System.currentTimeMillis()
        if (skipLogWindowStartMs == 0L || now - skipLogWindowStartMs >= SKIP_LOG_WINDOW_MS) {
            skipLogWindowStartMs = now
            skipLogCountInWindow = 0
        }
        if (skipLogCountInWindow >= SKIP_LOG_LIMIT_PER_WINDOW) return
        skipLogCountInWindow++

        val hex = toHex(cap.data)
        UnifiedLogger.debug(
            logger,
            "Unlocked packet skipped/rejected #{}/{} in {}ms: reason={}, device={}, src={}, dst={}, hex={}",
            skipLogCountInWindow,
            SKIP_LOG_LIMIT_PER_WINDOW,
            SKIP_LOG_WINDOW_MS,
            reason,
            cap.deviceName ?: "unknown",
            cap.srcPort,
            cap.dstPort,
            hex
        )
    }

    private fun toHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val chars = CharArray(bytes.size * 2)
        var idx = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            chars[idx++] = HEX_DIGITS[v ushr 4]
            chars[idx++] = HEX_DIGITS[v and 0x0F]
        }
        return String(chars)
    }

    private fun extractGamePayload(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data

        var candidate = data
        if (looksLikeNullLoopbackHeader(candidate) && candidate.size > 4) {
            candidate = candidate.copyOfRange(4, candidate.size)
        }

        val ipv4Payload = extractIpv4TcpPayload(candidate)
        return ipv4Payload ?: candidate
    }

    private fun looksLikeNullLoopbackHeader(data: ByteArray): Boolean {
        if (data.size < 5) return false
        val littleEndianAfInet =
            data[0] == 0x02.toByte() && data[1] == 0x00.toByte() && data[2] == 0x00.toByte() && data[3] == 0x00.toByte()
        val bigEndianAfInet =
            data[0] == 0x00.toByte() && data[1] == 0x00.toByte() && data[2] == 0x00.toByte() && data[3] == 0x02.toByte()
        if (!littleEndianAfInet && !bigEndianAfInet) return false

        val version = (data[4].toInt() ushr 4) and 0x0F
        return version == 4
    }

    private fun extractIpv4TcpPayload(data: ByteArray): ByteArray? {
        if (data.size < 20) return null
        val version = (data[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihl = data[0].toInt() and 0x0F
        val ipHeaderLength = ihl * 4
        if (ihl < 5 || data.size < ipHeaderLength + 20) return null

        val protocol = data[9].toInt() and 0xFF
        if (protocol != 0x06) return null

        val totalLength = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val ipPacketLength = if (totalLength in (ipHeaderLength + 20)..data.size) totalLength else data.size

        val tcpOffset = ipHeaderLength
        val tcpHeaderLength = ((data[tcpOffset + 12].toInt() ushr 4) and 0x0F) * 4
        if (tcpHeaderLength < 20) return null

        val payloadStart = tcpOffset + tcpHeaderLength
        if (payloadStart > ipPacketLength) return null
        if (payloadStart == ipPacketLength) return ByteArray(0)

        return data.copyOfRange(payloadStart, ipPacketLength)
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

    fun getParsingBacklog(): Int {
        synchronized(assemblers) {
            return assemblers.values.sumOf { it.bufferedBytes() }
        }
    }

    companion object {
        private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
        private const val WINDOW_CHECK_STOPPED_INTERVAL_MS = 10_000L
        private const val WINDOW_CHECK_RUNNING_INTERVAL_MS = 60_000L
        private const val SKIP_LOG_WINDOW_MS = 30_000L
        private const val SKIP_LOG_LIMIT_PER_WINDOW = 5
    }
}
