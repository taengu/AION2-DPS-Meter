package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.entity.ParsedDamagePacket
import com.tbread.entity.SpecialDamage
import com.tbread.DpsCalculator
import com.tbread.logging.UnifiedLogger
import org.slf4j.LoggerFactory

class StreamProcessor(private val dataStorage: DataStorage) {
    companion object {
        private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
    }

    private data class PendingCompactSkillContext(
        val actorId: Int,
        val skillRaw: Int
    )

    private val logger = LoggerFactory.getLogger(StreamProcessor::class.java)

    data class VarIntOutput(val value: Int, val length: Int)

    private val mask = 0x0f
    private val actorIdFilterKey = "dpsMeter.actorIdFilter"
    private var pendingCompactSkillContext: PendingCompactSkillContext? = null
    private val pendingSummonLinks = mutableSetOf<Int>()

    /**
     * Dedup set for embedded 04 38 scanning.
     * The game server re-sends recent damage records inside multiple consecutive context-update
     * packets, so the same sub-packet bytes can appear many times.  Tracking seen raw bytes
     * prevents triplication / quadruplication without losing legitimate distinct hits.
     */
    private val seenEmbeddedHexes = mutableSetOf<String>()

    private fun isActorAllowed(actorId: Int): Boolean {
        val rawFilter = PropertyHandler.getProperty(actorIdFilterKey)?.trim().orEmpty()
        if (rawFilter.isEmpty()) return true
        val filterValue = rawFilter.toIntOrNull() ?: return true
        return actorId == filterValue
    }

    private inner class DamagePacketReader(private val data: ByteArray, var offset: Int = 0) {
        fun readVarInt(): Int {
            if (offset >= data.size) return -1
            val result = readVarInt(data, offset)
            if (result.length <= 0 || offset + result.length > data.size) {
                return -1
            }
            offset += result.length
            return result.value
        }

        fun tryReadVarInt(): Int? {
            val value = readVarInt()
            return if (value < 0) null else value
        }

        fun remainingBytes(): Int = data.size - offset

    }

    fun consumeStream(buffer: ByteArray): Int {
        var offset = 0

        while (offset < buffer.size) {
            // 1. Skip zero padding
            if (buffer[offset] == 0x00.toByte()) {
                offset++
                continue
            }

            val lengthInfo = readVarInt(buffer, offset)
            if (lengthInfo.length <= 0 || lengthInfo.value <= 0) {
                // End of buffer reached, wait for more data
                if (offset + 5 > buffer.size) break
                offset++
                continue
            }

            // 2. THE AION 2 QUIRK:
            // Standard packets have 3 bytes inflation (length - 3 == physical size).
            val totalPacketBytes = lengthInfo.value - 3

            // INSTANT RESYNC: Expanded to 65535 because FF FF Bundles can be large
            // when they contain multiple payloads (like zoning into a new area).
            if (totalPacketBytes <= 0 || totalPacketBytes > 65535) {
                offset++ // Force immediate 1-byte resync
                continue
            }

            // 3. TCP Fragmentation Check (Aggressive Anti-Stall Gate)
            if (offset + totalPacketBytes > buffer.size) {
                // If the parser wants to stall and wait for more than 16KB, it's likely a trap.
                if (totalPacketBytes > 16384) {
                    offset++ // Break the stall, slide forward to find real hits instantly
                    continue
                }
                break // Legitimate fragment, wait for the next TCP chunk
            }

            // 4. Extract the perfectly aligned outer packet
            val fullPacket = buffer.copyOfRange(offset, offset + totalPacketBytes)

            // 5. Check if it's an FF FF Bundle
            val payloadStart = lengthInfo.length
            val isBundle = fullPacket.size > payloadStart + 1 &&
                    fullPacket[payloadStart] == 0xff.toByte() &&
                    fullPacket[payloadStart + 1] == 0xff.toByte()

            if (isBundle) {
                // Pass the raw payload (everything after the outer VarInt length) to the unwrapper
                val bundlePayload = fullPacket.copyOfRange(payloadStart, fullPacket.size)
                unwrapBundle(bundlePayload)
            } else {
                pendingSummonLinks.clear()
                parsePerfectPacket(fullPacket)
            }

            // 6. Advance exactly to the next outer boundary
            offset += totalPacketBytes
        }

        return offset
    }

    private fun unwrapBundle(payload: ByteArray) {
        // payload starts exactly at FF FF
        // Aion 2 bundles typically have an 8-byte header: FF FF + 6 bytes of sequence/metadata
        var offset = 8
        val damageCountBefore = currentStoredDamageCount()
        pendingCompactSkillContext = null
        pendingSummonLinks.clear()

        while (offset < payload.size) {
            // 1. Skip zero padding between nested packets
            if (payload[offset] == 0x00.toByte()) {
                offset++
                continue
            }

            val lengthInfo = readVarInt(payload, offset)
            if (lengthInfo.length <= 0 || lengthInfo.value <= 0) {
                break
            }

            val innerTotalBytes = lengthInfo.value - 3
            if (innerTotalBytes <= 0) {
                offset++
                continue
            }

            val innerPacketEnd = offset + innerTotalBytes
            if (innerPacketEnd > payload.size) {
                break // Safely break if padding or trailing garbage exceeds the bundle bounds
            }

            val innerPacket = payload.copyOfRange(offset, innerPacketEnd)

            // 2. Check if the nested packet is another bundle (rare, but protects against recursion bugs)
            val innerPayloadStart = lengthInfo.length
            val isNestedBundle = innerPacket.size > innerPayloadStart + 1 &&
                    innerPacket[innerPayloadStart] == 0xff.toByte() &&
                    innerPacket[innerPayloadStart + 1] == 0xff.toByte()

            if (isNestedBundle) {
                val nestedPayload = innerPacket.copyOfRange(innerPayloadStart, innerPacket.size)
                unwrapBundle(nestedPayload)
            } else {
                extractPendingCompactSkillContext(innerPacket)?.let { pendingCompactSkillContext = it }
                // It's a normal nested packet (like Damage, DoT, or Actor spawn)
                parsePerfectPacket(innerPacket)
            }

            // 3. Advance strictly to the next inner packet
            offset += innerTotalBytes
        }

        // Scan the full bundle payload for embedded 40 36 spawn opcodes.
        // Some bundles have only a few cleanly framed inner packets followed by a large
        // unframed payload containing spawn data that the inner packet loop can't reach.
        scanBundleForSummonSpawns(payload)

        // Resolve any summon spawns that couldn't find their owner in the same inner packet.
        // The owner info may be in a different inner packet or in the unframed bundle region.
        if (pendingSummonLinks.isNotEmpty()) {
            val ownerId = extractOwnerFromPacket(payload)
            if (ownerId != -1) {
                for (summonId in pendingSummonLinks) {
                    if (ownerId != summonId) {
                        logger.debug("Bundle summon linking: Owner {} -> Summon {}", ownerId, summonId)
                        UnifiedLogger.debugForActor(logger, ownerId, "Bundle summon linking: Owner {} -> Summon {}", ownerId, summonId)
                        dataStorage.appendSummon(ownerId, summonId)
                    }
                }
            }
            pendingSummonLinks.clear()
        }

        // If the normal bundle walk still produced no damage at all, fall back to a whole-body
        // embedded 04 38 scan so later combat frames are not lost.
        if (currentStoredDamageCount() == damageCountBefore && payload.isNotEmpty()) {
            tryParseEmbeddedDamagePacket(payload)
        }
        pendingCompactSkillContext = null
    }

