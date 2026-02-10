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
import com.tbread.logging.UnifiedLogger
import com.tbread.packet.LocalPlayer
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt
import java.util.UUID
import java.util.LinkedHashMap
import java.nio.charset.StandardCharsets

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
        ALL_TARGETS("allTargets"),
        TRAIN_TARGETS("trainTargets");

        companion object {
            fun fromId(id: String?): TargetSelectionMode {
                return entries.firstOrNull { it.id == id } ?: LAST_HIT_BY_ME
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

        val SKILL_MAP: Map<Int, String> by lazy {
            loadSkillMapFromResource()
        }

        private fun loadSkillMapFromResource(): Map<Int, String> {
            val stream = DpsCalculator::class.java.classLoader
                .getResourceAsStream("i18n/skills/en.json") ?: return emptyMap()
            val text = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val entryRegex = Regex("\"(\\d+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            return entryRegex.findAll(text)
                .mapNotNull { match ->
                    val code = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    val rawName = match.groupValues.getOrNull(2) ?: return@mapNotNull null
                    val name = rawName
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                    code to name
                }
                .toMap()
        }

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

        private const val NICKNAME_JOB_CACHE_LIMIT = 4096
    }

    private val targetInfoMap = hashMapOf<Int, TargetInfo>()

    private var mode: Mode = Mode.BOSS_ONLY
    private var currentTarget: Int = 0
    private var lastDpsSnapshot: DpsData? = null
    @Volatile private var targetSelectionMode: TargetSelectionMode = TargetSelectionMode.LAST_HIT_BY_ME
    private val targetSwitchStaleMs = 10_000L
    @Volatile private var targetSelectionWindowMs = 5_000L
    private var lastLocalHitTime: Long = -1L
    @Volatile private var lastKnownLocalPlayerId: Long? = null
    @Volatile private var allTargetsWindowMs = 120_000L
    @Volatile private var trainSelectionMode: TrainSelectionMode = TrainSelectionMode.ALL
    private val nicknameJobCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > NICKNAME_JOB_CACHE_LIMIT
        }
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        //모드 변경시 이전기록 초기화?
    }

    fun setTargetSelectionModeById(id: String?) {
        targetSelectionMode = TargetSelectionMode.fromId(id)
    }

    fun setAllTargetsWindowMs(windowMs: Long) {
        allTargetsWindowMs = windowMs.coerceIn(10_000L, 900_000L)
    }

    fun setTrainSelectionModeById(id: String?) {
        trainSelectionMode = TrainSelectionMode.fromId(id)
    }

    fun setTargetSelectionWindowMs(windowMs: Long) {
        targetSelectionWindowMs = windowMs.coerceIn(5_000L, 60_000L)
    }

    fun bindLocalActorId(actorId: Long) {
        updateLocalPlayerId(actorId)
    }

    fun bindLocalNickname(actorId: Long, nickname: String?) {
        val uid = actorId.toInt()
        val clean = nickname?.trim().orEmpty()
        if (uid <= 0 || clean.isBlank()) return
        dataStorage.bindNickname(uid, clean)
        val localName = LocalPlayer.characterName?.trim().orEmpty()
        if (localName.isNotBlank() && localName == clean) {
            updateLocalPlayerId(actorId)
        }
    }

    fun resetLocalIdentity() {
        LocalPlayer.playerId = null
        lastKnownLocalPlayerId = null
        dataStorage.resetNicknameStorage()
        resetDataStorage()
    }

    fun restartTargetSelection(clearDamage: Boolean = false) {
        lastLocalHitTime = -1L
        currentTarget = 0
        targetInfoMap.clear()
        lastDpsSnapshot = null
        if (clearDamage) {
            dataStorage.flushDamageStorage()
        }
        dataStorage.setCurrentTarget(0)
    }

    private fun updateLocalPlayerId(actorId: Long) {
        val nextId = actorId.takeIf { it > 0 }
        if (nextId == null || LocalPlayer.playerId == nextId) return
        LocalPlayer.playerId = nextId
        lastKnownLocalPlayerId = nextId
        restartTargetSelection()
    }

    fun getDps(): DpsData {
        val currentLocalId = LocalPlayer.playerId
        if (currentLocalId != lastKnownLocalPlayerId) {
            lastKnownLocalPlayerId = currentLocalId
            restartTargetSelection()
        }
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
        dpsData.localPlayerId = currentLocalId
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
        val isTrainCombined = targetDecision.mode == TargetSelectionMode.TRAIN_TARGETS
        val battleTime = when {
            isRecentCombined -> parseRecentBattleTime(targetDecision.targetIds, allTargetsWindowMs)
            isTrainCombined -> parseRecentBattleTime(targetDecision.targetIds, allTargetsWindowMs)
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
            isRecentCombined -> collectRecentPdp(targetDecision.targetIds, allTargetsWindowMs)
            isTrainCombined -> collectRecentPdp(targetDecision.targetIds, allTargetsWindowMs)
            targetDecision.mode == TargetSelectionMode.ALL_TARGETS ->
                collectRecentPdp(targetDecision.targetIds, allTargetsWindowMs)
            else -> pdpMap[currentTarget]?.toList() ?: return dpsData
        }
        pdps.forEach { pdp ->
            totalDamage += pdp.getDamage()
            val uid = dataStorage.getSummonData()[pdp.getActorId()] ?: pdp.getActorId()
            if (uid <= 0) return@forEach
            val nickname = resolveNickname(uid, nicknameData)
            val cachedJob = cachedJobForNickname(nickname)
            val existing = dpsData.map[uid]
            if (existing == null) {
                dpsData.map[uid] = if (!cachedJob.isNullOrBlank()) {
                    PersonalData(job = cachedJob, nickname = nickname)
                } else {
                    PersonalData(nickname = nickname)
                }
            } else {
                var next = existing
                if (existing.nickname != nickname) {
                    next = next.copy(nickname = nickname)
                }
                if (next.job.isEmpty() && !cachedJob.isNullOrBlank()) {
                    next = next.copy(job = cachedJob)
                }
                if (next != existing) {
                    dpsData.map[uid] = next
                }
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
                    cacheJobForNickname(nickname, job.className)
                }
            }
        }
        val localActorIds = resolveConfirmedLocalActorIds()
        val iterator = dpsData.map.iterator()
        while (iterator.hasNext()) {
            val (uid, data) = iterator.next()
            val keep = if (data.job == "") {
                if (localActorIds != null && localActorIds.contains(uid)) {
                    data.job = "Unknown"
                    true
                } else {
                    iterator.remove()
                    false
                }
            } else {
                true
            }
            if (keep) {
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

    private fun cachedJobForNickname(nickname: String): String? {
        val key = nickname.trim().lowercase()
        if (key.isBlank() || key.all { it.isDigit() }) return null
        return nicknameJobCache[key]?.takeIf { it.isNotBlank() && it != "Unknown" }
    }

    private fun cacheJobForNickname(nickname: String, job: String) {
        if (job.isBlank() || job == "Unknown") return
        val key = nickname.trim().lowercase()
        if (key.isBlank() || key.all { it.isDigit() }) return
        nicknameJobCache[key] = job
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
                if (uid <= 0) return@forEach
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
            if (uid <= 0) return@forEach
            val damage = pdp.getDamage()
            totalTargetDamage += damage
            if (actorIds != null && !actorIds.contains(uid)) {
                return@forEach
            }
            val timestamp = pdp.getTimeStamp()
            val currentStart = startTime
            if (currentStart == null || timestamp < currentStart) {
                startTime = timestamp
            }
            val currentEnd = endTime
            if (currentEnd == null || timestamp > currentEnd) {
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
                    multiHitCount = 0,
                    multiHitDamage = 0,
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
                heal = next.heal + pdp.getHealAmount(),
                multiHitCount = next.multiHitCount + pdp.getMultiHitCount(),
                multiHitDamage = next.multiHitDamage + pdp.getMultiHitDamage()
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

        val battleTime = run {
            val start = startTime
            val end = endTime
            if (start != null && end != null) {
                end - start
            } else {
                targetInfoMap[targetId]?.parseBattleTime() ?: 0L
            }
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
                    val recentTargets = selectRecentTargetsForUnknownPlayer(allTargetsWindowMs)
                    TargetDecision(recentTargets, "", targetSelectionMode, 0)
                } else {
                    val targetId = selectTargetLastHitByMe(localActors, currentTarget)
                    if (targetId == 0) {
                        val recentTargets = selectRecentTargetsForUnknownPlayer(allTargetsWindowMs)
                        TargetDecision(recentTargets, "", targetSelectionMode, 0)
                    } else {
                        TargetDecision(setOf(targetId), resolveTargetName(targetId), targetSelectionMode, targetId)
                    }
                }
            }
            TargetSelectionMode.ALL_TARGETS -> {
                val recentTargets = selectRecentTargetsForUnknownPlayer(allTargetsWindowMs)
                TargetDecision(recentTargets, "", targetSelectionMode, 0)
            }
            TargetSelectionMode.TRAIN_TARGETS -> {
                val localActors = resolveConfirmedLocalActorIds()
                val recentTargets = if (localActors == null) {
                    selectRecentTargetsForUnknownPlayer(allTargetsWindowMs)
                } else {
                    selectRecentTargetsByLocalActors(localActors, allTargetsWindowMs)
                }
                val filteredTargets = when (trainSelectionMode) {
                    TrainSelectionMode.ALL -> recentTargets
                    TrainSelectionMode.HIGHEST_DAMAGE -> selectHighestDamageTarget(recentTargets)
                }
                TargetDecision(filteredTargets, "", targetSelectionMode, 0)
            }
        }
    }

    private fun selectHighestDamageTarget(targetIds: Set<Int>): Set<Int> {
        if (targetIds.isEmpty()) return emptySet()
        val winner = targetIds.maxByOrNull { targetInfoMap[it]?.damagedAmount() ?: 0 } ?: return emptySet()
        return setOf(winner)
    }

    private fun selectRecentTargetsByLocalActors(localActorIds: Set<Int>, windowMs: Long): Set<Int> {
        if (localActorIds.isEmpty()) return emptySet()
        val cutoff = System.currentTimeMillis() - windowMs
        val actorData = dataStorage.getActorData()
        val targets = mutableSetOf<Int>()
        localActorIds.forEach { actorId ->
            val pdps = actorData[actorId] ?: return@forEach
            pdps.forEach { pdp ->
                if (pdp.getTimeStamp() < cutoff) return@forEach
                val targetId = pdp.getTargetId()
                if (targetId > 0 && pdp.getDamage() > 0) {
                    targets.add(targetId)
                }
            }
        }
        return targets
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
        val normalizedLocalName = localName.lowercase()

        val localActorIds = mutableSetOf<Int>()
        val localPlayerId = LocalPlayer.playerId?.toInt()
        if (localPlayerId != null) {
            // If packet parsing has confirmed local player ID, trust it directly.
            // Nickname mapping may appear later or differ temporarily.
            localActorIds.add(localPlayerId)
        } else if (normalizedLocalName.isNotBlank()) {
            val nicknameData = dataStorage.getNickname()
            localActorIds.addAll(
                nicknameData
                    .filterValues { it.trim().lowercase() == normalizedLocalName }
                    .keys
            )
        }

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
                val currentStart = startTime
                if (currentStart == null || timestamp < currentStart) {
                    startTime = timestamp
                }
                val currentEnd = endTime
                if (currentEnd == null || timestamp > currentEnd) {
                    endTime = timestamp
                }
            }
        }
        val start = startTime ?: return 0
        val end = endTime ?: return 0
        return end - start
    }

    private fun selectTargetLastHitByMe(localActorIds: Set<Int>, fallbackTarget: Int): Int {
        val actorData = dataStorage.getActorData()
        val now = System.currentTimeMillis()
        val cutoff = now - targetSelectionWindowMs
        val wasIdle = lastLocalHitTime < 0 || now - lastLocalHitTime > 5_000L
        var mostRecentTarget = fallbackTarget
        var mostRecentTime = -1L
        val recentDamage = mutableMapOf<Int, Int>()
        val recentTimes = mutableMapOf<Int, Long>()

        localActorIds.forEach { actorId ->
            val pdps = actorData[actorId] ?: return@forEach
            for (pdp in pdps) {
                val timestamp = pdp.getTimeStamp()
                val targetId = pdp.getTargetId()
                val damage = pdp.getDamage()
                if (targetId <= 0 || damage <= 0) continue
                if (timestamp > mostRecentTime) {
                    mostRecentTime = timestamp
                    mostRecentTarget = targetId
                }
                if (timestamp >= cutoff) {
                    recentDamage[targetId] = (recentDamage[targetId] ?: 0) + damage
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
        if (wasIdle && mostRecentTime > lastLocalHitTime) {
            lastLocalHitTime = mostRecentTime
            return mostRecentTarget
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
        UnifiedLogger.debug(
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
            UnifiedLogger.debug(logger, "-----------------------------------------")
            logger.debug(
                "Nickname: {} job: {} total damage: {} contribution: {}",
                pData.nickname,
                pData.job,
                pData.amount,
                pData.damageContribution
            )
            UnifiedLogger.debug(
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
                UnifiedLogger.debug(
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
                UnifiedLogger.debug(
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
                UnifiedLogger.debug(
                    logger,
                    "Skill damage share: {}%",
                    (data.damageAmount / pData.amount * 100).roundToInt()
                )
            }
            logger.debug("-----------------------------------------")
            UnifiedLogger.debug(logger, "-----------------------------------------")
        }
    }

}
    enum class TrainSelectionMode(val id: String) {
        ALL("all"),
        HIGHEST_DAMAGE("highestDamage");

        companion object {
            fun fromId(id: String?): TrainSelectionMode {
                return entries.firstOrNull { it.id == id } ?: ALL
            }
        }
    }
