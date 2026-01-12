package com.tbread.entity

import kotlinx.serialization.Serializable

@Serializable
data class DpsData(val map:MutableMap<String,Double> = mutableMapOf(),var targetName:String? = null)

