package com.tbread.entity

import com.tbread.packet.StreamProcessor
import java.util.concurrent.atomic.AtomicLong

class ParsedDamagePacket {
        companion object {
                private val ID_GEN = AtomicLong(0)
        }

        private var actorId = 0
        private var targetId = 0
        private var damage = 0
        private var skillCode = 0
        private var type = 0
        private val timestamp = System.currentTimeMillis()
        private val id = ID_GEN.incrementAndGet()
        private var specials: List<SpecialDamage> = emptyList()
        private var dot = false
        private var multiHitCount = 0
        private var multiHitDamage = 0
        private var healAmount = 0
        private var hexPayload: String = ""

        fun setSpecials(specials: List<SpecialDamage>) {
                this.specials = specials
        }
        fun setActorId(actorInfo: StreamProcessor.VarIntOutput){
                this.actorId = actorInfo.value
        }
        fun setTargetId(targetInfo: StreamProcessor.VarIntOutput){
                this.targetId = targetInfo.value
        }
        fun setDamage(damageInfo: StreamProcessor.VarIntOutput){
                this.damage = damageInfo.value
        }
        fun setSkillCode(skillCode:Int){
                this.skillCode = skillCode
        }
        fun setType(typeInfo: StreamProcessor.VarIntOutput){
                this.type = typeInfo.value
        }
        fun setMultiHitCount(count: Int) {
                this.multiHitCount = count
        }
        fun setMultiHitDamage(damage: Int) {
                this.multiHitDamage = damage
        }
        fun setHealAmount(healAmount: Int) {
                this.healAmount = healAmount
        }
        fun setHexPayload(hexPayload: String) {
                this.hexPayload = hexPayload
        }

        fun getActorId(): Int {
                return this.actorId
        }
        fun getDamage():Int{
                return this.damage
        }
        fun getSkillCode1():Int{
                return this.skillCode
        }
        fun getTargetId():Int{
                return this.targetId
        }
        fun getType():Int{
                return this.type
        }
        fun getMultiHitCount(): Int {
                return this.multiHitCount
        }
        fun getMultiHitDamage(): Int {
                return this.multiHitDamage
        }
        fun getHealAmount(): Int {
                return this.healAmount
        }
        fun getHexPayload(): String {
                return this.hexPayload
        }
        fun getTimeStamp(): Long {
                return this.timestamp
        }
        fun getId(): Long {
                return this.id
        }
        fun getSpecials():List<SpecialDamage>{
                return this.specials
        }
        fun isCrit(): Boolean {
                return this.specials.contains(SpecialDamage.CRITICAL)
        }
        fun isDoT():Boolean{
                return dot
        }
        fun setDot(dot: Boolean) {
                this.dot = dot
        }
}