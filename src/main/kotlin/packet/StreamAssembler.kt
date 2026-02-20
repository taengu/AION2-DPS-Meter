package com.tbread.packet

class StreamAssembler(private val processor: StreamProcessor) {
    private val accumulator = PacketAccumulator()

    fun processChunk(data: ByteArray): Boolean {
        accumulator.append(data)
        var parsedAny = false

        while (accumulator.size() > 0) {
            val snapshot = accumulator.snapshot()

            // Ask the processor exactly how many bytes it successfully parsed
            val consumed = processor.consumeStream(snapshot)

            if (consumed > 0) {
                // Only discard the safely parsed bytes, keeping fragments in the buffer!
                accumulator.discardBytes(consumed)
                parsedAny = true
            } else {
                break // Fragmented chunk reached, wait for more TCP data
            }
        }
        return parsedAny
    }

    fun bufferedBytes(): Int = accumulator.size()
}