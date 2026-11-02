package com.android.messaging.twill

import android.provider.BaseColumns
import com.android.messaging.datamodel.DataModel
import com.android.messaging.datamodel.DatabaseHelper
import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns
import com.android.messaging.datamodel.DatabaseHelper.PartColumns
import com.android.messaging.datamodel.data.MessageData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Searches the text of one SMS/MMS conversation's messages, newest first. */
internal object SmsMessageSearch {
    private const val MAX_HITS = 500

    private val SQL = """
        SELECT m.${BaseColumns._ID},
            (SELECT COUNT(*) FROM ${DatabaseHelper.MESSAGES_TABLE} n
                WHERE n.${MessageColumns.CONVERSATION_ID} = m.${MessageColumns.CONVERSATION_ID}
                AND n.${MessageColumns.STATUS} <> ${MessageData.BUGLE_STATUS_OUTGOING_DRAFT}
                AND n.${MessageColumns.RECEIVED_TIMESTAMP} >= m.${MessageColumns.RECEIVED_TIMESTAMP})
        FROM ${DatabaseHelper.MESSAGES_TABLE} m
        WHERE m.${MessageColumns.CONVERSATION_ID} = ?
            AND m.${MessageColumns.STATUS} <> ${MessageData.BUGLE_STATUS_OUTGOING_DRAFT}
            AND EXISTS (SELECT 1 FROM ${DatabaseHelper.PARTS_TABLE} p
                WHERE p.${PartColumns.MESSAGE_ID} = m.${BaseColumns._ID}
                AND p.${PartColumns.TEXT} LIKE ? ESCAPE '\')
        ORDER BY m.${MessageColumns.RECEIVED_TIMESTAMP} DESC
        LIMIT $MAX_HITS
    """.trimIndent()

    suspend fun search(conversationId: String, query: String): List<TwillSearchHit> =
        withContext(Dispatchers.IO) {
            DataModel.get().database
                .rawQuery(SQL, arrayOf(conversationId, "%" + escapeLike(query) + "%"))
                .use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(TwillSearchHit(cursor.getString(0), cursor.getInt(1)))
                        }
                    }
                }
        }
}

/** An SMS/MMS conversation or message found by the inbox search. */
internal data class SmsSearchHit(
    val conversationId: String,
    val conversationName: String,
    /** Null for a conversation whose name matched. */
    val messageId: String?,
    val text: String,
    val timeMillis: Long,
    val isOutgoing: Boolean,
)

/** The inbox search over all SMS/MMS conversations: names first, then messages, newest first. */
internal object SmsGlobalSearch {
    private const val MAX_CONVERSATIONS = 20
    private const val MAX_MESSAGES = 100

    private val CONVERSATIONS_SQL = """
        SELECT ${BaseColumns._ID}, ${ConversationColumns.NAME}, ${ConversationColumns.SORT_TIMESTAMP}
        FROM ${DatabaseHelper.CONVERSATIONS_TABLE}
        WHERE ${ConversationColumns.NAME} LIKE ? ESCAPE '\'
        ORDER BY ${ConversationColumns.SORT_TIMESTAMP} DESC
        LIMIT $MAX_CONVERSATIONS
    """.trimIndent()

    private val MESSAGES_SQL = """
        SELECT m.${BaseColumns._ID}, m.${MessageColumns.CONVERSATION_ID}, c.${ConversationColumns.NAME},
            p.${PartColumns.TEXT}, m.${MessageColumns.RECEIVED_TIMESTAMP}, m.${MessageColumns.STATUS}
        FROM ${DatabaseHelper.MESSAGES_TABLE} m
        JOIN ${DatabaseHelper.PARTS_TABLE} p ON p.${PartColumns.MESSAGE_ID} = m.${BaseColumns._ID}
        JOIN ${DatabaseHelper.CONVERSATIONS_TABLE} c
            ON c.${BaseColumns._ID} = m.${MessageColumns.CONVERSATION_ID}
        WHERE m.${MessageColumns.STATUS} <> ${MessageData.BUGLE_STATUS_OUTGOING_DRAFT}
            AND p.${PartColumns.TEXT} LIKE ? ESCAPE '\'
        GROUP BY m.${BaseColumns._ID}
        ORDER BY m.${MessageColumns.RECEIVED_TIMESTAMP} DESC
        LIMIT $MAX_MESSAGES
    """.trimIndent()

    suspend fun search(query: String): List<SmsSearchHit> = withContext(Dispatchers.IO) {
        val pattern = "%" + escapeLike(query) + "%"
        val database = DataModel.get().database
        val conversations = database.rawQuery(CONVERSATIONS_SQL, arrayOf(pattern)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SmsSearchHit(
                            conversationId = cursor.getString(0),
                            conversationName = cursor.getString(1).orEmpty(),
                            messageId = null,
                            text = "",
                            timeMillis = cursor.getLong(2),
                            isOutgoing = false,
                        ),
                    )
                }
            }
        }
        val messages = database.rawQuery(MESSAGES_SQL, arrayOf(pattern)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SmsSearchHit(
                            conversationId = cursor.getString(1),
                            conversationName = cursor.getString(2).orEmpty(),
                            messageId = cursor.getString(0),
                            text = cursor.getString(3).orEmpty(),
                            timeMillis = cursor.getLong(4),
                            isOutgoing = cursor.getInt(5) < MessageData.BUGLE_STATUS_FIRST_INCOMING,
                        ),
                    )
                }
            }
        }
        conversations + messages
    }
}

/** Escapes LIKE wildcards so the query is matched literally (with ESCAPE '\'). */
internal fun escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
