package com.tbread

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

class DataStorage {
    private val byTargetStorage = ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>>()
    private val byActorStorage = ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>>()
    private val nicknameStorage = ConcurrentHashMap<Int, String>()

    @Synchronized
    fun appendDamage(pdp: ParsedDamagePacket) {
        byActorStorage.getOrPut(pdp.getActorId()) { ConcurrentSkipListSet(compareBy<ParsedDamagePacket> { it.getTimeStamp() }.thenBy { it.getUuid() }) }
            .add(pdp)
        byTargetStorage.getOrPut(pdp.getTargetId()) { ConcurrentSkipListSet(compareBy<ParsedDamagePacket> { it.getTimeStamp() }.thenBy { it.getUuid() }) }
            .add(pdp)
    }

    fun appendNickname(uid: Int, nickname: String) {
        if (nicknameStorage[uid] != null && nicknameStorage[uid].equals(nickname)) return
        println("$uid 할당 닉네임 변경됨 이전: ${nicknameStorage[uid]} 현재: $nickname")
        nicknameStorage[uid] = nickname
    }

    @Synchronized
    private fun flushDamageStorage() {
        byActorStorage.clear()
        byTargetStorage.clear()
    }

    private fun flushNicknameStorage() {
        nicknameStorage.clear()
    }

    fun printDamageByActor() {
        byActorStorage.forEach { (actorId, actorSet) ->
            var damageSum = 0
            val nickname = nicknameStorage[actorId] ?: actorId
            println("공격자: $nickname")
            actorSet.forEach { p ->
                val targetMap = HashMap<Int,ParsedDamagePacket>()
                println("피격자: ${p.getTargetId()}, 스킬: ${p.getSkillCode1()},${p.getSkillCode2()}, 데미지: ${p.getDamage()}")
            }
            val time =
                ((actorSet.last().getTimeStamp() - actorSet.first().getTimeStamp()) / 1000).takeIf { it != 0L } ?: 1
            println("데미지합산: $damageSum, DPS: ${damageSum / time}")
        }
    }

    fun printDamageByTarget(){
        byTargetStorage.forEach { (targetId, targetSet) ->
            println("피격자: $targetId")
            targetSet.forEach { p ->
                println("공격자: ${nicknameStorage[p.getActorId()]}, 스킬: ${p.getSkillCode1()},${p.getSkillCode2()}, 데미지: ${p.getDamage()}")
            }
        }
    }
}