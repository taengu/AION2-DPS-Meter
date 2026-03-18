package com.tbread.packet

data class CapturedPayload(
    val srcPort: Int,
    val dstPort: Int,
    val data: ByteArray,
    val deviceName: String?,
    val timestampNanos: Long = System.nanoTime(),
    val srcIp: String? = null,
    val dstIp: String? = null,
    val tcpSeq: Long = 0,
    val tcpAck: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapturedPayload) return false
        return srcPort == other.srcPort && dstPort == other.dstPort &&
            data.contentEquals(other.data) && deviceName == other.deviceName &&
            timestampNanos == other.timestampNanos && srcIp == other.srcIp &&
            dstIp == other.dstIp && tcpSeq == other.tcpSeq && tcpAck == other.tcpAck
    }

    override fun hashCode(): Int {
        var result = srcPort
        result = 31 * result + dstPort
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (deviceName?.hashCode() ?: 0)
        result = 31 * result + timestampNanos.hashCode()
        result = 31 * result + (srcIp?.hashCode() ?: 0)
        result = 31 * result + (dstIp?.hashCode() ?: 0)
        result = 31 * result + tcpSeq.hashCode()
        result = 31 * result + tcpAck.hashCode()
        return result
    }
}
