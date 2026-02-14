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
import java.nio.charset.StandardCharsets

class DpsCalculator(private val dataStorage: DataStorage) {
    private val logger = LoggerFactory.getLogger(DpsCalculator::class.java)

    private data class ActorMetaBuilder(
        var nickname: String,
        var job: String = ""
    )

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
    }

    private val targetInfoMap = hashMapOf<Int, TargetInfo>()

    private var currentTarget: Int = 0
    private var lastDpsSnapshot: DpsData? = null
    @Volatile private var targetSelectionMode: TargetSelectionMode = TargetSelectionMode.LAST_HIT_BY_ME
    private val targetSwitchStaleMs = 10_000L
    @Volatile private var targetSelectionWindowMs = 5_000L
    private var lastLocalHitTime: Long = -1L
    @Volatile private var lastKnownLocalPlayerId: Long? = null
    @Volatile private var allTargetsWindowMs = 120_000L
    @Volatile private var trainSelectionMode: TrainSelectionMode = TrainSelectionMode.ALL
    private val nicknameJobCache = mutableMapOf<String, String>()

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
        val pdpMap = dataStorage.getBossModeDataSnapshot()

        pdpMap.forEach { (target, data) ->
            data.forEach { pdp ->
                val targetInfo = targetInfoMap.getOrPut(target) {
                    TargetInfo(target, 0, pdp.getTimeStamp(), pdp.getTimeStamp())
                }
                targetInfo.processPdp(pdp)
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
            localActorsForBattleTime != null ->
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
            else -> pdpMap[currentTarget] ?: return dpsData
        }

        val summonData = dataStorage.getSummonData()

        pdps.forEach { pdp ->
            totalDamage += pdp.getDamage()

            val uid = summonData[pdp.getActorId()] ?: pdp.getActorId()
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
        val pdpMap = dataStorage.getBossModeDataSnapshot()
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
        val pdps = dataStorage.getBossModeDataSnapshot()[targetId] ?: return TargetDetailsResponse(
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
        val actorData = dataStorage.getActorDataSnapshot()
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
        val actorData = dataStorage.getActorDataSnapshot()
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
        val actorData = dataStorage.getActorDataSnapshot()
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
        targetIds.forEach { targetId ->
            dataStorage.getBossModeDataSnapshot()[targetId]?.forEach { pdp ->
                if (pdp.getTimeStamp() >= cutoff) combined.add(pdp)
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

    private fun inferOriginalSkillCode(
        skillCode: Int,
        targetId: Int,
        actorId: Int,
        damage: Int,
        payloadHex: String?
    ): Int? {
        val isValidRange = skillCode in 11_000_000..19_999_999 ||
                skillCode in 3_000_000..3_999_999 ||
                skillCode in 100_000..199_999

        if (isValidRange) {
            return skillCode
        }

        for (offset in POSSIBLE_OFFSETS) {
            val possibleOrigin = skillCode - offset
            val isInferredValid = possibleOrigin in 11_000_000..19_999_999 ||
                    possibleOrigin in 3_000_000..3_999_999 ||
                    possibleOrigin in 100_000..199_999
            if (isInferredValid) {
                logger.debug("Inferred original skill code: {}", possibleOrigin)
                return possibleOrigin
            }
        }

        // --- NEW: Log NPC skills in debug mode ---
        if (skillCode in 1_000_000..9_999_999) {
            if (logger.isDebugEnabled()) {
                val skillName = SKILL_MAP[skillCode] ?: skillCode.toString()
                logger.debug("NPC {} attacked {} with {}", actorId, targetId, skillName)

                if (UnifiedLogger.isDebugEnabled()) {
                    UnifiedLogger.debug(logger, "NPC {} attacked {} with {}", actorId, targetId, skillName)
                }
            }
            // Return null so the caller safely defaults to the raw ID without throwing a warning
            return null
        }
        // -----------------------------------------

        // --- NEW: Silently ignore non-player entities to prevent log spam ---
        val isPlayer = dataStorage.getNickname().containsKey(actorId) ||
                dataStorage.getSummonData().containsKey(actorId) ||
                LocalPlayer.playerId?.toInt() == actorId

        if (dataStorage.getMobData().containsKey(actorId) || !isPlayer) {
            return null
        }
        // --------------------------------------------------------------------

        logger.debug(
            "Failed to infer skill code: {} (target {}, actor {}, damage {})",
            skillCode,
            targetId,
            actorId,
            damage
        )
        if (!payloadHex.isNullOrBlank()) {
            logger.debug(
                "Failed to infer skill code payload={}",
                payloadHex
            )
        }
        UnifiedLogger.debug(
            logger,
            "Failed to infer skill code: {} (target {}, actor {}, damage {}) payload={}",
            skillCode,
            targetId,
            actorId,
            damage,
            payloadHex ?: "<omitted>"
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

    enum class TrainSelectionMode(val id: String) {
        ALL("all"),
        HIGHEST_DAMAGE("highestDamage");

        companion object {
            fun fromId(id: String?): TrainSelectionMode {
                return entries.firstOrNull { it.id == id } ?: ALL
            }
        }
    }
}