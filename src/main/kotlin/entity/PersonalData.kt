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
    val nickname: String
) {
    private fun addDamage(damage: Double) {
        amount += damage
    }

    fun mergeFrom(other: PersonalData) {
        amount += other.amount
        for ((skillCode, otherSkill) in other.analyzedData) {
            val existing = analyzedData[skillCode]
            if (existing == null) {
                analyzedData[skillCode] = otherSkill
            } else {
                existing.times += otherSkill.times
                existing.damageAmount += otherSkill.damageAmount
                existing.critTimes += otherSkill.critTimes
                existing.backTimes += otherSkill.backTimes
                existing.parryTimes += otherSkill.parryTimes
                existing.doubleTimes += otherSkill.doubleTimes
                existing.perfectTimes += otherSkill.perfectTimes
                existing.dotTimes += otherSkill.dotTimes
                existing.dotDamageAmount += otherSkill.dotDamageAmount
                existing.healAmount += otherSkill.healAmount
            }
        }
    }

    fun processPdp(pdp: ParsedDamagePacket) {
        addDamage((pdp.getDamage() + pdp.getMultiHitDamage()).toDouble())
        if (!analyzedData.containsKey(pdp.getSkillCode1())) {
            val analyzedSkill = AnalyzedSkill(pdp)
            analyzedData[pdp.getSkillCode1()] = analyzedSkill
        }
        val analyzedSkill = analyzedData[pdp.getSkillCode1()]!!
        if (pdp.getHealAmount() > 0) {
            analyzedSkill.healAmount += pdp.getHealAmount()
        }
        if (pdp.isDoT()) {
            analyzedSkill.dotTimes ++
            analyzedSkill.dotDamageAmount += pdp.getDamage() + pdp.getMultiHitDamage()
        } else {
            analyzedSkill.times++
            analyzedSkill.damageAmount += pdp.getDamage() + pdp.getMultiHitDamage()
            if (pdp.isCrit()) analyzedSkill.critTimes++
            if (pdp.getSpecials().contains(SpecialDamage.BACK)) analyzedSkill.backTimes++
            if (pdp.getSpecials().contains(SpecialDamage.PARRY)) analyzedSkill.parryTimes++
            if (pdp.getSpecials().contains(SpecialDamage.DOUBLE)) analyzedSkill.doubleTimes++
            if (pdp.getSpecials().contains(SpecialDamage.PERFECT)) analyzedSkill.perfectTimes++
        }
    }
}
