package com.tbread.packet

data class CapturedPayload(
    val srcPort: Int,
    val dstPort: Int,
    val data: ByteArray
)
