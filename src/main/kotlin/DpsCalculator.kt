package com.tbread

import com.tbread.entity.DetailSkillEntry
import com.tbread.entity.DetailsActorSummary
import com.tbread.entity.DetailsContext
import com.tbread.entity.DetailsTargetSummary
import com.tbread.entity.DpsData
import com.tbread.entity.JobClass
import com.tbread.entity.ParsedDamagePacket
import com.tbread.entity.PersonalData
import com.tbread.entity.SpecialDamage
import com.tbread.entity.TargetDetailsResponse
import com.tbread.entity.TargetInfo
import com.tbread.logging.DebugLogWriter
import com.tbread.packet.LocalPlayer
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt
import java.util.UUID

class DpsCalculator(private val dataStorage: DataStorage) {
    private val logger = LoggerFactory.getLogger(DpsCalculator::class.java)

    private data class ActorMetaBuilder(
        var nickname: String,
        var job: String = ""
    )

    enum class Mode {
        ALL, BOSS_ONLY
    }

    enum class TargetSelectionMode(val id: String) {
        MOST_DAMAGE("mostDamage"),
        MOST_RECENT("mostRecent"),
        LAST_HIT_BY_ME("lastHitByMe"),
        ALL_TARGETS("allTargets");

        companion object {
            fun fromId(id: String?): TargetSelectionMode {
                return entries.firstOrNull { it.id == id } ?: MOST_DAMAGE
            }
        }
    }

    data class TargetDecision(
        val targetIds: Set<Int>,
        val targetName: String,
        val mode: TargetSelectionMode,
        val trackingTargetId: Int,
    )

