package com.tbread.util

object HexUtil {
    val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

    /** Hex-encode with spaces between bytes: "0A FF 12" */
    fun toHexSpaced(bytes: ByteArray, startInclusive: Int = 0, endExclusive: Int = bytes.size): String {
        if (bytes.isEmpty()) return ""
        val start = startInclusive.coerceIn(0, bytes.size)
        val end = endExclusive.coerceIn(start, bytes.size)
        val length = end - start
        if (length <= 0) return ""
        val hex = CharArray(length * 3 - 1)
        var pos = 0
        for (index in 0 until length) {
            val value = bytes[start + index].toInt() and 0xFF
            hex[pos++] = HEX_DIGITS[value ushr 4]
            hex[pos++] = HEX_DIGITS[value and 0x0F]
            if (index != length - 1) {
                hex[pos++] = ' '
            }
        }
        return String(hex)
    }

    /** Hex-encode without separators: "0AFF12" */
    fun toHexCompact(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val chars = CharArray(bytes.size * 2)
        var idx = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            chars[idx++] = HEX_DIGITS[v ushr 4]
            chars[idx++] = HEX_DIGITS[v and 0x0F]
        }
        return String(chars)
    }
}
