package com.tbread.packet

/**
 * Tracks and locks the TCP port used for combat traffic.
 *
 * IMPORTANT:
 * - This detector does NOT inspect raw packet payloads.
 * - Locking should be triggered by auto-detection based on packet filtering.
 */
object CombatPortDetector {

    @Volatile
    private var lockedPort: Int? = null

    @Volatile
    private var lockedIp: String? = null

    data class LockInfo(val ip: String?, val port: Int?)

    /**
     * Returns the currently locked combat port, or null if not locked yet.
     */
    fun currentPort(): Int? = lockedPort

    fun currentIp(): String? = lockedIp

    fun currentLock(): LockInfo? {
        if (lockedPort == null && lockedIp == null) {
            return null
        }
        return LockInfo(lockedIp, lockedPort)
    }

    /**
     * Locks the combat port.
     * Safe to call multiple times — only the first call has effect.
     */
    fun lock(port: Int, ip: String? = null) {
        if (lockedPort == null) {
            lockedPort = port
            lockedIp = ip
            println("🔥 Combat port locked: $port (${ip ?: "unknown ip"})")
        } else if (lockedPort == port && lockedIp == null && ip != null) {
            lockedIp = ip
        }
    }

    /**
     * Clears the locked port.
     * Optional — only use if you explicitly want to re-detect.
     */
    fun reset() {
        lockedPort = null
        lockedIp = null
    }
}
