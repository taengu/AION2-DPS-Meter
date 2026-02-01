package com.tbread.packet

import org.slf4j.LoggerFactory

object CombatPortDetector {
    private val logger = LoggerFactory.getLogger(CombatPortDetector::class.java)
    @Volatile private var lockedPort: Int? = null
    @Volatile private var lockedDevice: String? = null
    private val localConnections = mutableMapOf<Pair<Int, Int>, DirectionState>()
    private val excludedPorts = mutableSetOf<Int>()
    private val candidatePorts = mutableSetOf<Int>()

    private data class DirectionState(
        var aToB: Boolean = false,
        var bToA: Boolean = false
    )

    @Synchronized
    fun lock(port: Int, deviceName: String?) {
        if (lockedPort == null) {
            lockedPort = port
            lockedDevice = deviceName
            PropertyHandler.setProperty("server.port", port.toString())
            val trimmedDevice = deviceName?.trim().orEmpty()
            if (trimmedDevice.isNotEmpty()) {
                PropertyHandler.setProperty("server.device", trimmedDevice)
            }
            logger.info("🔥 Combat port locked: {}", port)
        }
    }

    @Synchronized
    fun observeLocalConnection(srcPort: Int, dstPort: Int, deviceName: String?) {
        if (!isLoopbackDevice(deviceName)) return

        val a = minOf(srcPort, dstPort)
        val b = maxOf(srcPort, dstPort)
        val state = localConnections.getOrPut(a to b) { DirectionState() }

        if (srcPort == a) {
            state.aToB = true
        } else {
            state.bToA = true
        }

        if (state.aToB && state.bToA) {
            excludedPorts.add(a)
            excludedPorts.add(b)
            candidatePorts.remove(a)
            candidatePorts.remove(b)
        } else {
            if (!excludedPorts.contains(a)) {
                candidatePorts.add(a)
            }
            if (!excludedPorts.contains(b)) {
                candidatePorts.add(b)
            }
        }
    }

    @Synchronized
    fun bestLocalPort(): Int? = candidatePorts.maxOrNull()

    fun currentPort(): Int? = lockedPort
    fun currentDevice(): String? = lockedDevice

    @Synchronized
    fun reset() {
        if (lockedPort != null) {
            logger.info("Combat port lock cleared")
        }
        lockedPort = null
        lockedDevice = null
        localConnections.clear()
        excludedPorts.clear()
        candidatePorts.clear()
    }

    private fun isLoopbackDevice(deviceName: String?): Boolean =
        deviceName?.contains("loopback", ignoreCase = true) == true
}
