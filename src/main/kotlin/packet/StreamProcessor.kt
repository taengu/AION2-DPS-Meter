package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.entity.ParsedDamagePacket
import com.tbread.entity.SpecialDamage
import com.tbread.logging.DebugLogWriter
import org.slf4j.LoggerFactory

class StreamProcessor(private val dataStorage: DataStorage) {
    private val logger = LoggerFactory.getLogger(StreamProcessor::class.java)

    data class VarIntOutput(val value: Int, val length: Int)

    private val mask = 0x0f
    private val packetStartMarker = byteArrayOf(0x06, 0x00, 0x36)

    fun onPacketReceived(packet: ByteArray) {
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) {
            logger.warn("Broken packet: failed to read varint length {}", toHex(packet))
            return
        }
        val packetSize = computePacketSize(packetLengthInfo)
        if (packetSize <= 0) {
            logger.warn(
                "Broken packet: invalid computed size {} from length {} (varint length {})",
                packetSize,
                packetLengthInfo.value,
                packetLengthInfo.length
            )
            return
        }
        if (packet.size == packetSize) {
            logger.trace(
                "Current byte length matches expected length: {}",
                toHex(packet.copyOfRange(0, packetSize))
            )
            if (isUnknownFFPacket(packet)) {
                logger.debug(
                    "Skipping unknown packet with trailing FF FF until structure is known: {}",
                    toHex(packet)
                )
                return
            }
            parsePerfectPacket(packet.copyOfRange(0, packetSize))
            //더이상 자를필요가 없는 최종 패킷뭉치
            return
        }
        if (packet.size <= 3) return
        // 매직패킷 단일로 올때 무시
        if (packetSize > packet.size) {
            logger.warn("Broken packet: current byte length is shorter than expected: {}", toHex(packet))
            val resyncIdx = findArrayIndex(packet, packetStartMarker)
            if (resyncIdx > 0) {
                onPacketReceived(packet.copyOfRange(resyncIdx, packet.size))
            } else {
                if (isUnknownFFPacket(packet)) {
                    logger.debug(
                        "Skipping unknown broken packet with trailing FF FF until structure is known: {}",
                        toHex(packet)
                    )
                    return
                }
                parseBrokenLengthPacket(packet)
            }
            //길이헤더가 실제패킷보다 김 보통 여기 닉네임이 몰려있는듯?
            return
        }