    companion object {
        val POSSIBLE_OFFSETS: IntArray =
            intArrayOf(
                0, 10, 20, 30, 40, 50,
                120, 130, 140, 150,
                230, 240, 250,
                340, 350,
                450,
                1230, 1240, 1250,
                1340, 1350,
                1450,
                2340, 2350,
                2450,
                3450
            )

        val SKILL_MAP = mapOf(
            /*
        Cleric
         */
            17010000 to "Earth's Retribution",
            17020000 to "Thunder and Lightning",
            17030000 to "Discharge",
            17040000 to "Judgment Thunder",
            17050000 to "Divine Punishment",
            17040007 to "Divine Punishment(1회 추Thorn전)",
            17080000 to "Debilitating Mark",
            17070000 to "Chain of Torment",
            17150000 to "Divine Aura",
            17150002 to "Divine Aura",
            17090000 to "Light of Regeneration",
            17350000 to "Condemnation",
            17100000 to "Healing Light",
            17120000 to "Radiant Recovery",
            17120001 to "Radiant Recovery MAX",
            17370000 to "Lightning Strike Scattershot",
            17060000 to "Bolt",
            17060001 to "Bolt Level 1",
            17060002 to "Bolt Level 2",
            17060003 to "Bolt MAX",
            17240000 to "Defiance",
            17320000 to "Blessing of Regeneration",
            17280000 to "Power Burst",
            17290000 to "Absolution",
            17160000 to "Healing Aura",
            17430000 to "Prayer of Amplification",
            17390000 to "Summon Resurrection",
            17400000 to "Earth Punishment",
            17270000 to "Salvation",
            17190000 to "Root",
            17410000 to "Light of Protection",
            17420000 to "Yustiel's Power",
            17300000 to "Voice of Doom",
            17700000 to "Assault Mark",
            17710000 to "Warm Benediction",
            17720000 to "Empyrean Lords' Benediction",
            17730001 to "Empyrean Lord's Grace",
            17730002 to "Empyrean Lord's Grace (Prayer of Amplification)",
            17740000 to "Healing Enhancement",
            17750000 to "Immortal Veil",
            17760000 to "Heal Block",
            17770000 to "Prayer of Concentration",
            17780000 to "Earth's Grace",
            17790000 to "Survival Willpower",
            17800000 to "Radiant Benediction",

            /*
        Gladiator
         */
            11020000 to "Keen Strike",
            11030000 to "Rupture Strike",
            11040000 to "Wrathful Strike",
            11180000 to "Reckless Strike",
            11010000 to "Rending Blow",
            11420000 to "Smashing Blow",
            11190000 to "Leaping Slam",
            11290000 to "유린의검",
            11290001 to "Mocking Blade",
            11170000 to "Overhead Slam",
            11440000 to "Upward Strike",
            11280000 to "Sword Aura Rampage",
            11200000 to "Ankle Slice",
            11210000 to "Ankle Smash",
            11050000 to "Crushing Wave",
            11060000 to "Frenzied Wave",
            11360000 to "Rush Strike",
            11360001 to "Rush Strike - Max",
            11300000 to "Aerial Snare",
            11370000 to "Forced Fall",
            11100000 to "Ruinous Blow",
            11100001 to "Ruinous Blow - Lv. 1",
            11100002 to "Ruinous Blow - MAX",
            11260000 to "Defiance",
            11320000 to "Wrath Burst",
            11240000 to "Wrath Wave",
            11400000 to "Lunge Stance",
            11250000 to "Zikel's Blessing",
            11110000 to "Focused Block",
            11130000 to "Armor of Balance",
            11080000 to "Blade Toss",
            11090000 to "Blade Dance",
            11380000 to "Tenaciousness",
            11340000 to "Lifestealing Blade",
            11390000 to "Rage Burst",
            11410000 to "Wave Armor",
            11430000 to "Forced Restraint",
            11700000 to "Assault Strike",
            11170037 to "Overhead Slam",
            11710000 to "Survival Stance",
            11720000 to "Protection Armor",
            11730000 to "Blood Absorption",
            11740000 to "Identify Weakness",
            11750000 to "Attack Preparation",
            11760000 to "Impact Hit",
            11770000 to "Destructive Impulse",
            11770007 to "Destruction",
            11780000 to "Experienced Counterstrike",
            11790000 to "Survival Willpower",
            11800008 to "Murderous Burst",

            /*
        Elementalist
         */
            16010000 to "Cold Shock",
            16020000 to "Vacuum Explosion",
            16030000 to "Earth Tremor",
            16040000 to "Combustion",
            16050000 to "Ashy Call",
            16100004 to "Fire Spirit: Leaping Slam",
            16110004 to "Water Spirit: Discharge Chill",
            16110005 to "Water Spirit: Enhanced Discharge Chill",
            16140000 to "Jointstrike: Curse",
            16001104 to "Fire Spirit: Flame Explosion",
            16001108 to "Water Spirit: Water Bomb",
            16001110 to "Wind Spirit: Malicious Whirlwind",
            16001113 to "Earth Spirit: Headbutt",
            16001117 to "Ancient Spirit: Destruction",
            16340000 to "Rapid Scattershot",
            16130001 to "Earth Spirit: Tackle",
            16130005 to "Earth Spirit: Enhanced Tackle",
            16330000 to "Dimensional Control",
            16120002 to "Wind Spirit: Falling Wind",
            16120005 to "Wind Spirit: Enhanced Falling Wind",
            16070000 to "Soul's Cry",
            16300000 to "Elemental Fusion",
            16300001 to "Elemental Fusion - Lv.1",
            16300002 to "Elemental Fusion - Lv.2",
            16300003 to "Elemental Fusion - Max",
            16200000 to "Defiance",
            16210000 to "Curse of Despair",
            16710000 to "Spirit Strike",
            16720000 to "Spirit Protection",
            16730001 to "Spirit's Descent",
            16740001 to "Corrode",
            16750000 to "Spirit Revitalization",
            16760000 to "Mental Focus",
            16800001 to "Consecutive Countercurrent",
            16770000 to "Spirit Communion",
            16790000 to "Revitalization Contract",
            16780000 to "Element Unification",
            16240000 to "Jointstrike: Destructive Attack",
            16240001 to "Jointstrike: Destructive Attack - Lv.1",
            16240002 to "Jointstrike: Destructive Attack - Lv.2",
            16240003 to "Jointstrike: Destructive Attack - Max",
            16001301 to "Fire Spirit: Summon Meteor",
            16001305 to "Water Spirit: Glacier Harpoon",
            16001309 to "Wind Spirit: Storm",
            16001313 to "Earth Spirit: Colossal Stalk",
            16001317 to "Ancient Spirit: Magnetic Storm",
            16190000 to "Enhance: Spirit's Benediction",
            16370000 to "Flame Blessing",
            16250000 to "Summon: Ancient Spirit",
            16250001 to "Ancient Spirit: Plasma Cannon",
            16150000 to "Jointstrike: Corrode",
            16151100 to "Fire Spirit: Rage Burst",
            16152100 to "Water Spirit: Ice Chain",
            16154100 to "Wind Spirit: Gale",
            16153100 to "Earth Spirit: Taunt",
            16360000 to "Kaisinel's Power",
            16060000 to "Siphon",
            16080000 to "Cry of Terror",
            16220000 to "Cursed Cloud",
            16230000 to "Seize Magic",
            16260000 to "Magic Block",
            16700000 to "Assault Terror",
            100055 to "기본Attack (고대 정령)",
            100051 to "기본Attack (고대 정령)",
            100014 to "기본Attack (Fire Spirit)",
            100024 to "기본Attack (Water Spirit)",
            100032 to "기본Attack (Wind Spirit)",
            100045 to "기본Attack (Earth Spirit)",
            100041 to "기본Attack (Earth Spirit)",
            100018 to "기본Attack (Fire Spirit)",
            100028 to "기본Attack (Water Spirit)",


            /*
        Chanter
         */
            18010000 to "Onslaught",
            18020000 to "Resonance Crush",
            18030000 to "Bolt Crush",
            18370000 to "Storm Chain",
            18040000 to "Incandescent Blow",
            18050000 to "Bursting Blow",
            18090000 to "Rushing Smash",
            18090001 to "Rushing Smash - Lv. 1",
            18090002 to "Rushing Smash - Lv. 2",
            18090003 to "Rushing Smash - MAX",
            18060000 to "Impactful Crush",
            18070000 to "Crushing Blow",
            18100000 to "Dark Crush",
            18400000 to "Piercing Strike",
            18300000 to "Gust Rampage",
            18150000 to "Heat Wave Blow",
            18120000 to "Recuperation",
            18210000 to "Tremor Crush",
            18390000 to "Surging Strike",
            18080000 to "Wave Blow",
            18080001 to "Wave Blow",
            18290000 to "Spinning Strike",
            18200000 to "Defiance",
            18410000 to "Crushing Strike",
            18220000 to "Obliterate",
            18190000 to "Undefeated Mantra",
            18140000 to "Focused Defense",
            18160000 to "Sprint Mantra",
            18130000 to "Fracturing Blow",
            18330000 to "Marchutan's Wrath",
            18240000 to "Impeding Authority",
            18230000 to "Ensnaring Mark",
            18170000 to "Healing Touch",
            18250000 to "Power of the Storm",
            18420000 to "Guardian Blessing",
            18700000 to "Assault Shock",
            18710000 to "Blessing of Life",
            18720000 to "Crossguard",
            18730000 to "Protection Circle",
            18740000 to "Inspiring Spell",
            18750000 to "Attack Preparation",
            18760000 to "Impact Hit",
            18770000 to "Raging Spell",
            18780000 to "Earth's Promise",
            18790000 to "Survival Willpower",
            18800001 to "Wind's Promise",

            /*
        Ranger
         */
            14020000 to "Snipe",
            14030000 to "Rapid Fire",
            14040000 to "Spiral Arrow",
            14100000 to "Tempest Arrow",
            14340000 to "Tempest Shot",
            14130000 to "Snare Shot",
            14140000 to "Shackling Arrow",
            14090000 to "Marking Shot",
            14050000 to "Drill Dart",
            14330000 to "Arrow Scattershot",
            14110000 to "Gale Arrow",
            14110001 to "Gale Arrow - Lv.1",
            14110002 to "Gale Arrow - Lv.2",
            14110003 to "Gale Arrow - Max",
            14170001 to "Explosion Trap",
            14080000 to "Burst Arrow",
            14070000 to "Suppressing Arrow",
            14010000 to "Deadshot",
            14010001 to "Deadshot - Lv.1",
            14010002 to "Deadshot - Lv.2",
            14010003 to "Deadshot - Max",
            14260000 to "Defiance",
            14370000 to "Lightning Arrow",
            14720007 to "Concentrated Fire",
            14770007 to "Rooting Eye",
            14780008 to "Melee Fire",
            14800007 to "Hunter's Soul",
            14270000 to "Arrow Storm",
            14120000 to "Ambush Kick",
            14180000 to "Ensnaring Trap",
            14150000 to "Sleep Arrow",
            14160000 to "Sealing Arrow",
            14350000 to "Mother Nature's Breath",
            14060000 to "Griffon Arrow",
            14360000 to "Explosive Arrow",
            14700000 to "Assault Smite",
            14200000 to "Eye of Detection",

            /*
        Sorcerer
         */
            15210000 to "Flame Arrow",
            15030000 to "Burst",
            15250000 to "Pyroclasm",
            15090000 to "Ice Chain",
            15100000 to "Cold Wave",
            15040000 to "Firestorm",
            15280002 to "Bittercold Wind",
            15280003 to "Bittercold Wind - 첫타",
            15050000 to "Blaze",
            15050007 to "Blaze (Delayed Damage)",
            15010000 to "Flame Scattershot",
            15150000 to "Frost",
            15110000 to "Winter's Shackles",
            15330000 to "Winter's Illusion",
            15220000 to "Frost Burst",
            15310000 to "Wish of Concentration",
            15060000 to "Hellfire",
            15060001 to "Hellfire - Level 1",
            15060002 to "Hellfire - Level 2",
            15060003 to "Hellfire - Max",
            15060008 to "Hellfire (Flame Zone)",
            15240000 to "Defiance",
            15340000 to "Curse: Old Tree",
            15710008 to "Fire Mark",
            15720000 to "Robe of Earth",
            15730007 to "Cold Snap",
            15740000 to "Robe of Flame",
            15760000 to "Absorb Essence",
            15770000 to "Grace of Resistance",
            15750000 to "Robe of Cold",
            15780000 to "Grace of Enhancement",
            15790000 to "Revitalization Contract",
            15800007 to "Vitality Evaporation",
            15360000 to "Divine Burst",
            15160000 to "Steel Barrier",
            15400000 to "Element Enhancement",
            15140000 to "Curse: Tree",
            15230000 to "Arctic Armor",
            15130000 to "Soul Freeze",
            15200000 to "Cold Storm",
            15390000 to "Fire Wall",
            15390002 to "Fire Wall - Embers",
            15390008 to "Fire Wall - Embers 추가피해",
            15300000 to "Lumiel's Space",
            15300001 to "The Depths",
            15320007 to "Delayed Explosion",
            15120000 to "Glacial Smite",
            15700000 to "Assault Bombardment",

            /*
        Templar
         */
            12010000 to "Vicious Strike",
            12020000 to "Decisive Strike",
            12030000 to "Desperate Strike",
            12440000 to "Threatening Blow",
            12040000 to "Pummel",
            12060000 to "Punishing Strike",
            12060005 to "Punishing Strike (추가 시전)",
            12130000 to "Poach",
            12100000 to "Shield Smite",
            12240000 to "Judgment",
            12240009 to "Judgment (추가 피해)",
            12340000 to "Flash Rampage",
            12270000 to "Debilitating Smash",
            12350000 to "Warding Strike",
            12430000 to "Shield Rush",
            12430001 to "Shield Rush - Max",
            12300000 to "Annihilate",
            12090000 to "Punishment",
            12090001 to "Punishment - Level 1",
            12090002 to "Punishment - Level 2",
            12090003 to "Punishment - Max",
            12260000 to "Defiance",
            12330000 to "Capture",
            12710000 to "Enhance Health",
            12720000 to "Warding Shield",
            12730001 to "Punishing Benediction",
            12740000 to "Ironclad Defense",
            12750000 to "Guarding Seal",
            12760000 to "Impact Hit",
            12770000 to "Insulting Roar",
            12780000 to "Fury",
            12790000 to "Survival Willpower",
            12800000 to "Block Pain",
            12310000 to "Empyrean Lord's Punishment",
            12320000 to "Nezekan's Shield",
            12110000 to "Shield of Protection",
            12120000 to "Taunt",
            12200000 to "Armor of Balance",
            12190000 to "Second Skin",
            12070000 to "Doom Shield",
            12230000 to "Noble Armor",
            12410000 to "Executing Blade",
            12250000 to "Comrade in Arms",
            12220000 to "Grapple",
            12420000 to "Blade Storm",
            12700000 to "Assault Fury",

            /*
        Assassin
         */
            13010000 to "Quick Slice",
            13030000 to "Breaking Slice",
            13040000 to "Swift Slice",
            13100000 to "Savage Roar",
            13110000 to "Savage Back Kick",
            13120000 to "Savage Smash",
            13070000 to "Shadowstrike",
            13060000 to "Ambush",
            13350000 to "Heart Gore",
            13340000 to "Storm Rampage",
            13210000 to "Whirlwind Slice",
            13050000 to "Flash Slice",
            13360000 to "Infiltrate",
            13380000 to "Dark Strike",
            13220000 to "Shadow Fall",
            13130000 to "Insignia Explosion",
            13260000 to "Defiance",
            13330000 to "Storm Slice",
            13710000 to "Heightened Sixth Sense",
            13720000 to "Exploit Weakness",
            13720005 to "Doppelganger Attack",
            13720006 to "Doppelganger Attack",
            13720007 to "Doppelganger Attack",
            13720008 to "Doppelganger Attack",
            13720009 to "Doppelganger Attack",
            13730000 to "Apply Poison",
            13730007 to "Poison",
            13740000 to "Rear Smite",
            13750000 to "Assault Stance",
            13760000 to "Impact Hit",
            13770000 to "Ambush Stance",
            13780000 to "Defense Break",
            13790000 to "Revitalization Contract",
            13800007 to "Determination",
            13270000 to "Savage Fang",
            13390000 to "Swift Contract",
            13250000 to "Smoke Bomb",
            13080000 to "Evasion Stance",
            13280000 to "Spiral Slice",
            13180000 to "Shadow Walk",
            13020000 to "Throw Shadowblade",
            13090000 to "Shadowblade Pursuit",
            13300000 to "Triniel's Dagger",
            13230000 to "Aerial Bind",
            13240000 to "Aerial Slaughter",
            13310000 to "Illusive Clone",
            13370000 to "Evasion Contract",
            13700000 to "Assault Ambush"

        )

        val SKILL_CODES: IntArray =
            intArrayOf(
                100051,
                100055,
                11010000,
                11020000,
                11030000,
                11040000,
                11050000,
                11060000,
                11080000,
                11090000,
                11100000,
                11100001,
                11100002,
                11110000,
                11130000,
                11170000,
                11170037,
                11180000,
                11190000,
                11200000,
                11210000,
                11240000,
                11250000,
                11260000,
                11280000,
                11290000,
                11290001,
                11300000,
                11320000,
                11340000,
                11360000,
                11360001,
                11370000,
                11380000,
                11390000,
                11400000,
                11410000,
                11420000,
                11430000,
                11440000,
                11700000,
                11710000,
                11720000,
                11730000,
                11740000,
                11750000,
                11760000,
                11770000,
                11770007,
                11780000,
                11790000,
                11800000,
                11800008,
                12010000,
                12020000,
                12030000,
                12040000,
                12060000,
                12060005,
                12070000,
                12090000,
                12090001,
                12090002,
                12090003,
                12100000,
                12110000,
                12120000,
                12130000,
                12190000,
                12200000,
                12220000,
                12230000,
                12240000,
                12240009,
                12250000,
                12260000,
                12270000,
                12300000,
                12310000,
                12320000,
                12330000,
                12340000,
                12350000,
                12410000,
                12420000,
                12430000,
                12430001,
                12440000,
                12700000,
                12710000,
                12720000,
                12730000,
                12730001,
                12740000,
                12750000,
                12760000,
                12770000,
                12780000,
                12790000,
                12800000,
                13010000,
                13020000,
                13030000,
                13040000,
                13050000,
                13060000,
                13070000,
                13080000,
                13090000,
                13100000,
                13110000,
                13120000,
                13130000,
                13180000,
                13210000,
                13220000,
                13230000,
                13240000,
                13250000,
                13260000,
                13270000,
                13280000,
                13300000,
                13310000,
                13330000,
                13340000,
                13350000,
                13360000,
                13370000,
                13380000,
                13390000,
                13700000,
                13710000,
                13720000,
                13720005,
                13720006,
                13720007,
                13720008,
                13720009,
                13730000,
                13730007,
                13740000,
                13750000,
                13760000,
                13770000,
                13780000,
                13790000,
                13800000,
                13800007,
                14010000,
                14010001,
                14010002,
                14010003,
                14020000,
                14030000,
                14040000,
                14050000,
                14060000,
                14070000,
                14080000,
                14090000,
                14100000,
                14110000,
                14110001,
                14110002,
                14110003,
                14120000,
                14130000,
                14140000,
                14150000,
                14160000,
                14170000,
                14170001,
                14180000,
                14200000,
                14260000,
                14270000,
                14330000,
                14340000,
                14350000,
                14360000,
                14370000,
                14700000,
                14720000,
                14720007,
                14770000,
                14770007,
                14780000,
                14780008,
                14800000,
                14800007,
                15010000,
                15030000,
                15040000,
                15050000,
                15050007,
                15060000,
                15060001,
                15060002,
                15060003,
                15060008,
                15090000,
                15100000,
                15110000,
                15120000,
                15130000,
                15140000,
                15150000,
                15160000,
                15200000,
                15210000,
                15220000,
                15230000,
                15240000,
                15250000,
                15280000,
                15280002,
                15280003,
                15300000,
                15300001,
                15310000,
                15320000,
                15320007,
                15330000,
                15340000,
                15360000,
                15390000,
                15390002,
                15390008,
                15400000,
                15700000,
                15710000,
                15710008,
                15720000,
                15730000,
                15730007,
                15740000,
                15750000,
                15760000,
                15770000,
                15780000,
                15790000,
                15800000,
                15800007,
                16000000,
                16001104,
                16001108,
                16001110,
                16001113,
                16001117,
                16001301,
                16001305,
                16001309,
                16001313,
                16001317,
                16010000,
                16020000,
                16030000,
                16040000,
                16050000,
                16060000,
                16070000,
                16080000,
                16100000,
                16100004,
                16110000,
                16110004,
                16110005,
                16120000,
                16120002,
                16120005,
                16130000,
                16130001,
                16130005,
                16140000,
                16150000,
                16151100,
                16152100,
                16153100,
                16154100,
                16190000,
                16200000,
                16210000,
                16220000,
                16230000,
                16240000,
                16240001,
                16240002,
                16240003,
                16250000,
                16250001,
                16260000,
                16300000,
                16300001,
                16300002,
                16300003,
                16330000,
                16340000,
                16360000,
                16370000,
                16700000,
                16710000,
                16720000,
                16730000,
                16730001,
                16740000,
                16740001,
                16750000,
                16760000,
                16770000,
                16780000,
                16790000,
                16800000,
                16800001,
                17010000,
                17020000,
                17030000,
                17040000,
                17040007,
                17050000,
                17060000,
                17060001,
                17060002,
                17060003,
                17070000,
                17080000,
                17090000,
                17100000,
                17120000,
                17120001,
                17150000,
                17150002,
                17160000,
                17190000,
                17240000,
                17270000,
                17280000,
                17290000,
                17300000,
                17320000,
                17350000,
                17370000,
                17390000,
                17400000,
                17410000,
                17420000,
                17430000,
                17700000,
                17710000,
                17720000,
                17730000,
                17730001,
                17730002,
                17740000,
                17750000,
                17760000,
                17770000,
                17780000,
                17790000,
                17800000,
                18010000,
                18020000,
                18030000,
                18040000,
                18050000,
                18060000,
                18070000,
                18080000,
                18080001,
                18090000,
                18090001,
                18090002,
                18090003,
                18100000,
                18120000,
                18130000,
                18140000,
                18150000,
                18160000,
                18170000,
                18190000,
                18200000,
                18210000,
                18220000,
                18230000,
                18240000,
                18250000,
                18290000,
                18300000,
                18330000,
                18370000,
                18390000,
                18400000,
                18410000,
                18420000,
                18700000,
                18710000,
                18720000,
                18730000,
                18740000,
                18750000,
                18760000,
                18770000,
                18780000,
                18790000,
                18800000,
                18800001
            ).apply { sort() }
    }

