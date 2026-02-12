package com.tbread.packet

import org.slf4j.LoggerFactory

class StreamAssembler(private val processor: StreamProcessor) {
    private val logger = LoggerFactory.getLogger(StreamAssembler::class.java)
    private val buffer = PacketAccumulator()
    private val maxFrameLength = 2 * 1024 * 1024

    private data class VarIntRead(val value: Int, val length: Int)

    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntRead? {
        var value = 0
        var shift = 0
        var count = 0
        while (offset + count < bytes.size && count < 5) {
            val byteVal = bytes[offset + count].toInt() and 0xFF
            value = value or ((byteVal and 0x7F) shl shift)
            count++
            if ((byteVal and 0x80) == 0) {
                return VarIntRead(value, count)
            }
            shift += 7
        }
        return null
    }

    suspend fun processChunk(chunk: ByteArray): Boolean {
        var parsed = false
        buffer.append(chunk)

        while (true) {
            val snapshot = buffer.snapshot()
            if (snapshot.isEmpty()) break

            val header = readVarInt(snapshot, 0) ?: break
            val payloadLength = header.value
            if (payloadLength <= 0 || payloadLength > maxFrameLength) {
                logger.debug("Invalid frame length {}, resyncing by 1 byte", payloadLength)
                buffer.discardBytes(1)
                continue
            }

            val totalLength = header.length + payloadLength
            if (snapshot.size < totalLength) break

            val fullPacket = snapshot.copyOfRange(0, totalLength)
            parsed = processor.onPacketReceived(fullPacket) || parsed
            buffer.discardBytes(totalLength)
        }
        return parsed
    }

    fun bufferedBytes(): Int = buffer.size()
}
