package com.tbread.packet

import org.slf4j.LoggerFactory

class StreamAssembler(private val processor: StreamProcessor) {
    private val logger = LoggerFactory.getLogger(StreamAssembler::class.java)
    private val buffer = PacketAccumulator()

    private val MAGIC_PACKET = byteArrayOf(0x06.toByte(), 0x00.toByte(), 0x36.toByte())

    suspend fun processChunk(chunk: ByteArray) {
        buffer.append(chunk)

        while (true) {
            val suffixIndex = buffer.indexOf(MAGIC_PACKET)
            if (suffixIndex == -1) break

            val cutPoint = suffixIndex + MAGIC_PACKET.size
            val fullPacket = buffer.getRange(0, cutPoint)

            if (fullPacket.isNotEmpty()) {
                processor.onPacketReceived(fullPacket)
            }

            buffer.discardBytes(cutPoint)
        }
    }
}
