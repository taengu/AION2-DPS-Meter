package com.tbread.packet

/**
 * Holds information about the local player (you).
 * The playerId will be learned automatically from packets.
 */
object LocalPlayer {

    /**
     * Your in-game character name.
     * MUST match exactly (case-sensitive).
     */
    @Volatile
    var characterName: String? = null

    /**
     * Unique actor / player ID extracted from packets.
     */
    @Volatile
    var playerId: Long? = null

    /**
     * User-provided actor ID for debugging/association purposes.
     */
    @Volatile
    var knownActorId: Int? = null

    /**
     * User-provided nickname for debugging/association purposes.
     */
    @Volatile
    var knownNickname: String? = null

    data class KnownIdentity(val actorId: Int?, val nickname: String?)

    fun loadKnownIdentity(): KnownIdentity {
        val actorId = PropertyHandler.getProperty("player.knownActorId")?.toIntOrNull()
        val nickname = PropertyHandler.getProperty("player.knownNickname")?.trim()?.takeIf { it.isNotEmpty() }

        if (actorId != null) {
            knownActorId = actorId
        }
        if (nickname != null) {
            knownNickname = nickname
        }

        return KnownIdentity(actorId, nickname)
    }
}
