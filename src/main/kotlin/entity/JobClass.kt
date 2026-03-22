package com.tbread.entity

enum class JobClass(val className: String, val classPrefix: Int, val basicSkillCode: Int) {
    GLADIATOR("검성", 11, 11020000),
    TEMPLAR("수호성", 12, 12010000),
    RANGER("궁성", 14, 14020000),
    ASSASSIN("살성", 13, 13010000),
    SORCERER("마도성", 15, 15210000),
    CLERIC("치유성", 17, 17010000),
    ELEMENTALIST("정령성", 16, 16010000),
    CHANTER("호법성", 18, 18010000);

    companion object {
        fun convertFromSkill(skillCode: Int): JobClass? {
            // 1. PC Elementalist specific 6-digit skills (Earth Chain, etc.)
            if (skillCode in 100510..103500 || skillCode in 109300..109362) {
                return ELEMENTALIST
            }

            // 2. 8-digit Standard Player Skills
            if (skillCode in 10_000_000..19_999_999) {
                val prefix = skillCode / 1_000_000
                val sub = (skillCode / 10000) % 100

                // GLOBAL FILTER: Exclude generic mob skills (sub 00) for ALL classes.
                // Exception for Elementalist Spirit Commands (160011xx..160013xx)
                if (sub == 0) {
                    if (prefix == 16) {
                        val commandRange = (skillCode / 100) % 100
                        if (commandRange in 11..13) return ELEMENTALIST
                    }
                    return null
                }

                // Elementalist (16) Strict Whitelist
                if (prefix == 16) {
                    // Valid PC Sub-ranges (Revised based on Spirit usage logs):
                    // 1..8: Basic Attacks/Spells (Safe?)
                    // 14, 15: Jointstrikes (Safe)
                    // 17: Sacrifice (Safe)
                    // 19: Enhance (Safe)
                    // 21..26: Curses/Masks/Summon Ancient (Safe)
                    // 30, 31, 32, 34, 35, 36, 37: Fusions/Controls (EXCLUDE 33: Dimensional Control used by Spirit)
                    // 70..76, 80: Stigmas (EXCLUDE 77: Communion, 78: Unification used by Spirit/NPC)

                    val isPcRange = when (sub) {
                        in 1..8 -> true
                        14, 15, 17, 19 -> true
                        in 21..26 -> true
                        // Exclude 33 (Dimensional Control)
                        30, 31, 32, 34, 35, 36, 37 -> true
                        // Exclude 77 (Communion), 78 (Unification)
                        in 70..76 -> true
                        80 -> true
                        else -> false
                    }
                    return if (isPcRange) ELEMENTALIST else null
                }

                // For other classes, allow if prefix matches (and sub != 0)
                val found = entries.find { it.classPrefix == prefix }
                if (found != null) return found
            }

            return entries.find { it.basicSkillCode == skillCode }
        }
    }
}