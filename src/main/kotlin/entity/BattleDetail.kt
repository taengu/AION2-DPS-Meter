package com.tbread.entity

import kotlinx.serialization.Serializable

@Serializable
data class BattleDetail(
    val skills: Map<Int, AnalyzedSkill> = emptyMap(),
    val multiHitCount: Int = 0,
    val multiHitDamage: Int = 0
)
