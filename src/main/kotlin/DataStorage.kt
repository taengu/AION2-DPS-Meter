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

    @Synchronized
    fun appendDamage(pdp: ParsedDamagePacket) {
        byActorStorage.getOrPut(pdp.getActorId()) { ArrayList() }.add(pdp)
        byTargetStorage.getOrPut(pdp.getTargetId()) { ArrayList() }.add(pdp)
        applyPendingNickname(pdp.getActorId())
    }

    fun setCurrentTarget(targetId: Int){
        currentTarget.set(targetId)
    }

    fun getCurrentTarget(): Int{
        return currentTarget.get()
    }

    fun appendMob(mid: Int, code: Int) {
        mobStorage[mid] = code
    }

    fun appendSummon(summoner: Int, summon: Int) {
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
        summonStorage.clear()
        currentTarget.set(0)
        logger.info("Damage packets reset")
    }

    @Synchronized
    fun getBossModeDataSnapshot(): Map<Int, List<ParsedDamagePacket>> {
        val snapshot = HashMap<Int, List<ParsedDamagePacket>>()
        byTargetStorage.forEach { (k, v) ->
            snapshot[k] = ArrayList(v)
        }
        return snapshot
    }

    @Synchronized
    fun getActorDataSnapshot(): Map<Int, List<ParsedDamagePacket>> {
        val snapshot = HashMap<Int, List<ParsedDamagePacket>>()
        byActorStorage.forEach { (k, v) ->
            snapshot[k] = ArrayList(v)
        }
        return snapshot
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