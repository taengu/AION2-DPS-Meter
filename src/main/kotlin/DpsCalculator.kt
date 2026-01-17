package com.tbread

import com.tbread.entity.DpsData
import com.tbread.entity.JobClass
import com.tbread.entity.PersonalData
import com.tbread.entity.TargetInfo
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory

class DpsCalculator(private val dataStorage: DataStorage) {
    private val logger = LoggerFactory.getLogger(DpsCalculator::class.java)

    enum class Mode {
        ALL, BOSS_ONLY
    }

    private val POSSIBLE_OFFSETS: IntArray =
        intArrayOf(
            0, 10, 20, 30, 40, 50,
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

    private val SKILL_CODES: IntArray =
        intArrayOf(9952, 60832, 33872, 19216, 53136, 36176, 5648, 16912).apply { sort() }

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
        val dpsData = DpsData()
        val targetData = decideTarget()
        dpsData.targetName = targetData.second
        val battleTime = targetInfoMap[currentTarget]?.parseBattleTime() ?: 0
        val nicknameData = dataStorage.getNickname()
        var totalDamage = 0.0
        if (battleTime == 0L) {
            return dpsData
        }
        pdpMap[currentTarget]!!.forEach lastPdpLoop@{ pdp ->
            totalDamage += pdp.getDamage()
            val nickname = nicknameData[pdp.getActorId()] ?: nicknameData[dataStorage.getSummonData()[pdp.getActorId()]
                ?: return@lastPdpLoop] ?: return@lastPdpLoop
            if (!dpsData.map.containsKey(nickname)) {
                dpsData.map[nickname] = PersonalData()
            }
            dpsData.map[nickname]!!.addDamage(pdp.getDamage().toDouble())
            if (dpsData.map[nickname]!!.job == "") {
                val origSkillCode = inferOriginalSkillCode(pdp.getSkillCode1()) ?: -1
                val job = JobClass.convertFromSkill(origSkillCode)
                if (job != null) {
                    dpsData.map[nickname]!!.job = job.className
                }
            }
        }
        dpsData.map.forEach { (_, data) ->
            data.dps = data.amount / battleTime * 1000
            data.damageContribution = data.amount / totalDamage * 100
        }
        return dpsData
    }

    private fun decideTarget(): Pair<Int, String> {
        val target: Int = targetInfoMap.maxByOrNull { it.value.damagedAmount() }?.key ?: 0
        var targetName = ""
        currentTarget = target
        //데미지 누계말고도 건수누적방식도 추가하는게 좋을지도? 지금방식은 정복같은데선 타겟변경에 너무 오랜시간이듬
        if (dataStorage.getMobData().containsKey(target)) {
            val mobCode = dataStorage.getMobData()[target]
            if (dataStorage.getMobCodeData().containsKey(mobCode)) {
                targetName = dataStorage.getMobCodeData()[mobCode]!!
            }
        }

        return Pair(target, targetName)
    }

    private fun inferOriginalSkillCode(skillCode: Int): Int? {
        for (offset in POSSIBLE_OFFSETS) {
            val possibleOrigin = skillCode - offset
            if (SKILL_CODES.binarySearch(possibleOrigin) >= 0) {
                logger.debug("추론 성공한 원본 스킬코드 :{}",possibleOrigin)
                return possibleOrigin
            }
        }
        logger.debug("스킬코드 추론 실패")
        return null
    }

    fun resetDataStorage() {
        dataStorage.flushDamageStorage()
        targetInfoMap.clear()
        logger.info("대상 데미지 누적 데이터 초기화 완료")
    }

}