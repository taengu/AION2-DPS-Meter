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
        setDebugEnabled(false)
        PropertyHandler.setProperty(DEBUG_SETTING_KEY, false.toString())
    }

    fun isDebugEnabled(): Boolean = debugEnabled

    fun crash(message: String, throwable: Throwable? = null) {
        val timestamp = LocalDateTime.now().format(crashTimestampFormatter)
        val threadName = Thread.currentThread().name
        val line = "$timestamp [$threadName] $message"
        appendLine(crashLogFile, line, throwable)
    }

    fun debug(logger: Logger, message: String, vararg args: Any?) {
        writeDebug("DEBUG", logger.name, message, args)
    }

    fun info(logger: Logger, message: String, vararg args: Any?) {
        writeDebug("INFO", logger.name, message, args)
    }

    private fun writeDebug(level: String, loggerName: String, message: String, args: Array<out Any?>) {
        if (!debugEnabled) return
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
