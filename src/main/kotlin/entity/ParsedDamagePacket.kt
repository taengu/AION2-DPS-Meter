package com.tbread.entity

import com.tbread.packet.StreamProcessor
import java.util.UUID

class ParsedDamagePacket {
        private var actorId = 0
        private var targetId = 0
        private var flag = 0
        private var damage = 0
        private var skillCode = 0
        private var type = 0
        private var unknown = 0
        private var switchVariable = 0
        private var loop = 0
        private var skipValues = mutableListOf<Int>()
        private val timestamp = System.currentTimeMillis()
        private val id = UUID.randomUUID()
        private var specials:List<SpecialDamage> = arrayListOf()
        private var dot = false
        private var payload: ByteArray? = null

        fun setSpecials(specials: List<SpecialDamage>) {
                this.specials = specials
        }
        fun setActorId(actorInfo: StreamProcessor.VarIntOutput){
                this.actorId = actorInfo.value
        }
        fun setTargetId(targetInfo: StreamProcessor.VarIntOutput){
                this.targetId = targetInfo.value
        }
        fun setFlag(flagInfo: StreamProcessor.VarIntOutput){
                this.flag = flagInfo.value
        }
        fun setDamage(damageInfo: StreamProcessor.VarIntOutput){
                this.damage = damageInfo.value
        }
        fun setSkillCode(skillCode:Int){
                this.skillCode = skillCode
        }
        fun setUnknown(unknownInfo: StreamProcessor.VarIntOutput){
                this.unknown = unknownInfo.value
        }
        fun setSwitchVariable(switchVariableInfo: StreamProcessor.VarIntOutput){
                this.switchVariable = switchVariableInfo.value
        }
        fun setLoop(loopInfo: StreamProcessor.VarIntOutput){
                this.loop = loopInfo.value
        }
        fun addSkipData(skipValueInfo: StreamProcessor.VarIntOutput){
                this.skipValues.add(skipValueInfo.value)
        }
        fun setType(typeInfo: StreamProcessor.VarIntOutput){
                this.type = typeInfo.value
        }

        fun getActorId(): Int {
                return this.actorId
        }

        fun getDamage():Int{
                return this.damage
        }

        fun getFlag():Int{
                return this.flag
        }

        fun getSkillCode1():Int{
                return this.skillCode
        }


        fun getTargetId():Int{
                return this.targetId
        }

        fun getUnknown():Int{
                return this.unknown
        }
        fun getSwitchVariable():Int{
                return this.switchVariable
        }
        fun getLoop():Int{
                return this.loop
        }
        fun getType():Int{
                return this.type
        }
        fun getTimeStamp(): Long {
                return this.timestamp
        }
        fun getUuid():UUID{
                return this.id
        }
        fun getSpecials():List<SpecialDamage>{
                return this.specials
        }

        fun isCrit():Boolean{
                return this.type == 3
        }
        fun isDoT():Boolean{
                return dot
        }
        fun setDot(dot: Boolean) {
                this.dot = dot
        }

        fun setPayload(payload: ByteArray) {
                this.payload = payload.copyOf()
        }

        fun getHexPayload(): String {
                val bytes = payload ?: return ""
                return bytes.joinToString(" ") { "%02X".format(it) }
        }



}
