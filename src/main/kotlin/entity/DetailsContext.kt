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
    val crit: Int,
    val parry: Int,
    val back: Int,
    val perfect: Int,
    val double: Int,
    val heal: Int,
    val job: String = "",
    val isDot: Boolean = false
)

@Serializable
data class TargetDetailsResponse(
    val targetId: Int,
    val totalTargetDamage: Int,
    val battleTime: Long,
    val skills: List<DetailSkillEntry>
)
