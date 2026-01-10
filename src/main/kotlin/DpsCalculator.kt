package com.tbread

import com.tbread.entity.DpsData
import com.tbread.entity.TargetInfo
import java.util.HashMap
import java.util.UUID

class DpsCalculator(private val dataStorage: DataStorage) {

    enum class Mode {
        ALL, BOSS_ONLY
    }

    private val targetInfoMap = hashMapOf<Int, TargetInfo>()

    private var mode: Mode = Mode.BOSS_ONLY
    private var currentTarget: Int = 0

    fun setMode(mode: Mode) {
        this.mode = mode
        //모드 변경시 이전기록 초기화?
    }

    fun getDps(): DpsData {
        val pdpMap = dataStorage.getBossModeData()

        pdpMap.forEach { (target, data) ->
            var flag = false
            var targetInfo = targetInfoMap[target]
            if (!targetInfoMap.containsKey(target)) {
                flag = true
            }
            data.forEach { pdp ->
                if (flag) {
                    flag = false
                    targetInfo = TargetInfo(target, 0, pdp.getTimeStamp(), pdp.getTimeStamp())
                    targetInfoMap[target] = targetInfo!!
                }
                targetInfo!!.processPdp(pdp)
                //그냥 아래에서 재계산하는거 여기서 해놓고 아래에선 그냥 골라서 주는게 맞는거같은데 나중에 고민할필요있을듯
            }
        }
        decideTarget()
        val battleTime = targetInfoMap[currentTarget]?.parseBattleTime() ?: 0
        val dpsData = DpsData()
        val nicknameData = dataStorage.getNickname()
        if (battleTime == 0L) {
            return dpsData
        }
        pdpMap[currentTarget]!!.forEach lastPdpLoop@{ pdp ->
            val nickname = nicknameData[pdp.getActorId()] ?: return@lastPdpLoop
            dpsData.map.merge(nickname, pdp.getDamage(), Int::plus)
        }
        return dpsData
    }

    private fun decideTarget(): Int {
        val target: Int = targetInfoMap.maxByOrNull { it.value.damagedAmount() }?.key ?: 0
        currentTarget = target
        return target
    }

}