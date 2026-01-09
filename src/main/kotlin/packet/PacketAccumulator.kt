package com.tbread.packet

import java.io.ByteArrayOutputStream
import java.util.*

class PacketAccumulator {
    private val buffer = ByteArrayOutputStream()

    // 프로퍼티스로 옮길까? 우선도는 낮음
    private val MAX_BUFFER_SIZE = 2 * 1024 * 1024
    private val WARN_BUFFER_SIZE = 1024 * 1024

    @Synchronized
    fun append(data: ByteArray) {
        //뭔가 꼬였을때 한번 날려서 oom 회피하기, 추후 시간체크같은거 추가해서 용량조절이랑 발생 상황 체크 해주면 될듯?
        if (buffer.size() in (WARN_BUFFER_SIZE + 1)..<MAX_BUFFER_SIZE) {
            println("${this::class.java.simpleName} : [경고] $this 버퍼 용량 제한 임박")
        }
        if (buffer.size() > MAX_BUFFER_SIZE) {
            println("${this::class.java.simpleName} : [에러] $this 버퍼 용량 제한 초과로 강제 초기화 진행")
            buffer.reset()
        }
        buffer.write(data)
    }

    @Synchronized
    fun indexOf(target: ByteArray): Int {
        //매직패킷 탐색용
        val allBytes = buffer.toByteArray()
        if (allBytes.size < target.size) return -1

        for (i in 0..allBytes.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (allBytes[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    @Synchronized
    fun getRange(start: Int, endExclusive: Int): ByteArray {
        //완성패킷 찾았을때 복사용
        //todo: 패킷구조 좀 더확실하게 확인되면 바꿀수도? (페이로드안에 매직패킷처럼 생긴 데이터 있는경우 / 아직까진 사례 없음)
        val allBytes = buffer.toByteArray()
        if (start < 0 || endExclusive > allBytes.size || start > endExclusive) {
            return ByteArray(0)
        }
        return Arrays.copyOfRange(allBytes, start, endExclusive)
    }

    @Synchronized
    fun discardBytes(length: Int) {
        //완성패킷 찾았을때 제거용
        //gc 관련 문서좀 더 찾아서 어느정도의 효과인지 체크좀 해야할것같음
        val allBytes = buffer.toByteArray()
        buffer.reset()
        //이거 싹 비우고 재조립하는게 최선이 맞나? 나중에 확인 필요함 우선도는 낮음

        if (length < allBytes.size) {
            buffer.write(allBytes, length, allBytes.size - length)
        }
    }

}