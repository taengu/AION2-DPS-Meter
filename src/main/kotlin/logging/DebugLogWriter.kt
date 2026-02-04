package com.tbread.logging

import com.tbread.packet.PropertyHandler
import org.slf4j.Logger
import org.slf4j.helpers.MessageFormatter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DebugLogWriter {
    const val SETTING_KEY = "dpsMeter.debugLoggingEnabled"
    const val ACTOR_FILTER_SETTING_KEY = "dpsMeter.actorIdFilter"
    private const val MAX_MESSAGE_LENGTH = 240

    private val lock = Any()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val logFile = File("debug.log")

    @Volatile
    private var enabled = false

    fun loadFromSettings() {
        setEnabled(false)
        PropertyHandler.setProperty(SETTING_KEY, false.toString())
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun isEnabled(): Boolean = enabled

    fun debug(logger: Logger, message: String, vararg args: Any?) {
        write("DEBUG", logger.name, message, args)
    }

    fun debugAt(logger: Logger, timestampMs: Long?, message: String, vararg args: Any?) {
        write("DEBUG", logger.name, message, args, timestampMs)
    }

    fun info(logger: Logger, message: String, vararg args: Any?) {
        write("INFO", logger.name, message, args)
    }

    fun infoAt(logger: Logger, timestampMs: Long?, message: String, vararg args: Any?) {
        write("INFO", logger.name, message, args, timestampMs)
    }

    fun getActorFilterId(): Int? {
        val raw = PropertyHandler.getProperty(ACTOR_FILTER_SETTING_KEY)?.trim().orEmpty()
        if (raw.isBlank()) return null
        val parsed = raw.toIntOrNull() ?: return null
        return parsed.takeIf { it > 0 }
    }

    fun hasActorFilter(): Boolean = getActorFilterId() != null

    fun shouldLogBufferWarnings(): Boolean = !hasActorFilter()

    private fun write(
        level: String,
        loggerName: String,
        message: String,
        args: Array<out Any?>,
        timestampMs: Long? = null
    ) {
        if (!enabled) return
        val result = MessageFormatter.arrayFormat(message, args)
        val formattedMessage = truncate(result.message ?: "")
        val timestamp = if (timestampMs != null) {
            Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(timeFormatter)
        } else {
            LocalTime.now().format(timeFormatter)
        }
        val shortLoggerName = loggerName.substringAfterLast('.')
        val line = "$timestamp $level $shortLoggerName - $formattedMessage"
        synchronized(lock) {
            logFile.parentFile?.mkdirs()
            FileWriter(logFile, true).use { writer ->
                writer.append(line).append('\n')
                result.throwable?.let { throwable ->
                    writer.append(throwable.stackTraceToString()).append('\n')
                }
            }
        }
    }

    private fun truncate(message: String): String {
        if (message.length <= MAX_MESSAGE_LENGTH) return message
        return message.take(MAX_MESSAGE_LENGTH - 1) + "…"
    }
}
