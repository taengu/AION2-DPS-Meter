package com.tbread.packet

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import java.io.File
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class FilePacketCapturer(
    private val filePath: String,
    private val channel: Channel<CapturedPayload>,
    private val playbackSpeed: Double = 1.0
) {
    private val logger = LoggerFactory.getLogger(FilePacketCapturer::class.java)
    private val running = AtomicBoolean(false)

    suspend fun start() {
        if (!running.compareAndSet(false, true)) return
        logger.info("Starting offline replay from {}", filePath)

        val file = File(filePath)
        if (!file.exists()) {
            logger.error("Replay file not found: {}", filePath)
            return
        }

        var previousTimestamp: OffsetDateTime? = null

        try {
            file.useLines { lines ->
                for (line in lines) {
                    if (!running.get() || !coroutineContext.isActive) break
                    if (line.isBlank() || line.startsWith("#")) continue

                    val parts = line.split("|")
                    if (parts.size != 3) continue

                    val timestamp = try { OffsetDateTime.parse(parts[0]) } catch (e: Exception) { null } ?: continue
                    val streamKey = parts[1]
                    val hexData = parts[2]

                    if (previousTimestamp != null) {
                        val delayMs = ChronoUnit.MILLIS.between(previousTimestamp, timestamp)
                        if (delayMs > 0) {
                            delay((delayMs / playbackSpeed).toLong())
                        }
                    }
                    previousTimestamp = timestamp

                    // Extract the client port from "Client:55729"
                    val portStr = streamKey.split(":").lastOrNull()
                    val srcPort = portStr?.toIntOrNull() ?: 55555
                    val dstPort = 50349 // Default AION server port

                    val data = decodeHex(hexData)
                    channel.trySend(CapturedPayload(srcPort, dstPort, data, "FilePacketCapture"))
                }
            }
            logger.info("Finished replaying file.")
        } catch (e: Exception) {
            logger.error("Error during replay", e)
        } finally {
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
    }

    private fun decodeHex(hex: String): ByteArray {
        val cleanHex = hex.replace("\\s+".toRegex(), "")
        require(cleanHex.length % 2 == 0) { "Hex string must have an even length" }
        return cleanHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}