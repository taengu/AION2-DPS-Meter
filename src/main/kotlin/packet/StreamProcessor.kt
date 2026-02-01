package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.entity.ParsedDamagePacket
import com.tbread.entity.SpecialDamage
import com.tbread.logging.DebugLogWriter
import org.slf4j.LoggerFactory
import kotlin.math.min

class StreamProcessor(private val dataStorage: DataStorage) {
    private val logger = LoggerFactory.getLogger(StreamProcessor::class.java)

    data class VarIntOutput(val value: Int, val length: Int)

    private val mask = 0x0f
    private val packetStartMarker = byteArrayOf(0x06, 0x00, 0x36)

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
        val packetSize = computePacketSize(packetLengthInfo)

        try {
            if (packet.copyOfRange(0, packetSize).size != 3) {
                if (packet.copyOfRange(0, packetSize).isNotEmpty()) {
                    logger.trace(
                        "Packet split succeeded: {}",
                        toHex(packet.copyOfRange(0, packetSize))
                    )
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
                parseNicknameFromBrokenLengthPacket(packet)
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

    private fun parseNicknameFromBrokenLengthPacket(packet: ByteArray) {
        scanMarkerNicknames(packet)

        var originOffset = 0
        while (originOffset < packet.size) {
            val info = readVarInt(packet, originOffset)
            if (info.length == -1) {
                return
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
                            "Potential nickname found in pattern 1: {} (hex={})",
                            sanitizedName,
                            toHex(possibleNameBytes)
                        )
                        DebugLogWriter.info(
                            logger,
                            "Potential nickname found in pattern 1: {} (hex={})",
                            sanitizedName,
                            toHex(possibleNameBytes)
                        )
                        dataStorage.appendNickname(info.value, sanitizedName)
                    }
                }
            }
            // Pattern 2 disabled temporarily due to unreliable results.
            if (packet.size > innerOffset + 5) {
                if (packet[innerOffset + 3] == 0x00.toByte() && packet[innerOffset + 4] == 0x07.toByte()) {
                    val possibleNameLength = packet[innerOffset + 5].toInt() and 0xff
                    if (packet.size > innerOffset + possibleNameLength + 6) {
                        val possibleNameBytes =
                            packet.copyOfRange(innerOffset + 6, innerOffset + possibleNameLength + 6)
                        val possibleName = String(possibleNameBytes, Charsets.UTF_8)
                        val sanitizedName = sanitizeNickname(possibleName)
                        if (sanitizedName != null) {
                            logger.info(
                                "Potential nickname found in new pattern: {} (hex={})",
                                sanitizedName,
                                toHex(possibleNameBytes)
                            )
                            DebugLogWriter.info(
                                logger,
                                "Potential nickname found in new pattern: {} (hex={})",
                                sanitizedName,
                                toHex(possibleNameBytes)
                            )
                            dataStorage.appendNickname(info.value, sanitizedName)
                        }
                    }
                }
            }
            originOffset++
        }
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

    private fun parsePerfectPacket(packet: ByteArray) {
        if (packet.size < 3) return
        scanMarkerNicknames(packet)
        var flag = parsingDamage(packet)
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
        if (LocalPlayer.characterName != null && sanitizedName == LocalPlayer.characterName) {
            LocalPlayer.playerId = playerInfo.value.toLong()
        }

        return true
    }

    private fun parsingDamage(packet: ByteArray): Boolean {
        if (packet[0] == 0x20.toByte()) return false
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        if (packetLengthInfo.length < 0) return false
        val pdp = ParsedDamagePacket()

        offset += packetLengthInfo.length

        if (offset >= packet.size) return false
        if (packet[offset] != 0x04.toByte()) return false
        if (packet[offset + 1] != 0x38.toByte()) return false
        offset += 2
        if (offset >= packet.size) return false
        val targetInfo = readVarInt(packet, offset)
        if (targetInfo.length < 0) return false
        pdp.setTargetId(targetInfo)
        offset += targetInfo.length //타겟
        if (offset >= packet.size) return false

        val switchInfo = readVarInt(packet, offset)
        if (switchInfo.length < 0) return false
        pdp.setSwitchVariable(switchInfo)
        offset += switchInfo.length //점프용
        if (offset >= packet.size) return false

        val flagInfo = readVarInt(packet, offset)
        if (flagInfo.length < 0) return false
        pdp.setFlag(flagInfo)
        offset += flagInfo.length //플래그
        if (offset >= packet.size) return false

        val actorInfo = readVarInt(packet, offset)
        if (actorInfo.length < 0) return false
        pdp.setActorId(actorInfo)
        offset += actorInfo.length
        if (offset >= packet.size) return false

        if (offset + 5 >= packet.size) return false

        val temp = offset

        val skillCode = parseUInt32le(packet, offset)
        pdp.setSkillCode(skillCode)

        offset = temp + 5

        val typeInfo = readVarInt(packet, offset)
        if (typeInfo.length < 0) return false
        pdp.setType(typeInfo)
        offset += typeInfo.length
        if (offset >= packet.size) return false

        val damageType = packet[offset]

        val andResult = switchInfo.value and mask
        val start = offset
        var tempV = 0
        tempV += when (andResult) {
            4 -> 8
            5 -> 12
            6 -> 10
            7 -> 14
            else -> return false
        }
        if (start+tempV > packet.size) return false
        pdp.setSpecials(parseSpecialDamageFlags(packet.copyOfRange(start, start + tempV)))
        offset += tempV


        if (offset >= packet.size) return false

        val unknownInfo = readVarInt(packet, offset)
        if (unknownInfo.length < 0) return false
        pdp.setUnknown(unknownInfo)
        offset += unknownInfo.length
        if (offset >= packet.size) return false

        val damageInfo = readVarInt(packet, offset)
        if (damageInfo.length < 0) return false
        pdp.setDamage(damageInfo)
        offset += damageInfo.length
        if (offset >= packet.size) return false

        val loopInfo = readVarInt(packet, offset)
        if (loopInfo.length < 0) return false
        pdp.setLoop(loopInfo)
        offset += loopInfo.length

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
        logger.trace("Varint packet: {}", toHex(packet.copyOfRange(start, start + tempV)))
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

        if (pdp.getActorId() != pdp.getTargetId()) {
            //추후 hps 를 넣는다면 수정하기
            //혹시 나중에 자기자신에게 데미지주는 보스 기믹이 나오면..
            dataStorage.appendDamage(pdp)
        }
        scanMarkerNicknames(packet)
        return true

    }

    private fun toHex(bytes: ByteArray): String {
        //출력테스트용
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun scanMarkerNicknames(packet: ByteArray): Boolean {
        if (scanMarkerNicknamesInPacket(packet)) return true
        if (scanOpcodeNicknamesInPacket(packet)) return true
        if (scanLengthPrefixedNicknamesInPacket(packet)) return true
        var found = false
        var searchOffset = 0
        while (searchOffset <= packet.size - packetStartMarker.size) {
            val markerIndex = findArrayIndex(packet.copyOfRange(searchOffset, packet.size), packetStartMarker)
            if (markerIndex < 0) break
            val packetStart = searchOffset + markerIndex
            val lengthInfo = readVarInt(packet, packetStart)
            if (lengthInfo.length > 0) {
                val packetSize = computePacketSize(lengthInfo)
                val packetEnd = packetStart + packetSize
                if (packetSize > 0 && packetEnd <= packet.size) {
                    val packetSlice = packet.copyOfRange(packetStart, packetEnd)
                    if (scanMarkerNicknamesInPacket(packetSlice) ||
                        scanOpcodeNicknamesInPacket(packetSlice) ||
                        scanLengthPrefixedNicknamesInPacket(packetSlice)
                    ) {
                        found = true
                    }
                    searchOffset = packetEnd
                    continue
                }
            }
            searchOffset = packetStart + 1
        }
        return found
    }

    private fun scanLengthPrefixedNicknamesInPacket(packet: ByteArray): Boolean {
        var found = false
        var offset = 0
        while (offset + 3 < packet.size) {
            val actorInfo = readVarInt(packet, offset)
            if (actorInfo.length != 2 || actorInfo.value < 1000) {
                offset++
                continue
            }
            val nicknameLength = packet[offset + actorInfo.length].toInt() and 0xff
            val nicknameStart = offset + actorInfo.length + 1
            val nicknameEnd = nicknameStart + nicknameLength
            if (nicknameLength == 0 || nicknameLength > 72 || nicknameEnd > packet.size) {
                offset++
                continue
            }
            val possibleNameBytes = packet.copyOfRange(nicknameStart, nicknameEnd)
            val possibleName = String(possibleNameBytes, Charsets.UTF_8)
            val sanitizedName = sanitizeNickname(possibleName)
            if (sanitizedName != null) {
                logger.info(
                    "Length nickname mapped: actor={} nickname={}",
                    actorInfo.value,
                    sanitizedName
                )
                logger.info(
                    "Potential nickname found in length pattern: {} (hex={})",
                    sanitizedName,
                    toHex(possibleNameBytes)
                )
                DebugLogWriter.info(
                    logger,
                    "Potential nickname found in length pattern: {} (hex={})",
                    sanitizedName,
                    toHex(possibleNameBytes)
                )
                dataStorage.appendNickname(actorInfo.value, sanitizedName)
                found = true
                offset = nicknameEnd
                continue
            }
            offset++
        }
        return found
    }

    private fun scanOpcodeNicknamesInPacket(packet: ByteArray): Boolean {
        var found = false
        var offset = 0
        while (offset + 2 < packet.size) {
            if (isOpcodeNicknameMarker(packet, offset)) {
                found = scanOpcodeNicknameAtOffset(packet, offset) || found
            }
            offset++
        }
        return found
    }

    private fun isOpcodeNicknameMarker(packet: ByteArray, offset: Int): Boolean {
        if (packet[offset + 2] != 0x05.toByte()) return false
        val b0 = packet[offset]
        val b1 = packet[offset + 1]
        return (b0 == 0x00.toByte() && b1 == 0x07.toByte()) ||
            (b0 == 0x01.toByte() && b1 == 0x07.toByte()) ||
            (b0 == 0x02.toByte() && b1 == 0x0d.toByte())
    }

    private fun scanOpcodeNicknameAtOffset(packet: ByteArray, offset: Int): Boolean {
        val actorInfo = findActorIdBeforeOpcode(packet, offset)
        val actorId = actorInfo.value
        val nicknameStart = offset + 3
        if (actorInfo.length == 2 && actorId >= 1000 && nicknameStart < packet.size) {
            var nicknameEnd = nicknameStart
            while (nicknameEnd < packet.size && packet[nicknameEnd] != 0x00.toByte()) {
                nicknameEnd++
            }
            if (nicknameEnd > nicknameStart) {
                val possibleNameBytes = packet.copyOfRange(nicknameStart, nicknameEnd)
                val possibleName = String(possibleNameBytes, Charsets.UTF_8)
                val sanitizedName = sanitizeNickname(possibleName)
                if (sanitizedName != null) {
                    logger.info(
                        "Opcode nickname mapped: actor={} nickname={}",
                        actorId,
                        sanitizedName
                    )
                    logger.info(
                        "Potential nickname found in opcode pattern: {} (hex={})",
                        sanitizedName,
                        toHex(possibleNameBytes)
                    )
                    DebugLogWriter.info(
                        logger,
                        "Potential nickname found in opcode pattern: {} (hex={})",
                        sanitizedName,
                        toHex(possibleNameBytes)
                    )
                    dataStorage.appendNickname(actorId, sanitizedName)
                    return true
                }
            }
        }
        return false
    }

    private fun findActorIdBeforeOpcode(packet: ByteArray, opcodeOffset: Int): VarIntOutput {
        if (opcodeOffset <= 1) return VarIntOutput(-1, -1)
        val maxBacktrack = min(8, opcodeOffset)
        for (backtrack in 2..maxBacktrack) {
            val start = opcodeOffset - backtrack
            val info = readVarInt(packet, start)
                if (info.length == 2 && start + info.length == opcodeOffset) {
                    return info
                }
        }
        return VarIntOutput(-1, -1)
    }

    private fun scanMarkerNicknamesInPacket(packet: ByteArray): Boolean {
        var found = false
        var markerOffset = 0
        while (markerOffset + 2 < packet.size) {
            if (packet[markerOffset] == 0xF8.toByte() &&
                packet[markerOffset + 1] == 0x03.toByte() &&
                packet[markerOffset + 2] == 0x05.toByte()
            ) {
                val actorInfo = decodeVarIntBeforeMarker(packet, markerOffset)
                val actorId = actorInfo.value
                val nicknameStart = markerOffset + 3
                if (actorInfo.length == 2 && actorId >= 1000 && nicknameStart < packet.size) {
                    var nicknameEnd = nicknameStart
                    while (nicknameEnd < packet.size && packet[nicknameEnd] != 0x00.toByte()) {
                        nicknameEnd++
                    }
                    if (nicknameEnd <= nicknameStart) {
                        markerOffset++
                        continue
                    }
                    val possibleNameBytes = packet.copyOfRange(nicknameStart, nicknameEnd)
                    val possibleName = String(possibleNameBytes, Charsets.UTF_8)
                    val sanitizedName = sanitizeNickname(possibleName)
                    if (sanitizedName != null) {
                        logger.info(
                            "Marker nickname mapped: actor={} nickname={}",
                            actorId,
                            sanitizedName
                        )
                        logger.info(
                            "Potential nickname found in marker pattern: {} (hex={})",
                            sanitizedName,
                            toHex(possibleNameBytes)
                        )
                        DebugLogWriter.info(
                            logger,
                            "Potential nickname found in marker pattern: {} (hex={})",
                            sanitizedName,
                            toHex(possibleNameBytes)
                        )
                        dataStorage.appendNickname(actorId, sanitizedName)
                        found = true
                    }
                }
            }
            markerOffset++
        }
        return found
    }

    private fun decodeVarIntBeforeMarker(bytes: ByteArray, markerOffset: Int): VarIntOutput {
        if (markerOffset <= 0) return VarIntOutput(-1, -1)
        val expectedLength = 2
        if (markerOffset < expectedLength) return VarIntOutput(-1, -1)
        val start = markerOffset - expectedLength
        val info = readVarInt(bytes, start)
        return if (info.length == expectedLength && start + info.length == markerOffset) {
            info
        } else {
            VarIntOutput(-1, -1)
        }
    }

    private fun computePacketSize(info: VarIntOutput): Int {
        return info.value + info.length - 4
    }

    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntOutput {
        //구글 Protocol Buffers 라이브러리에 이미 있나? 코드 효율성에 차이있어보이면 나중에 바꾸는게 나을듯?
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

    private fun parseSpecialDamageFlags(packet: ByteArray): List<SpecialDamage> {
        val flags = mutableListOf<SpecialDamage>()

        if (packet.size == 8) {
            return emptyList()
        }
        if (packet.size >= 10) {
            val flagByte = packet[0].toInt() and 0xFF

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


}
