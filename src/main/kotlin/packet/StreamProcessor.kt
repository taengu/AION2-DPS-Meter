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

    private fun logSkippedOrPartialPacket(reason: String, packet: ByteArray) {
        val hex = toHex(packet)
        val marker0438 = has0438Marker(packet)
        val markerFfFf = hasFfFfMarker(packet)
        logger.debug("{}: {}", reason, hex)
        UnifiedLogger.debug(
            logger,
            "{} marker0438={} markerFfFf={} hex={}",
            reason,
            marker0438,
            markerFfFf,
            hex
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
            val start = offset
            for (i in 0..5) {
                if (start + i + 4 > data.size) break
                val rawUnsigned = (data[start + i].toLong() and 0xFFL) or
                        ((data[start + i + 1].toLong() and 0xFFL) shl 8) or
                        ((data[start + i + 2].toLong() and 0xFFL) shl 16) or
                        ((data[start + i + 3].toLong() and 0xFFL) shl 24)

                val candidates = buildSkillCandidates(rawUnsigned)

                for (candidate in candidates) {
                    val normalized = normalizeSkillId(candidate)
                    if (isValidSkillCode(normalized)) {
                        offset = start + i + 5
                        return normalized
                    }
                }
            }

            throw IllegalStateException("skill not found")
        }
    }

    fun onPacketReceived(packet: ByteArray): Boolean {
        var parsed = false

        // 1. Read the packet length first. We need this for both Bundle detection and Standard processing.
        val packetLengthInfo = readVarInt(packet)

        // --- FIXED LOGIC: Unzip FF FF Bundle Packets ---
        // Bundles start with a standard VarInt length, followed by FF FF, then 6 bytes of padding.
        if (packetLengthInfo.length > 0 && packetLengthInfo.value > 0) {
            val payloadStart = packetLengthInfo.length

            // Check if this is a bundle packet (starts with FF FF at the payload position)
            if (packet.size >= payloadStart + 8 &&
                packet[payloadStart] == 0xff.toByte() &&
                packet[payloadStart + 1] == 0xff.toByte()
            ) {
                // FF-FF transport bundles use an 8-byte envelope header AFTER the length varint.
                // Structure: [VarInt Length] [FF FF] [6 Bytes Padding] [Nested Packets...]
                // We must skip: payloadStart + 2 (FFFF) + 6 (Padding) = payloadStart + 8
                val bundleDataStart = payloadStart + 8

                if (bundleDataStart < packet.size) {
                    val bundlePayload = packet.copyOfRange(bundleDataStart, packet.size)
                    var offset = 0

                    while (offset < bundlePayload.size) {
                        // Read the length of the next nested packet
                        val lengthInfo = readVarInt(bundlePayload, offset)

                        // Safety check: break if we hit padding or malformed varints
                        if (lengthInfo.length <= 0 || lengthInfo.value <= 0) {
                            logSkippedOrPartialPacket("FF-FF bundle nested packet length malformed", bundlePayload.copyOfRange(offset, bundlePayload.size))
                            break
                        }

                        val nestedPacketLength = lengthInfo.value

                        // Safety check: break if the nested packet claims to be longer than the remaining buffer
                        if (offset + nestedPacketLength > bundlePayload.size) {
                            logSkippedOrPartialPacket("FF-FF bundle nested packet truncated", bundlePayload.copyOfRange(offset, bundlePayload.size))
                            break
                        }

                        // Slice out the exact nested packet
                        val nestedPacket = bundlePayload.copyOfRange(offset, offset + nestedPacketLength)

                        // Recursively send this standard packet back through the parser!
                        if (onPacketReceived(nestedPacket)) {
                            parsed = true
                        }

                        // Move the offset forward to the start of the next packet in the train
                        offset += nestedPacketLength
                    }
                    if (!parsed) {
                        logSkippedOrPartialPacket("FF-FF bundle had no fully parsed nested packets", packet)
                    }
                    return parsed // Return early since we successfully processed the bundle
                }
                logSkippedOrPartialPacket("FF-FF bundle payload missing after header", packet)
            }
        }
        // ----------------------------------------------

        // Standard processing (unchanged, but uses the packetLengthInfo read at the top)
        if (packetLengthInfo.length <= 0 || packetLengthInfo.value <= 0) {
            logSkippedOrPartialPacket("Malformed packet length header skipped", packet)
            return parsed
        }

        if (packet.size == packetLengthInfo.value) {
            val payloadEnd = packet.size - 3
            if (payloadEnd <= 0) {
                logSkippedOrPartialPacket("Short packet with no payload skipped", packet)
                return parsed
            }
            logger.trace(
                "Current byte length matches expected length: {}",
                toHex(packet.copyOfRange(0, payloadEnd))
            )
            if (parsePerfectPacket(packet.copyOfRange(0, payloadEnd))) parsed = true
            return parsed
        }
        if (packet.size <= 3) return parsed

        if (packetLengthInfo.value > packet.size) {
            logger.trace("Current byte length is shorter than expected: {}", toHex(packet))
            if (parseBrokenLengthPacket(packet)) parsed = true
            return parsed
        }
        if (packetLengthInfo.value <= 3) {
            if (onPacketReceived(packet.copyOfRange(1, packet.size))) parsed = true
            return parsed
        }

        val payloadEnd = packetLengthInfo.value - 3
        if (payloadEnd <= 0 || payloadEnd > packet.size) {
            logSkippedOrPartialPacket("Invalid payload boundary skipped: length=${packetLengthInfo.value}", packet)
            return parsed
        }

        try {
            val headPacket = packet.copyOfRange(0, payloadEnd)
            if (headPacket.size != 3) {
                if (headPacket.isNotEmpty()) {
                    logger.trace(
                        "Packet split succeeded: {}",
                        toHex(headPacket)
                    )
                    if (parsePerfectPacket(headPacket)) parsed = true
                }
            }

            if (onPacketReceived(packet.copyOfRange(payloadEnd, packet.size))) parsed = true
        } catch (_: IndexOutOfBoundsException) {
            logSkippedOrPartialPacket("Truncated tail packet skipped", packet)
            return parsed
        }
        return parsed
    }

    private fun parseBrokenLengthPacket(packet: ByteArray, flag: Boolean = true): Boolean {
        var parsed = false
        if (packet.size < 4) {
            logSkippedOrPartialPacket("Truncated packet skipped", packet)
            return parsed
        }

        logger.trace("Remaining packet buffer: {}", toHex(packet))
        val target = dataStorage.getCurrentTarget()
        var processed = false

        if (target != 0) {
            val targetBytes = convertVarInt(target)
            val damageOpcodes = byteArrayOf(0x04, 0x38)
            val dotOpcodes = byteArrayOf(0x05, 0x38)
            val damageKeyword = damageOpcodes + targetBytes
            val dotKeyword = dotOpcodes + targetBytes
            val damageIdx = findArrayIndex(packet, damageKeyword)
            val dotIdx = findArrayIndex(packet, dotKeyword)

            val (idx, isDamage) = when {
                damageIdx > 0 && dotIdx > 0 -> {
                    if (damageIdx < dotIdx) damageIdx to true
                    else dotIdx to false
                }
                damageIdx > 0 -> damageIdx to true
                dotIdx > 0 -> dotIdx to false
                else -> -1 to false
            }

            if (idx > 0) {
                val packetLengthInfo = readVarInt(packet, idx - 1)
                if (packetLengthInfo.length == 1) {
                    val startIdx = idx - 1
                    val endIdx = idx - 1 + packetLengthInfo.value - 3
                    if (startIdx in 0..<endIdx && endIdx <= packet.size) {
                        val extractedPacket = packet.copyOfRange(startIdx, endIdx)
                        if (isDamage) {
                            if (parsingDamage(extractedPacket)) parsed = true
                        } else {
                            parseDoTPacket(extractedPacket)
                        }
                        processed = true
                        if (endIdx < packet.size) {
                            val remainingPacket = packet.copyOfRange(endIdx, packet.size)
                            if (parseBrokenLengthPacket(remainingPacket, false)) parsed = true
                        }
                    }
                }
            }
        }

        if (flag && !processed) {
            logger.trace("Remaining packet {}", toHex(packet))
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
            val info = readVarInt(packet, originOffset)
            if (info.length == -1) {
                return false
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
        // Case 2: Compact/Local Summon Packet (0x4A 0x36)
        else if (opcode1 == 0x4A && opcode2 == 0x36) {
            offset += 2

            // The first VarInt is the Summon ID (e.g. 8C 8D 05 -> 83596)
            val summonInfo = readVarInt(packet, offset)
            if (summonInfo.length <= 0) return false

            val summonId = summonInfo.value

            // This packet type usually implies the summon belongs to the local player.
            // We use the LocalPlayer ID if available.
            val localPlayerId = LocalPlayer.playerId
            if (localPlayerId != null && localPlayerId > 0) {
                logger.debug("Local summon mapping found: Owner {} -> Summon {}", localPlayerId, summonId)
                UnifiedLogger.debug(logger, "Local summon mapping found: Owner {} -> Summon {}", localPlayerId, summonId)
                dataStorage.appendSummon(localPlayerId.toInt(), summonId)
                return true
            } else {
                // Fallback: If we don't know the local player ID yet, we might want to cache this
                // or try to infer it from the previous packet's context (rare).
                logger.debug("Found local summon packet for {} but LocalPlayer ID is unknown", summonId)
                return false
            }
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
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        offset += packetLengthInfo.length

        if (packet[offset] != 0x04.toByte()) return false
        if (packet[offset + 1] != 0x8d.toByte()) return false
        offset = 10

        if (offset >= packet.size) return false

        val playerInfo = readVarInt(packet, offset)
        if (playerInfo.length <= 0) return false
        offset += playerInfo.length

        if (offset >= packet.size) return false

        val nicknameLength = packet[offset]
        if (nicknameLength < 0 || nicknameLength > 72) return false
        if (nicknameLength + offset > packet.size) return false

        val np = packet.copyOfRange(offset + 1, offset + nicknameLength + 1)

        val possibleName = String(np, Charsets.UTF_8)
        val sanitizedName = sanitizeNickname(possibleName) ?: return false
        logger.debug("Confirmed nickname found in pattern 0 {}", sanitizedName)
        UnifiedLogger.debug(logger, "Confirmed nickname found in pattern 0 {}", sanitizedName)
        dataStorage.appendNickname(playerInfo.value, sanitizedName)

        return true
    }

    private fun parsingDamage(packet: ByteArray): Boolean {
        // The 3 hardcoded filters that blocked 30, 31, and 32-byte packets have been removed!

        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        val reader = DamagePacketReader(packet, packetLengthInfo.length)

        if (reader.offset >= packet.size) {
            logSkippedOrPartialPacket("Damage packet skipped: payload offset out of bounds", packet)
            return false
        }
        if (reader.offset + 1 >= packet.size) {
            logSkippedOrPartialPacket("Damage packet skipped: incomplete opcode", packet)
            return false
        }
        if (packet[reader.offset] != 0x04.toByte() || packet[reader.offset + 1] != 0x38.toByte()) {
            if (has0438Marker(packet)) {
                logSkippedOrPartialPacket("Damage packet skipped: 04 38 marker present but not at opcode position", packet)
            }
            return false
        }
        reader.offset += 2
        fun logUnparsedDamage(reason: String): Boolean {
            logSkippedOrPartialPacket("Unparsed damage packet: $reason", packet)
            return false
        }
        if (reader.offset >= packet.size) return logUnparsedDamage("field decode failure")
        val targetValue = reader.tryReadVarInt() ?: return logUnparsedDamage("field decode failure")
        val targetInfo = VarIntOutput(targetValue, 1)
        if (reader.offset >= packet.size) return logUnparsedDamage("field decode failure")

        val switchValue = reader.tryReadVarInt() ?: return logUnparsedDamage("field decode failure")

        if (reader.offset >= packet.size) return logUnparsedDamage("field decode failure")
        val andResult = switchValue and mask
        if (andResult !in 4..7) {
            return true
        }

        reader.tryReadVarInt() ?: return logUnparsedDamage("field decode failure") // Consume Unused flag value

        if (reader.offset >= packet.size) return logUnparsedDamage("field decode failure")

        val actorValue = reader.tryReadVarInt() ?: return logUnparsedDamage("field decode failure")
        val actorInfo = VarIntOutput(actorValue, 1)
        if (reader.offset >= packet.size) return logUnparsedDamage("field decode failure")
        if (actorInfo.value == targetInfo.value) return true
        if (!isActorAllowed(actorInfo.value)) return true

        if (reader.offset + 5 >= packet.size) return logUnparsedDamage("field decode failure")

        val skillCode = try {
            reader.readSkillCode()
        } catch (_: IllegalStateException) {
            return logUnparsedDamage("field decode failure")
        }

        val typeValue = reader.tryReadVarInt() ?: return logUnparsedDamage("field decode failure")
        val typeInfo = VarIntOutput(typeValue, 1)
        if (reader.offset >= packet.size) return logUnparsedDamage("field decode failure")

        val damageType = typeInfo.value.toByte()

        val start = reader.offset
        var tempV = 0
        tempV += when (andResult) {
            4 -> 8
            5 -> 12
            6 -> 10
            7 -> 14
            else -> return logUnparsedDamage("field decode failure")
        }
        if (start + tempV > packet.size) return logUnparsedDamage("field decode failure")
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

        if (reader.offset >= packet.size) return logUnparsedDamage("field decode failure")

        reader.tryReadVarInt() ?: return logUnparsedDamage("field decode failure") // Consume Unused Unknown value

        val finalDamage = reader.tryReadVarInt() ?: return logUnparsedDamage("field decode failure")
        var multiHitCount = 0
        var multiHitDamage = 0
        var healAmount = 0
        val hitCount = if (
            reader.remainingBytes() >= 2 &&
            packet[reader.offset] == 0x03.toByte() &&
            packet[reader.offset + 1] == 0x00.toByte()
        ) {
            null
        } else {
            reader.tryReadVarInt()
        }
        if (hitCount != null && hitCount > 0 && reader.remainingBytes() > 0) {
            var hitSum = 0
            var hitsRead = 0
            while (hitsRead < hitCount && reader.remainingBytes() > 0) {
                val hitValue = reader.tryReadVarInt() ?: break
                hitSum += hitValue
                hitsRead++
            }
            if (hitsRead == hitCount) {
                multiHitCount = hitsRead
                multiHitDamage = hitSum
            }
        }
        if (
            reader.remainingBytes() >= 2 &&
            packet[reader.offset] == 0x03.toByte() &&
            packet[reader.offset + 1] == 0x00.toByte()
        ) {
            reader.offset += 2
            healAmount = reader.tryReadVarInt() ?: 0
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

        if (logger.isTraceEnabled) {
            logger.trace("{}", toHex(packet))
            logger.trace("Type packet {}", toHex(byteArrayOf(damageType)))
            logger.trace(
                "Type packet bits {}",
                Integer.toBinaryString(damageType.toInt() and 0xFF).padStart(8, '0')
            )
            logger.trace("Varint packet: {}", toHex(packet.copyOfRange(start, start + tempV)))
        }
        logger.debug(
            "Target: {}, attacker: {}, skill: {}, type: {}, damage: {}, damage flag:{}",
            pdp.getTargetId(),
            pdp.getActorId(),
            pdp.getSkillCode1(),
            pdp.getType(),
            pdp.getDamage(),
            pdp.getSpecials()
        )
        if (UnifiedLogger.isDebugEnabled()) {
            UnifiedLogger.debug(
                logger,
                "Target: {}, attacker: {}, skill: {}, type: {}, damage: {}, damage flag:{}, hex={}",
                pdp.getTargetId(),
                pdp.getActorId(),
                pdp.getSkillCode1(),
                pdp.getType(),
                pdp.getDamage(),
                pdp.getSpecials(),
                toHex(packet)
            )
        }

        if (pdp.getActorId() != pdp.getTargetId()) {
            dataStorage.appendDamage(pdp)
        }
        return true

    }

    private fun toHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val hex = CharArray(bytes.size * 3 - 1)
        var pos = 0
        bytes.forEachIndexed { index, b ->
            val value = b.toInt() and 0xFF
            val high = value ushr 4
            val low = value and 0x0F
            hex[pos++] = HEX_DIGITS[high]
            hex[pos++] = HEX_DIGITS[low]
            if (index != bytes.lastIndex) {
                hex[pos++] = ' '
            }
        }
        return String(hex)
    }

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
        return (
            skillCode in 11_000_000..19_999_999 ||
                    skillCode in 3_000_000..3_999_999 ||
                    skillCode in 100_000..199_999 ||
                    skillCode in 1_000_000..9_999_999 ||
                    skillCode in 30_000_000..30_999_999
                )
    }


    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntOutput {
        var value = 0
        var shift = 0
        var count = 0

        while (true) {
            if (offset + count >= bytes.size) {
                logSkippedOrPartialPacket("Truncated packet skipped at varint decode (offset=$offset count=$count)", bytes)
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
                    toHex(bytes.copyOfRange(offset, offset + 4)),
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
