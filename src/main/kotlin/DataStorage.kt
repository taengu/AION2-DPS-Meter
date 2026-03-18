package com.tbread

import com.tbread.entity.ParsedDamagePacket
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
        mobHpData.clear()
        currentTarget.set(0)
        snapshotDirty = true
        cachedByTargetSnapshot = emptyMap()
        cachedByActorSnapshot = emptyMap()
        logger.info("Damage packets reset")
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