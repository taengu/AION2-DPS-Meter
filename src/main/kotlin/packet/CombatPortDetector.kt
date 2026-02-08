package com.tbread.packet

import org.slf4j.LoggerFactory

object CombatPortDetector {
    private val logger = LoggerFactory.getLogger(CombatPortDetector::class.java)
    @Volatile private var lockedPort: Int? = null
    @Volatile private var lockedDevice: String? = null
    @Volatile private var lastParsedAtMs: Long = 0
    private val candidates = LinkedHashMap<Int, String?>()
    private val deviceFlows = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()

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
            return
        }
        candidates.putIfAbsent(port, trimmedDevice)
    }

    @Synchronized
    fun confirmCandidate(portA: Int, portB: Int, deviceName: String?) {
        if (lockedPort != null) return
        val port = when {
            candidates.containsKey(portA) -> portA
            candidates.containsKey(portB) -> portB
            else -> null
        } ?: return
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
            return
        }
        lock(port, deviceForLock)
    }

    @Synchronized
    fun clearCandidates() {
        if (lockedPort == null) {
            candidates.clear()
            deviceFlows.clear()
        }
    }

    fun currentPort(): Int? = lockedPort
    fun currentDevice(): String? = lockedDevice
    fun lastParsedAtMs(): Long = lastParsedAtMs

    fun markPacketParsed() {
        lastParsedAtMs = System.currentTimeMillis()
    }

    @Synchronized
    fun reset() {
        if (lockedPort != null) {
            logger.info("Combat port lock cleared")
        }
        lockedPort = null
        lockedDevice = null
        lastParsedAtMs = 0
        candidates.clear()
        deviceFlows.clear()
    }
}
