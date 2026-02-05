package com.tbread.packet

import com.tbread.config.PcapCapturerConfig
import kotlinx.coroutines.channels.Channel
import org.pcap4j.core.*
import org.pcap4j.packet.Packet
import org.pcap4j.packet.TcpPacket
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class PcapCapturer(
    private val config: PcapCapturerConfig,
    private val channel: Channel<CapturedPayload>
) {
    companion object {
        private val logger = LoggerFactory.getLogger(PcapCapturer::class.java)
        private const val FALLBACK_DELAY_MS = 5000L

        private fun getAllDevices(): List<PcapNetworkInterface> =
            try { Pcaps.findAllDevs() ?: emptyList() }
            catch (e: PcapNativeException) {
                logger.error("Failed to initialize pcap", e)
                exitProcess(2)
            }
    }

    private fun getLoopbackDevice(devices: List<PcapNetworkInterface>): PcapNetworkInterface? =
        devices.firstOrNull {
            it.isLoopBack || it.description?.contains("loopback", ignoreCase = true) == true
        }

    private val activeHandles = ConcurrentHashMap<String, PcapHandle>()
    private val running = AtomicBoolean(false)

    private fun captureOnDevice(nif: PcapNetworkInterface) = thread(name = "pcap-${nif.name}") {
        val deviceLabel = nif.description ?: nif.name
        logger.info("Using capture device: {}", deviceLabel)

        try {
            if (!running.get()) return@thread
            val handle = nif.openLive(
                config.snapshotSize,
                PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                config.timeout
            )
            activeHandles[nif.name] = handle

            val filter = "tcp"
            handle.setFilter(filter, BpfProgram.BpfCompileMode.OPTIMIZE)
            logger.info("Packet filter set to \"$filter\" on {}", nif.description ?: nif.name)

            val listener = PacketListener { packet: Packet ->
                val tcp = packet.get(TcpPacket::class.java) ?: return@PacketListener
                val payload = tcp.payload ?: return@PacketListener
                val data = payload.rawData
                if (data.isEmpty()) return@PacketListener

                val src = tcp.header.srcPort.valueAsInt()
                val dst = tcp.header.dstPort.valueAsInt()

                channel.trySend(CapturedPayload(src, dst, data, deviceLabel))
            }

            handle.use { h -> h.loop(-1, listener) }
        } catch (e: Exception) {
            logger.error("Packet capture failed on {}", nif.description ?: nif.name, e)
        } finally {
            activeHandles.remove(nif.name)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val devices = getAllDevices()
        if (devices.isEmpty()) {
            logger.error("No capture devices found")
            exitProcess(1)
        }

        val loopback = getLoopbackDevice(devices)
        val started = mutableSetOf<String>()
        val nonLoopbacks = devices.filterNot { it == loopback || it.isLoopBack }

        fun startDevices(targets: List<PcapNetworkInterface>, reason: String) {
            if (targets.isEmpty()) {
                logger.warn("No non-loopback adapters available to start ({})", reason)
                return
            }
            logger.info("Starting capture on other adapters ({})", reason)
            targets.forEach { nif ->
                if (started.add(nif.name)) {
                    captureOnDevice(nif)
                }
            }
        }

        if (loopback != null) {
            started.add(loopback.name)
            captureOnDevice(loopback)

            thread(name = "pcap-fallback") {
                Thread.sleep(FALLBACK_DELAY_MS)
                if (CombatPortDetector.currentPort() == null) {
                    logger.warn("No combat port lock detected on loopback; checking other adapters")
                    startDevices(nonLoopbacks, "fallback from loopback")
                }
            }
        } else {
            logger.warn("Loopback capture device not found")
            startDevices(nonLoopbacks, "loopback unavailable")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        activeHandles.values.forEach { handle ->
            try {
                handle.breakLoop()
            } catch (e: Exception) {
                logger.debug("Failed to break capture loop", e)
            }
            try {
                handle.close()
            } catch (e: Exception) {
                logger.debug("Failed to close capture handle", e)
            }
        }
        activeHandles.clear()
    }
}