    private val targetInfoMap = hashMapOf<Int, TargetInfo>()

    private var mode: Mode = Mode.BOSS_ONLY
    private var currentTarget: Int = 0
    private var lastDpsSnapshot: DpsData? = null
    @Volatile private var targetSelectionMode: TargetSelectionMode = TargetSelectionMode.LAST_HIT_BY_ME
    private val targetSwitchStaleMs = 10_000L
    private var lastLocalHitTime: Long = -1L
    private val unknownPlayerTargetWindowMs = 120_000L
    private val allTargetsWindowMs = 60_000L

    fun setMode(mode: Mode) {
        this.mode = mode
        //모드 변경시 이전기록 초기화?
    }

    fun setTargetSelectionModeById(id: String?) {
        targetSelectionMode = TargetSelectionMode.fromId(id)
    }

    fun getDps(): DpsData {
        val pdpMap = dataStorage.getBossModeData()

        pdpMap.forEach { (target, data) ->
            data.forEach { pdp ->
                val targetInfo = targetInfoMap.getOrPut(target) {
                    TargetInfo(target, 0, pdp.getTimeStamp(), pdp.getTimeStamp())
                }
                targetInfo.processPdp(pdp)
                //그냥 아래에서 재계산하는거 여기서 해놓고 아래에선 그냥 골라서 주는게 맞는거같은데 나중에 고민할필요있을듯
            }
        }
        val dpsData = DpsData()
        val targetDecision = decideTarget()
        dpsData.targetName = targetDecision.targetName
        dpsData.targetMode = targetDecision.mode.id

        currentTarget = targetDecision.trackingTargetId
        dpsData.targetId = currentTarget
        dataStorage.setCurrentTarget(currentTarget)

        val localActorsForBattleTime =
            if (targetDecision.mode == TargetSelectionMode.LAST_HIT_BY_ME) resolveConfirmedLocalActorIds() else null
        val isRecentCombined = targetDecision.mode == TargetSelectionMode.LAST_HIT_BY_ME &&
            targetDecision.trackingTargetId == 0 &&
            targetDecision.targetIds.isNotEmpty()
        val battleTime = when {
            isRecentCombined -> parseRecentBattleTime(targetDecision.targetIds, unknownPlayerTargetWindowMs)
            localActorsForBattleTime != null && targetDecision.mode == TargetSelectionMode.LAST_HIT_BY_ME ->
                parseActorBattleTimeForTarget(localActorsForBattleTime, currentTarget)
            targetDecision.mode == TargetSelectionMode.ALL_TARGETS ->
                parseRecentBattleTime(targetDecision.targetIds, allTargetsWindowMs)
            else -> targetInfoMap[currentTarget]?.parseBattleTime() ?: 0
        }
        val nicknameData = dataStorage.getNickname()
        var totalDamage = 0.0
        if (battleTime == 0L) {
            val snapshot = lastDpsSnapshot
            if (snapshot != null) {
                refreshNicknameSnapshot(snapshot, nicknameData)
                snapshot.targetName = dpsData.targetName
                snapshot.targetMode = dpsData.targetMode
                snapshot.targetId = dpsData.targetId
                snapshot.battleTime = dpsData.battleTime
                return snapshot
            }
            return dpsData
        }
        val pdps = when {
            isRecentCombined -> collectRecentPdp(targetDecision.targetIds, unknownPlayerTargetWindowMs)
            targetDecision.mode == TargetSelectionMode.ALL_TARGETS ->
                collectRecentPdp(targetDecision.targetIds, allTargetsWindowMs)
            else -> pdpMap[currentTarget]?.toList() ?: return dpsData
        }
        pdps.forEach { pdp ->
            totalDamage += pdp.getDamage()
            val uid = dataStorage.getSummonData()[pdp.getActorId()] ?: pdp.getActorId()
            val nickname = resolveNickname(uid, nicknameData)
            val existing = dpsData.map[uid]
            if (existing == null) {
                dpsData.map[uid] = PersonalData(nickname = nickname)
            } else if (existing.nickname != nickname) {
                dpsData.map[uid] = existing.copy(nickname = nickname)
            }
            pdp.setSkillCode(
                inferOriginalSkillCode(
                    pdp.getSkillCode1(),
                    pdp.getTargetId(),
                    pdp.getActorId(),
                    pdp.getDamage(),
                    pdp.getHexPayload()
                ) ?: pdp.getSkillCode1()
            )
            dpsData.map[uid]!!.processPdp(pdp)
            if (dpsData.map[uid]!!.job == "") {
                val origSkillCode = inferOriginalSkillCode(
                    pdp.getSkillCode1(),
                    pdp.getTargetId(),
                    pdp.getActorId(),
                    pdp.getDamage(),
                    pdp.getHexPayload()
                ) ?: -1
                val job = JobClass.convertFromSkill(origSkillCode)
                if (job != null) {
                    dpsData.map[uid]!!.job = job.className
                }
            }
        }
        val iterator = dpsData.map.iterator()
        while (iterator.hasNext()) {
            val (_, data) = iterator.next()
            if (data.job == "") {
                iterator.remove()
            } else {
                data.dps = data.amount / battleTime * 1000
                data.damageContribution = data.amount / totalDamage * 100
            }
        }
        dpsData.battleTime = battleTime
        if (dpsData.map.isNotEmpty()) {
            lastDpsSnapshot = dpsData
        }
        return dpsData
    }

