package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.logging.UnifiedLogger
import com.tbread.util.HexUtil
import com.tbread.windows.WindowTitleDetector
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

class CaptureDispatcher(
    private val channel: Channel<CapturedPayload>,
    private val dataStorage: DataStorage,
    private val isOfflineReplay: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(CaptureDispatcher::class.java)
    private var lastWindowCheckMs = 0L
    private var isAionRunning = false

    // One assembler per (portA, portB) pair so streams don't mix
    private val assemblers = mutableMapOf<Pair<Int, Int>, StreamAssembler>()

    // For offline replay we reuse a single StreamProcessor so that the embedded-packet
    // dedup set is shared across all replay lines (the same damage sub-record can appear
    // in multiple consecutive context-update chunks, which would otherwise cause duplicates).
    // A fresh StreamAssembler is still created per line so the TCP fragment buffer stays clean.
    private val offlineReplayProcessor: StreamProcessor? =
        if (isOfflineReplay) StreamProcessor(dataStorage) else null

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

                val currentLockedPort = CombatPortDetector.currentPort()
                val lockedDevice = CombatPortDetector.currentDevice()
                if (!isOfflineReplay && lockedDevice != null && !deviceMatches(lockedDevice, cap.deviceName)) {
                    continue
                }

                // 1. If we are securely locked to AION, completely ignore all other background ports
                if (currentLockedPort != null && cap.srcPort != currentLockedPort && cap.dstPort != currentLockedPort) {
                    continue
                }

                // Feed packets to PingTracker after device/port filtering so we only
                // measure ping on the same locked device and port as combat data.
                if (currentLockedPort != null) {
                    PingTracker.onPacket(cap)
                }

                // Only parse server→client packets (srcPort == locked port).
                // Client→server data uses a different wire format and would corrupt the
                // StreamAssembler's accumulator if interleaved with partial server segments.
                // PingTracker already received both directions above.
                if (currentLockedPort != null && cap.srcPort != currentLockedPort) {
                    continue
                }

                // 2. Run all the filters FIRST before creating any memory-heavy assemblers!
                val unlocked = currentLockedPort == null
                val tlsPayload = looksLikeTlsPayload(cap.data)

                if (unlocked && tlsPayload) {
                    logUnlockedPacketSkip(cap, "TLS payload ignored while waiting for combat lock")
                    continue
                }

                val hasCombatMagic = if (tlsPayload) false else contains(cap.data, MAGIC)
                if (unlocked && !hasCombatMagic) {
                    val reason = when {
                        cap.data.isEmpty() -> "Empty payload while waiting for combat signature"
                        cap.data.size < MAGIC.size -> "Payload too short for combat signature while waiting for lock"
                        else -> "No combat signature (06 00 36) while waiting for combat lock"
                    }
                    logUnlockedPacketSkip(cap, reason)
                    continue
                }

                // 3. NOW it is safe to create or retrieve the StreamAssembler
                val a = minOf(cap.srcPort, cap.dstPort)
                val b = maxOf(cap.srcPort, cap.dstPort)
                val key = a to b

                val assembler = if (isOfflineReplay) {
                    // Replay logs already contain discrete captured payloads, so create a fresh
                    // StreamAssembler (clean fragment buffer) each line, but reuse the shared
                    // StreamProcessor so that the embedded-packet dedup set persists across lines.
                    StreamAssembler(offlineReplayProcessor!!)
                } else {
                    assemblers.getOrPut(key) {
                        StreamAssembler(StreamProcessor(dataStorage))
                    }
                }

                if (unlocked) {
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

                com.tbread.logging.RawPacketLogger.onPacket(cap)
                val parsed = assembler.processChunk(cap.data)
                if (parsed && CombatPortDetector.currentPort() == null) {
                    CombatPortDetector.confirmCandidate(cap.srcPort, cap.dstPort, cap.deviceName)

                    // 4. Garbage collect any orphaned assemblers from false-positives once locked
                    synchronized(assemblers) {
                        assemblers.keys.retainAll { it == key }
                    }
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

    private fun toHex(bytes: ByteArray): String = HexUtil.toHexCompact(bytes)

    private fun ensureAionRunning(): Boolean {
        if (isOfflineReplay) return true
        val now = System.currentTimeMillis()
        val intervalMs = if (isAionRunning) WINDOW_CHECK_RUNNING_INTERVAL_MS else WINDOW_CHECK_STOPPED_INTERVAL_MS
        if (now - lastWindowCheckMs >= intervalMs) {
            lastWindowCheckMs = now
            val running = WindowTitleDetector.findAion2WindowTitle() != null
            if (!running && isAionRunning) {
                CombatPortDetector.reset()
                PingTracker.reset()
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
        private const val WINDOW_CHECK_STOPPED_INTERVAL_MS = 10_000L
        private const val WINDOW_CHECK_RUNNING_INTERVAL_MS = 60_000L
        private const val SKIP_LOG_WINDOW_MS = 30_000L
        private const val SKIP_LOG_LIMIT_PER_WINDOW = 5
    }
}
