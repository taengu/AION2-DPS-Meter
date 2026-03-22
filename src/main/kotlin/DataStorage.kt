package com.tbread

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
    private val summonStorage = ConcurrentHashMap<Int, Int>()
    private val mobCodeData = ConcurrentHashMap<Int, String>()
    private val mobStorage = ConcurrentHashMap<Int, Int>()
    private val knownPlayerIds = ConcurrentHashMap.newKeySet<Int>()
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
            return // Safely drop likely-NPC packets to save memory
        }

        // Track actors using player-class skills as known players
        if (isPlayerSkill(skillCode)) {
            val isNew = knownPlayerIds.add(pdp.getActorId())
            if (isNew) {
                purgeFriendlyPackets(pdp.getActorId())
            }
        }

        // Skip friendly actions (heals/buffs between known players or their summons)
        if (isFriendlyAction(pdp.getActorId(), pdp.getTargetId())) {
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
     * Remove stored packets where the newly-identified player [uid] was involved
     * in a friendly action (both actor and target are known players).
     * Called when a player is first identified (by nickname or player-class skill),
     * so heals that arrived before we knew both sides were players get retroactively cleaned out.
     */
    /**
     * Check if an action between actor and target is friendly (both sides resolve
     * to known players through summon ownership chains).
     */
    private fun isFriendlyAction(actorId: Int, targetId: Int): Boolean {
        val resolvedActor = SummonResolver.resolve(actorId, summonStorage)
        val resolvedTarget = SummonResolver.resolve(targetId, summonStorage)
        return knownPlayerIds.contains(resolvedActor) && knownPlayerIds.contains(resolvedTarget)
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


    fun appendMob(mid: Int, code: Int) {
        mobStorage[mid] = code
    }

    fun appendSummon(summoner: Int, summon: Int) {
        // Never register a known player (has nickname) as a summon
        if (nicknameStorage.containsKey(summon)) {
            logger.debug("Summon registration blocked: {} is a known player, not registering as summon of {}", summon, summoner)
            return
        }
        // Don't allow a summon or mob to be registered as an owner
        if (summonStorage.containsKey(summoner)) {
            logger.debug("Summon registration blocked: summoner {} is itself a summon, not registering {} as its summon", summoner, summon)
            return
        }
        if (mobStorage.containsKey(summoner) && !summonStorage.containsKey(summoner)) {
            logger.debug("Summon registration blocked: summoner {} is a known mob, not registering {} as its summon", summoner, summon)
            return
        }
        summonStorage[summon] = summoner
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
        logger.debug("Nickname registered {} -> {}", nicknameStorage[uid], nickname)
        UnifiedLogger.debug(logger, "Nickname registered {} -> {}", nicknameStorage[uid], nickname)
        nicknameStorage[uid] = nickname

        // If this ID was previously registered as a summon, remove it — it's a player
        if (summonStorage.remove(uid) != null) {
            logger.debug("Removed false summon mapping for player {} (nickname: {})", uid, nickname)
        }

        // Register as known player and retroactively purge friendly-action packets
        val isNew = knownPlayerIds.add(uid)
        if (isNew) {
            purgeFriendlyPackets(uid)
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

    @Synchronized
    fun resetNicknameStorage() {
        nicknameStorage.clear()
        pendingNicknameStorage.clear()
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