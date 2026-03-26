package com.tbread.logging

import com.tbread.packet.PropertyHandler
import org.slf4j.Logger
import org.slf4j.helpers.MessageFormatter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object UnifiedLogger {
    const val DEBUG_SETTING_KEY = "dpsMeter.debugLoggingEnabled"
    private const val DEBUG_ACTOR_KEY = "dpsMeter.debugActorId"
    private const val MAX_MESSAGE_LENGTH = 240

    private val lock = Any()
    private val crashTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val debugTimestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val crashLogFile = File("crash.log")
    private val debugLogFile = File("debug.log")

    @Volatile
    private var debugEnabled = false

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
        appendLine(crashLogFile, line, throwable)
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
        appendLine(debugLogFile, line, result.throwable)
    }

    private fun appendLine(file: File, line: String, throwable: Throwable?) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            FileWriter(file, true).use { writer ->
                writer.append(line).append('\n')
                throwable?.let { writer.append(it.stackTraceToString()).append('\n') }
            }
        }
    }

    private fun truncate(message: String): String {
        if (message.length <= MAX_MESSAGE_LENGTH) return message
        return message.take(MAX_MESSAGE_LENGTH - 1) + "…"
    }
}
