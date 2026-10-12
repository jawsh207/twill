package com.android.messaging.data.scheduled

import android.content.Context
import android.content.SharedPreferences
import com.android.messaging.data.conversation.model.ConversationId
import com.android.messaging.data.conversation.model.ParticipantId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A text message the user asked to send at [sendAtMillis]. */
internal data class ScheduledMessage(
    val id: String,
    val conversationId: ConversationId,
    val selfParticipantId: ParticipantId?,
    val messageText: String,
    val subjectText: String,
    val sendAtMillis: Long,
)

/**
 * Persists scheduled messages in a private SharedPreferences file as a JSON array. The volume is
 * tiny (a handful of pending messages), so this avoids a schema migration on the main database.
 */
@Singleton
internal class ScheduledMessageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val lock = Any()
    private val _messages = MutableStateFlow<List<ScheduledMessage>>(emptyList())
    private var isLoaded = false

    /** All pending scheduled messages, soonest first. */
    val messages: StateFlow<List<ScheduledMessage>>
        get() {
            ensureLoaded()
            return _messages.asStateFlow()
        }

    fun all(): List<ScheduledMessage> {
        ensureLoaded()
        return _messages.value
    }

    fun add(
        conversationId: ConversationId,
        selfParticipantId: ParticipantId?,
        messageText: String,
        subjectText: String,
        sendAtMillis: Long,
    ): ScheduledMessage {
        val message = ScheduledMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            selfParticipantId = selfParticipantId,
            messageText = messageText,
            subjectText = subjectText,
            sendAtMillis = sendAtMillis,
        )
        update { current -> current + message }
        return message
    }

    fun remove(id: String): ScheduledMessage? {
        var removed: ScheduledMessage? = null
        update { current ->
            removed = current.firstOrNull { it.id == id }
            current.filterNot { it.id == id }
        }
        return removed
    }

    private fun ensureLoaded() {
        synchronized(lock) {
            if (isLoaded) return
            _messages.value = read()
            isLoaded = true
        }
    }

    private fun update(transform: (List<ScheduledMessage>) -> List<ScheduledMessage>) {
        synchronized(lock) {
            if (!isLoaded) {
                _messages.value = read()
                isLoaded = true
            }
            val updated = transform(_messages.value).sortedBy { it.sendAtMillis }
            write(updated)
            _messages.value = updated
        }
    }

    private fun read(): List<ScheduledMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { index -> array.optJSONObject(index)?.toScheduledMessage() }
                .sortedBy { it.sendAtMillis }
        } catch (exception: JSONException) {
            emptyList()
        }
    }

    private fun write(messages: List<ScheduledMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put(FIELD_ID, message.id)
                    .put(FIELD_CONVERSATION_ID, message.conversationId.value)
                    .put(FIELD_SELF_PARTICIPANT_ID, message.selfParticipantId?.value ?: "")
                    .put(FIELD_TEXT, message.messageText)
                    .put(FIELD_SUBJECT, message.subjectText)
                    .put(FIELD_SEND_AT, message.sendAtMillis),
            )
        }
        // commit() so a message is never lost if the process dies right after scheduling.
        prefs.edit().putString(KEY_MESSAGES, array.toString()).commit()
    }

    private fun JSONObject.toScheduledMessage(): ScheduledMessage? {
        val conversationId = ConversationId.fromOrNull(optString(FIELD_CONVERSATION_ID))
            ?: return null
        val id = optString(FIELD_ID).takeIf { it.isNotBlank() } ?: return null

        return ScheduledMessage(
            id = id,
            conversationId = conversationId,
            selfParticipantId = ParticipantId.fromOrNull(optString(FIELD_SELF_PARTICIPANT_ID)),
            messageText = optString(FIELD_TEXT),
            subjectText = optString(FIELD_SUBJECT),
            sendAtMillis = optLong(FIELD_SEND_AT),
        )
    }

    private companion object {
        const val PREFS_NAME = "scheduled_messages"
        const val KEY_MESSAGES = "messages"
        const val FIELD_ID = "id"
        const val FIELD_CONVERSATION_ID = "conversation_id"
        const val FIELD_SELF_PARTICIPANT_ID = "self_participant_id"
        const val FIELD_TEXT = "text"
        const val FIELD_SUBJECT = "subject"
        const val FIELD_SEND_AT = "send_at"
    }
}
