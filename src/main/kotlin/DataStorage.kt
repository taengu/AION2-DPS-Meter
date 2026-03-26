package com.tbread

import com.tbread.entity.JobClass
import com.tbread.entity.ParsedDamagePacket
import com.tbread.entity.SummonResolver
import com.tbread.logging.UnifiedLogger
import com.tbread.packet.LocalPlayer
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DataStorage {
    private val logger = LoggerFactory.getLogger(DataStorage::class.java)
    private val byTargetStorage = HashMap<Int, ArrayList<ParsedDamagePacket>>()
    private val byActorStorage = HashMap<Int, ArrayList<ParsedDamagePacket>>()
    private val nicknameStorage = ConcurrentHashMap<Int, String>()
    private val pendingNicknameStorage = ConcurrentHashMap<Int, String>()
    private val permanentNicknames = ConcurrentHashMap<Int, String>()
    private val summonStorage = ConcurrentHashMap<Int, Int>()
    private val mobCodeData = ConcurrentHashMap<Int, String>()
    private val mobStorage = ConcurrentHashMap<Int, Int>()
    private val knownPlayerIds = ConcurrentHashMap.newKeySet<Int>()
    private val confirmedSummonIds = ConcurrentHashMap.newKeySet<Int>()
    private val pendingSummonByOwnerName = ConcurrentHashMap<String, MutableSet<Int>>()
    private val currentTarget = AtomicInteger(0)
    private val packetOrder = ArrayDeque<ParsedDamagePacket>()
    private val mobHpData = ConcurrentHashMap<Int, Int>()

    // --- UPDATED LIMITS ---
    // Increased from 60,000 -> 200,000 (Approx 60 mins of heavy data)
    // Since we are now using Hardware Acceleration, the Heap has plenty of space for this.
    private val maxStoredPackets = 200_000

    // Increased from 20,000 -> 100,000 (Ensures long fights don't get truncated in the UI)
    private val maxSnapshotPackets = 100_000
    // ----------------------

    // Snapshot cache — rebuilt only when new packets arrive
    private var snapshotDirty = true
    private var cachedByTargetSnapshot: Map<Int, List<ParsedDamagePacket>> = emptyMap()
    private var cachedByActorSnapshot: Map<Int, List<ParsedDamagePacket>> = emptyMap()

    @Synchronized
    fun appendDamage(pdp: ParsedDamagePacket) {
        // Drop packets from known mobs to save memory, but only when the skill id also
        // looks like an NPC skill. False-positive mob mappings can happen during noisy
        // stream recovery, and we must not discard valid player damage in that case.
        val skillCode = pdp.getSkillCode1()
        val usesLikelyNpcSkill = skillCode in 1_000_000..9_999_999
        if (
            mobStorage.containsKey(pdp.getActorId()) &&
            !summonStorage.containsKey(pdp.getActorId()) &&
            usesLikelyNpcSkill
        ) {
            if (UnifiedLogger.isDebugEnabled()) {
                UnifiedLogger.debug(logger,
                    "Skipped damage (NPC actor): actor={}, target={}, skill={}, damage={}",
                    pdp.getActorId(), pdp.getTargetId(), skillCode, pdp.getDamage())
            }
            return
        }

        // Track actors using player-class skills as known players.
        // Confirmed summons (identified by 5F 00 spawn marker) are excluded — their
        // skills overlap with their owner's class but they are NOT players.
        if (isPlayerSkill(skillCode) && !confirmedSummonIds.contains(pdp.getActorId())) {
            val isNew = knownPlayerIds.add(pdp.getActorId())
            if (isNew) {
                // If this actor was falsely registered as a summon, remove the link
                if (summonStorage.remove(pdp.getActorId()) != null) {
                    UnifiedLogger.info(logger, "Removed false summon mapping for player {} (uses player skill {})",
                        pdp.getActorId(), skillCode)
                }
                purgeFriendlyPackets(pdp.getActorId())
            }
        }

        // Skip friendly actions (heals/buffs between known players or their summons)
        if (isFriendlyAction(pdp.getActorId(), pdp.getTargetId())) {
            if (UnifiedLogger.isDebugEnabled()) {
                UnifiedLogger.debug(logger,
                    "Skipped damage (friendly action): actor={}, target={}, skill={}, damage={}",
                    pdp.getActorId(), pdp.getTargetId(), skillCode, pdp.getDamage())
            }
            return
        }

        byActorStorage.getOrPut(pdp.getActorId()) { ArrayList() }.add(pdp)
        byTargetStorage.getOrPut(pdp.getTargetId()) { ArrayList() }.add(pdp)
        packetOrder.addLast(pdp)
        snapshotDirty = true
        trimStoredDamageIfNeeded()
        applyPendingNickname(pdp.getActorId())
    }

    private fun trimStoredDamageIfNeeded() {
        while (packetOrder.size > maxStoredPackets) {
            if (packetOrder.isEmpty()) break
            val oldest = packetOrder.removeFirst()
            removePacketReferences(oldest)
        }
    }

    private fun removePacketReferences(pdp: ParsedDamagePacket) {
        // Packets are appended in chronological order, so the oldest packet
        // for a given actor/target is always at index 0. O(1) instead of O(n).
        byActorStorage[pdp.getActorId()]?.let { actorPackets ->
            if (actorPackets.isNotEmpty() && actorPackets[0].getId() == pdp.getId()) {
                actorPackets.removeAt(0)
            }
            if (actorPackets.isEmpty()) {
                byActorStorage.remove(pdp.getActorId())
            }
        }
        byTargetStorage[pdp.getTargetId()]?.let { targetPackets ->
            if (targetPackets.isNotEmpty() && targetPackets[0].getId() == pdp.getId()) {
                targetPackets.removeAt(0)
            }
            if (targetPackets.isEmpty()) {
                byTargetStorage.remove(pdp.getTargetId())
            }
        }
    }

    /**
     * Check if an action between actor and target is friendly (both sides resolve
     * to known players through summon ownership chains).
     */
    private fun isFriendlyAction(actorId: Int, targetId: Int): Boolean {
        val resolvedActor = SummonResolver.resolve(actorId, summonStorage)
        val resolvedTarget = SummonResolver.resolve(targetId, summonStorage)
        return knownPlayerIds.contains(resolvedActor) && knownPlayerIds.contains(resolvedTarget)
    }

    /**
     * Check if an entity is an active damage target of known players.
     * If players have been dealing damage to it, it's a hostile mob — not a player's pet.
     */
    private fun isHostileDamageTarget(entityId: Int): Boolean {
        val packets = byTargetStorage[entityId] ?: return false
        return packets.any { pdp ->
            val resolvedAttacker = SummonResolver.resolve(pdp.getActorId(), summonStorage)
            knownPlayerIds.contains(resolvedAttacker)
        }
    }

    private fun purgeFriendlyPackets(uid: Int) {
        val toRemove = mutableListOf<ParsedDamagePacket>()

        // Check packets where this uid was the actor — is the target also a known player (or their summon)?
        byActorStorage[uid]?.forEach { pdp ->
            if (isFriendlyAction(pdp.getActorId(), pdp.getTargetId())) {
                toRemove.add(pdp)
            }
        }
        // Check packets where this uid was the target — is the actor also a known player (or their summon)?
        byTargetStorage[uid]?.forEach { pdp ->
            if (isFriendlyAction(pdp.getActorId(), pdp.getTargetId())) {
                toRemove.add(pdp)
            }
        }

        if (toRemove.isEmpty()) return

        logger.debug("Purging {} friendly-action packets for newly identified player {}", toRemove.size, uid)
        val removeIds = toRemove.mapTo(HashSet()) { it.getId() }
        packetOrder.removeAll { it.getId() in removeIds }
        for (pdp in toRemove) {
            byActorStorage[pdp.getActorId()]?.removeAll { it.getId() in removeIds }
            byTargetStorage[pdp.getTargetId()]?.removeAll { it.getId() in removeIds }
        }
        // Clean up empty lists
        byActorStorage.entries.removeIf { it.value.isEmpty() }
        byTargetStorage.entries.removeIf { it.value.isEmpty() }
        snapshotDirty = true
    }

    fun setCurrentTarget(targetId: Int){
        currentTarget.set(targetId)
    }


    /**
     * Register a confirmed summon by owner ID (extracted directly from spawn packet).
     * This bypasses heuristic checks since the ownership is determined from packet structure.
     */
    @Synchronized
    fun registerConfirmedSummonById(summonId: Int, ownerId: Int) {
        confirmedSummonIds.add(summonId)
        if (knownPlayerIds.remove(summonId)) {
            UnifiedLogger.info(logger, "Reclassified {} as confirmed summon (was in knownPlayerIds)", summonId)
        }
        summonStorage[summonId] = ownerId
        UnifiedLogger.info(logger, "Summon linked (spawn packet): owner {} -> summon {}", ownerId, summonId)
        purgeFriendlyPackets(summonId)
    }

    fun isConfirmedSummon(entityId: Int): Boolean = confirmedSummonIds.contains(entityId)

    fun appendMob(mid: Int, code: Int) {
        mobStorage[mid] = code
        UnifiedLogger.info(logger, "Mob registered: id {} -> code {}", mid, code)
    }

    @Synchronized
    fun appendSummon(summoner: Int, summon: Int) {
        // Never register a known player (has nickname) as a summon
        if (nicknameStorage.containsKey(summon)) {
            UnifiedLogger.info(logger, "Summon blocked (known player): {} has nickname, not summon of {}", summon, summoner)
            return
        }
        // Never register an actor that has used player-class skills as a summon
        if (knownPlayerIds.contains(summon)) {
            UnifiedLogger.info(logger, "Summon blocked (player skills): {} uses player skills, not summon of {}", summon, summoner)
            return
        }
        // Never register an entity that known players have been attacking as a summon.
        // If it's in byTargetStorage it's a hostile mob, not a player's pet.
        if (isHostileDamageTarget(summon)) {
            UnifiedLogger.info(logger, "Summon blocked (hostile target): {} is being attacked by players, not summon of {}", summon, summoner)
            return
        }
        // Don't allow a summon or mob to be registered as an owner
        if (summonStorage.containsKey(summoner)) {
            UnifiedLogger.info(logger, "Summon blocked (chain): summoner {} is itself a summon, not registering {} as its summon", summoner, summon)
            return
        }
        if (mobStorage.containsKey(summoner) && !summonStorage.containsKey(summoner)) {
            UnifiedLogger.info(logger, "Summon blocked (mob owner): summoner {} is a known mob, not registering {} as its summon", summoner, summon)
            return
        }
        // Job compatibility check: if the summon has used class-specific skills,
        // its inferred job must match the candidate owner's job.
        // Generic skills (basic attacks, mob sub-00) return null and are ignored.
        val summonJob = inferJobFromSkills(summon)
        val ownerJob = inferJobFromSkills(summoner)
        if (summonJob != null && ownerJob != null && summonJob != ownerJob) {
            UnifiedLogger.info(logger, "Summon blocked (job mismatch): summon {} job {} != owner {} job {}",
                summon, summonJob.className, summoner, ownerJob.className)
            return
        }
        summonStorage[summon] = summoner
        UnifiedLogger.info(logger, "Summon registered: owner {} -> summon {}", summoner, summon)
    }

    /**
     * Infer an actor's job class from the skills they have used in combat.
     * Returns null if no class-specific skill has been recorded yet.
     * Generic/mob skills (sub-00) and unrecognized codes are ignored,
     * so summons that have only used basic attacks won't be filtered.
     */
    @Synchronized
    private fun inferJobFromSkills(actorId: Int): JobClass? {
        val packets = byActorStorage[actorId] ?: return null
        for (pdp in packets) {
            val job = JobClass.convertFromSkill(pdp.getSkillCode1())
            if (job != null) return job
        }
        return null
    }

    @Synchronized
    fun appendNickname(uid: Int, nickname: String) {
        if (nicknameStorage[uid] != null && nicknameStorage[uid].equals(nickname)) {
            val localName = LocalPlayer.characterName?.trim().orEmpty()
            if (localName.isNotBlank() && nickname.trim() == localName) {
                LocalPlayer.playerId = uid.toLong()
            }
            return
        }
        if (nicknameStorage[uid] != null &&
            nickname.toByteArray(Charsets.UTF_8).size == 2 &&
            nickname.toByteArray(Charsets.UTF_8).size < nicknameStorage[uid]!!.toByteArray(Charsets.UTF_8).size
        ) {
            logger.debug("Nickname registration skipped {} -x> {}", nicknameStorage[uid], nickname)
            UnifiedLogger.debug(
                logger,
                "Nickname registration skipped {} -x> {}",
                nicknameStorage[uid],
                nickname
            )
            return
        }
        UnifiedLogger.info(logger, "Nickname registered: {} -> {} (was: {})", uid, nickname, nicknameStorage[uid])
        UnifiedLogger.debug(logger, "Nickname registered {} -> {}", nicknameStorage[uid], nickname)
        nicknameStorage[uid] = nickname

        // If this ID was previously registered as a summon (but is NOT a confirmed
        // 5F 00 summon), remove it — it's a player, not a pet.
        if (!confirmedSummonIds.contains(uid) && summonStorage.remove(uid) != null) {
            UnifiedLogger.info(logger, "Removed false summon mapping for player {} (nickname: {})", uid, nickname)
        }

        // Register as known player and retroactively purge friendly-action packets
        // (but not if this is a confirmed summon — summons can have display names too)
        if (!confirmedSummonIds.contains(uid)) {
            val isNew = knownPlayerIds.add(uid)
            if (isNew) {
                purgeFriendlyPackets(uid)
            }
        }

        // Resolve any pending summon links waiting for this owner name
        val pendingSummons = pendingSummonByOwnerName.remove(nickname)
        if (pendingSummons != null) {
            for (summonId in pendingSummons) {
                summonStorage[summonId] = uid
                UnifiedLogger.info(logger, "Summon linked (deferred): owner {} ({}) -> summon {}", uid, nickname, summonId)
            }
        }

        val localName = LocalPlayer.characterName?.trim().orEmpty()
        if (localName.isNotBlank() && nickname.trim() == localName) {
            LocalPlayer.playerId = uid.toLong()
        }
    }

    fun bindNickname(uid: Int, nickname: String) {
        if (uid <= 0 || nickname.isBlank()) return
        appendNickname(uid, nickname.trim())
    }

    @Synchronized
    fun cachePendingNickname(uid: Int, nickname: String) {
        if (nicknameStorage[uid] != null) return
        logger.debug("Pending nickname stored {} -> {}", uid, nickname)
        UnifiedLogger.debug(logger, "Pending nickname stored {} -> {}", uid, nickname)
        pendingNicknameStorage[uid] = nickname
    }

    /**
     * Register a nickname that survives resets (e.g. replay override).
     */
    fun setPermanentNickname(uid: Int, nickname: String) {
        permanentNicknames[uid] = nickname
        appendNickname(uid, nickname)
    }

    @Synchronized
    fun resetNicknameStorage() {
        nicknameStorage.clear()
        pendingNicknameStorage.clear()
        // Re-apply permanent nicknames (replay overrides)
        permanentNicknames.forEach { (uid, nick) -> nicknameStorage[uid] = nick }
    }

    @Synchronized
    private fun applyPendingNickname(uid: Int) {
        if (nicknameStorage[uid] != null) return
        val pending = pendingNicknameStorage.remove(uid) ?: return
        appendNickname(uid, pending)
    }

    @Synchronized
    fun flushDamageStorage() {
        byActorStorage.clear()
        byTargetStorage.clear()
        packetOrder.clear()
        summonStorage.clear()
        knownPlayerIds.clear()
        confirmedSummonIds.clear()
        pendingSummonByOwnerName.clear()
        mobHpData.clear()
        currentTarget.set(0)
        snapshotDirty = true
        cachedByTargetSnapshot = emptyMap()
        cachedByActorSnapshot = emptyMap()
        logger.info("Damage packets reset")
    }

    companion object {
        /** Player-class skills: 10M-29M (class abilities) and 30M (theostones) */
        fun isPlayerSkill(skillCode: Int): Boolean =
            skillCode in 10_000_000..29_999_999 || skillCode in 30_000_000..30_999_999
    }

    @Synchronized
    private fun rebuildSnapshotCaches() {
        if (!snapshotDirty) return
        val byTarget = HashMap<Int, MutableList<ParsedDamagePacket>>()
        val byActor = HashMap<Int, MutableList<ParsedDamagePacket>>()
        val size = packetOrder.size
        val skip = (size - maxSnapshotPackets).coerceAtLeast(0)
        if (size > 0) {
            // Use iterator with index skip — avoids copying into an intermediate list
            val iter = packetOrder.iterator()
            var idx = 0
            while (iter.hasNext()) {
                val pdp = iter.next()
                if (idx >= skip) {
                    byTarget.getOrPut(pdp.getTargetId()) { ArrayList() }.add(pdp)
                    byActor.getOrPut(pdp.getActorId()) { ArrayList() }.add(pdp)
                }
                idx++
            }
        }
        cachedByTargetSnapshot = byTarget
        cachedByActorSnapshot = byActor
        snapshotDirty = false
    }

    @Synchronized
    fun getBossModeDataSnapshot(): Map<Int, List<ParsedDamagePacket>> {
        rebuildSnapshotCaches()
        return cachedByTargetSnapshot
    }

    @Synchronized
    fun getActorDataSnapshot(): Map<Int, List<ParsedDamagePacket>> {
        rebuildSnapshotCaches()
        return cachedByActorSnapshot
    }

    fun appendMobHp(mid: Int, hp: Int) {
        if (hp > 0) {
            mobHpData[mid] = hp
        }
    }

    fun getMobHpData(): ConcurrentMap<Int, Int> {
        return mobHpData
    }

    fun getNickname(): ConcurrentHashMap<Int, String> {
        return nicknameStorage
    }

    fun getSummonData(): ConcurrentMap<Int, Int> {
        return summonStorage
    }

    fun getMobCodeData(): ConcurrentMap<Int, String> {
        return mobCodeData
    }

    fun getMobData(): ConcurrentMap<Int, Int> {
        return mobStorage
    }
}