package com.android.messaging.twill

import com.android.messaging.data.conversation.model.ConversationId

/**
 * Lets Nostr and Delta Chat messages use SMS's message schedule. The schedule keys messages by
 * SMS conversation id, so a Twill chat gets a key of its own, `twill:<protocol>:<chat>`, which
 * no SMS conversation id can look like (those are database row numbers). When one is due, the
 * scheduler hands it to that protocol instead of the SMS sender.
 */
internal object TwillScheduledTarget {
    private const val PREFIX = "twill:"

    /** The schedule's key for [chatId] in [protocol]. */
    fun conversationId(protocol: TwillProtocolId, chatId: String) =
        ConversationId("$PREFIX${protocol.key}:$chatId")

    /** The protocol and chat a schedule key stands for, or null for an SMS conversation. */
    fun parse(conversationId: ConversationId): Pair<TwillProtocolId, String>? {
        val value = conversationId.value
        if (!value.startsWith(PREFIX)) return null
        val rest = value.removePrefix(PREFIX)
        val protocol = TwillProtocolId.entries.firstOrNull { rest.startsWith(it.key + ":") }
            ?: return null
        return protocol to rest.removePrefix(protocol.key + ":")
    }
}
