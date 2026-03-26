package com.tbread.packet

import com.tbread.logging.UnifiedLogger
import org.slf4j.LoggerFactory

object CombatPortDetector {
    private val logger = LoggerFactory.getLogger(CombatPortDetector::class.java)
    @Volatile private var lockedPort: Int? = null
    @Volatile private var lockedDevice: String? = null
    @Volatile private var lastParsedAtMs: Long = 0
    @Volatile var onDeviceLocked: ((String) -> Unit)? = null
    @Volatile var onReset: (() -> Unit)? = null
    private val candidates = LinkedHashMap<Int, String?>()
    private val deviceFlows = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()

    private fun debugLockDecision(message: String, vararg args: Any?) {
        if (!UnifiedLogger.isDebugEnabled()) return
        logger.debug(message, *args)
        UnifiedLogger.debug(logger, message, *args)
    }

    private fun isLoopbackDevice(deviceName: String?): Boolean {
        if (deviceName.isNullOrBlank()) return false
        return deviceName.contains("loopback", ignoreCase = true)
    }

    @Synchronized
    private fun lock(port: Int, deviceName: String?) {
        if (lockedPort == null) {
            lockedPort = port
            lockedDevice = deviceName?.trim()?.takeIf { it.isNotBlank() }
            logger.info("🔥 Combat port locked: {}", port)
            candidates.clear()
            deviceFlows.clear()
            lockedDevice?.let { device -> onDeviceLocked?.invoke(device) }
        }
    }

    @Synchronized
    private fun promoteLoopback(port: Int, deviceName: String?) {
        if (lockedPort != port) return
        if (!isLoopbackDevice(deviceName)) return
        if (isLoopbackDevice(lockedDevice)) return
        val previous = lockedDevice ?: "unknown"
        lockedDevice = deviceName?.trim()?.takeIf { it.isNotBlank() }
        logger.info("🔁 Switching combat device to loopback: {} -> {}", previous, lockedDevice ?: "loopback")
    }

    @Synchronized
    fun registerCandidate(port: Int, flowKey: Pair<Int, Int>, deviceName: String?) {
        val trimmedDevice = deviceName?.trim()?.takeIf { it.isNotBlank() }
        if (lockedPort != null) {
            promoteLoopback(port, trimmedDevice)
            debugLockDecision(
                "Connection not considered for lock: port={} flow={}-{} device={} reason=already locked on {} ({})",
                port,
                flowKey.first,
                flowKey.second,
                trimmedDevice ?: "unknown",
                lockedPort,
                lockedDevice ?: "unknown"
            )
            return
        }
        if (trimmedDevice != null) {
            deviceFlows.getOrPut(trimmedDevice) { mutableSetOf() }.add(flowKey)
            if (isLoopbackDevice(trimmedDevice)) {
                lock(port, trimmedDevice)
                return
            }
        }
        val existing = candidates[port]
        if (existing.isNullOrBlank() && !trimmedDevice.isNullOrBlank()) {
            candidates[port] = trimmedDevice
            debugLockDecision(
                "Connection registered as candidate: port={} flow={}-{} device={} reason=first device observed for this port",
                port,
                flowKey.first,
                flowKey.second,
                trimmedDevice
            )
            return
        }
        candidates.putIfAbsent(port, trimmedDevice)
        debugLockDecision(
            "Connection candidate unchanged: port={} flow={}-{} device={} reason=existing candidate preserved ({})",
            port,
            flowKey.first,
            flowKey.second,
            trimmedDevice ?: "unknown",
            existing ?: "unknown"
        )
    }

    @Synchronized
    fun confirmCandidate(portA: Int, portB: Int, deviceName: String?) {
        if (lockedPort != null) {
            debugLockDecision(
                "Connection not confirmed: flowPorts={}-{} device={} reason=already locked on {} ({})",
                portA,
                portB,
                deviceName?.trim()?.takeIf { it.isNotBlank() } ?: "unknown",
                lockedPort,
                lockedDevice ?: "unknown"
            )
            return
        }

        val port = when {
            candidates.containsKey(portA) -> portA
            candidates.containsKey(portB) -> portB
            else -> null
        }

        if (port == null) {
            debugLockDecision(
                "Connection not confirmed: flowPorts={}-{} device={} reason=no registered candidate for either port",
                portA,
                portB,
                deviceName?.trim()?.takeIf { it.isNotBlank() } ?: "unknown"
            )
            return
        }

        val trimmedDevice = deviceName?.trim()?.takeIf { it.isNotBlank() }
        val candidateDevice = candidates[port]
        val deviceForLock = trimmedDevice ?: candidateDevice
        val loopbackDevice = deviceFlows.keys.firstOrNull { isLoopbackDevice(it) }
        if (loopbackDevice != null && !isLoopbackDevice(deviceForLock)) {
            logger.info(
                "Deferring combat port lock on {} because loopback ({}) is available.",
                deviceForLock ?: "unknown",
                loopbackDevice
            )
            debugLockDecision(
                "Connection not locked yet: candidatePort={} device={} reason=loopback candidate exists ({})",
                port,
                deviceForLock ?: "unknown",
                loopbackDevice
            )
            return
        }
        lock(port, deviceForLock)
    }

    fun currentPort(): Int? = lockedPort
    fun currentDevice(): String? = lockedDevice
    fun lastParsedAtMs(): Long = lastParsedAtMs

    @Volatile
    var preferredDevice: String? = null
        private set

    @Volatile
    var onPreferredDeviceChanged: ((String?) -> Unit)? = null

    fun setPreferredDevice(device: String?) {
        preferredDevice = device?.trim()?.takeIf { it.isNotBlank() }
        onPreferredDeviceChanged?.invoke(preferredDevice)
    }

    fun markPacketParsed() {
        lastParsedAtMs = System.currentTimeMillis()
    }

    @Synchronized
    fun reset() {
        val wasLocked = lockedPort != null
        if (wasLocked) {
            logger.info("Combat port lock cleared")
        }
        lockedPort = null
        lockedDevice = null
        lastParsedAtMs = 0
        candidates.clear()
        deviceFlows.clear()
        PingTracker.reset()
        if (wasLocked) {
            onReset?.invoke()
        }
    }
}
