package com.tbread.packet

class StreamAssembler(private val processor: StreamProcessor) {

    private val buffer = PacketAccumulator()

    private val MAGIC_PACKET = byteArrayOf(0x06.toByte(), 0x00.toByte(), 0x36.toByte())

    suspend fun processChunk(chunk: ByteArray) {
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