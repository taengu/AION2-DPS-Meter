package com.tbread.packet

object PacketCaptureStatus {
    @Volatile private var npcapAvailable: Boolean = true

    fun setNpcapAvailable(available: Boolean) {
        npcapAvailable = available
    }

    fun isNpcapAvailable(): Boolean = npcapAvailable
}
