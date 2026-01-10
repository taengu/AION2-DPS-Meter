package com.tbread

import com.tbread.entity.DpsData
import com.tbread.entity.TargetInfo

class DpsCalculator(private val dataStorage: DataStorage) {

    enum class Mode {
        ALL, BOSS_ONLY
    }

    private val POSSIBLE_OFFSETS: IntArray =
        intArrayOf(
            10, 20, 30, 40, 50,
            120, 130, 140, 150,
            230, 240, 250,
            340, 350,
            450,
            1230, 1240, 1250,
            1340, 1350,
            1450,
            2340, 2350,
            2450,
            3450
        )

    private val SKILL_CODES: IntArray = intArrayOf()

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
            val nickname = nicknameData[pdp.getActorId()] ?: nicknameData[dataStorage.getSummonData()[pdp.getActorId()]
                ?: return@lastPdpLoop] ?: return@lastPdpLoop
            dpsData.map.merge(nickname, pdp.getDamage().toDouble(), Double::plus)
        }
        dpsData.map.forEach { (name, damage) ->
            dpsData.map[name] = damage / battleTime * 1000
        }
        return dpsData
    }

    private fun decideTarget(): Int {
        val target: Int = targetInfoMap.maxByOrNull { it.value.damagedAmount() }?.key ?: 0
        currentTarget = target
        //데미지 누계말고도 건수누적방식도 추가하는게 좋을지도? 지금방식은 정복같은데선 타겟변경에 너무 오랜시간이듬
        return target
    }

    private fun inferOriginalSkillCode(skillCode: Int): Int? {
        for (offset in POSSIBLE_OFFSETS) {
            val possibleOrigin = skillCode - offset
            if (SKILL_CODES.binarySearch(possibleOrigin) >= 0) {
                return possibleOrigin
            }
        }
        return null
    }


}