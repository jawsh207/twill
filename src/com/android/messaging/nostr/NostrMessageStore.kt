package com.android.messaging.nostr

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@Immutable
internal data class NostrMessage(
    val id: String,
    val peerPubKey: String,
    val senderPubKey: String,
    val content: String,
    val createdAtSeconds: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean,
    val status: Status,
) {
    enum class Status(
        val code: Int,
    ) {
        Sent(0),
        Sending(1),
        Failed(2),
        ;

        companion object {
            fun from(code: Int) = entries.firstOrNull { it.code == code } ?: Sent
        }
    }
}

@Immutable
internal data class NostrConversation(
    val peerPubKey: String,
    val displayName: String?,
    val lastMessage: NostrMessage,
    val unreadCount: Int,
    val isPinned: Boolean,
)

@Immutable
internal data class NostrProfile(
    val pubKey: String,
    val name: String?,
    val picture: String?,
    val nip05: String?,
)

/**
 * Nostr DMs, profiles and sync state in Twill's own database (Nostr messages can't live in
 * Android's SMS provider). Reads are cheap queries re-run whenever [changes] ticks.
 */
@Singleton
internal class NostrMessageStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val helper = Helper(context)
    private val changes = MutableStateFlow(0L)

    fun conversations(): Flow<List<NostrConversation>> =
        changes.map { queryConversations() }.flowOn(Dispatchers.IO)

    fun messages(peerPubKey: String): Flow<List<NostrMessage>> =
        changes.map { queryMessages(peerPubKey) }.flowOn(Dispatchers.IO)

    fun profile(pubKey: String): Flow<NostrProfile?> =
        changes.map { queryProfile(pubKey) }.flowOn(Dispatchers.IO)

    fun hasSeenWrap(wrapId: String): Boolean {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM seen_wraps WHERE id = ? LIMIT 1",
            arrayOf(wrapId),
        ).use { return it.moveToFirst() }
    }

    fun markWrapSeen(wrapId: String) {
        helper.writableDatabase.insertWithOnConflict(
            "seen_wraps",
            null,
            ContentValues().apply { put("id", wrapId) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** Inserts a message unless one with the same id already exists. Returns true if added. */
    fun insertMessage(message: NostrMessage): Boolean {
        val rowId = helper.writableDatabase.insertWithOnConflict(
            "messages",
            null,
            message.toValues(),
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (rowId != -1L) notifyChanged()
        return rowId != -1L
    }

    fun updateStatus(id: String, status: NostrMessage.Status) {
        helper.writableDatabase.update(
            "messages",
            ContentValues().apply { put("status", status.code) },
            "id = ?",
            arrayOf(id),
        )
        notifyChanged()
    }

    fun deleteMessage(id: String) {
        helper.writableDatabase.delete("messages", "id = ?", arrayOf(id))
        notifyChanged()
    }

    fun markConversationRead(peerPubKey: String) {
        val updated = helper.writableDatabase.update(
            "messages",
            ContentValues().apply { put("is_read", 1) },
            "peer = ? AND is_read = 0",
            arrayOf(peerPubKey),
        )
        if (updated > 0) notifyChanged()
    }

    fun setConversationRead(peerPubKey: String, isRead: Boolean) {
        val db = helper.writableDatabase
        if (isRead) {
            markConversationRead(peerPubKey)
            return
        }
        // "Mark as unread" flags the newest incoming message.
        db.execSQL(
            "UPDATE messages SET is_read = 0 WHERE id = (" +
                "SELECT id FROM messages WHERE peer = ? AND outgoing = 0 " +
                "ORDER BY created_at DESC LIMIT 1)",
            arrayOf(peerPubKey),
        )
        notifyChanged()
    }

    fun setPinned(peerPubKey: String, isPinned: Boolean) {
        val db = helper.writableDatabase
        if (isPinned) {
            db.insertWithOnConflict(
                "pinned",
                null,
                ContentValues().apply { put("peer", peerPubKey) },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        } else {
            db.delete("pinned", "peer = ?", arrayOf(peerPubKey))
        }
        notifyChanged()
    }

    fun deleteConversation(peerPubKey: String) {
        val db = helper.writableDatabase
        db.delete("messages", "peer = ?", arrayOf(peerPubKey))
        db.delete("pinned", "peer = ?", arrayOf(peerPubKey))
        notifyChanged()
    }

    fun upsertProfile(profile: NostrProfile) {
        helper.writableDatabase.insertWithOnConflict(
            "profiles",
            null,
            ContentValues().apply {
                put("pubkey", profile.pubKey)
                put("name", profile.name)
                put("picture", profile.picture)
                put("nip05", profile.nip05)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        notifyChanged()
    }

    fun profileUpdatedAt(pubKey: String): Long? {
        helper.readableDatabase.rawQuery(
            "SELECT updated_at FROM profiles WHERE pubkey = ?",
            arrayOf(pubKey),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    /** Newest gift wrap time seen, used as the `since` for the next subscription. */
    fun lastSyncSeconds(): Long? = readState("last_sync")?.toLongOrNull()

    fun setLastSyncSeconds(value: Long) {
        val current = lastSyncSeconds() ?: 0L
        if (value > current) writeState("last_sync", value.toString())
    }

    fun readState(key: String): String? {
        helper.readableDatabase.rawQuery(
            "SELECT value FROM state WHERE key = ?",
            arrayOf(key),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun writeState(key: String, value: String) {
        helper.writableDatabase.insertWithOnConflict(
            "state",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Wipes everything; used when the user signs out. */
    fun clearAll() {
        val db = helper.writableDatabase
        listOf("messages", "seen_wraps", "profiles", "pinned", "state").forEach { table ->
            db.delete(table, null, null)
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        changes.value = changes.value + 1
    }

    private fun queryConversations(): List<NostrConversation> {
        val db = helper.readableDatabase
        val pinned = db.rawQuery("SELECT peer FROM pinned", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

        val sql = """
            SELECT m.*, p.name AS profile_name,
                (SELECT COUNT(*) FROM messages u
                    WHERE u.peer = m.peer AND u.is_read = 0 AND u.outgoing = 0) AS unread
            FROM messages m
            LEFT JOIN profiles p ON p.pubkey = m.peer
            WHERE m.created_at = (SELECT MAX(created_at) FROM messages x WHERE x.peer = m.peer)
            GROUP BY m.peer
            ORDER BY m.created_at DESC
        """.trimIndent()

        return db.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val message = cursor.toMessage()
                    add(
                        NostrConversation(
                            peerPubKey = message.peerPubKey,
                            displayName = cursor.getStringOrNull("profile_name"),
                            lastMessage = message,
                            unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow("unread")),
                            isPinned = message.peerPubKey in pinned,
                        ),
                    )
                }
            }
        }
    }

    private fun queryMessages(peerPubKey: String): List<NostrMessage> {
        return helper.readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE peer = ? ORDER BY created_at ASC, rowid ASC",
            arrayOf(peerPubKey),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toMessage()) }
        }
    }

    private fun queryProfile(pubKey: String): NostrProfile? {
        return helper.readableDatabase.rawQuery(
            "SELECT * FROM profiles WHERE pubkey = ?",
            arrayOf(pubKey),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            NostrProfile(
                pubKey = pubKey,
                name = cursor.getStringOrNull("name"),
                picture = cursor.getStringOrNull("picture"),
                nip05 = cursor.getStringOrNull("nip05"),
            )
        }
    }

    private fun NostrMessage.toValues() = ContentValues().apply {
        put("id", id)
        put("peer", peerPubKey)
        put("sender", senderPubKey)
        put("content", content)
        put("created_at", createdAtSeconds)
        put("outgoing", if (isOutgoing) 1 else 0)
        put("is_read", if (isRead) 1 else 0)
        put("status", status.code)
    }

    private fun android.database.Cursor.toMessage() = NostrMessage(
        id = getString(getColumnIndexOrThrow("id")),
        peerPubKey = getString(getColumnIndexOrThrow("peer")),
        senderPubKey = getString(getColumnIndexOrThrow("sender")),
        content = getString(getColumnIndexOrThrow("content")),
        createdAtSeconds = getLong(getColumnIndexOrThrow("created_at")),
        isOutgoing = getInt(getColumnIndexOrThrow("outgoing")) == 1,
        isRead = getInt(getColumnIndexOrThrow("is_read")) == 1,
        status = NostrMessage.Status.from(getInt(getColumnIndexOrThrow("status"))),
    )

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private class Helper(
        context: Context,
    ) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE messages (" +
                    "id TEXT PRIMARY KEY, peer TEXT NOT NULL, sender TEXT NOT NULL, " +
                    "content TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                    "outgoing INTEGER NOT NULL, is_read INTEGER NOT NULL, status INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX messages_peer ON messages(peer, created_at)")
            db.execSQL("CREATE TABLE seen_wraps (id TEXT PRIMARY KEY)")
            db.execSQL(
                "CREATE TABLE profiles (pubkey TEXT PRIMARY KEY, name TEXT, picture TEXT, " +
                    "nip05 TEXT, updated_at INTEGER)",
            )
            db.execSQL("CREATE TABLE pinned (peer TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val DATABASE_NAME = "nostr.db"
        const val DATABASE_VERSION = 1
    }
}
