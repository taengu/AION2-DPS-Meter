package com.tbread.packet

import org.slf4j.LoggerFactory

object CombatPortDetector {
    private val logger = LoggerFactory.getLogger(CombatPortDetector::class.java)
    @Volatile private var lockedPort: Int? = null
    @Volatile private var lockedDevice: String? = null

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

    fun currentPort(): Int? = lockedPort
    fun currentDevice(): String? = lockedDevice

    @Synchronized
    fun reset() {
        if (lockedPort != null) {
            logger.info("Combat port lock cleared")
        }
        lockedPort = null
        lockedDevice = null
    }
}