    private fun resolveNickname(uid: Int, nicknameData: Map<Int, String>): String {
        val summonData = dataStorage.getSummonData()
        return nicknameData[uid]
            ?: nicknameData[summonData[uid] ?: uid]
            ?: uid.toString()
    }

    private fun refreshNicknameSnapshot(snapshot: DpsData, nicknameData: Map<Int, String>) {
        snapshot.map.entries.toList().forEach { (uid, data) ->
            val nickname = resolveNickname(uid, nicknameData)
            if (data.nickname != nickname) {
                snapshot.map[uid] = data.copy(nickname = nickname)
            }
        }
    }

    fun getDetailsContext(): DetailsContext {
        val pdpMap = dataStorage.getBossModeData()
        val nicknameData = dataStorage.getNickname()
        val summonData = dataStorage.getSummonData()

        val actorMeta = mutableMapOf<Int, ActorMetaBuilder>()
        val targets = mutableListOf<DetailsTargetSummary>()

        pdpMap.forEach { (targetId, pdps) ->
            var totalDamage = 0
            val actorDamage = mutableMapOf<Int, Int>()

            pdps.forEach { pdp ->
                val uid = summonData[pdp.getActorId()] ?: pdp.getActorId()
                val damage = pdp.getDamage()
                totalDamage += damage
                actorDamage[uid] = (actorDamage[uid] ?: 0) + damage

                val meta = actorMeta.getOrPut(uid) { ActorMetaBuilder(resolveNickname(uid, nicknameData)) }
                if (meta.job.isEmpty()) {
                    val inferredCode = inferOriginalSkillCode(
                        pdp.getSkillCode1(),
                        pdp.getTargetId(),
                        pdp.getActorId(),
                        pdp.getDamage(),
                        pdp.getHexPayload()
                    ) ?: -1
                    val job = JobClass.convertFromSkill(inferredCode)
                    if (job != null) {
                        meta.job = job.className
                    }
                }
            }

            val info = targetInfoMap[targetId]
            targets.add(
                DetailsTargetSummary(
                    targetId = targetId,
                    battleTime = info?.parseBattleTime() ?: 0L,
                    lastDamageTime = info?.lastDamageTime() ?: 0L,
                    totalDamage = totalDamage,
                    actorDamage = actorDamage
                )
            )
        }

        val actors = actorMeta.map { (id, meta) ->
            DetailsActorSummary(actorId = id, nickname = meta.nickname, job = meta.job)
        }

        return DetailsContext(currentTargetId = currentTarget, targets = targets, actors = actors)
    }