        try {
            if (packet.copyOfRange(0, packetSize).size != 3) {
                if (packet.copyOfRange(0, packetSize).isNotEmpty()) {
                    logger.trace(
                        "Packet split succeeded: {}",
                        toHex(packet.copyOfRange(0, packetSize))
                    )
                    if (isUnknownFFPacket(packet.copyOfRange(0, packetSize))) {
                        logger.debug(
                            "Skipping unknown packet with trailing FF FF until structure is known: {}",
                            toHex(packet.copyOfRange(0, packetSize))
                        )
                        return
                    }
                    parsePerfectPacket(packet.copyOfRange(0, packetSize))
                    //매직패킷이 빠져있는 패킷뭉치
                }
            }

            onPacketReceived(packet.copyOfRange(packetSize, packet.size))
            //남은패킷 재처리
        } catch (e: Exception) {
            logger.error("Exception while consuming packet {}", toHex(packet), e)
            return
        }

    }

    private fun isUnknownFFPacket(packet: ByteArray): Boolean {
        if (packet.size < 2) return false
        return packet[packet.size - 2] == 0xff.toByte() &&
            packet[packet.size - 1] == 0xff.toByte()
    }

    private fun parseBrokenLengthPacket(packet: ByteArray, flag: Boolean = true) {
        logger.warn("Broken packet buffer detected: {}", toHex(packet))
        if (packet[2] != 0xff.toByte() || packet[3] != 0xff.toByte()) {
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
                val dotIdx = findArrayIndex(packet,dotKeyword)
                val (idx, handler) = when {
                    damageIdx > 0 && dotIdx > 0 -> {
                        if (damageIdx < dotIdx) damageIdx to { slice: ByteArray -> parsingDamage(slice) }
                        else dotIdx to { slice: ByteArray -> parseDoTPacket(slice); true }
                    }
                    damageIdx > 0 -> damageIdx to { slice: ByteArray -> parsingDamage(slice) }
                    dotIdx > 0 -> dotIdx to { slice: ByteArray -> parseDoTPacket(slice); true }
                    else -> -1 to null
                }
                processed = processBrokenPacketSlice(packet, idx, handler)
            }
            if (!processed) {
                val damageIdx = findArrayIndex(packet, byteArrayOf(0x04, 0x38))
                val dotIdx = findArrayIndex(packet, byteArrayOf(0x05, 0x38))
                val (idx, handler) = when {
                    damageIdx > 0 && dotIdx > 0 -> {
                        if (damageIdx < dotIdx) damageIdx to { slice: ByteArray -> parsingDamage(slice) }
                        else dotIdx to { slice: ByteArray -> parseDoTPacket(slice); true }
                    }
                    damageIdx > 0 -> damageIdx to { slice: ByteArray -> parsingDamage(slice) }
                    dotIdx > 0 -> dotIdx to { slice: ByteArray -> parseDoTPacket(slice); true }
                    else -> -1 to null
                }
                processed = processBrokenPacketSlice(packet, idx, handler)
            }
            if (flag && !processed) {
                logger.debug("Remaining packet {}", toHex(packet))
            }
            return
        }
        val newPacket = packet.copyOfRange(10, packet.size)
        onPacketReceived(newPacket)
    }

    private fun processBrokenPacketSlice(
        packet: ByteArray,
        idx: Int,
        handler: ((ByteArray) -> Boolean)?
    ): Boolean {
        if (idx <= 0 || handler == null) return false
        val packetLengthInfo = readVarInt(packet, idx - 1)
        if (packetLengthInfo.length != 1) return false
        val startIdx = idx - 1
        val packetSize = computePacketSize(packetLengthInfo)
        if (packetSize <= 0) return false
        val endIdx = startIdx + packetSize
        if (startIdx !in 0..<endIdx || endIdx > packet.size) return false
        val extractedPacket = packet.copyOfRange(startIdx, endIdx)
        val handled = handler(extractedPacket)
        if (handled && endIdx < packet.size) {
            val remainingPacket = packet.copyOfRange(endIdx, packet.size)
            parseBrokenLengthPacket(remainingPacket, false)
        }
        return handled
    }

    private fun sanitizeNickname(nickname: String): String? {
        val sanitizedNickname = nickname.substringBefore('\u0000').trim()
        if (sanitizedNickname.isEmpty()) return null
        if (sanitizedNickname.contains('\uFFFD')) return null
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
        val hasDigit = trimmedNickname.any { it.isDigit() }
        if (onlyNumbers || (trimmedNickname.firstOrNull()?.isDigit() == true)) return null
        if (trimmedNickname.length == 1) {
            return if (hasHan) trimmedNickname else null
        }
        val hasLetter = trimmedNickname.any { it.isLetter() }
        if (trimmedNickname.length == 2) {
            return if (hasLetter && hasDigit) trimmedNickname else null
        }
        return if (hasLetter) trimmedNickname else null
    }

    private fun parsePerfectPacket(packet: ByteArray) {
        if (packet.size < 3) return
        var flag = parsingDamage(packet)
        if (flag) return
        flag = parseActorNameBindingRules(packet)
        if (flag) return
        flag = parseLootAttributionActorName(packet)
        if (flag) return
        flag = parseSummonPacket(packet)
        if (flag) return
        parseDoTPacket(packet)

    }

    private fun parseActorNameBindingRules(packet: ByteArray): Boolean {
        var i = 0
        var lastAnchor: ActorAnchor? = null
        val namedActors = mutableSetOf<Int>()
        while (i < packet.size) {
            if (packet[i] == 0x36.toByte()) {
                val actorInfo = readVarInt(packet, i + 1)
                lastAnchor = if (actorInfo.length > 0 && actorInfo.value >= 1000) {
                    ActorAnchor(actorInfo.value, i, i + 1 + actorInfo.length)
                } else {
                    null
                }
                i++
                continue
            }

            if (packet[i] == 0x07.toByte()) {
                val nameInfo = readAsciiName(packet, i)
                if (nameInfo == null) {
                    i++
                    continue
                }
                if (lastAnchor != null && lastAnchor.actorId !in namedActors) {
                    val distance = i - lastAnchor.endIndex
                    if (distance >= 0) {
                        val canBind = registerAsciiNickname(
                            packet,
                            lastAnchor.actorId,
                            nameInfo.first,
                            nameInfo.second
                        )
                        if (canBind) {
                            namedActors.add(lastAnchor.actorId)
                            lastAnchor = null
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
        val candidates = mutableListOf<ActorNameCandidate>()
        var idx = 0
        while (idx + 2 < packet.size) {
            val marker = packet[idx].toInt() and 0xff
            val markerNext = packet[idx + 1].toInt() and 0xff
            val isMarker = marker == 0xF8 && markerNext == 0x03
            if (isMarker) {
                val actorOffset = idx - 2
                if (actorOffset < 0 || !canReadVarInt(packet, actorOffset)) {
                    idx++
                    continue
                }
                val actorInfo = readVarInt(packet, actorOffset)
                if (actorInfo.length != 2 || actorOffset + actorInfo.length != idx) {
                    idx++
                    continue
                }
                if (actorInfo.value !in 100..99999 || actorInfo.value == 0) {
                    idx++
                    continue
                }
                val lengthIdx = idx + 2
                if (lengthIdx >= packet.size) {
                    idx++
                    continue
                }
                val nameLength = packet[lengthIdx].toInt() and 0xff
                if (nameLength !in 3..16) {
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
                candidates.add(ActorNameCandidate(actorInfo.value, sanitizedName, nameBytes))
                idx = skipGuildName(packet, nameEnd)
                continue
            }
            idx++
        }

        if (candidates.isEmpty()) return false
        val allowPrepopulate = candidates.size > 1
        var foundAny = false
        for (candidate in candidates) {
            if (!allowPrepopulate && !actorAppearsInCombat(candidate.actorId)) {
                dataStorage.cachePendingNickname(candidate.actorId, candidate.name)
                continue
            }
            if (dataStorage.getNickname()[candidate.actorId] != null) continue
            logger.info(
                "Loot attribution actor name found {} -> {} (hex={})",
                candidate.actorId,
                candidate.name,
                toHex(candidate.nameBytes)
            )
            DebugLogWriter.info(
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

    private fun actorExists(actorId: Int): Boolean {
        return dataStorage.getNickname().containsKey(actorId) ||
            dataStorage.getActorData().containsKey(actorId) ||
            dataStorage.getBossModeData().containsKey(actorId) ||
            dataStorage.getSummonData().containsKey(actorId)
    }

    private fun actorAppearsInCombat(actorId: Int): Boolean {
        return dataStorage.getActorData().containsKey(actorId) ||
            dataStorage.getBossModeData().containsKey(actorId) ||
            dataStorage.getSummonData().containsKey(actorId)
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
        decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (ex: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun skipGuildName(packet: ByteArray, startIndex: Int): Int {
        if (startIndex >= packet.size) return startIndex
        val length = packet[startIndex].toInt() and 0xff
        if (length !in 1..32) return startIndex
        val nameStart = startIndex + 1
        val nameEnd = nameStart + length
        if (nameEnd > packet.size) return startIndex
        val nameBytes = packet.copyOfRange(nameStart, nameEnd)
        val possibleName = decodeUtf8Strict(nameBytes) ?: return startIndex
        return nameEnd
    }

    private data class ActorNameCandidate(
        val actorId: Int,
        val name: String,
        val nameBytes: ByteArray
    )

    private data class ActorAnchor(val actorId: Int, val startIndex: Int, val endIndex: Int)

    private fun readAsciiName(packet: ByteArray, anchorIndex: Int): Pair<Int, Int>? {
        val lengthIndex = anchorIndex + 1
        if (lengthIndex >= packet.size) return null
        val nameLength = packet[lengthIndex].toInt() and 0xff
        if (nameLength !in 1..16) return null
        val nameStart = lengthIndex + 1
        val nameEnd = nameStart + nameLength
        if (nameEnd > packet.size) return null
        return nameStart to nameLength
    }

    private fun registerAsciiNickname(
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
            dataStorage.cachePendingNickname(actorId, sanitizedName)
            return true
        }
        val existingNickname = dataStorage.getNickname()[actorId]
        if (existingNickname != sanitizedName) {
            logger.info(
                "Actor name binding found {} -> {} (hex={})",
                actorId,
                sanitizedName,
                toHex(possibleNameBytes)
            )
            DebugLogWriter.info(
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
        offset += actorInfo.length
        if (packet.size < offset) return
        pdp.setActorId(actorInfo)

        val unknownInfo = readVarInt(packet,offset)
        if (unknownInfo.length <0) return
        offset += unknownInfo.length

        val skillCode:Int = parseUInt32le(packet,offset) / 100
        offset += 4
        if (packet.size <= offset) return
        pdp.setSkillCode(skillCode)

        val damageInfo = readVarInt(packet,offset)
        if (damageInfo.length < 0) return
        pdp.setDamage(damageInfo)

        logger.debug("{}", toHex(packet))
        DebugLogWriter.debug(logger, "{}", toHex(packet))
        logger.debug(
            "Dot damage actor {}, target {}, skill {}, damage {}",
            pdp.getActorId(),
            pdp.getTargetId(),
            pdp.getSkillCode1(),
            pdp.getDamage()
        )
        DebugLogWriter.debug(
            logger,
            "Dot damage actor {}, target {}, skill {}, damage {}",
            pdp.getActorId(),
            pdp.getTargetId(),
            pdp.getSkillCode1(),
            pdp.getDamage()
        )
        logger.debug("----------------------------------")
        DebugLogWriter.debug(logger, "----------------------------------")
        if (pdp.getActorId() != pdp.getTargetId()) {
            dataStorage.appendDamage(pdp)
        }

    }

    private fun findArrayIndex(data: ByteArray, vararg pattern: Int): Int {
        if (pattern.isEmpty()) return 0

        val p = ByteArray(pattern.size) { pattern[it].toByte() }

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


        if (packet[offset] != 0x40.toByte()) return false
        if (packet[offset + 1] != 0x36.toByte()) return false
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
                    logger.debug("mid: {}, code: {}", summonInfo.value, mobInfo.value)
                    DebugLogWriter.debug(logger, "mid: {}, code: {}", summonInfo.value, mobInfo.value)
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
        DebugLogWriter.debug(logger, "Summon mob mapping succeeded {},{}", realActorId, summonInfo.value)
        dataStorage.appendSummon(realActorId, summonInfo.value)
        return true
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


    private fun parsingDamage(packet: ByteArray): Boolean {
        if (packet[0] == 0x20.toByte()) return false
        val reader = DamagePacketReader(packet)
        val parsed = reader.parse() ?: return false
        val pdp = parsed.pdp
        val damageType = parsed.damageType
        pdp.setPayload(packet)

//        if (loopInfo.value != 0 && offset >= packet.size) return false
//
//        if (loopInfo.value != 0) {
//            for (i in 0 until loopInfo.length) {
//                var skipValueInfo = readVarInt(packet, offset)
//                if (skipValueInfo.length < 0) return false
//                pdp.addSkipData(skipValueInfo)
//                offset += skipValueInfo.length
//            }
//        }

        logger.trace("{}", toHex(packet))
        logger.trace("Type packet {}", toHex(byteArrayOf(damageType)))
        logger.trace(
            "Type packet bits {}",
            String.format("%8s", (damageType.toInt() and 0xFF).toString(2)).replace(' ', '0')
        )
        logger.trace("Varint packet: {}", toHex(packet.copyOfRange(parsed.flagsOffset, parsed.flagsOffset + parsed.flagsLength)))
        logger.debug(
            "Target: {}, attacker: {}, skill: {}, type: {}, damage: {}, damage flag: {}",
            pdp.getTargetId(),
            pdp.getActorId(),
            pdp.getSkillCode1(),
            pdp.getType(),
            pdp.getDamage(),
            pdp.getSpecials()
        )
        DebugLogWriter.debug(
            logger,
            "Target: {}, attacker: {}, skill: {}, type: {}, damage: {}, damage flag: {}",
            pdp.getTargetId(),
            pdp.getActorId(),
            pdp.getSkillCode1(),
            pdp.getType(),
            pdp.getDamage(),
            pdp.getSpecials()
        )

        val isAccepted = pdp.getActorId() != pdp.getTargetId()
        if (isAccepted) {
            //추후 hps 를 넣는다면 수정하기
            //혹시 나중에 자기자신에게 데미지주는 보스 기믹이 나오면..
            dataStorage.appendDamage(pdp)
        }
        return isAccepted

    }

    private fun toHex(bytes: ByteArray): String {
        //출력테스트용
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun computePacketSize(info: VarIntOutput): Int {
        return info.value + info.length - 4
    }

    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntOutput {
        //구글 Protocol Buffers 라이브러리에 이미 있나? 코드 효율성에 차이있어보이면 나중에 바꾸는게 나을듯?
        if (!canReadVarInt(bytes, offset)) {
            logger.debug("Unable to read varint, packet {} offset {}", toHex(bytes), offset)
            return VarIntOutput(-1, -1)
        }
        var value = 0
        var shift = 0
        var count = 0

        while (true) {
            if (offset + count >= bytes.size) {
                logger.error("Array out of bounds, packet {} offset {} count {}", toHex(bytes), offset, count)
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

    private fun parseSpecialDamageFlags(packet: ByteArray, offset: Int = 0, length: Int = packet.size): List<SpecialDamage> {
        val flags = mutableListOf<SpecialDamage>()

        if (length == 8) {
            return emptyList()
        }
        if (length >= 10 && offset < packet.size) {
            val flagByte = packet[offset].toInt() and 0xFF

            if ((flagByte and 0x01) != 0) {
                flags.add(SpecialDamage.BACK)
            }
            if ((flagByte and 0x02) != 0) {
                flags.add(SpecialDamage.UNKNOWN)
            }

            if ((flagByte and 0x04) != 0) {
                flags.add(SpecialDamage.PARRY)
            }

            if ((flagByte and 0x08) != 0) {
                flags.add(SpecialDamage.PERFECT)
            }

            if ((flagByte and 0x10) != 0) {
                flags.add(SpecialDamage.DOUBLE)
            }

            if ((flagByte and 0x20) != 0) {
                flags.add(SpecialDamage.ENDURE)
            }

            if ((flagByte and 0x40) != 0) {
                flags.add(SpecialDamage.UNKNOWN4)
            }

            if ((flagByte and 0x80) != 0) {
                flags.add(SpecialDamage.POWER_SHARD)
            }
        }

        return flags
    }

    private data class DamagePacketParseResult(
        val pdp: ParsedDamagePacket,
        val damageType: Byte,
        val flagsOffset: Int,
        val flagsLength: Int
    )

    private inner class DamagePacketReader(private val packet: ByteArray) {
        private var offset = 0

        fun parse(): DamagePacketParseResult? {
            if (!readAndValidateHeader()) return null

            val targetInfo = readVarIntAt() ?: return null
            if (targetInfo.value <= 0) return null

            val switchInfo = readVarIntAt() ?: return null
            val switchValue = switchInfo.value and mask
            if (switchValue !in 4..7) return null

            val flagInfo = readVarIntAt() ?: return null

            val actorInfo = readVarIntAt() ?: return null
            if (actorInfo.value <= 0) return null

            if (!hasRemaining(5)) return null
            val skillCode = parseUInt32le(packet, offset)
            offset += 5

            val typeInfo = readVarIntAt() ?: return null
            if (!hasRemaining()) return null
            val damageType = packet[offset]

            val flagsOffset = offset
            val flagsLength = getSpecialBlockSize(switchValue)
            if (!hasRemaining(flagsLength)) return null
            val specialFlags = parseSpecialDamageFlags(packet, flagsOffset, flagsLength)
            offset += flagsLength

            val unknownInfo = readVarIntAt() ?: return null
            val damageInfo = readVarIntAt() ?: return null
            val loopInfo = readVarIntAt() ?: return null

            val pdp = ParsedDamagePacket()
            pdp.setTargetId(targetInfo)
            pdp.setSwitchVariable(switchInfo)
            pdp.setFlag(flagInfo)
            pdp.setActorId(actorInfo)
            pdp.setSkillCode(skillCode)
            pdp.setType(typeInfo)
            pdp.setSpecials(specialFlags)
            pdp.setUnknown(unknownInfo)
            pdp.setDamage(damageInfo)
            pdp.setLoop(loopInfo)
            return DamagePacketParseResult(pdp, damageType, flagsOffset, flagsLength)
        }

        private fun readAndValidateHeader(): Boolean {
            val lengthInfo = readVarInt(packet)
            if (lengthInfo.length < 0) return false
            offset += lengthInfo.length
            if (!hasRemaining(2)) return false
            if (packet[offset] != 0x04.toByte() || packet[offset + 1] != 0x38.toByte()) return false
            offset += 2
            return hasRemaining()
        }

        private fun readVarIntAt(): VarIntOutput? {
            val info = readVarInt(packet, offset)
            if (info.length < 0) return null
            offset += info.length
            return info
        }

        private fun hasRemaining(count: Int = 1): Boolean {
            return offset + count <= packet.size
        }

        private fun getSpecialBlockSize(switchValue: Int): Int {
            return when (switchValue) {
                4 -> 8
                5 -> 12
                6 -> 10
                7 -> 14
                else -> 0
            }
        }
    }

}
