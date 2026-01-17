package com.tbread.entity

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PersonalData(@Required var job: String = "", var dps: Double = 0.0, @Transient var amount: Double = 0.0,@Required var damageContribution:Double = 0.0) {
    fun addDamage(damage: Double) {
        amount += damage
    }
}