    fun getTargetDetails(targetId: Int, actorIds: Set<Int>?): TargetDetailsResponse {
        val pdps = dataStorage.getBossModeData()[targetId] ?: return TargetDetailsResponse(
            targetId = targetId,
            totalTargetDamage = 0,
            battleTime = 0L,
            skills = emptyList()
        )
        val summonData = dataStorage.getSummonData()
        val actorJobs = mutableMapOf<Int, String>()
        val skillMap = mutableMapOf<String, DetailSkillEntry>()
        var totalTargetDamage = 0
        var startTime: Long? = null
        var endTime: Long? = null

        pdps.forEach { pdp ->
            val uid = summonData[pdp.getActorId()] ?: pdp.getActorId()
            val damage = pdp.getDamage()
            totalTargetDamage += damage
            if (actorIds != null && !actorIds.contains(uid)) {
                return@forEach
            }
            val timestamp = pdp.getTimeStamp()
            if (startTime == null || timestamp < startTime!!) {
                startTime = timestamp
            }
            if (endTime == null || timestamp > endTime!!) {
                endTime = timestamp
            }

            val inferredCode = inferOriginalSkillCode(
                pdp.getSkillCode1(),
                pdp.getTargetId(),
                pdp.getActorId(),
                pdp.getDamage(),
                pdp.getHexPayload()
            ) ?: pdp.getSkillCode1()
            var job = actorJobs[uid] ?: ""
            if (job.isEmpty()) {
                val converted = JobClass.convertFromSkill(inferredCode)
                if (converted != null) {
                    job = converted.className
                    actorJobs[uid] = job
                }
            }

            val isDot = pdp.isDoT()
            val key = "$uid|$inferredCode|$isDot"
            val existing = skillMap[key]
            val next = if (existing == null) {
                DetailSkillEntry(
                    actorId = uid,
                    code = inferredCode,
                    name = SKILL_MAP[inferredCode] ?: "",
                    time = 0,
                    dmg = 0,
                    crit = 0,
                    parry = 0,
                    back = 0,
                    perfect = 0,
                    double = 0,
                    heal = 0,
                    job = job,
                    isDot = isDot
                )
            } else if (existing.job.isEmpty() && job.isNotEmpty()) {
                existing.copy(job = job)
            } else {
                existing
            }

            var updated = next.copy(
                time = next.time + 1,
                dmg = next.dmg + damage,
                heal = next.heal + pdp.getHealAmount()
            )

            if (!isDot) {
                updated = updated.copy(
                    crit = updated.crit + if (pdp.isCrit()) 1 else 0,
                    parry = updated.parry + if (pdp.getSpecials().contains(SpecialDamage.PARRY)) 1 else 0,
                    back = updated.back + if (pdp.getSpecials().contains(SpecialDamage.BACK)) 1 else 0,
                    perfect = updated.perfect + if (pdp.getSpecials().contains(SpecialDamage.PERFECT)) 1 else 0,
                    double = updated.double + if (pdp.getSpecials().contains(SpecialDamage.DOUBLE)) 1 else 0
                )
            }

            skillMap[key] = updated
        }

        val battleTime = if (startTime != null && endTime != null) {
            endTime!! - startTime!!
        } else {
            targetInfoMap[targetId]?.parseBattleTime() ?: 0L
        }
        return TargetDetailsResponse(
            targetId = targetId,
            totalTargetDamage = totalTargetDamage,
            battleTime = battleTime,
            skills = skillMap.values.toList()
        )
    }

