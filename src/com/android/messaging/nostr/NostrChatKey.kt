package com.android.messaging.nostr

/**
 * Identifies a Nostr conversation from the signed-in user's point of view.
 *
 * - One-to-one: the other person's hex public key (unchanged from before groups existed).
 * - Group (NIP-17): `g:` followed by the other members' hex keys, sorted and comma-separated.
 *   Each member excludes themselves, so everyone's key for the same group is stable for them.
 */
internal object NostrChatKey {
    private const val GROUP_PREFIX = "g:"

    fun isGroup(chatKey: String) = chatKey.startsWith(GROUP_PREFIX)

    /** Everyone in the chat except the signed-in user. */
    fun members(chatKey: String): List<String> = when {
        isGroup(chatKey) -> chatKey.removePrefix(GROUP_PREFIX).split(",").filter { it.isNotBlank() }
        else -> listOf(chatKey)
    }

    /**
     * The key for a chat whose participants (including or excluding [me]) are [participants].
     * Talking only to yourself gives your own key.
     */
    fun forParticipants(participants: Collection<String>, me: String): String {
        val others = participants.filter { it != me }.distinct().sorted()
        return when (others.size) {
            0 -> me
            1 -> others.single()
            else -> GROUP_PREFIX + others.joinToString(",")
        }
    }
}
