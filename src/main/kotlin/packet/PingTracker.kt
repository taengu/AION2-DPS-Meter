package com.tbread.packet

import org.slf4j.LoggerFactory

/**
 * Measures game ping by detecting Ping_RS packets (opcode 03 36) in the
 * server→client game protocol stream.
 *
 * The server echoes the client's local timestamp (_client_sent_time) in
 * every Ping_RS response.  Since the DPS meter runs on the same machine
 * as the game client they share the same system clock, so:
 *
 *   RTT = System.currentTimeMillis() - (clientSentTime - DOTNET_EPOCH_OFFSET_MS)
 *
 * Ping_RS structure (21-byte sub-packet content):
 *   [0-1]   03 36        opcode
 *   [2-3]   00 00        _result
 *   [4-11]  int64 LE     _client_sent_time  (ms from .NET epoch 0001-01-01)
 *   [12-19] int64 LE     _server_game_clock (ms from Unix epoch — unused)
 *   [20]    trailing byte
 *
 * Works on both loopback and external connections — only needs the
 * server→client direction.  Arrives every ~10 seconds.
 */
object PingTracker {
    private val logger = LoggerFactory.getLogger(PingTracker::class.java)
    private const val MAX_PING_MS = 9999

    /** Milliseconds between .NET epoch (0001-01-01) and Unix epoch (1970-01-01). */
    private const val DOTNET_EPOCH_OFFSET_MS = 62135596800000L

    /** Minimum bytes: opcode (2) + result (2) + timestamp (8) = 12. */
    private const val MIN_PING_RS_BYTES = 12

    @Volatile private var lastPing: Int? = null
    @Volatile var onPingUpdate: ((Int) -> Unit)? = null

    // Timestamped ping history: (wallClockMs, pingMs)
    private val history = mutableListOf<Pair<Long, Int>>()
    private const val MAX_HISTORY = 10_000

    @Synchronized
    fun onPacket(cap: CapturedPayload) {
        if (cap.data.size >= MIN_PING_RS_BYTES) {
            tryPingRs(cap.data, cap.capturedAtMs)
        }
    }

    /** Returns a snapshot of all ping samples within [startMs, endMs] (wall-clock absolute ms). */
    @Synchronized
    fun getPingHistory(startMs: Long, endMs: Long): List<Pair<Long, Int>> =
        history.filter { (ts, _) -> ts >= startMs && ts <= endMs }

    /**
     * Scan raw TCP payload for Ping_RS sub-packets (03 36 00 00 + int64 timestamp).
     */
    private fun tryPingRs(data: ByteArray, arrivalMs: Long) {
        var i = 0
        while (i <= data.size - MIN_PING_RS_BYTES) {
            if (data[i] == 0x03.toByte() &&
                data[i + 1] == 0x36.toByte() &&
                data[i + 2] == 0x00.toByte() &&
                data[i + 3] == 0x00.toByte()
            ) {
                val clientSentRaw = readInt64LE(data, i + 4)
                val clientSentUnixMs = clientSentRaw - DOTNET_EPOCH_OFFSET_MS
                val rttMs = (arrivalMs - clientSentUnixMs).toInt()

                com.tbread.logging.UnifiedLogger.debug(logger, "Ping_RS parsed: rtt={}ms clientSentUnix={} arrivalMs={}", rttMs, clientSentUnixMs, arrivalMs)
                if (rttMs in 1..MAX_PING_MS) {
                    lastPing = rttMs
                    history.add(System.currentTimeMillis() to rttMs)
                    if (history.size > MAX_HISTORY) history.removeAt(0)
                    onPingUpdate?.invoke(rttMs)
                }
                i += 12
            } else {
                i++
            }
        }
    }

    fun currentPingMs(): Int? = lastPing

    @Synchronized
    fun reset() {
        lastPing = null
        history.clear()
    }

    private fun readInt64LE(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (j in 0..7) {
            v = v or ((data[offset + j].toLong() and 0xFF) shl (j * 8))
        }
        return v
    }
}
