package com.tbread.packet

import com.tbread.config.PcapCapturerConfig
import kotlinx.coroutines.channels.Channel
import org.pcap4j.core.*
import org.pcap4j.packet.Packet
import org.pcap4j.packet.TcpPacket
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

class PcapCapturer(
    private val config: PcapCapturerConfig,
    private val channel: Channel<CapturedPayload>
) {
    companion object {
        private val logger = LoggerFactory.getLogger(javaClass.enclosingClass)

        private fun getAllDevices(): List<PcapNetworkInterface> =
            try { Pcaps.findAllDevs() ?: emptyList() }
            catch (e: PcapNativeException) {
                logger.error("Failed to initialize pcap", e)
                exitProcess(2)
            }
    }

    private fun getLoopbackDevice(): PcapNetworkInterface? =
        getAllDevices().firstOrNull {
            it.isLoopBack || it.description?.contains("loopback", ignoreCase = true) == true
        }

    fun start() {
        val nif = getLoopbackDevice() ?: run {
            logger.error("Failed to find loopback capture device")
            exitProcess(1)
        }

        logger.info("Using capture device: {}", nif.description)

        val handle = nif.openLive(
            config.snapshotSize,
            PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
            config.timeout
        )

        val filter = "tcp"
        handle.setFilter(filter, BpfProgram.BpfCompileMode.OPTIMIZE)
        logger.info("Packet filter set to \"$filter\"")

        val listener = PacketListener { packet: Packet ->
            val tcp = packet.get(TcpPacket::class.java) ?: return@PacketListener
            val payload = tcp.payload ?: return@PacketListener
            val data = payload.rawData
            if (data.isEmpty()) return@PacketListener

            val src = tcp.header.srcPort.valueAsInt()
            val dst = tcp.header.dstPort.valueAsInt()

            channel.trySend(CapturedPayload(src, dst, data))
        }

        try {
            handle.use { h -> h.loop(-1, listener) }
        } catch (e: InterruptedException) {
            logger.error("Packet capture interrupted", e)
        }
    }
}
