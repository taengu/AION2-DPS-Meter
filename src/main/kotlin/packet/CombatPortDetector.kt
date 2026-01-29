package com.tbread.packet

import org.slf4j.LoggerFactory

object CombatPortDetector {
    private val logger = LoggerFactory.getLogger(CombatPortDetector::class.java)
    @Volatile private var lockedPort: Int? = null

    @Synchronized
    fun lock(port: Int) {
        if (lockedPort == null) {
            lockedPort = port
            logger.info("🔥 Combat port locked: {}", port)
        }
    }

    fun currentPort(): Int? = lockedPort
}