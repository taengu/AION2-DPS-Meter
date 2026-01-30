package com.tbread.entity

import java.util.UUID

data class TargetInfo(
    private val targetId: Int,
    private var damagedAmount: Int = 0,
    private var targetDamageStarted: Long,
    private var targetDamageEnded: Long,
    private val processedUuid: MutableSet<UUID> = mutableSetOf(),
) {
    fun processedUuid(): MutableSet<UUID> {
        return processedUuid
    }

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

    fun processPdp(pdp:ParsedDamagePacket){
        if (processedUuid.contains(pdp.getUuid())) return
        damagedAmount += pdp.getDamage()
        val ts = pdp.getTimeStamp()
        if (ts < targetDamageStarted){
            targetDamageStarted = ts
        } else if (ts > targetDamageEnded){
            targetDamageEnded = ts
        }
        processedUuid.add(pdp.getUuid())
    }

    fun parseBattleTime():Long{
        return targetDamageEnded - targetDamageStarted
    }
}
