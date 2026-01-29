package com.tbread.packet

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.*

class PacketAccumulator {
    private val logger = LoggerFactory.getLogger(PacketAccumulator::class.java)
    private val buffer = ByteArrayOutputStream()

    private val MAX_BUFFER_SIZE = 2 * 1024 * 1024
    private val WARN_BUFFER_SIZE = 1024 * 1024

    @Synchronized
    fun append(data: ByteArray) {
        if (buffer.size() in (WARN_BUFFER_SIZE + 1)..<MAX_BUFFER_SIZE) {
            logger.warn("{} : buffer nearing limit", logger.name)
        }
        if (buffer.size() > MAX_BUFFER_SIZE) {
            logger.error("{} : buffer exceeded limit, resetting", logger.name)
            buffer.reset()
        }
        buffer.write(data)
    }

    @Synchronized
    fun snapshot(): ByteArray = buffer.toByteArray()

    @Synchronized
    fun indexOf(target: ByteArray): Int {
        val allBytes = buffer.toByteArray()
        if (allBytes.size < target.size) return -1
        for (i in 0..allBytes.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (allBytes[i + j] != target[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }

    @Synchronized
    fun getRange(start: Int, endExclusive: Int): ByteArray {
        val allBytes = buffer.toByteArray()
        if (start < 0 || endExclusive > allBytes.size || start > endExclusive) return ByteArray(0)
        return Arrays.copyOfRange(allBytes, start, endExclusive)
    }

    @Synchronized
    fun discardBytes(length: Int) {
        val allBytes = buffer.toByteArray()
        buffer.reset()
        if (length < allBytes.size) {
            buffer.write(allBytes, length, allBytes.size - length)
        }
    }
}
