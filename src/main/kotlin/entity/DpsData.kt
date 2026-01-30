package com.tbread.entity

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@Serializable
data class DpsData(
    val map: MutableMap<Int, PersonalData> = mutableMapOf(),
    @Required var targetName: String = "",
    @Required var targetMode: String = "mostDamage",
    var battleTime: Long = 0L
)
