package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.entity.ParsedDamagePacket
import com.tbread.entity.SpecialDamage
import org.slf4j.LoggerFactory

class StreamProcessor(private val dataStorage: DataStorage) {
    private val logger = LoggerFactory.getLogger(StreamProcessor::class.java)

    data class VarIntOutput(val value: Int, val length: Int)

    private val mask = 0x0f

    fun onPacketReceived(packet: ByteArray) {
        val packetLengthInfo = readVarInt(packet)
        if (packet.size == packetLengthInfo.value) {
            logger.trace("현재 바이트길이와 예상 길이가 같음 : {}", toHex(packet.copyOfRange(0, packet.size - 3)))
            parsePerfectPacket(packet.copyOfRange(0, packet.size - 3))
            //더이상 자를필요가 없는 최종 패킷뭉치
            return
        }
        if (packet.size <= 3) return
        // 매직패킷 단일로 올때 무시
        if (packetLengthInfo.value > packet.size) {
            logger.trace("현재 바이트길이가 예상 길이보다 짧음 : {}", toHex(packet))
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
                    logger.trace("패킷을 성공적으로 분리함 : {}", toHex(packet.copyOfRange(0, packetLengthInfo.value - 3)))
                    parsePerfectPacket(packet.copyOfRange(0, packetLengthInfo.value - 3))
                    //매직패킷이 빠져있는 패킷뭉치
                }
            }

            onPacketReceived(packet.copyOfRange(packetLengthInfo.value - 3, packet.size))
            //남은패킷 재처리
        } catch (e: Exception) {
            logger.error("패킷 소비중 예외발생 {}", toHex(packet), e)
            return
        }

    }

    private fun parseBrokenLengthPacket(packet: ByteArray) {
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
                    if (hasPossibilityNickname(String(possibleNameBytes, Charsets.UTF_8))) {
                        logger.debug("1번패턴에서 발견된 예상 닉네임 : {}", String(possibleNameBytes, Charsets.UTF_8))
                        dataStorage.appendNickname(info.value, String(possibleNameBytes, Charsets.UTF_8))
                        originOffset++
                    }
                }
            }
            if (packet.size > innerOffset + 3 && packet[innerOffset + 1] == 0x00.toByte()) {
                val possibleNameLength = packet[innerOffset + 2].toInt() and 0xff
                if (packet.size >= innerOffset + possibleNameLength + 3 && possibleNameLength.toInt() != 0) {
                    val possibleNameBytes = packet.copyOfRange(innerOffset + 3, innerOffset + possibleNameLength + 3)
                    if (hasPossibilityNickname(String(possibleNameBytes, Charsets.UTF_8))) {
                        logger.debug("2번패턴에서 발견된 예상 닉네임 : {}", String(possibleNameBytes, Charsets.UTF_8))
                        dataStorage.appendNickname(info.value, String(possibleNameBytes, Charsets.UTF_8))
                        originOffset++
                    }
                }
            }
            if (packet.size > innerOffset + 5) {
                if (packet[innerOffset + 3] == 0x00.toByte() && packet[innerOffset + 4] == 0x07.toByte()) {
                    val possibleNameLength = packet[innerOffset + 5].toInt() and 0xff
                    if (packet.size > innerOffset + possibleNameLength + 6) {
                        val possibleNameBytes =
                            packet.copyOfRange(innerOffset + 6, innerOffset + possibleNameLength + 6)
                        if (hasPossibilityNickname(String(possibleNameBytes, Charsets.UTF_8))) {
                            logger.debug("신규 패턴에서 발견된 예상 닉네임 : {}", String(possibleNameBytes, Charsets.UTF_8))
                            dataStorage.appendNickname(info.value, String(possibleNameBytes, Charsets.UTF_8))
                            originOffset++
                        }
                    }
                }
            }
            originOffset++
        }
    }

    private fun hasPossibilityNickname(nickname: String): Boolean {
        if (nickname.isEmpty()) return false
        val regex = Regex("^[가-힣a-zA-Z0-9]+$")
        if (!regex.matches(nickname)) return false
        val onlyNumbers = Regex("^[0-9]+$")
        if (onlyNumbers.matches(nickname)) return false
        val oneAlphabet = Regex("^[A-Za-z]$")
        return !oneAlphabet.matches(nickname)
    }

    private fun parsePerfectPacket(packet: ByteArray) {
        if (packet.size < 3) return
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
        offset += actorInfo.length + 1
        if (packet.size < offset) return
        pdp.setActorId(actorInfo)

        val skillCode:Int = parseUInt32le(packet,offset) / 100
        offset += 4
        if (packet.size < offset) return
        pdp.setSkillCode(skillCode)

        val damageInfo = readVarInt(packet,offset)
        if (damageInfo.length < 0) return
        pdp.setDamage(damageInfo)

        logger.info("도트데미지 공격자 {},피격자 {},스킬 {},데미지 {}",pdp.getActorId(),pdp.getTargetId(),pdp.getSkillCode1(),pdp.getDamage())
        logger.info("전체 패킷: {}",toHex(packet))
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

        logger.debug("소환몹 맵핑 성공 {},{}", realActorId, summonInfo.value)
        dataStorage.appendSummon(realActorId, summonInfo.value)
        return true
    }

    private fun parseUInt16le(packet: ByteArray, offset: Int = 0): Int {
        return (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun parseUInt32le(packet: ByteArray, offset: Int = 0): Int {
        require(offset + 4 <= packet.size) { "패킷 길이가 필요길이보다 짧음" }
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

        logger.debug("0번 패턴에서 발견된 확정 닉네임 {}", String(np, Charsets.UTF_8))
        dataStorage.appendNickname(playerInfo.value, String(np, Charsets.UTF_8))

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
        pdp.setSpecials(parseSpecialDamageFlags(packet.copyOfRange(start,start+tempV)))
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

        if (loopInfo.value != 0 && offset >= packet.size) return false

        if (loopInfo.value != 0) {
            for (i in 0 until loopInfo.length) {
                var skipValueInfo = readVarInt(packet, offset)
                if (skipValueInfo.length < 0) return false
                pdp.addSkipData(skipValueInfo)
                offset += skipValueInfo.length
            }
        }

        logger.trace("{}", toHex(packet))
        logger.trace("타입패킷 {}", toHex(byteArrayOf(damageType)))
        logger.trace(
            "타입패킷비트 {}", String.format("%8s", (damageType.toInt() and 0xFF).toString(2))
                .replace(' ', '0')
        )
        logger.trace("가변패킷: {}", toHex(packet.copyOfRange(start, start + tempV)))
        logger.info(
            "피격자: {},공격자: {},스킬: {},타입: {},데미지: {},데미지플래그: {}",
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
        return true

    }

    private fun toHex(bytes: ByteArray): String {
        //출력테스트용
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun readVarInt(bytes: ByteArray, offset: Int = 0): VarIntOutput {
        //구글 Protocol Buffers 라이브러리에 이미 있나? 코드 효율성에 차이있어보이면 나중에 바꾸는게 나을듯?
        var value = 0
        var shift = 0
        var count = 0

        while (true) {
            if (offset + count >= bytes.size) {
                logger.error("배열범위초과, 패킷 {} 오프셋 {} count {}", toHex(bytes), offset, count)
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
                    "가변정수 오버플로우, 패킷 {} 오프셋 {} shift {}",
                    toHex(bytes.copyOfRange(offset, offset + 4)),
                    offset,
                    shift
                )
                return VarIntOutput(-1, -1)
            }
        }
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
