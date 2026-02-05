package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.entity.ParsedDamagePacket
import com.tbread.entity.SpecialDamage
import com.tbread.logging.DebugLogWriter
import org.slf4j.LoggerFactory

private const val AUTO_ATTACK_CODE = 1

class StreamProcessor(private val dataStorage: DataStorage) {
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
                val raw = (data[start + i].toInt() and 0xFF) or
                    ((data[start + i + 1].toInt() and 0xFF) shl 8) or
                    ((data[start + i + 2].toInt() and 0xFF) shl 16) or
                    ((data[start + i + 3].toInt() and 0xFF) shl 24)
                val normalized = normalizeSkillId(raw)
                if (normalized in 3_000_000..3_999_999) {
                    offset = start + i + 4
                    return AUTO_ATTACK_CODE
                }
                if (
                    normalized in 11_000_000..19_999_999 ||
                    normalized in 100_000..199_999
                ) {
                    offset = start + i + 4
                    return normalized
                }
            }

            throw IllegalStateException("skill not found")
        }

    }

    fun onPacketReceived(packet: ByteArray) {
        val packetLengthInfo = readVarInt(packet)
        if (packet.size == packetLengthInfo.value) {
            logger.trace(
                "Current byte length matches expected length: {}",
                toHex(packet.copyOfRange(0, packet.size - 3))
            )
            parsePerfectPacket(packet.copyOfRange(0, packet.size - 3))
            //더이상 자를필요가 없는 최종 패킷뭉치
            return
        }
        if (packet.size <= 3) return
        // 매직패킷 단일로 올때 무시
        if (packetLengthInfo.value > packet.size) {
            logger.trace("Current byte length is shorter than expected: {}", toHex(packet))
            parseBrokenLengthPacket(packet)
            //길이헤더가 실제패킷보다 김 보통 여기 닉네임이 몰려있는듯?
            return
        }
        if (packetLengthInfo.value <= 3) {
            onPacketReceived(packet.copyOfRange(1, packet.size))
            return
        }

        try {
            if (packet.copyOfRange(0, packetLengthInfo.value - 3).size != 3) {
                if (packet.copyOfRange(0, packetLengthInfo.value - 3).isNotEmpty()) {
                    logger.trace(
                        "Packet split succeeded: {}",
                        toHex(packet.copyOfRange(0, packetLengthInfo.value - 3))
                    )
                    parsePerfectPacket(packet.copyOfRange(0, packetLengthInfo.value - 3))
                    //매직패킷이 빠져있는 패킷뭉치
                }
            }

            onPacketReceived(packet.copyOfRange(packetLengthInfo.value - 3, packet.size))
            //남은패킷 재처리
        } catch (e: IndexOutOfBoundsException) {
            logger.debug("Truncated tail packet skipped: {}", toHex(packet))
            return
        }

    }

    private fun parseBrokenLengthPacket(packet: ByteArray, flag: Boolean = true) {
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
                        if (damageIdx < dotIdx) damageIdx to ::parsingDamage
                        else dotIdx to ::parseDoTPacket
                    }
                    damageIdx > 0 -> damageIdx to ::parsingDamage
                    dotIdx > 0 -> dotIdx to ::parseDoTPacket
                    else -> -1 to null
                }
                if (idx > 0 && handler != null){
                    val packetLengthInfo = readVarInt(packet, idx - 1)
                    if (packetLengthInfo.length == 1) {
                        val startIdx = idx - 1
                        val endIdx = idx - 1 + packetLengthInfo.value - 3
                        if (startIdx in 0..<endIdx && endIdx <= packet.size) {
                            val extractedPacket = packet.copyOfRange(startIdx, endIdx)
                            handler(extractedPacket)
                            processed = true
                            if (endIdx < packet.size) {
                                val remainingPacket = packet.copyOfRange(endIdx, packet.size)
                                parseBrokenLengthPacket(remainingPacket, false)
                            }
                        }
                    }
                }
            }
            if (flag && !processed) {
                logger.debug("Remaining packet {}", toHex(packet))
                castNicknameNet(packet)
            }
            return
        }
        val newPacket = packet.copyOfRange(10, packet.size)
        onPacketReceived(newPacket)
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
                        logger.info(
                            "Potential nickname found in cast net: {} (hex={})",
                            sanitizedName,
                            toHex(possibleNameBytes)
                        )
                        DebugLogWriter.info(
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
        decodeUtf8Strict(nameBytes) ?: return startIndex
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
        val nameBytes = packet.copyOfRange(nameStart, nameEnd)
        if (!isPrintableAscii(nameBytes)) return null
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
        if (!isPrintableAscii(possibleNameBytes)) return false
        val possibleName = String(possibleNameBytes, Charsets.US_ASCII)
        if (!actorExists(actorId)) {
            dataStorage.cachePendingNickname(actorId, possibleName)
            return true
        }
        val existingNickname = dataStorage.getNickname()[actorId]
        if (existingNickname != possibleName) {
            logger.info(
                "Actor name binding found {} -> {} (hex={})",
                actorId,
                possibleName,
                toHex(possibleNameBytes)
            )
            DebugLogWriter.info(
                logger,
                "Actor name binding found {} -> {} (hex={})",
                actorId,
                possibleName,
                toHex(possibleNameBytes)
            )
        }
        dataStorage.appendNickname(actorId, possibleName)
        return true
    }

    private fun isPrintableAscii(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        for (b in bytes) {
            val value = b.toInt() and 0xff
            if (value < 0x20 || value > 0x7e) return false
        }
        return true
    }

    private fun parsePerfectPacket(packet: ByteArray) {
        if (packet.size < 3) return
        var flag = parsingDamage(packet)
        if (flag) return
        flag = parseActorNameBindingRules(packet)
        if (flag) return
        flag = parseLootAttributionActorName(packet)
        if (flag) return
        flag = parsingNickname(packet)
        if (flag) return
        flag = parseSummonPacket(packet)
        if (flag) return
        parseDoTPacket(packet)

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

        val skillCode:Int = parseUInt32le(packet,offset) / 100
        offset += 4
        if (packet.size <= offset) return
        pdp.setSkillCode(skillCode)

        val damageInfo = readVarInt(packet,offset)
        if (damageInfo.length < 0) return
        pdp.setDamage(damageInfo)
        pdp.setHexPayload(toHex(packet))

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

    private fun parsingNickname(packet: ByteArray): Boolean {
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        offset += packetLengthInfo.length
//        if (packetLengthInfo.value < 32) return
        //좀더 검증필요 대부분이 0x20,0x23 정도였음

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
        DebugLogWriter.debug(logger, "Confirmed nickname found in pattern 0 {}", sanitizedName)
        dataStorage.appendNickname(playerInfo.value, sanitizedName)

        return true
    }

    private fun parsingDamage(packet: ByteArray): Boolean {
        if (packet[0] == 0x20.toByte()) return false
        if (packet[0] == 0x1f.toByte()) return true
        if (packet[0] == 0x1e.toByte()) return true
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        val reader = DamagePacketReader(packet, packetLengthInfo.length)

        if (reader.offset >= packet.size) return false
        if (packet[reader.offset] != 0x04.toByte()) return false
        if (packet[reader.offset + 1] != 0x38.toByte()) return false
        reader.offset += 2
        fun logUnparsedDamage(): Boolean {
            DebugLogWriter.debug(logger, "Unparsed damage packet hex={}", toHex(packet))
            return false
        }
        if (reader.offset >= packet.size) return logUnparsedDamage()
        val targetValue = reader.tryReadVarInt() ?: return logUnparsedDamage()
        val targetInfo = VarIntOutput(targetValue, 1)
        if (reader.offset >= packet.size) return logUnparsedDamage()

        val switchValue = reader.tryReadVarInt() ?: return logUnparsedDamage()
        val switchInfo = VarIntOutput(switchValue, 1)
        if (reader.offset >= packet.size) return logUnparsedDamage()
        val andResult = switchInfo.value and mask
        if (andResult !in 4..7) {
            return true
        }

        val flagValue = reader.tryReadVarInt() ?: return logUnparsedDamage()
        val flagInfo = VarIntOutput(flagValue, 1)
        if (reader.offset >= packet.size) return logUnparsedDamage()

        val actorValue = reader.tryReadVarInt() ?: return logUnparsedDamage()
        val actorInfo = VarIntOutput(actorValue, 1)
        if (reader.offset >= packet.size) return logUnparsedDamage()
        if (actorInfo.value == targetInfo.value) return true
        if (!isActorAllowed(actorInfo.value)) return true

        if (reader.offset + 5 >= packet.size) return logUnparsedDamage()

        val skillCode = try {
            reader.readSkillCode()
        } catch (e: IllegalStateException) {
            return logUnparsedDamage()
        }

        val typeValue = reader.tryReadVarInt() ?: return logUnparsedDamage()
        val typeInfo = VarIntOutput(typeValue, 1)
        if (reader.offset >= packet.size) return logUnparsedDamage()

        val damageType = typeInfo.value.toByte()

        val start = reader.offset
        var tempV = 0
        tempV += when (andResult) {
            4 -> 8
            5 -> 12
            6 -> 10
            7 -> 14
            else -> return logUnparsedDamage()
        }
        if (start + tempV > packet.size) return logUnparsedDamage()
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

        if (reader.offset >= packet.size) return logUnparsedDamage()

        val unknownInfo: VarIntOutput?
        val unknownValue = reader.tryReadVarInt() ?: return logUnparsedDamage()
        unknownInfo = VarIntOutput(unknownValue, 1)
        val finalDamage = reader.tryReadVarInt() ?: return logUnparsedDamage()
        var adjustedDamage = finalDamage
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
                adjustedDamage = (finalDamage - hitSum).coerceAtLeast(0)
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

        val pdp = ParsedDamagePacket()
        pdp.setTargetId(targetInfo)
        pdp.setSwitchVariable(switchInfo)
        pdp.setFlag(flagInfo)
        pdp.setActorId(actorInfo)
        pdp.setSkillCode(skillCode)
        pdp.setType(typeInfo)
        pdp.setSpecials(specials)
        pdp.setMultiHitCount(multiHitCount)
        pdp.setMultiHitDamage(multiHitDamage)
        pdp.setHealAmount(healAmount)
        unknownInfo?.let { pdp.setUnknown(it) }
        pdp.setDamage(VarIntOutput(adjustedDamage, 1))
        pdp.setHexPayload(toHex(packet))

        logger.trace("{}", toHex(packet))
        logger.trace("Type packet {}", toHex(byteArrayOf(damageType)))
        logger.trace(
            "Type packet bits {}",
            String.format("%8s", (damageType.toInt() and 0xFF).toString(2)).replace(' ', '0')
        )
        logger.trace("Varint packet: {}", toHex(packet.copyOfRange(start, start + tempV)))
        logger.debug(
            "Target: {}, attacker: {}, skill: {}, type: {}, damage: {}, damage flag:{}",
            pdp.getTargetId(),
            pdp.getActorId(),
            pdp.getSkillCode1(),
            pdp.getType(),
            pdp.getDamage(),
            pdp.getSpecials()
        )
        DebugLogWriter.debug(
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

        if (pdp.getActorId() != pdp.getTargetId()) {
            //추후 hps 를 넣는다면 수정하기
            //혹시 나중에 자기자신에게 데미지주는 보스 기믹이 나오면..
            dataStorage.appendDamage(pdp)
        }
        return true

    }

    private fun toHex(bytes: ByteArray): String {
        //출력테스트용
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun normalizeSkillId(raw: Int): Int {
        return raw - (raw % 10000)
    }


    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntOutput {
        //구글 Protocol Buffers 라이브러리에 이미 있나? 코드 효율성에 차이있어보이면 나중에 바꾸는게 나을듯?
        var value = 0
        var shift = 0
        var count = 0

        while (true) {
            if (offset + count >= bytes.size) {
                logger.debug("Truncated packet skipped: {} offset {} count {}", toHex(bytes), offset, count)
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
