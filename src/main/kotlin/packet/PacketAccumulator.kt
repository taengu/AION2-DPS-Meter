package com.tbread.packet

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.Arrays

class PacketAccumulator {
    private val logger = LoggerFactory.getLogger(PacketAccumulator::class.java)

    private val buffer = ByteArrayOutputStream(64 * 1024) // pre-size
    private var cachedBytes: ByteArray? = null

    private val MAX_BUFFER_SIZE = 2 * 1024 * 1024
    private val WARN_BUFFER_SIZE = 1024 * 1024

    @Synchronized
    fun append(data: ByteArray) {
        val size = buffer.size()

        if (size in (WARN_BUFFER_SIZE + 1)..<MAX_BUFFER_SIZE) {
            logger.trace("{} : buffer nearing limit", logger.name)
        }

        if (size > MAX_BUFFER_SIZE) {
            logger.error("{} : buffer exceeded limit, resetting", logger.name)
            buffer.reset()
        }

        buffer.write(data)
        cachedBytes = null // invalidate cache
    }

    @Synchronized
    fun snapshot(): ByteArray {
        return getCached()
    }

    @Synchronized
    fun indexOf(target: ByteArray): Int {
        val allBytes = getCached()
        if (allBytes.size < target.size) return -1

        outer@ for (i in 0..allBytes.size - target.size) {
            for (j in target.indices) {
                if (allBytes[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }

    @Synchronized
    fun getRange(start: Int, endExclusive: Int): ByteArray {
        val allBytes = getCached()
        if (start < 0 || endExclusive > allBytes.size || start >= endExclusive) {
            return ByteArray(0)
        }
        return Arrays.copyOfRange(allBytes, start, endExclusive)
    }

    @Synchronized
    fun discardBytes(length: Int) {
        val allBytes = getCached()
        buffer.reset()

        if (length < allBytes.size) {
            buffer.write(allBytes, length, allBytes.size - length)
        }

        cachedBytes = null
    }

    @Synchronized
    private fun getCached(): ByteArray {
        val cached = cachedBytes
        if (cached != null) return cached

        val bytes = buffer.toByteArray()
        cachedBytes = bytes
        return bytes
    }
}
