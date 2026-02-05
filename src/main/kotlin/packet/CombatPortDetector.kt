package com.tbread.packet

import org.slf4j.LoggerFactory

object CombatPortDetector {
    private val logger = LoggerFactory.getLogger(CombatPortDetector::class.java)
    @Volatile private var lockedPort: Int? = null
    @Volatile private var lockedDevice: String? = null
    private val candidates = LinkedHashMap<Int, String?>()

    @Synchronized
    private fun lock(port: Int, deviceName: String?) {
        if (lockedPort == null) {
            lockedPort = port
            lockedDevice = deviceName?.trim()?.takeIf { it.isNotBlank() }
            logger.info("🔥 Combat port locked: {}", port)
            candidates.clear()
        }
    }

    @Synchronized
    fun registerCandidate(port: Int, deviceName: String?) {
        if (lockedPort != null) return
        val trimmedDevice = deviceName?.trim()
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
        val trimmedDevice = deviceName?.trim()
        val candidateDevice = candidates[port]
        lock(port, trimmedDevice?.takeIf { it.isNotBlank() } ?: candidateDevice)
    }

    @Synchronized
    fun clearCandidates() {
        if (lockedPort == null) {
            candidates.clear()
        }
    }

    fun currentPort(): Int? = lockedPort
    fun currentDevice(): String? = lockedDevice

    @Synchronized
    fun reset() {
        if (lockedPort != null) {
            logger.info("Combat port lock cleared")
        }
        lockedPort = null
        lockedDevice = null
        candidates.clear()
    }
}