    private fun decideTarget(): TargetDecision {
        if (targetInfoMap.isEmpty()) {
            return TargetDecision(emptySet(), "", targetSelectionMode, 0)
        }
        val mostDamageTarget = targetInfoMap.maxByOrNull { it.value.damagedAmount() }?.key ?: 0
        val mostRecentTarget = targetInfoMap.maxByOrNull { it.value.lastDamageTime() }?.key ?: 0
        val shouldPreferMostRecent = shouldPreferMostRecentTarget(mostDamageTarget, mostRecentTarget)

        return when (targetSelectionMode) {
            TargetSelectionMode.MOST_DAMAGE -> {
                val selectedTarget = if (shouldPreferMostRecent) mostRecentTarget else mostDamageTarget
                TargetDecision(setOf(selectedTarget), resolveTargetName(selectedTarget), targetSelectionMode, selectedTarget)
            }
            TargetSelectionMode.MOST_RECENT -> {
                TargetDecision(setOf(mostRecentTarget), resolveTargetName(mostRecentTarget), targetSelectionMode, mostRecentTarget)
            }
            TargetSelectionMode.LAST_HIT_BY_ME -> {
                val localActors = resolveConfirmedLocalActorIds()
                if (localActors == null) {
                    val recentTargets = selectRecentTargetsForUnknownPlayer(unknownPlayerTargetWindowMs)
                    TargetDecision(recentTargets, "", targetSelectionMode, 0)
                } else {
                    val targetId = selectTargetLastHitByMe(localActors, currentTarget)
                    TargetDecision(setOf(targetId), resolveTargetName(targetId), targetSelectionMode, targetId)
                }
            }
            TargetSelectionMode.ALL_TARGETS -> {
                val recentTargets = selectRecentTargetsForUnknownPlayer(allTargetsWindowMs)
                TargetDecision(recentTargets, "", targetSelectionMode, 0)
            }
        }
    }

