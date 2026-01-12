package com.tbread.packet

import com.tbread.DataStorage
import com.tbread.entity.ParsedDamagePacket

class StreamProcessor(private val dataStorage: DataStorage) {

    data class VarIntOutput(val value: Int, val length: Int)

    private val mask = 0x0f

    fun onPacketReceived(packet: ByteArray) {
        // 매직패킷 단일로 올때 무시
        val packetLengthInfo = readVarInt(packet)
        if (packet.size == packetLengthInfo.value) {
            parsePerfectPacket(packet.copyOfRange(0, packet.size - 3))
            //더이상 자를필요가 없는 최종 패킷뭉치
            return
        }
        if (packet.size <= 3) return
        if (packetLengthInfo.value > packet.size){
            parseBrokenLengthPacket(packet)
            //길이헤더가 실제패킷보다 김 보통 여기 닉네임이 몰려있는듯?
            return
        }
        if (packetLengthInfo.value <= 2) {
            onPacketReceived(packet.copyOfRange(1, packet.size))
            return
        }

        try {
            if (packet.copyOfRange(0, packetLengthInfo.value - 3).size != 3){
            parsePerfectPacket(packet.copyOfRange(0, packetLengthInfo.value - 3))
            //매직패킷이 빠져있는 패킷뭉치
                }

            onPacketReceived(packet.copyOfRange(packetLengthInfo.value - 3, packet.size))
            //남은패킷 재처리
        } catch (e: Exception) {
            println("${this::class.java.simpleName} : [경고] 패킷 소비자 에러")
            println(toHex(packet))
            e.printStackTrace()
            return
            //구현부끝나면 로거넣고 빼기
        }

    }

    private fun parseBrokenLengthPacket(packet:ByteArray){
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
                        dataStorage.appendNickname(info.value, String(possibleNameBytes, Charsets.UTF_8))
                        originOffset++
                        continue
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

    private fun parseSummonPacket(packet: ByteArray):Boolean {
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        offset += packetLengthInfo.length


        if (packet[offset] != 0x40.toByte()) return false
        if (packet[offset + 1] != 0x36.toByte()) return false
        offset += 2

        val summonInfo = readVarInt(packet, offset)
        offset += summonInfo.length + 28
        if (packet.size > offset){
            val mobInfo = readVarInt(packet, offset)
            offset += mobInfo.length
            if (packet.size > offset){
                val mobInfo2 = readVarInt(packet, offset)
                if (mobInfo.value == mobInfo2.value){
                    dataStorage.appendMob(summonInfo.value,mobInfo.value)
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

        dataStorage.appendSummon(realActorId,summonInfo.value)
        return true
    }

    private fun parseUInt16le(packet: ByteArray, offset: Int = 0): Int {
        return (packet[offset].toInt() and 0xff) or ((packet[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun parsingNickname(packet: ByteArray):Boolean {
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
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

        dataStorage.appendNickname(playerInfo.value, String(np, Charsets.UTF_8))

        return true
    }

    private fun parsingDamage(packet: ByteArray):Boolean {
        if (packet[0] == 0x20.toByte()) return false
        var offset = 0
        val packetLengthInfo = readVarInt(packet)
        val pdp = ParsedDamagePacket()

        offset += packetLengthInfo.length

        if (offset >= packet.size) return false
        if (packet[offset] != 0x04.toByte()) return false
        if (packet[offset + 1] != 0x38.toByte()) return false
        offset += 2
        if (offset >= packet.size) return false
        val targetInfo = readVarInt(packet, offset)
        pdp.setTargetId(targetInfo)
        offset += targetInfo.length //타겟
        if (offset >= packet.size) return false

        val switchInfo = readVarInt(packet, offset)
        pdp.setSwitchVariable(switchInfo)
        offset += switchInfo.length //점프용
        if (offset >= packet.size) return false

        val flagInfo = readVarInt(packet, offset)
        pdp.setFlag(flagInfo)
        offset += flagInfo.length //플래그
        if (offset >= packet.size) return false

        val actorInfo = readVarInt(packet, offset)
        pdp.setActorId(actorInfo)
        offset += actorInfo.length
        if (offset >= packet.size) return false

        if (offset + 5 >= packet.size) return false

        val temp = offset

        val skillCode = parseUInt16le(packet, offset)
        pdp.setSkillCode(skillCode)
        offset += 2

        val skillType = parseUInt16le(packet, offset)
        pdp.setSkillType(skillType)
        // 다음연계기가 있을경우 168,조건기 170? 절단2타와 올려치기 모두 174로 동일 유린/검난/결박 172
        // 발목격파가 171, 연계기 174 예상 파기
        offset = temp + 5

        val typeInfo = readVarInt(packet, offset)
        pdp.setType(typeInfo)
        offset += typeInfo.length
        if (offset >= packet.size) return false

        val andResult = switchInfo.value and mask
        offset += when (andResult) {
            4 -> 8
            5 -> 12
            6 -> 10
            7 -> 14
            else -> return false
        }

        if (offset >= packet.size) return false

        val unknownInfo = readVarInt(packet, offset)
        pdp.setUnknown(unknownInfo)
        offset += unknownInfo.length
        if (offset >= packet.size) return false

        val damageInfo = readVarInt(packet, offset)
        pdp.setDamage(damageInfo)
        offset += damageInfo.length
        if (offset >= packet.size) return false

        val loopInfo = readVarInt(packet, offset)
        pdp.setLoop(loopInfo)
        offset += loopInfo.length

        if (loopInfo.value != 0 && offset >= packet.size) return false

        if (loopInfo.value != 0) {
            for (i in 0 until loopInfo.length) {
                var skipValueInfo = readVarInt(packet, offset)
                pdp.addSkipData(skipValueInfo)
                offset += skipValueInfo.length
            }
        }

//        println(
//            "피격자: ${pdp.getTargetId()} 공격자: ${pdp.getActorId()} 스위치용변수: ${pdp.getSwitchVariable()} 스킬: ${pdp.getSkillCode1()} 스킬2: ${pdp.getSkillType()}" +
//                    " 플래그: ${pdp.getFlag()} 타입: ${pdp.getType()}" +
//                    " unknown : ${pdp.getUnknown()} 데미지: ${pdp.getDamage()} loop: ${pdp.getLoop()}"
//        )

        dataStorage.appendDamage(pdp)
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
                println("${this::class.java.simpleName} : [에러] 배열 범위 초과")
                return VarIntOutput(-1,-1)
            }

            val byteVal = bytes[offset + count].toInt()
            count++

            value = value or (byteVal and 0x7F shl shift)

            if ((byteVal and 0x80) == 0) {
                return VarIntOutput(value, count)
            }

            shift += 7
            if (shift >= 64) return VarIntOutput(-1,-1)
        }
    }
}
