package com.tbread.packet

import com.tbread.config.PcapCapturerConfig
import kotlinx.coroutines.channels.Channel
import org.pcap4j.core.BpfProgram
import org.pcap4j.core.PacketListener
import org.pcap4j.core.PcapNativeException
import org.pcap4j.core.PcapNetworkInterface
import org.pcap4j.core.Pcaps
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.IpV6Packet
import org.pcap4j.packet.Packet
import org.pcap4j.packet.TcpPacket
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

class PcapCapturer(
    private val config: PcapCapturerConfig,
    private val channel: Channel<ByteArray>
) {

    companion object {
        private val logger = LoggerFactory.getLogger(javaClass.enclosingClass)
        private val MAGIC_PACKET = byteArrayOf(0x06.toByte(), 0x00.toByte(), 0x36.toByte())
        private val EXCLUDED_PACKET = byteArrayOf(0x05.toByte(), 0x01.toByte(), 0x00.toByte())

        private fun getAllDevices(): List<PcapNetworkInterface> {
            return try {
                Pcaps.findAllDevs() ?: emptyList()
            } catch (e: PcapNativeException) {
                logger.error("Failed to initialize pcap", e)
                exitProcess(2)
            }
        }
    }

    /**
     * ExitLag / VPN traffic appears on the Npcap loopback adapter.
     */
    private fun getLoopbackDevice(): PcapNetworkInterface? {
        return getAllDevices().firstOrNull {
            it.isLoopBack || it.description?.contains("loopback", ignoreCase = true) == true
        }
    }

    fun start() {
        val nif = getLoopbackDevice()
        if (nif == null) {
            logger.error("Failed to find loopback capture device")
            exitProcess(1)
        }

        logger.info("Using capture device: {}", nif.description)

        val handle = nif.openLive(
            config.snapshotSize,
            PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
            config.timeout
        )

        /*
         * IMPORTANT:
         * - Npcap loopback does NOT support tcp.len, flags, etc.
         * - Keep filter minimal and safe.
         */
        val filter = "tcp"
        handle.setFilter(filter, BpfProgram.BpfCompileMode.OPTIMIZE)
        logger.info("Packet filter set to \"$filter\"")

        fun containsSequence(data: ByteArray, target: ByteArray): Boolean {
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

        fun extractSourceIp(packet: Packet): String? {
            val ipv4 = packet.get(IpV4Packet::class.java)
            if (ipv4 != null) {
                return ipv4.header.srcAddr.hostAddress
            }
            val ipv6 = packet.get(IpV6Packet::class.java)
            return ipv6?.header?.srcAddr?.hostAddress
        }

        val listener = PacketListener { packet: Packet ->
            val tcp = packet.get(TcpPacket::class.java) ?: return@PacketListener
            val payload = tcp.payload ?: return@PacketListener

            val data = payload.rawData
            if (data.isEmpty()) return@PacketListener

            if (packet.length < 60) return@PacketListener
            if (!containsSequence(data, MAGIC_PACKET)) return@PacketListener
            if (containsSequence(data, EXCLUDED_PACKET)) return@PacketListener

            val port = tcp.header.srcPort.valueAsInt()
            val ip = extractSourceIp(packet)

            /*
             * Forward packets as follows:
             * - Before combat port is locked → allow packets matching the magic filter
             * - After lock → only allow matching endpoint
             */
            val lock = CombatPortDetector.currentLock()
            if (lock == null) {
                CombatPortDetector.lock(port, ip)
            }

            val lockedPort = lock?.port
            val lockedIp = lock?.ip

            val portMatches = lockedPort == null || lockedPort == port
            val ipMatches = lockedIp == null || lockedIp == ip

            if (portMatches && ipMatches) {
                channel.trySend(data)
            }
        }

        try {
            handle.use { h ->
                h.loop(-1, listener)
            }
        } catch (e: InterruptedException) {
            logger.error("Packet capture interrupted", e)
        }
    }
}
