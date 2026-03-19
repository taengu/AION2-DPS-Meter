package com.tbread.entity

import kotlinx.serialization.Serializable

@Serializable
data class DetailsActorSummary(
    val actorId: Int,
    val nickname: String,
    val job: String = ""
)

@Serializable
data class DetailsTargetSummary(
    val targetId: Int,
    val targetName: String = "",
    val maxHp: Int = 0,
    val battleTime: Long,
    val lastDamageTime: Long,
    val totalDamage: Int,
    val actorDamage: Map<Int, Int>
)

@Serializable
data class DetailsContext(
    val currentTargetId: Int,
    val targets: List<DetailsTargetSummary>,
    val actors: List<DetailsActorSummary>
)

@Serializable
data class DetailSkillEntry(
    val actorId: Int,
    val code: Int,
    val name: String,
    val time: Int,
    val dmg: Int,
    val multiHitCount: Int,
    val multiHitDamage: Int,
    val multiHitHits: Int = 0,
    val minDmg: Int = 0,
    val maxDmg: Int = 0,
    val crit: Int,
    val parry: Int,
    val back: Int,
    val perfect: Int,
    val double: Int,
    val heal: Int,
    val job: String = "",
    val isDot: Boolean = false,
    val hitTimestamps: List<Long> = emptyList()
)

@Serializable
data class TargetDetailsResponse(
    val targetId: Int,
    val maxHp: Int = 0,
    val totalTargetDamage: Int,
    val battleTime: Long,
    val startTime: Long = 0L,
    val skills: List<DetailSkillEntry>
)
