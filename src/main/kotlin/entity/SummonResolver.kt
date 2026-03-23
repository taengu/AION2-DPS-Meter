package com.tbread.entity

/**
 * Resolves the ultimate owner of a summon chain by walking parent links.
 * Shared by DpsCalculator and BossEncounterLogger to avoid duplication.
 */
object SummonResolver {
    fun resolve(actorId: Int, summonData: Map<Int, Int>): Int {
        if (actorId <= 0) return actorId
        var resolved = actorId
        val visited = mutableSetOf<Int>()
        var hops = 0
        while (hops < 16 && visited.add(resolved)) {
            val parent = summonData[resolved] ?: break
            if (parent <= 0) break
            resolved = parent
            hops++
        }
        return resolved
    }
}
