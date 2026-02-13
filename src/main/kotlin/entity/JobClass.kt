package com.tbread.entity

enum class JobClass(val className: String, val classPrefix: Int, val basicSkillCode: Int) {
    GLADIATOR("검성", 11, 11020000),
    TEMPLAR("수호성", 12, 12010000),
    RANGER("궁성", 14, 14020000),
    ASSASSIN("살성", 13, 13010000),
    SORCERER("마도성", 15, 15210000), /* 마도 확인 필요함 */
    CLERIC("치유성", 17, 17010000),
    ELEMENTALIST("정령성", 16, 16010000),
    CHANTER("호법성", 18, 18010000);

    companion object{
        fun convertFromSkill(skillCode:Int):JobClass?{
            if (skillCode in 10_000_000..99_999_999) {
                val prefix = skillCode / 1_000_000
                return entries.find { it.classPrefix == prefix }
            }
            return entries.find { it.basicSkillCode == skillCode }
        }
    }
}
