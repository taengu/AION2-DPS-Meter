package com.tbread.logging

import com.tbread.DataStorage
import com.tbread.DpsCalculator
import com.tbread.entity.ParsedDamagePacket
import com.tbread.entity.SpecialDamage
import com.tbread.entity.SummonResolver
import com.tbread.packet.PropertyHandler
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object BossEncounterLogger {
    const val SETTING_KEY = "dpsMeter.bossLogsEnabled"
    private val logger = LoggerFactory.getLogger(BossEncounterLogger::class.java)
    private val fileTimeFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val lineFmt = DateTimeFormatter.ofPattern("mm:ss.SSS")
    private val logsDir = File("boss_logs")

    @Volatile
    var enabled = false

    fun loadFromSettings() {
        enabled = PropertyHandler.getProperty(SETTING_KEY)
            ?.trim()?.equals("true", ignoreCase = true) == true
    }

    fun dumpEncounter(dataStorage: DataStorage, dpsCalculator: DpsCalculator) {
        if (!enabled) return
        try {
            dumpEncounterInternal(dataStorage, dpsCalculator)
        } catch (e: Exception) {
            logger.error("Failed to write boss encounter log", e)
        }
    }

    private fun dumpEncounterInternal(dataStorage: DataStorage, dpsCalculator: DpsCalculator) {
        val byTarget = dataStorage.getBossModeDataSnapshot()
        if (byTarget.isEmpty()) return

        val nicknames = dataStorage.getNickname()
        val summonData = dataStorage.getSummonData()
        val mobData = dataStorage.getMobData()

        // Find the primary target (most total damage received)
        val primaryTargetId = byTarget.maxByOrNull { (_, packets) ->
            packets.sumOf { it.getDamage().toLong() }
        }?.key ?: return

        // Only log if the primary target is actually a boss
        if (!dpsCalculator.isBossTargetPublic(primaryTargetId)) return

        val allPackets = byTarget.values.flatten()
        if (allPackets.isEmpty()) return

        val targetName = dpsCalculator.resolveTargetNamePublic(primaryTargetId)
        if (targetName.isBlank()) return // Skip unnamed targets

        val firstHit = allPackets.minOf { it.getTimeStamp() }
        val fileTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(firstHit), ZoneId.systemDefault())
        val safeName = targetName.replace(Regex("[^\\w\\-]"), "_").take(40)
        val fileName = "${safeName}-${fileTime.format(fileTimeFmt)}.txt"

        if (!logsDir.exists()) logsDir.mkdirs()
        val file = File(logsDir, fileName)

        file.bufferedWriter().use { w ->
            val lastHit = allPackets.maxOf { it.getTimeStamp() }
            val duration = (lastHit - firstHit) / 1000
            val totalDmg = allPackets.sumOf { it.getDamage().toLong() + it.getMultiHitDamage().toLong() }

            w.write("$targetName | ${duration}s | ${totalDmg} total damage")
            w.newLine()

            // Per-player summary
            val byPlayer = mutableMapOf<String, Long>()
            for (pdp in allPackets) {
                val owner = resolveSummonerUid(pdp.getActorId(), summonData)
                val name = nicknames[owner] ?: nicknames[pdp.getActorId()] ?: pdp.getActorId().toString()
                byPlayer[name] = (byPlayer[name] ?: 0L) + pdp.getDamage() + pdp.getMultiHitDamage()
            }
            for ((name, dmg) in byPlayer.entries.sortedByDescending { it.value }) {
                val pct = if (totalDmg > 0) "%.1f%%".format(dmg * 100.0 / totalDmg) else "0%"
                w.write("  $name: $dmg ($pct)")
                w.newLine()
            }
            w.newLine()

            // Individual hits sorted by time
            val sorted = allPackets.sortedBy { it.getTimeStamp() }
            for (pdp in sorted) {
                val t = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(pdp.getTimeStamp()), ZoneId.systemDefault()
                ).format(lineFmt)
                val owner = resolveSummonerUid(pdp.getActorId(), summonData)
                val attacker = nicknames[owner] ?: nicknames[pdp.getActorId()] ?: pdp.getActorId().toString()
                val skill = DpsCalculator.SKILL_MAP[pdp.getSkillCode1()] ?: pdp.getSkillCode1().toString()
                val tgt = dpsCalculator.resolveTargetNamePublic(pdp.getTargetId())
                    .ifBlank { pdp.getTargetId().toString() }
                val dmg = pdp.getDamage() + pdp.getMultiHitDamage()
                val flags = buildFlags(pdp)
                val multiHitInfo = if (pdp.getMultiHitCount() > 0) " [${pdp.getMultiHitCount()}x multi=${pdp.getMultiHitDamage()}]" else ""
                w.write("$t $attacker > $tgt | $skill | $dmg$flags$multiHitInfo")
                w.newLine()
            }
        }
        logger.info("Boss encounter log saved: {}", file.name)
    }

    private fun buildFlags(pdp: ParsedDamagePacket): String {
        val parts = mutableListOf<String>()
        val specials = pdp.getSpecials()
        if (specials.contains(SpecialDamage.CRITICAL)) parts.add("C")
        if (specials.contains(SpecialDamage.BACK)) parts.add("B")
        if (specials.contains(SpecialDamage.PERFECT)) parts.add("P")
        if (specials.contains(SpecialDamage.DOUBLE)) parts.add("D")
        if (specials.contains(SpecialDamage.PARRY)) parts.add("Parry")
        if (pdp.isDoT()) parts.add("DoT")
        if (pdp.getMultiHitCount() > 0) parts.add("x${pdp.getMultiHitCount()}")
        return if (parts.isEmpty()) "" else " [${parts.joinToString(",")}]"
    }

    private fun resolveSummonerUid(actorId: Int, summonData: Map<Int, Int>): Int =
        SummonResolver.resolve(actorId, summonData)
}