    private fun currentStoredDamageCount(): Int {
        return dataStorage.getActorDataSnapshot().values.sumOf { it.size }
    }


    private fun sanitizeNickname(nickname: String): String? {
        val sanitizedNickname = nickname.substringBefore('\u0000').trim()
        if (sanitizedNickname.isEmpty()) return null
        val nicknameBuilder = StringBuilder()
        var onlyNumbers = true
        var hasHan = false
        for (ch in sanitizedNickname) {
            if (!Character.isLetterOrDigit(ch)) {
                if (nicknameBuilder.isEmpty()) return null
                break
            }
            if (ch == '\uFFFD') {
                if (nicknameBuilder.isEmpty()) return null
                break
            }
            if (Character.isISOControl(ch)) {
                if (nicknameBuilder.isEmpty()) return null
                break
            }
            nicknameBuilder.append(ch)
            if (Character.isLetter(ch)) onlyNumbers = false
            if (Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN) {
                hasHan = true
            }
        }
        val trimmedNickname = nicknameBuilder.toString()
        if (trimmedNickname.isEmpty()) return null
        if (trimmedNickname.length < 3 && !hasHan) return null
        if (onlyNumbers) return null
        if (trimmedNickname.length == 1 &&
            (trimmedNickname[0] in 'A'..'Z' || trimmedNickname[0] in 'a'..'z')
        ) {
            return null
        }
        return trimmedNickname
    }

    private fun parseActorNameBindingRules(packet: ByteArray): Boolean {
        var i = 0
        var lastAnchor: ActorAnchor? = null
        val namedActors = mutableSetOf<Int>()
        while (i < packet.size) {
            if (packet[i] == 0x36.toByte()) {
                if (i + 1 >= packet.size) {
                    i++
                    continue
                }
                val actorInfo = readVarInt(packet, i + 1)
                lastAnchor = if (actorInfo.length > 0 && actorInfo.value >= 100) {
                    ActorAnchor(actorInfo.value, i, i + 1 + actorInfo.length)
                } else {
                    null
                }
                i++
                continue
            }

            if (packet[i] == 0x07.toByte()) {
                val nameInfo = readUtf8Name(packet, i)
                if (nameInfo == null) {
                    i++
                    continue
                }
                if (lastAnchor != null && lastAnchor.actorId !in namedActors) {
                    val distance = i - lastAnchor.endIndex
                    if (distance >= 0) {
                        val canBind = registerUtf8Nickname(
                            packet,
                            lastAnchor.actorId,
                            nameInfo.first,
                            nameInfo.second
                        )
                        if (canBind) {
                            namedActors.add(lastAnchor.actorId)
                            return true
                        }
                    }
                }
            }
            i++
        }
        return false
    }

    private fun parseLootAttributionActorName(packet: ByteArray): Boolean {
        val candidates = mutableMapOf<Int, ActorNameCandidate>()
        var idx = 0
        while (idx + 2 < packet.size) {
            val marker = packet[idx].toInt() and 0xff
            val markerNext = packet[idx + 1].toInt() and 0xff
            val isMarker = marker in listOf(0xF5, 0xF8) && (markerNext == 0x03 || markerNext == 0xA3)
            if (isMarker) {
                var actorInfo: VarIntOutput? = null
                val minOffset = maxOf(0, idx - 8)
                for (actorOffset in minOffset..idx - 1) { // Scan forwards to catch multi-byte VarInts
                    if (!canReadVarInt(packet, actorOffset)) continue
                    val candidateInfo = readVarInt(packet, actorOffset)
                    if (candidateInfo.length <= 0 || actorOffset + candidateInfo.length != idx) continue
                    if (candidateInfo.value !in 100..99999) continue
                    actorInfo = candidateInfo
                    break
                }
                if (actorInfo == null) {
                    idx++
                    continue
                }
                val lengthIdx = idx + 2
                if (lengthIdx >= packet.size) {
                    idx++
                    continue
                }
                val nameLength = packet[lengthIdx].toInt() and 0xff
                if (nameLength !in 1..24) {
                    idx++
                    continue
                }
                val nameStart = lengthIdx + 1
                val nameEnd = nameStart + nameLength
                if (nameEnd > packet.size) {
                    idx++
                    continue
                }
                val nameBytes = packet.copyOfRange(nameStart, nameEnd)
                val possibleName = decodeUtf8Strict(nameBytes)
                if (possibleName == null) {
                    idx = nameEnd
                    continue
                }
                val sanitizedName = sanitizeNickname(possibleName)
                if (sanitizedName == null) {
                    idx = nameEnd
                    continue
                }
                val candidate = ActorNameCandidate(actorInfo.value, sanitizedName, nameBytes)
                val existingCandidate = candidates[candidate.actorId]
                if (existingCandidate == null || candidate.nameBytes.size > existingCandidate.nameBytes.size) {
                    candidates[candidate.actorId] = candidate
                }
                @Suppress("UNUSED_VALUE")
                idx = skipGuildName(packet, nameEnd)
                continue
            }
            idx++
        }

        if (candidates.isEmpty()) {
            val boundLocal = bindLocalNameFromLootAttributionPacket(packet)
            if (boundLocal) return true
            return false
        }
        val localName = LocalPlayer.characterName?.trim().orEmpty()
        val allowPrepopulate = candidates.size > 1
        var foundAny = false
        for (candidate in candidates.values) {
            val isLocalNameMatch = localName.isNotBlank() && candidate.name == localName
            val existingNickname = dataStorage.getNickname()[candidate.actorId]
            val canUpdateExisting = existingNickname != null &&
                    candidate.name.length > existingNickname.length &&
                    candidate.name.startsWith(existingNickname)
            val hasHanCharacters = candidate.name.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            if (!allowPrepopulate && !isLocalNameMatch && !actorAppearsInCombat(candidate.actorId) && !canUpdateExisting && !hasHanCharacters) {
                if (existingNickname == null) {
                    dataStorage.cachePendingNickname(candidate.actorId, candidate.name)
                }
                continue
            }
            if (existingNickname != null && !canUpdateExisting) continue
            logger.info(
                "Loot attribution actor name found {} -> {} (hex={})",
                candidate.actorId,
                candidate.name,
                toHex(candidate.nameBytes)
            )
            UnifiedLogger.info(
                logger,
                "Loot attribution actor name found {} -> {} (hex={})",
                candidate.actorId,
                candidate.name,
                toHex(candidate.nameBytes)
            )
            dataStorage.appendNickname(candidate.actorId, candidate.name)
            foundAny = true
        }
        return foundAny
    }

