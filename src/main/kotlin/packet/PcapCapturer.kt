package com.tbread.packet

import com.tbread.config.PcapCapturerConfig
import com.tbread.logging.UnifiedLogger
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
        private const val FALLBACK_DELAY_MS = 1500L
        private const val DEVICE_STATUS_INTERVAL_MS = 10_000L

        private fun getAllDevices(): List<PcapNetworkInterface> =
            try { Pcaps.findAllDevs() ?: emptyList() }
            catch (e: PcapNativeException) {
                logger.error("Failed to initialize pcap", e)
                UnifiedLogger.crash("Failed to initialize pcap", e)
                exitProcess(2)
            }
    }

    private fun getLoopbackDevice(devices: List<PcapNetworkInterface>): PcapNetworkInterface? {
        val npfLoopback = devices.firstOrNull {
            it.name.equals("\\Device\\NPF_Loopback", ignoreCase = true)
        }
        if (npfLoopback != null) return npfLoopback

        val nativeLoopback = devices.firstOrNull { it.isLoopBack }
        if (nativeLoopback != null) return nativeLoopback

        return devices.firstOrNull { it.description?.contains("loopback", ignoreCase = true) == true }
    }


    private fun isVirtualDevice(nif: PcapNetworkInterface): Boolean {
        val label = (nif.description ?: nif.name).lowercase()
        return nif.isLoopBack ||
            nif.name.equals("\\Device\\NPF_Loopback", ignoreCase = true) ||
            label.contains("loopback") ||
            label.contains("tap-windows") ||
            label.contains("tap") ||
            label.contains("wintun") ||
            label.contains("wireguard")
    }

    private fun openHandle(nif: PcapNetworkInterface): PcapHandle {
        return try {
            nif.openLive(
                config.snapshotSize,
                PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                config.timeout
            )
        } catch (e: PcapNativeException) {
            if (!isVirtualDevice(nif)) throw e

            logger.warn(
                "Promiscuous open failed on virtual adapter {}; retrying in non-promiscuous mode",
                nif.description ?: nif.name
            )
            nif.openLive(
                config.snapshotSize,
                PcapNetworkInterface.PromiscuousMode.NONPROMISCUOUS,
                config.timeout
            )
        }
    }

    private val activeHandles = ConcurrentHashMap<String, PcapHandle>()
    private val running = AtomicBoolean(false)

    private fun captureOnDevice(nif: PcapNetworkInterface) = thread(name = "pcap-${nif.name}") {
        val deviceLabel = nif.description ?: nif.name
        logger.info("Using capture device: {}", deviceLabel)

        try {
            if (!running.get()) return@thread
            val handle = openHandle(nif)
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
            UnifiedLogger.crash("Packet capture failed on ${nif.description ?: nif.name}", e)
        } finally {
            activeHandles.remove(nif.name)
        }
    }


    private fun logDeviceStatusesWhileUnlocked() = thread(name = "pcap-device-status") {
        while (running.get()) {
            try {
                Thread.sleep(DEVICE_STATUS_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return@thread
            }
            if (!running.get()) return@thread
            if (CombatPortDetector.currentPort() != null) continue

            val devices = getAllDevices()
            devices.forEachIndexed { index, device ->
                val label = device.description ?: device.name
                val addresses = device.addresses.joinToString { it.address?.hostAddress ?: "n/a" }
                val hasHandle = activeHandles.containsKey(device.name)
                val reason = when {
                    hasHandle -> "capturing"
                    device.addresses.isEmpty() -> "not capturing: no interface addresses"
                    else -> "not capturing: handle inactive (not started yet, failed, or waiting)"
                }
                logger.debug(
                    "[unlock-status] device[{}]: name={}, label={}, loopback={}, up={}, running={}, hasHandle={}, addresses=[{}], reason={}",
                    index,
                    device.name,
                    label,
                    device.isLoopBack,
                    device.isUp,
                    device.isRunning,
                    hasHandle,
                    addresses,
                    reason
                )
                if (UnifiedLogger.isDebugEnabled()) {
                    UnifiedLogger.debug(
                        logger,
                        "[unlock-status] device[{}]: name={}, label={}, loopback={}, up={}, running={}, hasHandle={}, addresses=[{}], reason={}",
                        index,
                        device.name,
                        label,
                        device.isLoopBack,
                        device.isUp,
                        device.isRunning,
                        hasHandle,
                        addresses,
                        reason
                    )
                }
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val devices = getAllDevices()
        if (devices.isEmpty()) {
            logger.error("No capture devices found")
            UnifiedLogger.crash("No capture devices found")
            exitProcess(1)
        }

        if (UnifiedLogger.isDebugEnabled()) {
            devices.forEachIndexed { index, device ->
                val label = device.description ?: device.name
                val addresses = device.addresses.joinToString { it.address?.hostAddress ?: "n/a" }
                logger.debug(
                    "PCAP device[{}]: name={}, label={}, loopback={}, up={}, running={}, addresses=[{}]",
                    index,
                    device.name,
                    label,
                    device.isLoopBack,
                    device.isUp,
                    device.isRunning,
                    addresses
                )
                UnifiedLogger.debug(
                    logger,
                    "PCAP device[{}]: name={}, label={}, loopback={}, up={}, running={}, addresses=[{}]",
                    index,
                    device.name,
                    label,
                    device.isLoopBack,
                    device.isUp,
                    device.isRunning,
                    addresses
                )
            }
        }

        logDeviceStatusesWhileUnlocked()

        val preferredLoopback = getLoopbackDevice(devices)
        val started = mutableSetOf<String>()
        val virtualDevices = devices.filter { isVirtualDevice(it) }
        val physicalDevices = devices.filterNot { isVirtualDevice(it) }

        fun startDevices(targets: List<PcapNetworkInterface>, reason: String) {
            if (targets.isEmpty()) {
                logger.warn("No adapters available to start ({})", reason)
                return
            }

            targets.forEach { target ->
                val reasonDetail = when {
                    started.contains(target.name) -> "already started"
                    target.addresses.isEmpty() -> "no interface addresses"
                    else -> null
                }

                if (reasonDetail != null) {
                    if (UnifiedLogger.isDebugEnabled()) {
                        logger.debug(
                            "PCAP device not started: {} ({}) reason={}",
                            target.description ?: target.name,
                            reason,
                            reasonDetail
                        )
                        UnifiedLogger.debug(
                            logger,
                            "PCAP device not started: {} ({}) reason={}",
                            target.description ?: target.name,
                            reason,
                            reasonDetail
                        )
                    }
                    return@forEach
                }

                started.add(target.name)
                logger.info(
                    "Starting capture on adapter {} ({})",
                    target.description ?: target.name,
                    reason
                )
                captureOnDevice(target)
            }
        }

        val prioritizedVirtualDevices = buildList {
            if (preferredLoopback != null) add(preferredLoopback)
            virtualDevices.forEach { if (it != preferredLoopback) add(it) }
        }

        startDevices(prioritizedVirtualDevices, "virtual/loopback priority")

        if (prioritizedVirtualDevices.isEmpty()) {
            logger.warn("No virtual/loopback adapter found; starting physical adapters immediately")
            startDevices(physicalDevices, "no virtual adapters")
            return
        }

        thread(name = "pcap-fallback") {
            Thread.sleep(FALLBACK_DELAY_MS)
            if (CombatPortDetector.currentPort() == null) {
                logger.warn("No combat port lock detected on virtual adapters; checking physical adapters")
                startDevices(physicalDevices, "fallback from virtual adapters")
            }
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
