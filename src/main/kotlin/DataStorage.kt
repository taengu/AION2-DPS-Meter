package com.tbread

import com.tbread.entity.ParsedDamagePacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

class DataStorage {
    private val byTargetStorage = ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>>()
    private val byActorStorage = ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>>()
    private val nicknameStorage = ConcurrentHashMap<Int, String>()
    private val summonStorage = HashMap<Int,Int>()
    private val skillCodeData = HashMap<Int,String>()

    @Synchronized
    fun appendDamage(pdp: ParsedDamagePacket) {
        byActorStorage.getOrPut(pdp.getActorId()) { ConcurrentSkipListSet(compareBy<ParsedDamagePacket> { it.getTimeStamp() }.thenBy { it.getUuid() }) }
            .add(pdp)
        byTargetStorage.getOrPut(pdp.getTargetId()) { ConcurrentSkipListSet(compareBy<ParsedDamagePacket> { it.getTimeStamp() }.thenBy { it.getUuid() }) }
            .add(pdp)
    }

    fun appendSummon(summoner:Int,summon:Int){
        summonStorage[summon] = summoner
    }

    fun appendNickname(uid: Int, nickname: String) {
        if (nicknameStorage[uid] != null && nicknameStorage[uid].equals(nickname)) return
        nicknameStorage[uid] = nickname
    }

    @Synchronized
    fun flushDamageStorage() {
        byActorStorage.clear()
        byTargetStorage.clear()
    }

    private fun flushNicknameStorage() {
        nicknameStorage.clear()
    }

    fun getSkillName(skillCode:Int):String{
        return skillCodeData[skillCode]?:skillCode.toString()
    }

    fun getBossModeData(): ConcurrentHashMap<Int, ConcurrentSkipListSet<ParsedDamagePacket>> {
        return byTargetStorage
    }

    fun getNickname():ConcurrentHashMap<Int, String>{
        return nicknameStorage
    }

    fun getSummonData(): HashMap<Int, Int> {
        return summonStorage
    }
}