    private fun bindLocalNameFromLootAttributionPacket(packet: ByteArray): Boolean {
        val localName = LocalPlayer.characterName?.trim().orEmpty()
        if (localName.isBlank()) return false

        val keyIdx = findArrayIndex(packet, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
        if (keyIdx == -1) return false
        val afterPacket = packet.copyOfRange(keyIdx + 8, packet.size)

        val opcodeIdx = findArrayIndex(afterPacket, 0x07, 0x02, 0x06)
        if (opcodeIdx == -1) return false

        val actorOffset = keyIdx + opcodeIdx + 11
        if (actorOffset + 2 > packet.size) return false

        val actorId = parseUInt16le(packet, actorOffset)
        if (actorId !in 100..99999) return false

        val existingNickname = dataStorage.getNickname()[actorId]
        if (existingNickname != null && existingNickname.isNotBlank()) return false

        logger.info(
            "Loot attribution local player binding {} -> {}",
            actorId,
            localName
        )
        UnifiedLogger.info(
            logger,
            "Loot attribution local player binding {} -> {}",
            actorId,
            localName
        )
        dataStorage.appendNickname(actorId, localName)
        return true
    }

    private fun actorExists(actorId: Int): Boolean {
        return dataStorage.getNickname().containsKey(actorId) ||
                dataStorage.getActorDataSnapshot().containsKey(actorId) ||
                dataStorage.getBossModeDataSnapshot().containsKey(actorId) ||
                dataStorage.getSummonData().containsKey(actorId)
    }

    private fun actorAppearsInCombat(actorId: Int): Boolean {
        return dataStorage.getActorDataSnapshot().containsKey(actorId) ||
                dataStorage.getBossModeDataSnapshot().containsKey(actorId) ||
                dataStorage.getSummonData().containsKey(actorId)
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
        decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun skipGuildName(packet: ByteArray, startIndex: Int): Int {
        if (startIndex >= packet.size) return startIndex

        var offset = startIndex
        if (packet[offset] == 0x00.toByte()) {
            offset++
            if (offset >= packet.size) return offset
        }

        val length = packet[offset].toInt() and 0xff
        if (length !in 1..32) return offset
        val nameStart = offset + 1
        val nameEnd = nameStart + length
        if (nameEnd > packet.size) return offset
        val nameBytes = packet.copyOfRange(nameStart, nameEnd)
        decodeUtf8Strict(nameBytes) ?: return offset
        return nameEnd
    }

    private class ActorNameCandidate(
        val actorId: Int,
        val name: String,
        val nameBytes: ByteArray
    )

    private data class ActorAnchor(val actorId: Int, val startIndex: Int, val endIndex: Int)

    private fun readUtf8Name(packet: ByteArray, anchorIndex: Int): Pair<Int, Int>? {
        val lengthIndex = anchorIndex + 1
        if (lengthIndex >= packet.size) return null
        val nameLength = packet[lengthIndex].toInt() and 0xff
        if (nameLength !in 1..16) return null
        val nameStart = lengthIndex + 1
        val nameEnd = nameStart + nameLength
        if (nameEnd > packet.size) return null
        val nameBytes = packet.copyOfRange(nameStart, nameEnd)
        val possibleName = decodeUtf8Strict(nameBytes) ?: return null
        val sanitizedName = sanitizeNickname(possibleName) ?: return null
        if (sanitizedName.isEmpty()) return null
        return nameStart to nameLength
    }

    private fun registerUtf8Nickname(
        packet: ByteArray,
        actorId: Int,
        nameStart: Int,
        nameLength: Int
    ): Boolean {
        if (dataStorage.getNickname()[actorId] != null) return false
        if (nameLength <= 0 || nameLength > 16) return false
        val nameEnd = nameStart + nameLength
        if (nameStart < 0 || nameEnd > packet.size) return false
        val possibleNameBytes = packet.copyOfRange(nameStart, nameEnd)
        val possibleName = decodeUtf8Strict(possibleNameBytes) ?: return false
        val sanitizedName = sanitizeNickname(possibleName) ?: return false
        if (!actorExists(actorId)) {
            dataStorage.appendNickname(actorId, sanitizedName)
            return true
        }
        val existingNickname = dataStorage.getNickname()[actorId]
        if (existingNickname != sanitizedName) {
            logger.debug(
                "Actor name binding found {} -> {} (hex={})",
                actorId,
                sanitizedName,
                toHex(possibleNameBytes)
            )
            UnifiedLogger.debugForActor(
                logger,
                actorId,
                "Actor name binding found {} -> {} (hex={})",
                actorId,
                sanitizedName,
                toHex(possibleNameBytes)
            )
        }
        dataStorage.appendNickname(actorId, sanitizedName)
        return true
    }

    private fun parsePerfectPacket(packet: ByteArray): Boolean {
        if (packet.size < 3) return false
        val parsedDamage = parsingDamage(packet)
        val parsedName = parseActorNameBindingRules(packet) ||
                parseLootAttributionActorName(packet) ||
                parsingNickname(packet)
        val parsedSummon = parseSummonPacket(packet)
        if (!parsedDamage && !parsedName && !parsedSummon) {
            parseDoTPacket(packet)
        }
        return parsedDamage || parsedName
    }

    private fun parseDoTPacket(packet:ByteArray){
        var offset = 0
        val pdp = ParsedDamagePacket()
        pdp.setDot(true)
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return
        offset += packetLengthInfo.length

        if (packet.size <= offset + 1) return
        if (packet[offset] != 0x05.toByte()) return
        if (packet[offset+1] != 0x38.toByte()) return
        offset += 2
        if (packet.size < offset) return

        val targetInfo = readVarInt(packet,offset)
        if (targetInfo.length < 0) return
        offset += targetInfo.length
        if (packet.size < offset) return
        pdp.setTargetId(targetInfo)

        offset += 1 // padding
        if (packet.size < offset) return

        val actorInfo = readVarInt(packet,offset)
        if (actorInfo.length < 0) return
        if (actorInfo.value == targetInfo.value) return
        if (!isActorAllowed(actorInfo.value)) return
        offset += actorInfo.length
        if (packet.size < offset) return
        pdp.setActorId(actorInfo)

        val unknownInfo = readVarInt(packet,offset)
        if (unknownInfo.length < 0) return
        offset += unknownInfo.length

        if (offset + 4 > packet.size) return
        val skillCode:Int = parseUInt32le(packet,offset) / 100
        offset += 4
        if (packet.size <= offset) return
        pdp.setSkillCode(skillCode)

        val damageInfo = readVarInt(packet,offset)
        if (damageInfo.length < 0) return
        pdp.setDamage(damageInfo)

        if (logger.isDebugEnabled) {
            logger.debug("DoT Target: {}, attacker: {}, skill: {}, dmg: {}, hex={}", pdp.getTargetId(), pdp.getActorId(), pdp.getSkillCode1(), pdp.getDamage(), toHex(packet))
        }

        if (pdp.getActorId() != pdp.getTargetId()) {
            dataStorage.appendDamage(pdp)
        }
    }

    private fun findArrayIndex(data: ByteArray, vararg pattern: Int): Int {
        if (pattern.isEmpty()) return 0
        val p = ByteArray(pattern.size) { pattern[it].toByte() }
        return findArrayIndex(data, p)
    }

    private fun findArrayIndex(data: ByteArray, p: ByteArray): Int {
        val lps = IntArray(p.size)
        var len = 0
        for (i in 1 until p.size) {
            while (len > 0 && p[i] != p[len]) len = lps[len - 1]
            if (p[i] == p[len]) len++
            lps[i] = len
        }

        var i = 0
        var j = 0
        while (i < data.size) {
            if (data[i] == p[j]) {
                i++; j++
                if (j == p.size) return i - j
            } else if (j > 0) {
                j = lps[j - 1]
            } else {
                i++
            }
        }
        return -1
    }

    private fun parseSummonPacket(packet: ByteArray): Boolean {
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        offset += packetLengthInfo.length

        var foundSummon = false

        // 1. Standard 40 36 Spawn Opcode
        if (offset + 1 < packet.size && packet[offset] == 0x40.toByte() && packet[offset + 1] == 0x36.toByte()) {
            if (parseSummonSpawnAt(packet, offset + 2)) {
                foundSummon = true
            }
        }

        // 2. Check for embedded spawn opcodes
        var searchOffset = 0
        while (searchOffset + 1 < packet.size) {
            val opcodeOffset = findArrayIndexFromOffset(packet, searchOffset, byteArrayOf(0x40, 0x36))
            if (opcodeOffset == -1) break
            if (parseSummonSpawnAt(packet, opcodeOffset + 2)) {
                foundSummon = true
            }
            searchOffset = opcodeOffset + 2
        }

        // NOTE: The "Exact Property Match" (0E 06 38 / 0F 06 38) has been deliberately REMOVED.
        // Unreal Engine property replication indices are relative to the Actor's class.
        // For a Boss, properties 0E and 0F usually represent CurrentTarget and LastAttacker.
        // Blindly mapping them globally causes bosses to be registered as player summons.

        return foundSummon
    }

    private fun parseSummonSpawnAt(packet: ByteArray, offsetAfterOpcode: Int): Boolean {
        var offset = offsetAfterOpcode
        val targetInfo = readVarInt(packet, offset)
        if (targetInfo.length < 0) return false
        offset += targetInfo.length

        // Strip Object Type
        var realActorId = targetInfo.value
        if (realActorId > 1_000_000) {
            realActorId = (realActorId and 0x3FFF) or 0x4000
        }

        var foundSomething = false

        // Scan for NPC Type
        var mobTypeId = -1
        var scanOffset = offset
        val maxScan = minOf(packet.size - 2, offset + 60)

        while (scanOffset < maxScan) {
            if (packet[scanOffset] == 0x00.toByte() &&
                (packet[scanOffset + 1] == 0x40.toByte() || packet[scanOffset + 1] == 0x00.toByte()) &&
                packet[scanOffset + 2] == 0x02.toByte()
            ) {
                if (scanOffset - 3 >= offset) {
                    val b1 = packet[scanOffset - 3].toInt() and 0xFF
                    val b2 = packet[scanOffset - 2].toInt() and 0xFF
                    val b3 = packet[scanOffset - 1].toInt() and 0xFF
                    mobTypeId = b1 or (b2 shl 8) or (b3 shl 16)
                }
                break
            }
            scanOffset++
        }

        if (mobTypeId != -1) {
            logger.debug("Summon mob mapping succeeded: Target {} -> NPC Type {}", realActorId, mobTypeId)
            UnifiedLogger.debugForActor(logger, realActorId, "Summon mob mapping succeeded: Target {} -> NPC Type {}", realActorId, mobTypeId)
            dataStorage.appendMob(realActorId, mobTypeId)

            var hpScanOffset = scanOffset + 3
            val hpScanEnd = minOf(packet.size - 2, hpScanOffset + 64)
            while(hpScanOffset < hpScanEnd) {
                if (packet[hpScanOffset] == 0x01.toByte()) {
                    val currentHpInfo = readVarInt(packet, hpScanOffset + 1)
                    if (currentHpInfo.length > 0 && currentHpInfo.value > 0) {
                        val maxHpInfo = readVarInt(packet, hpScanOffset + 1 + currentHpInfo.length)
                        if (maxHpInfo.length > 0 && maxHpInfo.value >= currentHpInfo.value) {
                            dataStorage.appendMobHp(realActorId, maxHpInfo.value)
                            logger.debug("Summon mob HP mapped: Target {} -> Max HP {}", realActorId, maxHpInfo.value)
                            break
                        }
                    }
                }
                hpScanOffset++
            }
            foundSomething = true
        }

        // --- SUMMON OWNER LINKING ---
        // The owner (player) varint appears after the fixed 8-byte marker 80 75 D5 2A BB 03 00 00.
        // In non-bundle packets this marker is preceded by FF*8; in bundle inner packets it is not.
        val ownerId = extractOwnerFromPacket(packet)
        if (ownerId != -1 && ownerId != realActorId) {
            logger.debug("Summon owner linking: Owner {} -> Summon {}", ownerId, realActorId)
            UnifiedLogger.debugForActor(logger, ownerId, "Summon owner linking: Owner {} -> Summon {}", ownerId, realActorId)
            dataStorage.appendSummon(ownerId, realActorId)
            foundSomething = true
        } else {
            // Owner not in this packet — queue for bundle-level resolution
            pendingSummonLinks.add(realActorId)
        }

        return foundSomething
    }


    /**
     * Scan the full bundle payload for embedded 40 36 spawn opcodes that weren't
     * reached by the inner packet unwrapper. For each found, extract the summon ID
     * and call parseSummonSpawnAt to process mob type/HP and queue for owner linking.
     */
    private fun scanBundleForSummonSpawns(payload: ByteArray) {
        var searchOffset = 0
        while (searchOffset + 1 < payload.size) {
            val opcodeOffset = findArrayIndexFromOffset(payload, searchOffset, byteArrayOf(0x40, 0x36))
            if (opcodeOffset == -1) break
            parseSummonSpawnAt(payload, opcodeOffset + 2)
            searchOffset = opcodeOffset + 2
        }
    }

    /**
     * Extract owner (player) actor ID from a packet by finding the fixed 8-byte marker
     * 80 75 D5 2A BB 03 00 00 followed by the owner varint.
     * Returns -1 if not found.
     */
    private fun extractOwnerFromPacket(packet: ByteArray): Int {
        val marker = byteArrayOf(
            0x80.toByte(), 0x75, 0xD5.toByte(), 0x2A, 0xBB.toByte(), 0x03, 0x00, 0x00
        )
        val markerIdx = findArrayIndex(packet, marker)
        if (markerIdx == -1) return -1
        val ownerOffset = markerIdx + marker.size
        if (ownerOffset >= packet.size) return -1
        val ownerInfo = readVarInt(packet, ownerOffset)
        if (ownerInfo.length <= 0 || ownerInfo.value !in 100..999999) return -1
        return ownerInfo.value
    }

    private fun findArrayIndexFromOffset(data: ByteArray, offset: Int, pattern: ByteArray): Int {
        if (offset >= data.size) return -1
        if (offset <= 0) return findArrayIndex(data, pattern)
        val idx = findArrayIndex(data.copyOfRange(offset, data.size), pattern)
        return if (idx == -1) -1 else offset + idx
    }

    private fun parseUInt16le(packet: ByteArray, offset: Int = 0): Int {
        return (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun parseUInt32le(packet: ByteArray, offset: Int = 0): Int {
        require(offset + 4 <= packet.size) { "Packet length is shorter than required" }
        return ((packet[offset].toInt() and 0xFF)) or
                ((packet[offset + 1].toInt() and 0xFF) shl 8) or
                ((packet[offset + 2].toInt() and 0xFF) shl 16) or
                ((packet[offset + 3].toInt() and 0xFF) shl 24)
    }


    private fun canReadVarInt(bytes: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset >= bytes.size) return false
        var idx = offset
        var count = 0
        while (idx < bytes.size && count < 5) {
            val byteVal = bytes[idx].toInt() and 0xff
            if ((byteVal and 0x80) == 0) {
                return true
            }
            idx++
            count++
        }
        return false
    }

    private fun parsingNickname(packet: ByteArray): Boolean {
        var parsedAny = false
        var searchOffset = 0

        while (searchOffset < packet.size - 2) {

            // PATTERN A: The "E2 07" Anchor (Perfectly catches 黑雪姬 -> 1181)
            // Aion strictly places E2 07 between the VarInt ID and the name length.
            if (packet[searchOffset] == 0xE2.toByte() && packet[searchOffset + 1] == 0x07.toByte()) {
                val lenIdx = searchOffset + 2
                if (lenIdx < packet.size) {
                    val nameLen = packet[lenIdx].toInt() and 0xFF
                    if (nameLen in 2..32 && lenIdx + 1 + nameLen <= packet.size) {
                        val np = packet.copyOfRange(lenIdx + 1, lenIdx + 1 + nameLen)
                        val possibleName = decodeUtf8Strict(np)

                        if (possibleName != null && possibleName.isNotEmpty() && Character.isLetterOrDigit(possibleName[0])) {
                            val sanitizedName = sanitizeNickname(possibleName)
                            if (sanitizedName != null && sanitizedName.length >= 2) {
                                // Name is valid! Look strictly backwards for the VarInt ID
                                for (vLen in 1..3) {
                                    val vStart = searchOffset - vLen
                                    if (vStart >= 0 && canReadVarInt(packet, vStart)) {
                                        val v = readVarInt(packet, vStart)
                                        if (v.length == vLen && v.value in 100..9999999) {
                                            logger.debug("Confirmed Pattern A nickname {} -> {}", v.value, sanitizedName)
                                            UnifiedLogger.debugForActor(logger, v.value, "Confirmed Pattern A nickname {} -> {}", v.value, sanitizedName)
                                            dataStorage.appendNickname(v.value, sanitizedName)
                                            parsedAny = true
                                            searchOffset = lenIdx + nameLen
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // PATTERN B: The "0F 1D 37" Block Anchor (Perfectly catches 业火红莲 -> 108)
            // Massive property update blocks that contain the string hundreds of bytes away from the ID.
            if (searchOffset + 2 < packet.size &&
                packet[searchOffset] == 0x0F.toByte() &&
                packet[searchOffset + 1] == 0x1D.toByte() &&
                packet[searchOffset + 2] == 0x37.toByte()) {

                val idOffset = searchOffset + 3
                if (canReadVarInt(packet, idOffset)) {
                    val blockActor = readVarInt(packet, idOffset)
                    if (blockActor.value in 100..9999999) {

                        var blockScan = idOffset + blockActor.length
                        val blockEnd = minOf(packet.size, blockScan + 500)

                        while (blockScan < blockEnd - 3) {
                            // Stop if we hit the Aion block termination marker
                            if (packet[blockScan] == 0x06.toByte() && packet[blockScan+1] == 0x00.toByte() && packet[blockScan+2] == 0x36.toByte()) {
                                break
                            }

                            // STRICT REQUIREMENT: The name length byte must be preceded by 00 00.
                            // This instantly prevents random coordinates from being parsed as "zaF".
                            if (packet[blockScan] == 0x00.toByte() && packet[blockScan + 1] == 0x00.toByte()) {
                                val lenIdx = blockScan + 2
                                val nameLen = packet[lenIdx].toInt() and 0xFF
                                if (nameLen in 2..32 && lenIdx + 1 + nameLen <= packet.size) {
                                    val np = packet.copyOfRange(lenIdx + 1, lenIdx + 1 + nameLen)
                                    val possibleName = decodeUtf8Strict(np)
                                    if (possibleName != null && possibleName.isNotEmpty() && Character.isLetterOrDigit(possibleName[0])) {
                                        val sanitizedName = sanitizeNickname(possibleName)
                                        if (sanitizedName != null && sanitizedName.length >= 2) {
                                            logger.debug("Confirmed Pattern B nickname {} -> {}", blockActor.value, sanitizedName)
                                            UnifiedLogger.debugForActor(logger, blockActor.value, "Confirmed Pattern B nickname {} -> {}", blockActor.value, sanitizedName)
                                            dataStorage.appendNickname(blockActor.value, sanitizedName)
                                            parsedAny = true
                                            blockScan = lenIdx + nameLen
                                            continue
                                        }
                                    }
                                }
                            }
                            blockScan++
                        }
                    }
                }
            }

            // PATTERN C: The Legacy Gatekeeper Net
            // Catches names immediately following standard 04 8D spawn opcodes if E2 07 is missing
            val b0 = packet[searchOffset].toInt() and 0xFF
            val b1 = packet[searchOffset + 1].toInt() and 0xFF
            if ((b0 == 0x04 && (b1 == 0x8D || b1 == 0x4C)) || (b0 == 0xF2 && b1 == 0x04)) {
                var idx = searchOffset + 2
                val stopScanningAt = minOf(packet.size, idx + 64)

                while (idx < stopScanningAt - 2) {
                    if (canReadVarInt(packet, idx)) {
                        val playerInfo = readVarInt(packet, idx)
                        if (playerInfo.length > 0 && playerInfo.value in 100..9999999) {
                            // Padding strictly bounded to 0..2 to prevent aggressive false positives
                            for (padding in 0..2) {
                                val lenIdx = idx + playerInfo.length + padding
                                if (lenIdx >= packet.size) continue
                                val nameLen = packet[lenIdx].toInt() and 0xFF
                                if (nameLen in 2..32 && lenIdx + 1 + nameLen <= packet.size) {
                                    val np = packet.copyOfRange(lenIdx + 1, lenIdx + 1 + nameLen)
                                    val possibleName = decodeUtf8Strict(np)
                                    if (possibleName != null && possibleName.isNotEmpty() && Character.isLetterOrDigit(possibleName[0])) {
                                        val sanitizedName = sanitizeNickname(possibleName)
                                        if (sanitizedName != null && sanitizedName.length >= 2) {
                                            logger.debug("Confirmed Pattern C nickname {} -> {}", playerInfo.value, sanitizedName)
                                            UnifiedLogger.debugForActor(logger, playerInfo.value, "Confirmed Pattern C nickname {} -> {}", playerInfo.value, sanitizedName)
                                            dataStorage.appendNickname(playerInfo.value, sanitizedName)
                                            parsedAny = true
                                            searchOffset = lenIdx + nameLen
                                            break
                                        }
                                    }
                                }
                            }
                            if (parsedAny) break
                        }
                    }
                    idx++
                }
            }

            // PATTERN D: The Terminator Anchor
            // Safely catches names attached to the very end of large property blocks (e.g., Naicha)
            if ((b0 == 0x04 || b0 == 0x00) && (b1 == 0x8D || b1 == 0x4C)) {
                val idIdx = searchOffset + 2
                if (canReadVarInt(packet, idIdx)) {
                    val playerInfo = readVarInt(packet, idIdx)
                    if (playerInfo.length > 0 && playerInfo.value in 100..9999999) {
                        // Scan up to 128 bytes ahead to find the universal terminator 06 00 36
                        val stopScanningAt = minOf(packet.size - 2, idIdx + 128)
                        var scanIdx = idIdx + playerInfo.length

                        while (scanIdx < stopScanningAt) {
                            if (packet[scanIdx] == 0x06.toByte() && packet[scanIdx + 1] == 0x00.toByte() && packet[scanIdx + 2] == 0x36.toByte()) {
                                // We found the terminator! Look strictly backwards for the string.
                                for (testLen in 2..32) {
                                    val lenByteIdx = scanIdx - testLen - 1
                                    if (lenByteIdx > idIdx) {
                                        val possibleLen = packet[lenByteIdx].toInt() and 0xFF
                                        if (possibleLen == testLen) {
                                            val np = packet.copyOfRange(lenByteIdx + 1, lenByteIdx + 1 + testLen)
                                            val possibleName = decodeUtf8Strict(np)

                                            if (possibleName != null && possibleName.isNotEmpty() && Character.isLetterOrDigit(possibleName[0])) {
                                                val sanitizedName = sanitizeNickname(possibleName)
                                                if (sanitizedName != null && sanitizedName.length >= 2) {
                                                    logger.debug("Confirmed Pattern D nickname {} -> {}", playerInfo.value, sanitizedName)
                                                    UnifiedLogger.debugForActor(logger, playerInfo.value, "Confirmed Pattern D nickname {} -> {}", playerInfo.value, sanitizedName)
                                                    dataStorage.appendNickname(playerInfo.value, sanitizedName)
                                                    parsedAny = true
                                                    searchOffset = scanIdx // Fast forward past the block
                                                    break
                                                }
                                            }
                                        }
                                    }
                                }
                                if (parsedAny) break
                            }
                            scanIdx++
                        }
                    }
                }
            }

            searchOffset++
        }
        return parsedAny
    }

    private fun tryParseEmbeddedDamagePacket(packet: ByteArray): Boolean {
        if (packet.size < 6) return false

        val op0 = 0x04.toByte()
        val op1 = 0x38.toByte()
        var parsedAny = false
        var searchOffset = 0

        while (searchOffset < packet.size - 1) {
            if (packet[searchOffset] != op0 || packet[searchOffset + 1] != op1) {
                searchOffset++
                continue
            }

            val remainingSize = packet.size - searchOffset
            val copyLen = remainingSize

            // Dedup: build the key from the raw bytes starting at 04 38 (no synthetic header).
            // The game server re-sends recent damage records inside multiple context-update
            // packets; identical raw bytes mean the same damage event → skip.
            val rawKey = toHexRange(packet, searchOffset, minOf(searchOffset + 64, packet.size))
            if (rawKey in seenEmbeddedHexes) {
                searchOffset++
                continue
            }

            val headlessCandidate = ByteArray(copyLen + 2)
            headlessCandidate[0] = 0xFF.toByte()
            headlessCandidate[1] = 0x01.toByte()
            System.arraycopy(packet, searchOffset, headlessCandidate, 2, copyLen)

            if (parsingDamage(headlessCandidate, allowEmbeddedScan = false, requireTrustedDamageShape = true)) {
                seenEmbeddedHexes.add(rawKey)
                parsedAny = true
                searchOffset += 2 // We caught a hit! Step past the 04 38
            } else {
                searchOffset++
            }
        }
        return parsedAny
    }

    private fun parsingDamage(
        packet: ByteArray,
        allowEmbeddedScan: Boolean = true,
        requireTrustedDamageShape: Boolean = false
    ): Boolean {
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        val reader = DamagePacketReader(packet, packetLengthInfo.length)

        if (reader.offset >= packet.size) return false
        if (reader.offset + 1 >= packet.size) return false

        // STRICT GATEKEEPER
        if (packet[reader.offset] != 0x04.toByte() || packet[reader.offset + 1] != 0x38.toByte()) {
            if (allowEmbeddedScan && tryParseEmbeddedDamagePacket(packet)) {
                return true
            }
            return false
        }
        reader.offset += 2

        var parsedAny = false

        while (reader.remainingBytes() > 0) {
            val checkpoint = reader.offset

            var isChainedHitMarker = false
            if (reader.remainingBytes() >= 2 && packet[reader.offset] == 0x01.toByte() && packet[reader.offset + 1] == 0x00.toByte()) {
                reader.offset += 2
                isChainedHitMarker = true
            }

            if (parsedAny && !isChainedHitMarker) {
                break
            }

            val targetValue = reader.tryReadVarInt() ?: break
            if (targetValue < 100) { reader.offset = checkpoint; break }
            val targetInfo = VarIntOutput(targetValue, 1)

            val switchValue = reader.tryReadVarInt() ?: break
            val andResult = switchValue and mask

            if (andResult !in 4..7) {
                // Not a damage packet (buff-only, etc.) — stop parsing, not an error
                reader.offset = checkpoint; break
            }

            reader.tryReadVarInt() ?: break // Unused flag

            val actorValue = reader.tryReadVarInt() ?: break
            if (actorValue < 100) { reader.offset = checkpoint; break }
            val actorInfo = VarIntOutput(actorValue, 1)

            val isAllowed = isActorAllowed(actorInfo.value)

            // Exact 4-byte Skill ID (No rounding / 100)
            if (reader.offset + 4 > packet.size) { reader.offset = checkpoint; break }
            val exactSkillCode = (packet[reader.offset].toLong() and 0xFF) or
                    ((packet[reader.offset + 1].toLong() and 0xFF) shl 8) or
                    ((packet[reader.offset + 2].toLong() and 0xFF) shl 16) or
                    ((packet[reader.offset + 3].toLong() and 0xFF) shl 24)
            reader.offset += 4

            // Reject out-of-range skill codes immediately — indicates we're not on a real damage packet
            if (exactSkillCode !in 1L..299_999_999L) { reader.offset = checkpoint; break }

            // 7-digit skill codes (1,000,000–9,999,999) are NPC/boss/mob skills — skip them
            if (exactSkillCode in 1_000_000L..9_999_999L) { reader.offset = checkpoint; break }

            // Skip the always-present 1-byte UID field after the skill code
            if (reader.remainingBytes() > 0) {
                reader.offset += 1
            }

            val dummyType = reader.tryReadVarInt() ?: break
            val damageType = dummyType.toByte()

            var tempV = 8
            if (andResult == 5) tempV = 12
            else if (andResult == 6) tempV = 10
            else if (andResult == 7) tempV = 14

            val specials = mutableListOf<SpecialDamage>()
            if (andResult in listOf(5, 6, 7) && reader.remainingBytes() > 0) {
                val specialByte = packet[reader.offset].toInt() and 0xFF
                if ((specialByte and 0x01) != 0) specials.add(SpecialDamage.BACK)
                if ((specialByte and 0x04) != 0) specials.add(SpecialDamage.PARRY)
                if ((specialByte and 0x08) != 0) specials.add(SpecialDamage.PERFECT)
                if ((specialByte and 0x10) != 0) specials.add(SpecialDamage.DOUBLE)
                if ((specialByte and 0x40) != 0) specials.add(SpecialDamage.SMITE)
            }
            if (damageType.toInt() == 3) specials.add(SpecialDamage.CRITICAL)

            reader.offset += tempV
            if (reader.offset >= packet.size) { reader.offset = checkpoint; break }

            // --- STRUCT DATA EXTRACTION ---
            // Most packets store attack speed before damage, but some crit/special variants
            // place damage first and follow it with a tiny counter/flag value.
            val firstValue = reader.tryReadVarInt() ?: break
            val afterFirstValueOffset = reader.offset
            val secondValue = reader.tryReadVarInt() ?: break

            val firstValueIsDamage = shouldTreatFirstValueAsDamage(
                firstValue = firstValue,
                secondValue = secondValue,
                andResult = andResult,
                damageType = damageType.toInt()
            )

            var finalDamage = if (firstValueIsDamage) {
                reader.offset = afterFirstValueOffset
                firstValue
            } else {
                secondValue
            }

            // 3. Multi-Hit Count & Target HP Safeguard
            var hitCount = 0
            val preHitOffset = reader.offset

            if (reader.remainingBytes() > 0) {
                val isMarkerNext = reader.remainingBytes() >= 2 &&
                        packet[reader.offset + 1] == 0x00.toByte() &&
                        packet[reader.offset] in 1..3

                if (!isMarkerNext) {
                    val peekVal = reader.tryReadVarInt()
                    if (peekVal != null) {
                        if (peekVal in 0..25) {
                            hitCount = peekVal
                        } else {
                            // Secondary stat like Target HP detected. Grab the real hit count.
                            val isMarkerAfterHP = reader.remainingBytes() >= 2 &&
                                    packet[reader.offset + 1] == 0x00.toByte() &&
                                    packet[reader.offset] in 1..3

                            if (!isMarkerAfterHP) {
                                val actualHitCount = reader.tryReadVarInt()
                                if (actualHitCount != null && actualHitCount in 0..25) {
                                    hitCount = actualHitCount
                                } else {
                                    reader.offset = preHitOffset
                                }
                            } else {
                                hitCount = 0
                            }
                        }
                    }
                }
            }

            if (finalDamage < 0 || finalDamage > 99_999_999) { reader.offset = checkpoint; break }

            // 4. Extract Multi-Hits
            var multiHitCount = 0
            var multiHitDamage = 0
            var firstMultiHitValue: Int? = null
            var allMultiHitsMatch = true

            if (hitCount > 0 && reader.remainingBytes() > 0) {
                var hitSum = 0
                var hitsRead = 0
                val safeMaxHits = minOf(hitCount, 25)

                while (hitsRead < safeMaxHits && reader.remainingBytes() > 0) {
                    val isMarkerNext = reader.remainingBytes() >= 2 &&
                            packet[reader.offset + 1] == 0x00.toByte() &&
                            packet[reader.offset] in 1..3

                    val isNextPacket = reader.remainingBytes() >= 2 &&
                            packet[reader.offset] == 0x04.toByte() &&
                            packet[reader.offset + 1] == 0x38.toByte()

                    if (isMarkerNext || isNextPacket) break

                    val hitValue = reader.tryReadVarInt() ?: break
                    if (firstMultiHitValue == null) {
                        firstMultiHitValue = hitValue
                    } else if (firstMultiHitValue != hitValue) {
                        allMultiHitsMatch = false
                    }
                    hitSum += hitValue
                    hitsRead++
                }
                multiHitCount = hitsRead
                multiHitDamage = hitSum
            }

            if (switchValue == 54 && hitCount > multiHitCount && multiHitCount == 1 && firstMultiHitValue != null && allMultiHitsMatch) {
                multiHitCount = hitCount
                multiHitDamage = firstMultiHitValue * hitCount
            }

            if (shouldUseRepeatedHitDamage(
                    switchValue = switchValue,
                    encodedDamage = secondValue,
                    multiHitCount = multiHitCount,
                    firstMultiHitValue = firstMultiHitValue,
                    allMultiHitsMatch = allMultiHitsMatch
                )
            ) {
                finalDamage = firstMultiHitValue!!
            }

            val pendingCompactContext = pendingCompactSkillContext
            val aggregatedCompactSkill = pendingCompactContext != null &&
                    exactSkillCode.toInt() == 99_745_942 &&
                    actorInfo.value == pendingCompactContext.actorId &&
                    hitCount > 1 &&
                    multiHitDamage > 0 &&
                    secondValue > multiHitDamage

            val resolvedSkillCode = if (aggregatedCompactSkill) {
                pendingCompactContext.skillRaw
            } else {
                normalizeSkillId(exactSkillCode.toInt())
            }
            if (aggregatedCompactSkill) {
                finalDamage = secondValue - multiHitDamage
                pendingCompactSkillContext = null
            }

            // 5. Extract Trailing Heals
            var healAmount = 0
            if (!firstValueIsDamage && reader.remainingBytes() >= 2 && packet[reader.offset + 1] == 0x00.toByte()) {
                val marker = packet[reader.offset]
                if (marker in 1..3) {
                    reader.offset += 2
                    val trailingValue = reader.tryReadVarInt()

                    if (trailingValue != null) {
                        healAmount = trailingValue
                    } else if (marker == 0x01.toByte()) {
                        healAmount = 0
                    } else {
                        healAmount = finalDamage
                    }
                }
            }

            val appendedToMeter = pdpTargetActorMismatch(targetInfo.value, actorInfo.value)
            if (requireTrustedDamageShape && !isTrustedRecoveredDamageShape(
                    actorId = actorInfo.value,
                    targetId = targetInfo.value,
                    damageType = dummyType,
                    damage = finalDamage,
                    skillCode = resolvedSkillCode
                )
            ) {
                reader.offset = checkpoint
                break
            }

            val pdp = ParsedDamagePacket()
            pdp.setTargetId(targetInfo)
            pdp.setActorId(actorInfo)
            pdp.setSkillCode(resolvedSkillCode)
            val typeInfo = VarIntOutput(dummyType, 1)
            pdp.setType(typeInfo)
            pdp.setSpecials(specials)
            pdp.setMultiHitCount(multiHitCount)
            pdp.setMultiHitDamage(multiHitDamage)
            pdp.setHealAmount(healAmount)
            pdp.setDamage(VarIntOutput(finalDamage, 1))
            pdp.setHexPayload(toHex(packet))

            if (UnifiedLogger.isDebugEnabled() && appendedToMeter) {
                UnifiedLogger.debugForActors(
                    logger,
                    pdp.getActorId(), pdp.getTargetId(),
                    "Target: {}, attacker: {}, skill: {}, type: {}, damage: {}, hits: {}, hex={}",
                    pdp.getTargetId(), pdp.getActorId(), pdp.getSkillCode1(), pdp.getType(), pdp.getDamage(), pdp.getMultiHitCount(), toHex(packet)
                )
            }

            if (isAllowed && appendedToMeter) {
                dataStorage.appendDamage(pdp)
            }

            parsedAny = true
        }

        return parsedAny
    }

    private fun pdpTargetActorMismatch(targetId: Int, actorId: Int): Boolean = actorId != targetId

    private fun extractPendingCompactSkillContext(packet: ByteArray): PendingCompactSkillContext? {
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length <= 0 || packetLengthInfo.length >= packet.size) return null
        val body = packet.copyOfRange(packetLengthInfo.length, packet.size)

        var markerIndex = -1
        for (idx in 0 until body.size - 4) {
            if (body[idx] == 0x08.toByte() &&
                body[idx + 1] in byteArrayOf(0x3B.toByte(), 0x3D.toByte()) &&
                body[idx + 2] == 0x38.toByte() &&
                body[idx + 3] == 0x00.toByte() &&
                body[idx + 4] == 0x00.toByte()
            ) {
                markerIndex = idx
                break
            }
        }
        if (markerIndex < 0) return null

        var compactOpcode = -1
        for (idx in markerIndex + 5 until body.size) {
            if (body[idx] == 0x38.toByte()) {
                compactOpcode = idx
                break
            }
        }
        if (compactOpcode < 0 || compactOpcode + 2 >= body.size) return null

        val actorInfo = readVarInt(body, compactOpcode + 1)
        if (actorInfo.length <= 0 || actorInfo.value < 100) return null

        val uidOffset = compactOpcode + 1 + actorInfo.length
        if (uidOffset >= body.size) return null
        val skillOffset = uidOffset + 1
        if (skillOffset + 3 > body.size) return null

        val candidates = mutableListOf<Int>()
        if (skillOffset + 4 <= body.size) {
            val fullSkill = (body[skillOffset].toInt() and 0xFF) or
                    ((body[skillOffset + 1].toInt() and 0xFF) shl 8) or
                    ((body[skillOffset + 2].toInt() and 0xFF) shl 16) or
                    ((body[skillOffset + 3].toInt() and 0xFF) shl 24)
            candidates.add(fullSkill)
        }
        val compactSkill = (body[skillOffset].toInt() and 0xFF) or
                ((body[skillOffset + 1].toInt() and 0xFF) shl 8) or
                ((body[skillOffset + 2].toInt() and 0xFF) shl 16)
        candidates.add(compactSkill)

        for (candidate in candidates.distinct()) {
            if (!isKnownSkillCode(candidate)) continue
            return PendingCompactSkillContext(
                actorId = actorInfo.value,
                skillRaw = normalizeSkillId(candidate)
            )
        }
        return null
    }

    private fun isTrustedDamageShape(actorId: Int, targetId: Int, damageType: Int, damage: Int): Boolean {
        if (actorId == targetId) return false
        if (damageType !in 1..3) return false
        if (damage <= 0) return false
        return true
    }

    private fun isTrustedRecoveredDamageShape(
        actorId: Int,
        targetId: Int,
        damageType: Int,
        damage: Int,
        skillCode: Int
    ): Boolean {
        if (!isTrustedDamageShape(actorId, targetId, damageType, damage)) return false
        if (!isKnownSkillCode(skillCode)) return false
        return true
    }

    private fun toHexRange(bytes: ByteArray, startInclusive: Int, endExclusive: Int): String {
        if (bytes.isEmpty()) return ""
        val start = startInclusive.coerceIn(0, bytes.size)
        val end = endExclusive.coerceIn(start, bytes.size)
        val length = end - start
        if (length <= 0) return ""
        val hex = CharArray(length * 3 - 1)
        var pos = 0
        for (index in 0 until length) {
            val value = bytes[start + index].toInt() and 0xFF
            val high = value ushr 4
            val low = value and 0x0F
            hex[pos++] = HEX_DIGITS[high]
            hex[pos++] = HEX_DIGITS[low]
            if (index != length - 1) {
                hex[pos++] = ' '
            }
        }
        return String(hex)
    }

    private fun toHex(bytes: ByteArray): String = toHexRange(bytes, 0, bytes.size)

    private fun shouldTreatFirstValueAsDamage(
        firstValue: Int,
        secondValue: Int,
        andResult: Int,
        damageType: Int
    ): Boolean {
        if (firstValue !in 1_000..99_999_999) return false
        if (secondValue !in 0..25) return false

        // Known Shadowstrike-style crit packets (e.g. BACK/DOUBLE special hits) encode the
        // real damage first, followed by a tiny counter/flag value instead of attack speed.
        return andResult == 6 && damageType == 3
    }

    private fun shouldUseRepeatedHitDamage(
        switchValue: Int,
        encodedDamage: Int,
        multiHitCount: Int,
        firstMultiHitValue: Int?,
        allMultiHitsMatch: Boolean
    ): Boolean {
        val repeatedDamage = firstMultiHitValue ?: return false
        if (switchValue != 54) return false
        if (multiHitCount <= 0 || !allMultiHitsMatch) return false

        // Some switch=54 packets encode the displayed per-hit damage in the repeated hit list,
        // while the "damage" field is just that value scaled by 10 with a trailing counter nibble.
        return encodedDamage / 10 == repeatedDamage
    }

    private fun normalizeSkillId(raw: Int): Int {
        // 1. Always preserve Theostones (8-digit IDs starting with 30M)
        if (raw in 30_000_000..30_999_999) {
            return raw
        }

        val base = raw - (raw % 10000)

        // 2. Data-Driven Aggregation

        // If we don't know the base, we have to stick with the raw ID
        val baseName = DpsCalculator.SKILL_MAP[base] ?: return raw

        // If we only know the base name but not the specific variant, use the base
        val rawName = DpsCalculator.SKILL_MAP[raw] ?: return base

        // If both are known, but the variant has a fundamentally different name than the base, preserve it!
        // (e.g., 11050047 "Wave Attack" != 11050000 "Crushing Wave")
        // (e.g., 16001101 "Fire Spirit: Flame Explosion" != 16000000 "Basic Attack")
        if (rawName != baseName) {
            return raw
        }

        // If the names are identical, safely crush it to the base for clean UI aggregation.
        // (e.g., 11020047 "Keen Strike" == 11020000 "Keen Strike")
        return base
    }

    private fun isValidSkillCode(skillCode: Int): Boolean {
        // Range covers everything from auto-attacks (1+) to bosses (200m+),
        // while strictly blocking random memory addresses in the billions.
        return skillCode in 1..299_999_999
    }

    private fun isKnownSkillCode(skillCode: Int): Boolean {
        if (!isValidSkillCode(skillCode)) return false
        val normalized = normalizeSkillId(skillCode)
        if (!isValidSkillCode(normalized)) return false
        if (normalized in 30_000_000..30_999_999) return true
        return DpsCalculator.SKILL_MAP.containsKey(normalized) || DpsCalculator.SKILL_MAP.containsKey(skillCode)
    }


    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntOutput {
        var value = 0
        var shift = 0
        var count = 0

        while (true) {
            if (offset + count >= bytes.size) {
                return VarIntOutput(-1, -1)
            }

            val byteVal = bytes[offset + count].toInt() and 0xff
            count++

            value = value or ((byteVal and 0x7F) shl shift)

            if ((byteVal and 0x80) == 0) {
                return VarIntOutput(value, count)
            }

            shift += 7
            if (shift >= 32) {
                logger.trace(
                    "Varint overflow, packet {} offset {} shift {}",
                    toHexRange(bytes, offset, minOf(offset + 4, bytes.size)),
                    offset,
                    shift
                )
                return VarIntOutput(-1, -1)
            }
        }
    }

}
