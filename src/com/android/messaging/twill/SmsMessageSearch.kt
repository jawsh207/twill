package com.android.messaging.twill

import android.provider.BaseColumns
import com.android.messaging.datamodel.DataModel
import com.android.messaging.datamodel.DatabaseHelper
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

/** Escapes LIKE wildcards so the query is matched literally (with ESCAPE '\'). */
internal fun escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
