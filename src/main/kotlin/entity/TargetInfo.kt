package com.tbread.entity

data class TargetInfo(
    private val targetId: Int,
    private var damagedAmount: Int = 0,
    private var targetDamageStarted: Long = Long.MAX_VALUE,
    private var targetDamageEnded: Long = Long.MIN_VALUE,
    private var lastProcessedId: Long = -1L
) {
    fun damagedAmount(): Int {
        return damagedAmount
    }

    fun lastDamageTime(): Long {
        return targetDamageEnded
    }

    fun processPdp(pdp: ParsedDamagePacket) {
        if (pdp.getId() <= lastProcessedId) return

        damagedAmount += pdp.getDamage()
        val ts = pdp.getTimeStamp()
        if (ts < targetDamageStarted){
            targetDamageStarted = ts
        }
        if (ts > targetDamageEnded){
            targetDamageEnded = ts
        }

        lastProcessedId = pdp.getId()
    }

    fun parseBattleTime(): Long {
        if (targetDamageStarted == Long.MAX_VALUE) return 0
        return targetDamageEnded - targetDamageStarted
    }
}