package com.tbread.entity

import java.util.UUID

data class TargetInfo(
    private val targetId: Int,
    private var damagedAmount: Int = 0,
    private var targetDamageStarted: Long,
    private var targetDamageEnded: Long,
    private var lastProcessedTimestamp: Long = Long.MIN_VALUE,
    private var lastProcessedUuid: UUID? = null,
) {
    fun damagedAmount(): Int {
        return damagedAmount
    }

    fun targetId(): Int {
        return targetId
    }

    fun firstDamageTime(): Long {
        return targetDamageStarted
    }

    fun lastDamageTime(): Long {
        return targetDamageEnded
    }

    fun processPdp(pdp: ParsedDamagePacket) {
        val ts = pdp.getTimeStamp()
        val id = pdp.getUuid()
        if (ts < lastProcessedTimestamp) return
        if (ts == lastProcessedTimestamp) {
            val prev = lastProcessedUuid
            if (prev != null && id <= prev) return
        }

        damagedAmount += pdp.getDamage()
        if (ts < targetDamageStarted) {
            targetDamageStarted = ts
        } else if (ts > targetDamageEnded) {
            targetDamageEnded = ts
        }

        lastProcessedTimestamp = ts
        lastProcessedUuid = id
    }

    fun parseBattleTime(): Long {
        return targetDamageEnded - targetDamageStarted
    }
}
