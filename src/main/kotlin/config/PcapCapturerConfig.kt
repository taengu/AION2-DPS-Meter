package com.tbread.config

import com.tbread.packet.PropertyHandler
import org.slf4j.LoggerFactory

data class PcapCapturerConfig(
    val serverIp: String,
    val serverPort: String,
    val timeout: Int = 10,
    val snapshotSize: Int = 65536
) {
    companion object {
        private val logger = LoggerFactory.getLogger(PcapCapturerConfig::class.java)
        fun loadFromProperties(): PcapCapturerConfig {
            val ip = PropertyHandler.getProperty("server.ip") ?: "127.0.0.1"
            val port = "50349"
            val timeout = PropertyHandler.getProperty("server.timeout")?.toInt() ?: 10
            val snapSize = PropertyHandler.getProperty("server.maxSnapshotSize")?.toInt() ?: 65536
            logger.debug("{},{},{},{}", ip, port, timeout, snapSize)
            logger.info("Properties initialized")
            return PcapCapturerConfig(ip, port, timeout, snapSize)
        }
    }
}
