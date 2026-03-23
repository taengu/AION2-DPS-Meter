package com.tbread.entity

import kotlinx.serialization.Serializable

@Serializable
data class FightRecord(
    val id: String,
    val bossName: String,
    val targetId: Int,
    val startTimeMs: Long,
    val durationMs: Long,
    val totalDamage: Int,
    val jobs: List<String>,
    val details: TargetDetailsResponse,
    val actors: List<DetailsActorSummary>,
    val isTrain: Boolean = false,
)

@Serializable
data class FightSummary(
    val id: String,
    val bossName: String,
    val targetId: Int,
    val startTimeMs: Long,
    val durationMs: Long,
    val totalDamage: Int,
    val jobs: List<String>,
    val isTrain: Boolean = false,
    val isLive: Boolean = false,
)
