package com.tbread

import com.tbread.entity.FightRecord
import com.tbread.entity.FightSummary
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

object FightHistoryManager {
    private val logger = LoggerFactory.getLogger(FightHistoryManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val historyDir: File by lazy {
        val appdata = System.getenv("APPDATA") ?: System.getProperty("user.home")
        File(appdata, "AionDPS/history").also { it.mkdirs() }
    }

    fun save(record: FightRecord) {
        try {
            val file = File(historyDir, "${sanitizeId(record.id)}.json")
            file.writeText(json.encodeToString(record))
        } catch (e: Exception) {
            logger.warn("Failed to save fight record ${record.id}", e)
        }
    }

    fun list(): List<FightSummary> {
        return try {
            historyDir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(100)
                ?.mapNotNull { f ->
                    try {
                        val record = json.decodeFromString<FightRecord>(f.readText())
                        FightSummary(
                            id = record.id,
                            bossName = record.bossName,
                            targetId = record.targetId,
                            startTimeMs = record.startTimeMs,
                            durationMs = record.durationMs,
                            totalDamage = record.totalDamage,
                            jobs = record.jobs,
                            isTrain = record.isTrain,
                        )
                    } catch (e: Exception) {
                        logger.warn("Failed to read fight record ${f.name}", e)
                        null
                    }
                } ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to list fight history", e)
            emptyList()
        }
    }

    fun load(id: String): FightRecord? {
        return try {
            val file = File(historyDir, "${sanitizeId(id)}.json")
            if (!file.exists()) return null
            json.decodeFromString<FightRecord>(file.readText())
        } catch (e: Exception) {
            logger.warn("Failed to load fight record $id", e)
            null
        }
    }

    fun delete(id: String): Boolean {
        return try {
            File(historyDir, "${sanitizeId(id)}.json").delete()
        } catch (e: Exception) {
            logger.warn("Failed to delete fight record $id", e)
            false
        }
    }

    private fun sanitizeId(id: String): String = id.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
}
