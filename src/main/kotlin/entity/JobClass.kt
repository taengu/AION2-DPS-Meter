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
        /**
         * Identifies the JobClass associated with a skill code.
         * Only returns a JobClass for skills that are uniquely identifying for a Player Job.
         * Explicitly excludes generic NPC/Spirit skill ranges to prevent false positives.
         */
        fun convertFromSkill(skillCode: Int): JobClass? {
            // 1. PC Elementalist specific 6-digit skills (Earth Chain, etc.)
            // Whitelisted based on the provided PC Elementalist list (100510..103500, etc.)
            if (skillCode in 100510..103500 || skillCode in 109300..109362) {
                return ELEMENTALIST
            }

            // 2. 8-digit Standard Player Skills
            if (skillCode in 10_000_000..19_999_999) {
                val prefix = skillCode / 1_000_000

                // Elementalist (16) has high overlap with Mobs/Summons.
                // We exclude generic combat (sub 00) and summon spells (sub 10-13)
                // to prevent NPCs from being identified as Elementalist players.
                if (prefix == 16) {
                    val sub = (skillCode / 10000) % 100

                    // Known Exclusions:
                    // 00: Spirit generic skills (e.g. 16000000) -> Not a Player
                    // 10..13: Summoning spells (e.g. 1612xxxx) -> Used by NPCs/Mobs
                    // 78: Element Unification (1678xxxx) -> Used by NPCs (Actor 17723)

                    // Whitelist valid PC sub-ranges:
                    // 1..8: Basic Attacks/Spells
                    // 14, 15: Jointstrikes
                    // 17: Sacrifice
                    // 19: Enhance
                    // 21..26: Curses/Masks/Summon Ancient
                    // 30..37: Fusions/Controls
                    // 70..77: Stigmas (excluding 78)
                    // 80: Consecutive Countercurrent

                    val isPcRange = when (sub) {
                        in 1..8 -> true
                        14, 15, 17, 19 -> true
                        in 21..26 -> true
                        in 30..37 -> true
                        in 70..77 -> true
                        80 -> true
                        else -> false
                    }
                    return if (isPcRange) ELEMENTALIST else null
                }

                // General Job Prefix matching for other classes
                val found = entries.find { it.classPrefix == prefix }
                if (found != null) return found
            }

            // 3. Exact match for Basic Skill Codes (Auto-attacks, etc.)
            return entries.find { it.basicSkillCode == skillCode }
        }
    }
}