    private fun shouldPreferMostRecentTarget(mostDamageTarget: Int, mostRecentTarget: Int): Boolean {
        if (mostDamageTarget == 0 || mostRecentTarget == 0 || mostDamageTarget == mostRecentTarget) {
            return false
        }
        val mostDamageInfo = targetInfoMap[mostDamageTarget] ?: return false
        val mostRecentInfo = targetInfoMap[mostRecentTarget] ?: return false
        val now = System.currentTimeMillis()
        val mostDamageStale = now - mostDamageInfo.lastDamageTime() >= targetSwitchStaleMs
        val mostRecentFresh = now - mostRecentInfo.lastDamageTime() < targetSwitchStaleMs
        return mostDamageStale && mostRecentFresh
    }

    private fun resolveConfirmedLocalActorIds(): Set<Int>? {
        val localName = LocalPlayer.characterName?.trim().orEmpty()
        if (localName.isBlank()) return null

        val nicknameData = dataStorage.getNickname()
        val localActorIds = mutableSetOf<Int>()
        val localPlayerId = LocalPlayer.playerId?.toInt()
        if (localPlayerId != null) {
            val recordedName = nicknameData[localPlayerId]
            if (recordedName != null && recordedName == localName) {
                localActorIds.add(localPlayerId)
            }
        }
        localActorIds.addAll(
            nicknameData
                .filterValues { it == localName }
                .keys
        )
        if (localActorIds.isEmpty()) return null
        val summonData = dataStorage.getSummonData()
        if (summonData.isNotEmpty()) {
            summonData.forEach { (summonId, summonerId) ->
                if (summonerId in localActorIds) {
                    localActorIds.add(summonId)
                }
                if (summonId in localActorIds) {
                    localActorIds.add(summonerId)
                }
            }
        }
        return localActorIds
    }

    private fun parseActorBattleTimeForTarget(localActorIds: Set<Int>, targetId: Int): Long {
        if (targetId == 0 || localActorIds.isEmpty()) return 0
        val actorData = dataStorage.getActorData()
        var startTime: Long? = null
        var endTime: Long? = null
        localActorIds.forEach { actorId ->
            val pdps = actorData[actorId] ?: return@forEach
            pdps.forEach { pdp ->
                if (pdp.getTargetId() != targetId) return@forEach
                val timestamp = pdp.getTimeStamp()
                if (startTime == null || timestamp < startTime!!) {
                    startTime = timestamp
                }
                if (endTime == null || timestamp > endTime!!) {
                    endTime = timestamp
                }
            }
        }
        if (startTime == null || endTime == null) return 0
        return endTime!! - startTime!!
    }

    private fun selectTargetLastHitByMe(localActorIds: Set<Int>, fallbackTarget: Int): Int {
        val actorData = dataStorage.getActorData()
        val cutoff = System.currentTimeMillis() - 5_000L
        var mostRecentTarget = fallbackTarget
        var mostRecentTime = -1L
        val recentDamage = mutableMapOf<Int, Int>()
        val recentTimes = mutableMapOf<Int, Long>()

        localActorIds.forEach { actorId ->
            val pdps = actorData[actorId] ?: return@forEach
            for (pdp in pdps) {
                if (pdp.isDoT()) continue
                val timestamp = pdp.getTimeStamp()
                val targetId = pdp.getTargetId()
                if (timestamp > mostRecentTime) {
                    mostRecentTime = timestamp
                    mostRecentTarget = targetId
                }
                if (timestamp >= cutoff) {
                    recentDamage[targetId] = (recentDamage[targetId] ?: 0) + pdp.getDamage()
                    val existingTime = recentTimes[targetId] ?: 0L
                    if (timestamp > existingTime) {
                        recentTimes[targetId] = timestamp
                    }
                }
            }
        }

        if (mostRecentTime < 0) {
            return fallbackTarget
        }
        if (mostRecentTime <= lastLocalHitTime && fallbackTarget != 0) {
            return fallbackTarget
        }

        val selectedTarget = if (recentDamage.isNotEmpty()) {
            recentDamage.entries.maxWithOrNull(
                compareBy<Map.Entry<Int, Int>> { it.value }
                    .thenBy { recentTimes[it.key] ?: 0L }
            )?.key ?: mostRecentTarget
        } else {
            mostRecentTarget
        }

        lastLocalHitTime = mostRecentTime
        return selectedTarget
    }

