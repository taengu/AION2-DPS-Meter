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

    private val logger = LoggerFactory.getLogger(StreamProcessor::class.java)

    data class VarIntOutput(val value: Int, val length: Int)

    private val mask = 0x0f
    private val actorIdFilterKey = "dpsMeter.actorIdFilter"
    private val skipLogWindowMs = 1_000L
    private val skipLogMaxPerWindow = 40
    private val maxLoggedPacketBytes = 256

    private var skipLogWindowStartMs = 0L
    private var skipLogCountInWindow = 0
    private var skipLogSuppressedInWindow = 0

    private fun isActorAllowed(actorId: Int): Boolean {
        val rawFilter = PropertyHandler.getProperty(actorIdFilterKey)?.trim().orEmpty()
        if (rawFilter.isEmpty()) return true
        val filterValue = rawFilter.toIntOrNull() ?: return true
        return actorId == filterValue
    }

    private fun has0438Marker(packet: ByteArray): Boolean {
        if (packet.size < 2) return false
        for (idx in 0 until packet.size - 1) {
            if (packet[idx] == 0x04.toByte() && packet[idx + 1] == 0x38.toByte()) {
                return true
            }
        }
        return false
    }

    private fun hasFfFfMarker(packet: ByteArray): Boolean {
        if (packet.size < 2) return false
        for (idx in 0 until packet.size - 1) {
            if (packet[idx] == 0xff.toByte() && packet[idx + 1] == 0xff.toByte()) {
                return true
            }
        }
        return false
    }

    private fun logSkippedOrPartialPacket(reason: String, packet: ByteArray, startOffset: Int = 0, length: Int = packet.size - startOffset) {
        val now = System.currentTimeMillis()
        if (now - skipLogWindowStartMs >= skipLogWindowMs) {
            if (skipLogSuppressedInWindow > 0) {
                val summary = "Suppressed ${skipLogSuppressedInWindow} skip/partial packet logs in previous window"
                logger.debug(summary)
                UnifiedLogger.debug(logger, summary)
            }
            skipLogWindowStartMs = now
            skipLogCountInWindow = 0
            skipLogSuppressedInWindow = 0
        }

        if (skipLogCountInWindow >= skipLogMaxPerWindow) {
            skipLogSuppressedInWindow++
            return
        }
        skipLogCountInWindow++

        val start = startOffset.coerceIn(0, packet.size)
        val maxLen = (packet.size - start).coerceAtLeast(0)
        val requestedLen = length.coerceAtLeast(0).coerceAtMost(maxLen)
        val endExclusive = start + requestedLen
        val bytesToLog = minOf(requestedLen, maxLoggedPacketBytes)
        val hex = toHexRange(packet, start, start + bytesToLog)
        val truncated = requestedLen > bytesToLog
        val marker0438 = has0438Marker(packet)
        val markerFfFf = hasFfFfMarker(packet)
        logger.debug("{}: {}", reason, if (truncated) "$hex ..." else hex)
        UnifiedLogger.debug(
            logger,
            "{} marker0438={} markerFfFf={} range=[{}..{}) loggedBytes={} totalBytes={} truncated={} hex={}",
            reason,
            marker0438,
            markerFfFf,
            start,
            endExclusive,
            bytesToLog,
            requestedLen,
            truncated,
            if (truncated) "$hex ..." else hex
        )
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

        fun readSkillCode(): Int {
            if (offset + 4 > data.size) throw IllegalStateException("skill not found")
            val rawUnsigned = (data[offset].toLong() and 0xFFL) or
                    ((data[offset + 1].toLong() and 0xFFL) shl 8) or
                    ((data[offset + 2].toLong() and 0xFFL) shl 16) or
                    ((data[offset + 3].toLong() and 0xFFL) shl 24)

            // Fix 3: Consume exactly 4 bytes first, like the Python script
            offset += 4

            // Dynamic Padding Check: If the 5th byte isn't a valid damage type (0..7), skip it!
            if (offset < data.size) {
                val nextByte = data[offset].toInt() and 0xFF
                if (nextByte > 7) {
                    offset += 1
                }
            }

            val candidates = buildSkillCandidates(rawUnsigned)
            for (candidate in candidates) {
                val normalized = normalizeSkillId(candidate)
                if (isValidSkillCode(normalized)) {
                    return normalized
                }
            }

            throw IllegalStateException("skill not found")
        }
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

            // 2. Peek ahead to check if this is an FF FF Bundle
            val payloadStart = offset + lengthInfo.length
            if (payloadStart + 1 >= buffer.size) {
                break // Wait for more data to safely read the signature
            }
            val isBundle = buffer[payloadStart] == 0xff.toByte() && buffer[payloadStart + 1] == 0xff.toByte()

            // 3. THE AION 2 QUIRK:
            // Bundles have NO inflation (length == exact physical size).
            // Standard packets have 3 bytes inflation (length - 3 == physical size).
            val inflation = if (isBundle) 0 else 3
            val totalPacketBytes = lengthInfo.value - inflation

            if (totalPacketBytes <= 0 || totalPacketBytes > 1048576) { // 1MB sanity check
                offset++ // Force resync
                continue
            }

            // 4. TCP Fragmentation Check
            if (offset + totalPacketBytes > buffer.size) {
                // Safety check against massive corrupted varints swallowing the stream
                if (totalPacketBytes > 65535 && buffer.size > 65535) {
                    offset++
                    continue
                }
                break // Fragmented chunk, wait for the rest
            }

            // 5. Extract the perfectly aligned packet
            val fullPacket = buffer.copyOfRange(offset, offset + totalPacketBytes)

            if (isBundle) {
                // Extract everything after the VarInt and the 8-byte Bundle Header
                val bundleDataStart = lengthInfo.length + 8
                if (bundleDataStart < fullPacket.size) {
                    parseBundle(fullPacket.copyOfRange(bundleDataStart, fullPacket.size))
                }
            } else {
                parsePerfectPacket(fullPacket)
            }

            // 6. Advance exactly to the next boundary
            offset += totalPacketBytes
        }

        return offset
    }

    private fun parseBundle(bundleData: ByteArray) {
        var offset = 0
        while (offset < bundleData.size) {
            if (bundleData[offset] == 0x00.toByte()) {
                offset++
                continue
            }

            val lengthInfo = readVarInt(bundleData, offset)
            if (lengthInfo.length <= 0 || lengthInfo.value <= 0) {
                offset++
                continue
            }

            // Nested packets are always standard packets, so they always use - 3
            val totalPacketBytes = lengthInfo.value - 3

            if (totalPacketBytes <= 0 || totalPacketBytes > 65535) {
                offset++
                continue
            }

            if (offset + totalPacketBytes > bundleData.size) {
                // Fallback scanner for corrupted nested packets
                parseBrokenLengthPacket(bundleData.copyOfRange(offset, bundleData.size))
                break
            }

            val nestedPacket = bundleData.copyOfRange(offset, offset + totalPacketBytes)
            parsePerfectPacket(nestedPacket)

            offset += totalPacketBytes
        }
    }

    private fun parseBrokenLengthPacket(packet: ByteArray, flag: Boolean = true): Boolean {
        var parsed = false
        if (packet.size < 4) return parsed

        if (tryParseEmbeddedDamagePacket(packet)) {
            parsed = true
        }

        if (flag && !parsed) {
            if (parseActorNameBindingRules(packet)) parsed = true
            if (parseLootAttributionActorName(packet)) parsed = true
            if (castNicknameNet(packet)) parsed = true
        }

        return parsed
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

    private fun castNicknameNet(packet: ByteArray): Boolean {
        var originOffset = 0
        while (originOffset < packet.size) {
            if (!canReadVarInt(packet, originOffset)) {
                originOffset++
                continue
            }

            val info = readVarInt(packet, originOffset)
            if (info.length == -1) {
                originOffset++
                continue // DO NOT return false here, just skip the byte!
            }
            val innerOffset = originOffset + info.length

            if (innerOffset + 6 >= packet.size) {
                originOffset++
                continue
            }

            if (packet[innerOffset + 3] == 0x01.toByte() && packet[innerOffset + 4] == 0x07.toByte()) {
                val possibleNameLength = packet[innerOffset + 5].toInt() and 0xff
                if (innerOffset + 6 + possibleNameLength <= packet.size) {
                    val possibleNameBytes = packet.copyOfRange(innerOffset + 6, innerOffset + 6 + possibleNameLength)
                    val possibleName = String(possibleNameBytes, Charsets.UTF_8)
                    val sanitizedName = sanitizeNickname(possibleName)
                    if (sanitizedName != null) {
                        logger.debug(
                            "Potential nickname found in cast net: {} (hex={})",
                            sanitizedName,
                            toHex(possibleNameBytes)
                        )
                        UnifiedLogger.debug(
                            logger,
                            "Potential nickname found in cast net: {} (hex={})",
                            sanitizedName,
                            toHex(possibleNameBytes)
                        )
                        dataStorage.appendNickname(info.value, sanitizedName)
                        return true
                    }
                }
            }
            originOffset++
        }
        return false
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
            UnifiedLogger.debug(
                logger,
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

        if (packet[offset] != 0x05.toByte()) return
        if (packet[offset+1] != 0x38.toByte()) return
        offset += 2
        if (packet.size < offset) return

        val targetInfo = readVarInt(packet,offset)
        if (targetInfo.length < 0) return
        offset += targetInfo.length
        if (packet.size < offset) return
        pdp.setTargetId(targetInfo)

        offset += 1
        if (packet.size < offset) return

        val actorInfo = readVarInt(packet,offset)
        if (actorInfo.length < 0) return
        if (actorInfo.value == targetInfo.value) return
        if (!isActorAllowed(actorInfo.value)) return
        offset += actorInfo.length
        if (packet.size < offset) return
        pdp.setActorId(actorInfo)

        val unknownInfo = readVarInt(packet,offset)
        if (unknownInfo.length <0) return
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
            logger.debug("{}", toHex(packet))
        }
        if (UnifiedLogger.isDebugEnabled()) {
            UnifiedLogger.debug(logger, "{}", toHex(packet))
        }
        logger.debug(
            "Dot damage actor {}, target {}, skill {}, damage {}",
            pdp.getActorId(),
            pdp.getTargetId(),
            pdp.getSkillCode1(),
            pdp.getDamage()
        )
        UnifiedLogger.debug(
            logger,
            "Dot damage actor {}, target {}, skill {}, damage {}",
            pdp.getActorId(),
            pdp.getTargetId(),
            pdp.getSkillCode1(),
            pdp.getDamage()
        )
        logger.debug("----------------------------------")
        UnifiedLogger.debug(logger, "----------------------------------")
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

        if (offset + 1 >= packet.size) return false

        val opcode1 = packet[offset].toInt() and 0xFF
        val opcode2 = packet[offset + 1].toInt() and 0xFF

        // Case 1: Standard/Old Summon Packet (0x40 0x36)
        if (opcode1 == 0x40 && opcode2 == 0x36) {
            offset += 2
            val summonInfo = readVarInt(packet, offset)
            if (summonInfo.length < 0) return false
            offset += summonInfo.length + 28

            if (packet.size > offset) {
                val mobInfo = readVarInt(packet, offset)
                if (mobInfo.length < 0) return false
                offset += mobInfo.length
                if (packet.size > offset) {
                    val mobInfo2 = readVarInt(packet, offset)
                    if (mobInfo2.length < 0) return false
                    if (mobInfo.value == mobInfo2.value) {
                        logger.trace("mid: {}, code: {}", summonInfo.value, mobInfo.value)
                        dataStorage.appendMob(summonInfo.value, mobInfo.value)
                    }
                }
            }

            val keyIdx = findArrayIndex(packet, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)
            if (keyIdx == -1) return false
            val afterPacket = packet.copyOfRange(keyIdx + 8, packet.size)

            val opcodeIdx = findArrayIndex(afterPacket, 0x07, 0x02, 0x06)
            if (opcodeIdx == -1) return false
            offset = keyIdx + opcodeIdx + 11

            if (offset + 2 > packet.size) return false
            val realActorId = parseUInt16le(packet, offset)

            logger.debug("Summon mob mapping succeeded {},{}", realActorId, summonInfo.value)
            UnifiedLogger.debug(logger, "Summon mob mapping succeeded {},{}", realActorId, summonInfo.value)
            dataStorage.appendSummon(realActorId, summonInfo.value)
            return true
        }
        return false
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

        while (searchOffset < packet.size - 1) {
            // Check for both 04 8D (Login) and 04 4C (Embedded Combat Event)
            if (packet[searchOffset] == 0x04.toByte() &&
                (packet[searchOffset + 1] == 0x8d.toByte() || packet[searchOffset + 1] == 0x4c.toByte())) {

                val maxLookahead = minOf(packet.size, searchOffset + 64)
                var maxNameEndIdx = -1
                var stopScanningAt = maxLookahead

                var idx = searchOffset + 2
                // stopScanningAt ensures we don't accidentally read VarInts from inside the UTF-8 name string itself!
                while (idx < stopScanningAt - 2) {
                    if (!canReadVarInt(packet, idx)) {
                        idx++
                        continue
                    }

                    val playerInfo = readVarInt(packet, idx)
                    if (playerInfo.length <= 0) {
                        idx++
                        continue
                    }

                    if (playerInfo.value in 100..9999999) {
                        // Tight leash: 0 to 2 bytes of mystery padding before the length byte
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
                                        logger.debug("Confirmed dynamic nickname {} -> {}", playerInfo.value, sanitizedName)
                                        UnifiedLogger.debug(logger, "Confirmed dynamic nickname {} -> {}", playerInfo.value, sanitizedName)
                                        dataStorage.appendNickname(playerInfo.value, sanitizedName)

                                        parsedAny = true
                                        maxNameEndIdx = maxOf(maxNameEndIdx, lenIdx + nameLen)

                                        // THE FIX: Do not break! Set a hard boundary at the length byte,
                                        // allowing the loop to catch misaligned inner VarInts (like 390 hiding inside 49970)
                                        stopScanningAt = minOf(stopScanningAt, lenIdx)
                                    }
                                }
                            }
                        }
                    }
                    idx++
                }

                if (maxNameEndIdx != -1) {
                    searchOffset = maxNameEndIdx // Safely skip past the extracted string
                } else {
                    searchOffset += 2
                }
            } else {
                searchOffset++
            }
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
            val copyLen = 255.coerceAtMost(remainingSize)

            val headlessCandidate = ByteArray(copyLen + 2)
            headlessCandidate[0] = 0xFF.toByte()
            headlessCandidate[1] = 0x01.toByte()
            System.arraycopy(packet, searchOffset, headlessCandidate, 2, copyLen)

            if (parsingDamage(headlessCandidate, allowEmbeddedScan = false)) {
                parsedAny = true
                searchOffset += 2 // We caught a hit! Step past the 04 38
            } else {
                searchOffset++
            }
        }
        return parsedAny
    }

    private fun parsingDamage(packet: ByteArray, allowEmbeddedScan: Boolean = true): Boolean {
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        val reader = DamagePacketReader(packet, packetLengthInfo.length)

        if (reader.offset >= packet.size) return false
        if (reader.offset + 1 >= packet.size) return false

        // STRICT GATEKEEPER RESTORED: It MUST start with 04 38 to be processed here
        if (packet[reader.offset] != 0x04.toByte() || packet[reader.offset + 1] != 0x38.toByte()) {
            if (allowEmbeddedScan && tryParseEmbeddedDamagePacket(packet)) {
                return true
            }
            return false
        }
        reader.offset += 2

        var parsedAny = false

        // ADDITIVE RULE: Loop to catch chained AoE targets appended to the valid 04 38 packet
        while (reader.remainingBytes() > 0) {
            val checkpoint = reader.offset

            // Skip chained AoE hit markers if they exist
            if (reader.remainingBytes() >= 2 && packet[reader.offset] == 0x01.toByte() && packet[reader.offset + 1] == 0x00.toByte()) {
                reader.offset += 2
            }

            val targetValue = reader.tryReadVarInt() ?: break
            val targetInfo = VarIntOutput(targetValue, 1)
            // If the next bytes aren't a valid target, it's just packet trailing noise. Break safely!
            if (targetInfo.value <= 0) { reader.offset = checkpoint; break }

            val switchValue = reader.tryReadVarInt() ?: break
            val andResult = switchValue and mask

            // Allow strict original masks (4..7) plus additional masks seen in live captures.
            // `0` appears in valid Heart Gore hits where the wire layout is still compatible with
            // the existing parser branch (same payload span / damage varint position).
            if (andResult !in 4..7 && andResult != 0 && andResult != 2 && andResult != 8 && andResult != 12) {
                reader.offset = checkpoint; break
            }

            reader.tryReadVarInt() ?: break // Consume Unused flag value

            val actorValue = reader.tryReadVarInt() ?: break
            val actorInfo = VarIntOutput(actorValue, 1)
            if (actorInfo.value <= 0) { reader.offset = checkpoint; break }

            val isAllowed = isActorAllowed(actorInfo.value)

            val skillCode = try {
                reader.readSkillCode()
            } catch (_: IllegalStateException) {
                reader.offset = checkpoint; break
            }

            val typeValue = reader.tryReadVarInt() ?: break
            if (typeValue !in 0..7) { reader.offset = checkpoint; break }
            val typeInfo = VarIntOutput(typeValue, 1)

            val damageType = typeInfo.value.toByte()

            val start = reader.offset
            var tempV = 0
            tempV += when (andResult) {
                4 -> 8
                5 -> 12
                6 -> 10
                7 -> 14
                0 -> 8
                2 -> 8
                8 -> 8
                12 -> 8
                else -> 8
            }
            if (start + tempV > packet.size) { reader.offset = checkpoint; break }

            var specialByte = 0
            val hasSpecialByte = reader.offset + 1 < packet.size && packet[reader.offset + 1] == 0x00.toByte()
            if (hasSpecialByte) {
                specialByte = packet[reader.offset].toInt() and 0xFF
                reader.offset += 2
            }
            val specials = parseSpecialDamageFlags(byteArrayOf(specialByte.toByte())).toMutableList()
            if (damageType.toInt() == 3) {
                specials.add(SpecialDamage.CRITICAL)
            }
            reader.offset += (tempV - (if (hasSpecialByte) 2 else 0))

            if (reader.offset >= packet.size) { reader.offset = checkpoint; break }

            val varint1 = reader.tryReadVarInt() ?: break
            val varint2 = reader.tryReadVarInt() ?: break

            var finalDamage = 0
            var hitCount = 0

            if (varint2 in 1..10 && varint1 > varint2) {
                finalDamage = varint1
                hitCount = varint2
            } else {
                finalDamage = varint2
                val preHitOffset = reader.offset

                if (reader.remainingBytes() > 0) {
                    val isMarkerNext = reader.remainingBytes() >= 2 &&
                            packet[reader.offset + 1] == 0x00.toByte() &&
                            packet[reader.offset] in 1..3

                    if (!isMarkerNext) {
                        val peekCount = reader.tryReadVarInt()
                        if (peekCount != null && peekCount in 1..25) {
                            hitCount = peekCount
                        } else {
                            reader.offset = preHitOffset
                        }
                    }
                }
            }

            if (finalDamage < 0 || finalDamage > 99_999_999) { reader.offset = checkpoint; break }

            var multiHitCount = 0
            var multiHitDamage = 0

            if (hitCount > 0 && reader.remainingBytes() > 0) {
                var hitSum = 0
                var hitsRead = 0
                val safeMaxHits = minOf(hitCount, 25)

                while (hitsRead < safeMaxHits && reader.remainingBytes() > 0) {
                    val isMarkerNext = reader.remainingBytes() >= 2 &&
                            packet[reader.offset + 1] == 0x00.toByte() &&
                            packet[reader.offset] in 1..3

                    var isNextPacket = false
                    val lookahead = minOf(15, reader.remainingBytes() - 1)
                    for (scan in 0..lookahead) {
                        if (packet[reader.offset + scan] == 0x04.toByte() && packet[reader.offset + scan + 1] == 0x38.toByte()) {
                            isNextPacket = true
                            break
                        }
                    }

                    if (isMarkerNext || isNextPacket) break

                    val hitValue = reader.tryReadVarInt() ?: break
                    hitSum += hitValue
                    hitsRead++
                }
                multiHitCount = hitsRead
                multiHitDamage = hitSum
            }

            var healAmount = 0
            if (reader.remainingBytes() >= 2 && packet[reader.offset + 1] == 0x00.toByte()) {
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

            val pdp = ParsedDamagePacket()
            pdp.setTargetId(targetInfo)
            pdp.setActorId(actorInfo)
            pdp.setSkillCode(skillCode)
            pdp.setType(typeInfo)
            pdp.setSpecials(specials)
            pdp.setMultiHitCount(multiHitCount)
            pdp.setMultiHitDamage(multiHitDamage)
            pdp.setHealAmount(healAmount)
            pdp.setDamage(VarIntOutput(finalDamage, 1))

            if (UnifiedLogger.isDebugEnabled()) {
                UnifiedLogger.debug(
                    logger,
                    "Target: {}, attacker: {}, skill: {}, type: {}, damage: {}, hits: {}",
                    pdp.getTargetId(),
                    pdp.getActorId(),
                    pdp.getSkillCode1(),
                    pdp.getType(),
                    pdp.getDamage(),
                    pdp.getMultiHitCount()
                )
            }

            val appendedToMeter = pdp.getActorId() != pdp.getTargetId()
            if (isAllowed && appendedToMeter) {
                dataStorage.appendDamage(pdp)
            }

            parsedAny = true
        }

        return parsedAny
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

    private fun buildSkillCandidates(rawUnsigned: Long): List<Int> {
        val seedCandidates = mutableListOf<Int>()
        if (rawUnsigned in 0L..Int.MAX_VALUE.toLong()) {
            seedCandidates.add(rawUnsigned.toInt())
        }
        if (rawUnsigned % 100L == 0L) {
            val divided = rawUnsigned / 100L
            if (divided in 0L..Int.MAX_VALUE.toLong()) {
                seedCandidates.add(divided.toInt())
            }
        }

        val expandedCandidates = mutableListOf<Int>()
        for (candidate in seedCandidates) {
            val scaledBy10 = candidate.toLong() * 10L
            if (scaledBy10 in 0L..Int.MAX_VALUE.toLong()) {
                val scaledInt = scaledBy10.toInt()
                val scaledPlusOne = scaledBy10 + 1L

                val scaledPlusOneInt = scaledPlusOne.toInt()
                if (DpsCalculator.SKILL_MAP.containsKey(scaledPlusOneInt)) {
                    expandedCandidates.add(scaledPlusOneInt)
                }

                if (DpsCalculator.SKILL_MAP.containsKey(scaledInt)) {
                    expandedCandidates.add(scaledInt)
                }
            }

            expandedCandidates.add(candidate)
        }

        return expandedCandidates.distinct()
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


    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntOutput {
        var value = 0
        var shift = 0
        var count = 0

        while (true) {
            if (offset + count >= bytes.size) {
                // logSkippedOrPartialPacket("Truncated packet skipped at varint decode (offset=$offset count=$count)", bytes)
                return VarIntOutput(-1, -1)
            }

            val byteVal = bytes[offset + count].toInt() and 0xff
            count++

            value = value or (byteVal and 0x7F shl shift)

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

    fun convertVarInt(value: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var num = value

        while (num > 0x7F) {
            bytes.add(((num and 0x7F) or 0x80).toByte())
            num = num ushr 7
        }
        bytes.add(num.toByte())

        return bytes.toByteArray()
    }

    private fun parseSpecialDamageFlags(packet: ByteArray): List<SpecialDamage> {
        val flags = mutableListOf<SpecialDamage>()
        if (packet.isEmpty()) return flags
        val b = packet[0].toInt() and 0xFF
        val flagMask = 0x01 or 0x04 or 0x08 or 0x10 or 0x40 or 0x80
        if ((b and flagMask) == 0) return flags

        if ((b and 0x01) != 0) flags.add(SpecialDamage.BACK)
        if ((b and 0x04) != 0) flags.add(SpecialDamage.PARRY)
        if ((b and 0x08) != 0) flags.add(SpecialDamage.PERFECT)
        if ((b and 0x10) != 0) flags.add(SpecialDamage.DOUBLE)
        if ((b and 0x40) != 0) flags.add(SpecialDamage.SMITE)
        if ((b and 0x80) != 0) flags.add(SpecialDamage.POWER_SHARD)

        return flags
    }
}
