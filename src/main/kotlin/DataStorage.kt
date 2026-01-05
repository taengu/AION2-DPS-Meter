package com.tbread

class DataStorage {
    private val damageStorage = HashMap<String, HashMap<String, MutableSet<ParsedDamagePacket>>>()
    private val nicknameStorage = HashMap<Int,String>()

    fun appendDamage(pdp: ParsedDamagePacket) {
        damageStorage.getOrPut("${pdp.getActorId()}"){ hashMapOf() }
            .getOrPut("${pdp.getTargetId()}"){ hashSetOf() }
            .add(pdp)
    }

    fun appendNickname() {
        //추후 추가
    }

    private fun flushDamageStorage() {
        damageStorage.clear()
    }

    private fun flushNicknameStorage() {
        nicknameStorage.clear()
    }
}