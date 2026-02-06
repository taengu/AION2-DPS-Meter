package com.tbread.packet

import org.slf4j.LoggerFactory

object CombatPortDetector {
    private val logger = LoggerFactory.getLogger(CombatPortDetector::class.java)
    @Volatile private var lockedPort: Int? = null
    @Volatile private var lockedDevice: String? = null
    private val candidates = LinkedHashMap<Int, String?>()
    private val deviceFlows = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()

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
    fun registerCandidate(port: Int, flowKey: Pair<Int, Int>, deviceName: String?) {
        if (lockedPort != null) return
        val trimmedDevice = deviceName?.trim()?.takeIf { it.isNotBlank() }
        if (trimmedDevice != null) {
            deviceFlows.getOrPut(trimmedDevice) { mutableSetOf() }.add(flowKey)
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
        if (deviceForLock != null) {
            val preferredDevice = deviceFlows.entries
                .minWithOrNull(compareBy({ it.value.size }, { it.key }))
                ?.key
            if (preferredDevice != null && preferredDevice != deviceForLock) {
                logger.info(
                    "Deferring combat port lock on {} ({} flow candidates) because {} has fewer ({}).",
                    deviceForLock,
                    deviceFlows[deviceForLock]?.size ?: 0,
                    preferredDevice,
                    deviceFlows[preferredDevice]?.size ?: 0
                )
                return
            }
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

    @Synchronized
    fun reset() {
        if (lockedPort != null) {
            logger.info("Combat port lock cleared")
        }
        lockedPort = null
        lockedDevice = null
        candidates.clear()
        deviceFlows.clear()
    }
}
