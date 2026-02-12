package com.tbread.packet

import org.slf4j.LoggerFactory

class StreamAssembler(private val processor: StreamProcessor) {
    private val logger = LoggerFactory.getLogger(StreamAssembler::class.java)
    private val buffer = PacketAccumulator()

    suspend fun processChunk(chunk: ByteArray): Boolean {
        var parsed = false
        buffer.append(chunk)

        while (true) {
            val snapshot = buffer.snapshot()
            if (snapshot.isEmpty()) break

            val lengthInfo = readVarInt(snapshot)
            if (lengthInfo.length <= 0 || lengthInfo.value <= 0) {
                buffer.discardBytes(1)
                continue
            }

            val frameLength = lengthInfo.length + lengthInfo.value
            if (frameLength > snapshot.size) {
                break
            }

            val fullPacket = buffer.getRange(0, frameLength)

            if (fullPacket.isNotEmpty()) {
                parsed = processor.onPacketReceived(fullPacket) || parsed
            }

            buffer.discardBytes(frameLength)
        }
        return parsed
    }

    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntOutput {
        var value = 0
        var shift = 0
        var count = 0

        while (true) {
            if (offset + count >= bytes.size) {
                return VarIntOutput(-1, -1)
            }

            val byteVal = bytes[offset + count].toInt() and 0xff
            count++
            value = value or ((byteVal and 0x7F) shl shift)

            if ((byteVal and 0x80) == 0) {
                return VarIntOutput(value, count)
            }

            shift += 7
            if (shift >= 32) {
                return VarIntOutput(-1, -1)
            }
        }
    }

    fun bufferedBytes(): Int = buffer.size()
}
