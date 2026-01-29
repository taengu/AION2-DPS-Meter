package com.tbread.packet

import org.slf4j.LoggerFactory

class StreamAssembler(private val processor: StreamProcessor) {
    private val logger = LoggerFactory.getLogger(StreamAssembler::class.java)

    private val buffer = PacketAccumulator()

    private val MAGIC_PACKET = byteArrayOf(0x06.toByte(), 0x00.toByte(), 0x36.toByte())
    private val EXCLUDED_PACKET = byteArrayOf(0x05.toByte(), 0x01.toByte(), 0x00.toByte())

    private fun containsSequence(data: ByteArray, target: ByteArray): Boolean {
        if (target.isEmpty() || data.size < target.size) return false
        for (i in 0..data.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (data[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    suspend fun processChunk(chunk: ByteArray) {
        if (!containsSequence(chunk, MAGIC_PACKET)) {
            return
        }
        if (containsSequence(chunk, EXCLUDED_PACKET)) {
            return
        }

        buffer.append(chunk)

        while (true) {
            val suffixIndex = buffer.indexOf(MAGIC_PACKET)
            //매직패킷찾기

            if (suffixIndex == -1) {
                //없으면 덜왔으니 대기
                break
            }
            val cutPoint = suffixIndex + MAGIC_PACKET.size

            val fullPacket = buffer.getRange(0, cutPoint)

            if (fullPacket.isNotEmpty()) {
                processor.onPacketReceived(fullPacket)
            }

            buffer.discardBytes(cutPoint)
            //완성 처리된건 지우기
        }
    }
}
