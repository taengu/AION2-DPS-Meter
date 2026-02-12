package com.tbread.entity

import com.tbread.DpsCalculator
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class AnalyzedSkill(
    @Transient val skillCode: Int = 0,
    var damageAmount: Int = 0,
    var dotDamageAmount: Int = 0,
    var dotTimes: Int = 0,
    var critTimes: Int = 0,
    var times: Int = 0,
    @Required val skillName:String,
    var backTimes: Int = 0,
    var perfectTimes: Int = 0,
    var doubleTimes: Int = 0,
    var parryTimes: Int = 0,
    var healAmount: Int = 0
) {
    constructor(pdp:ParsedDamagePacket) : this(pdp.getSkillCode1(),0,0,0,0,0,if (pdp.getSkillCode1() == 0) "Effect Damage" else (DpsCalculator.SKILL_MAP[pdp.getSkillCode1()] ?: ""),0,0,0,0)
}
