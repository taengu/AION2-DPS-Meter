package com.tbread.logging

import com.tbread.packet.CapturedPayload
import com.tbread.packet.PropertyHandler
import com.tbread.util.HexUtil
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object RawPacketLogger {
    const val SETTING_KEY = "dpsMeter.saveRawPackets"
    private val logger = LoggerFactory.getLogger(RawPacketLogger::class.java)
    private val fileTimeFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    @Volatile
    var enabled: Boolean = false
        set(value) {
            val prev = field
            field = value
            if (!prev && value) startNewLog()
            if (prev && !value) closeLog()
        }

    @Volatile
    private var writer: PrintWriter? = null
    private val writeLock = Any()

    fun loadFromSettings() {
        val stored = PropertyHandler.getProperty(SETTING_KEY)
            ?.trim()?.equals("true", ignoreCase = true) == true
        enabled = stored
    }

    fun onPacket(cap: CapturedPayload) {
        if (!enabled) return
        val w = writer ?: return
        try {
            val ts = isoFmt.format(ZonedDateTime.now())
            val key = "Client:${cap.srcPort}"
            val hex = HexUtil.toHexCompact(cap.data)
            synchronized(writeLock) {
                w.println("$ts|$key|$hex")
                w.flush()
            }
        } catch (e: Exception) {
            logger.warn("Failed to write raw packet", e)
        }
    }

    private fun startNewLog() {
        closeLog()
        try {
            val now = ZonedDateTime.now()
            val stamp = fileTimeFmt.format(now)
            val file = File("packets_${stamp}.txt")
            val pw = PrintWriter(file.bufferedWriter(Charsets.UTF_8))
            pw.println("# Packet capture started at ${isoFmt.format(now)}")
            pw.println("# Format: TIMESTAMP|STREAMKEY|HEX_DATA")
            pw.println()
            pw.flush()
            writer = pw
            logger.info("Raw packet logging started: {}", file.absolutePath)
        } catch (e: Exception) {
            logger.error("Failed to start raw packet log", e)
        }
    }

    private fun closeLog() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }
}
