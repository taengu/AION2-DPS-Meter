package com.tbread.packet

import com.tbread.DataStorage
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory

class CaptureDispatcher(
    private val channel: Channel<CapturedPayload>,
    dataStorage: DataStorage
) {
    private val logger = LoggerFactory.getLogger(CaptureDispatcher::class.java)

    private val processor = StreamProcessor(dataStorage)

    // One assembler per (portA, portB) pair so streams don't mix
    private val assemblers = mutableMapOf<Pair<Int, Int>, StreamAssembler>()

    // raw magic detector for "lock" logging (but we do NOT filter yet)
    private val MAGIC = byteArrayOf(0x06.toByte(), 0x00.toByte(), 0x36.toByte())

    suspend fun run() {
        for (cap in channel) {
            val a = minOf(cap.srcPort, cap.dstPort)
            val b = maxOf(cap.srcPort, cap.dstPort)
            val key = a to b

            val assembler = assemblers.getOrPut(key) { StreamAssembler(processor) }

            // "Lock" is informational for now; don't filter until parsing confirmed stable
            if (CombatPortDetector.currentPort() == null && contains(cap.data, MAGIC)) {
                // Choose srcPort for now (since magic typically comes from the sender)
                CombatPortDetector.lock(cap.srcPort, cap.deviceName)
                logger.info(
                    "Magic seen on flow {}-{} (src={}, dst={}, device={})",
                    a,
                    b,
                    cap.srcPort,
                    cap.dstPort,
                    cap.deviceName
                )
            }

            assembler.processChunk(cap.data)
        }
    }

    private fun contains(data: ByteArray, needle: ByteArray): Boolean {
        if (data.size < needle.size) return false
        for (i in 0..data.size - needle.size) {
            var ok = true
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) { ok = false; break }
            }
            if (ok) return true
        }
        return false
    }
}