    private fun selectRecentTargetsForUnknownPlayer(windowMs: Long): Set<Int> {
        val cutoff = System.currentTimeMillis() - windowMs
        return targetInfoMap.filterValues { it.lastDamageTime() >= cutoff }.keys
    }

    private fun collectRecentPdp(targetIds: Set<Int>, windowMs: Long): List<ParsedDamagePacket> {
        val cutoff = System.currentTimeMillis() - windowMs
        val combined = mutableListOf<ParsedDamagePacket>()
        val seen = mutableSetOf<UUID>()
        targetIds.forEach { targetId ->
            dataStorage.getBossModeData()[targetId]?.forEach { pdp ->
                if (pdp.getTimeStamp() < cutoff) return@forEach
                if (seen.add(pdp.getUuid())) {
                    combined.add(pdp)
                }
            }
        }
        return combined
    }

    private fun parseRecentBattleTime(targetIds: Set<Int>, windowMs: Long): Long {
        val pdps = collectRecentPdp(targetIds, windowMs)
        if (pdps.isEmpty()) return 0
        val start = pdps.minOf { it.getTimeStamp() }
        val end = pdps.maxOf { it.getTimeStamp() }
        return end - start
    }

    private fun resolveTargetName(target: Int): String {
        if (!dataStorage.getMobData().containsKey(target)) return ""
        val mobCode = dataStorage.getMobData()[target] ?: return ""
        return dataStorage.getMobCodeData()[mobCode] ?: ""
    }

    private fun parseAllBattleTime(targetIds: Set<Int>): Long {
        val targets = targetIds.mapNotNull { targetInfoMap[it] }
        if (targets.isEmpty()) return 0
        val start = targets.minOf { it.firstDamageTime() }
        val end = targets.maxOf { it.lastDamageTime() }
        return end - start
    }

    private fun collectAllPdp(
        pdpMap: Map<Int, Iterable<ParsedDamagePacket>>,
        targetIds: Set<Int>,
    ): List<ParsedDamagePacket> {
        val combined = mutableListOf<ParsedDamagePacket>()
        val seen = mutableSetOf<UUID>()
        targetIds.forEach { targetId ->
            pdpMap[targetId]?.forEach { pdp ->
                if (seen.add(pdp.getUuid())) {
                    combined.add(pdp)
                }
            }
        }
        return combined
    }

    private fun inferOriginalSkillCode(
        skillCode: Int,
        targetId: Int,
        actorId: Int,
        damage: Int,
        payloadHex: String
    ): Int? {
        // Check if skill code is in a valid range (even if not in our SKILL_CODES list)
        val isValidRange = skillCode in 11_000_000..19_999_999 ||
                          skillCode in 3_000_000..3_999_999 ||
                          skillCode in 100_000..199_999
        
        // If it's already in a valid range, use it as-is
        if (isValidRange) {
            return skillCode
        }
        
        // Otherwise, try to infer from offsets
        for (offset in POSSIBLE_OFFSETS) {
            val possibleOrigin = skillCode - offset
            // Check if the inferred skill is in valid range
            val isInferredValid = possibleOrigin in 11_000_000..19_999_999 ||
                                 possibleOrigin in 3_000_000..3_999_999 ||
                                 possibleOrigin in 100_000..199_999
            if (isInferredValid) {
                logger.debug("Inferred original skill code: {}", possibleOrigin)
                return possibleOrigin
            }
        }
        
        logger.debug(
            "Failed to infer skill code: {} (target {}, actor {}, damage {})",
            skillCode,
            targetId,
            actorId,
            damage
        )
        logger.debug(
            "Failed to infer skill code payload={}",
            payloadHex
        )
        DebugLogWriter.debug(
            logger,
            "Failed to infer skill code: {} (target {}, actor {}, damage {}) payload={}",
            skillCode,
            targetId,
            actorId,
            damage,
            payloadHex
        )
        return null
    }

    fun resetDataStorage() {
        dataStorage.flushDamageStorage()
        targetInfoMap.clear()
        lastLocalHitTime = -1L
        currentTarget = 0
        lastDpsSnapshot = null
        dataStorage.setCurrentTarget(0)
        logger.info("Target damage accumulation reset")
    }

    fun analyzingData(uid: Int) {
        val dpsData = getDps()
        dpsData.map.forEach { (_, pData) ->
            logger.debug("-----------------------------------------")
            DebugLogWriter.debug(logger, "-----------------------------------------")
            logger.debug(
                "Nickname: {} job: {} total damage: {} contribution: {}",
                pData.nickname,
                pData.job,
                pData.amount,
                pData.damageContribution
            )
            DebugLogWriter.debug(
                logger,
                "Nickname: {} job: {} total damage: {} contribution: {}",
                pData.nickname,
                pData.job,
                pData.amount,
                pData.damageContribution
            )
            pData.analyzedData.forEach { (key, data) ->
                logger.debug(
                    "Skill (code): {} total damage: {}",
                    SKILL_MAP[key] ?: key,
                    data.damageAmount
                )
                DebugLogWriter.debug(
                    logger,
                    "Skill (code): {} total damage: {}",
                    SKILL_MAP[key] ?: key,
                    data.damageAmount
                )
                logger.debug(
                    "Uses: {} critical hits: {} critical hit rate: {}",
                    data.times,
                    data.critTimes,
                    data.critTimes / data.times * 100
                )
                DebugLogWriter.debug(
                    logger,
                    "Uses: {} critical hits: {} critical hit rate: {}",
                    data.times,
                    data.critTimes,
                    data.critTimes / data.times * 100
                )
                logger.debug(
                    "Skill damage share: {}%",
                    (data.damageAmount / pData.amount * 100).roundToInt()
                )
                DebugLogWriter.debug(
                    logger,
                    "Skill damage share: {}%",
                    (data.damageAmount / pData.amount * 100).roundToInt()
                )
            }
            logger.debug("-----------------------------------------")
            DebugLogWriter.debug(logger, "-----------------------------------------")
        }
    }

}
