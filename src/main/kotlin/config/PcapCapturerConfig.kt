package com.tbread.config

import com.tbread.packet.PropertyHandler

data class PcapCapturerConfig(
    val serverIp: String,
    val serverPort: String,
    val timeout: Int = 10,
    val snapshotSize: Int = 65536
) {
    companion object {
        fun loadFromProperties(): PcapCapturerConfig {
            val ip = PropertyHandler.getProperty("server.ip") ?: "206.127.156.0/24"
            val port = PropertyHandler.getProperty("server.port") ?: "13328"
            val timeout = PropertyHandler.getProperty("server.timeout")?.toInt() ?: 10
            val snapSize = PropertyHandler.getProperty("server.maxSnapshotSize")?.toInt() ?: 65536

            return PcapCapturerConfig(ip, port, timeout, snapSize)
        }
    }
}