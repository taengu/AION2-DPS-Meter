package com.tbread.logging

import com.tbread.packet.PropertyHandler
import org.slf4j.Logger
import org.slf4j.helpers.MessageFormatter
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object UnifiedLogger {
    const val DEBUG_SETTING_KEY = "dpsMeter.debugLoggingEnabled"
    private const val DEBUG_ACTOR_KEY = "dpsMeter.debugActorId"
    private const val MAX_MESSAGE_LENGTH = 240
    private const val MAX_LOG_SIZE_BYTES = 5L * 1024 * 1024  // 5 MB
    private const val FLUSH_INTERVAL_MS = 2000L

    private val lock = Any()
    private val crashTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val debugTimestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val crashLogFile = File("crash.log")
    private val debugLogFile = File("debug.log")

    @Volatile
    private var debugEnabled = false

    // Buffered writer for debug.log — kept open to avoid per-line open/close overhead
    private var debugWriter: BufferedWriter? = null
    private var debugFileByteEstimate = 0L
    private var lastFlushMs = System.currentTimeMillis()

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }

    fun loadDebugFromSettings() {
        val enabled = PropertyHandler.getProperty(DEBUG_SETTING_KEY)
            ?.trim()
            ?.equals("true", ignoreCase = true) == true
        setDebugEnabled(enabled)
    }

    fun isDebugEnabled(): Boolean = debugEnabled

    fun crash(message: String, throwable: Throwable? = null) {
        val timestamp = LocalDateTime.now().format(crashTimestampFormatter)
        val threadName = Thread.currentThread().name
        val line = "$timestamp [$threadName] $message"
        appendCrashLine(line, throwable)
    }

    private fun getDebugActorFilter(): Int? =
        PropertyHandler.getProperty(DEBUG_ACTOR_KEY)?.trim()?.toIntOrNull()

    /** Non-actor-specific log — suppressed when a debugActorId filter is active. */
    fun debug(logger: Logger, message: String, vararg args: Any?) {
        if (getDebugActorFilter() != null) return
        writeDebug(logger.name, message, args)
    }

    /** Only written when actorId matches the debugActorId filter (or no filter is set). */
    fun debugForActor(logger: Logger, actorId: Int, message: String, vararg args: Any?) {
        val filter = getDebugActorFilter()
        if (filter != null && actorId != filter) return
        writeDebug(logger.name, message, args)
    }

    /** Only written when at least one of the given actor IDs matches the filter (or no filter is set). */
    fun debugForActors(logger: Logger, actor1: Int, actor2: Int, message: String, vararg args: Any?) {
        val filter = getDebugActorFilter()
        if (filter != null && actor1 != filter && actor2 != filter) return
        writeDebug(logger.name, message, args)
    }

    fun info(logger: Logger, message: String, vararg args: Any?) {
        if (!debugEnabled) return
        writeLog("INFO", logger.name, message, args)
    }

    private fun writeDebug(loggerName: String, message: String, args: Array<out Any?>) {
        if (!debugEnabled) return
        writeLog("DEBUG", loggerName, message, args)
    }

    private fun writeLog(level: String, loggerName: String, message: String, args: Array<out Any?>) {
        val result = MessageFormatter.arrayFormat(message, args)
        val formattedMessage = truncate(result.message ?: "")
        val timestamp = LocalTime.now().format(debugTimestampFormatter)
        val shortLoggerName = loggerName.substringAfterLast('.')
        val line = "$timestamp $level $shortLoggerName - $formattedMessage"
        appendDebugLine(line, result.throwable)
    }

    private fun appendCrashLine(line: String, throwable: Throwable?) {
        synchronized(lock) {
            crashLogFile.parentFile?.mkdirs()
            FileWriter(crashLogFile, true).use { writer ->
                writer.append(line).append('\n')
                throwable?.let { writer.append(it.stackTraceToString()).append('\n') }
            }
        }
    }

    private fun appendDebugLine(line: String, throwable: Throwable?) {
        synchronized(lock) {
            rotateIfNeeded()
            val writer = getOrCreateDebugWriter()
            writer.append(line).append('\n')
            debugFileByteEstimate += line.length + 1
            if (throwable != null) {
                val trace = throwable.stackTraceToString()
                writer.append(trace).append('\n')
                debugFileByteEstimate += trace.length + 1
            }
            // Periodic flush instead of per-line close
            val now = System.currentTimeMillis()
            if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                writer.flush()
                lastFlushMs = now
            }
        }
    }

    private fun getOrCreateDebugWriter(): BufferedWriter {
        var w = debugWriter
        if (w != null) return w
        debugLogFile.parentFile?.mkdirs()
        debugFileByteEstimate = if (debugLogFile.exists()) debugLogFile.length() else 0L
        w = BufferedWriter(FileWriter(debugLogFile, true), 8192)
        debugWriter = w
        return w
    }

    private fun rotateIfNeeded() {
        if (debugFileByteEstimate < MAX_LOG_SIZE_BYTES) return
        try {
            debugWriter?.flush()
            debugWriter?.close()
        } catch (_: Exception) {}
        debugWriter = null
        val backup = File(debugLogFile.path + ".old")
        backup.delete()
        debugLogFile.renameTo(backup)
        debugFileByteEstimate = 0L
    }

    /** Flush and close the buffered writer (e.g. on shutdown). */
    fun close() {
        synchronized(lock) {
            try {
                debugWriter?.flush()
                debugWriter?.close()
            } catch (_: Exception) {}
            debugWriter = null
        }
    }

    private fun truncate(message: String): String {
        if (message.length <= MAX_MESSAGE_LENGTH) return message
        return message.take(MAX_MESSAGE_LENGTH - 1) + "…"
    }
}
