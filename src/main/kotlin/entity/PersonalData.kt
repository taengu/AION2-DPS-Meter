package com.tbread.entity

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PersonalData(
    @Required var job: String = "",
    var dps: Double = 0.0,
    var amount: Double = 0.0,
    @Required var damageContribution: Double = 0.0,
    @Transient val analyzedData: MutableMap<Int, AnalyzedSkill> = mutableMapOf(),
    @Transient var multiHitCount: Int = 0,
    @Transient var multiHitDamage: Int = 0,
    val nickname: String
) {
    private fun addDamage(damage: Double) {
        amount += damage
    }

    fun processPdp(pdp: ParsedDamagePacket) {
        addDamage(pdp.getDamage().toDouble())
        if (pdp.getMultiHitCount() > 0) {
            multiHitCount += pdp.getMultiHitCount()
            multiHitDamage += pdp.getMultiHitDamage()
        }
        if (!analyzedData.containsKey(pdp.getSkillCode1())) {
            val analyzedSkill = AnalyzedSkill(pdp)
            analyzedData[pdp.getSkillCode1()] = analyzedSkill
        }
        val analyzedSkill = analyzedData[pdp.getSkillCode1()]!!
        if (pdp.isDoT()) {
            analyzedSkill.dotTimes ++
            analyzedSkill.dotDamageAmount += pdp.getDamage()
        } else {
            analyzedSkill.times++
            analyzedSkill.damageAmount += pdp.getDamage()
            if (pdp.isCrit()) analyzedSkill.critTimes++
            if (pdp.getSpecials().contains(SpecialDamage.BACK)) analyzedSkill.backTimes++
            if (pdp.getSpecials().contains(SpecialDamage.PARRY)) analyzedSkill.parryTimes++
            if (pdp.getSpecials().contains(SpecialDamage.DOUBLE)) analyzedSkill.doubleTimes++
            if (pdp.getSpecials().contains(SpecialDamage.PERFECT)) analyzedSkill.perfectTimes++
        }
    }
}
