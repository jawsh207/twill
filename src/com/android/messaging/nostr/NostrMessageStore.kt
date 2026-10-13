package com.android.messaging.nostr

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** An encrypted file sent with NIP-17 (kind 15). */
@Immutable
internal data class NostrAttachment(
    val url: String,
    val mimeType: String?,
    val keyHex: String,
    val nonceHex: String,
    val algorithm: String,
    val encryptedHash: String?,
    val size: Long?,
    val width: Int?,
    val height: Int?,
    val fileName: String?,
    /** Decrypted copy on this device, once downloaded. */
    val localPath: String?,
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true

    val isDownloaded: Boolean
        get() = localPath != null && File(localPath).exists()
}

@Immutable
internal data class NostrMessage(
    val id: String,
    val chatKey: String,
    val senderPubKey: String,
    val content: String,
    val createdAtSeconds: Long,
    val isOutgoing: Boolean,
    val isRead: Boolean,
    val status: Status,
    val attachment: NostrAttachment? = null,
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
    val chatKey: String,
    /** Profile name for one-to-one chats, or the group's name. */
    val displayName: String?,
    /** Names (or null) of each other member, in [NostrChatKey.members] order. */
    val memberNames: List<String?>,
    /** Profile picture URL of the other person (one-to-one chats only). */
    val pictureUrl: String?,
    val lastMessage: NostrMessage,
    val unreadCount: Int,
    val isPinned: Boolean,
) {
    val isGroup: Boolean
        get() = NostrChatKey.isGroup(chatKey)
}

@Immutable
internal data class NostrProfile(
    val pubKey: String,
    val name: String?,
    val picture: String?,
    val nip05: String?,
    val isNip05Verified: Boolean = false,
)

@Immutable
internal data class NostrGroupInfo(
    val chatKey: String,
    val name: String?,
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

    /** Per-chat counters, so an open chat only re-reads its messages when they change. */
    private val chatVersions = ConcurrentHashMap<String, MutableStateFlow<Long>>()

    private fun chatVersion(chatKey: String) =
        chatVersions.getOrPut(chatKey) { MutableStateFlow(0L) }

    fun conversations(): Flow<List<NostrConversation>> =
        changes.map { queryConversations() }.flowOn(Dispatchers.IO)

    /** The newest [limit] messages of [chatKey], oldest first. Re-read only when it changes. */
    fun messages(chatKey: String, limit: Int): Flow<List<NostrMessage>> =
        chatVersion(chatKey).map { queryMessages(chatKey, limit) }.flowOn(Dispatchers.IO)

    fun profile(pubKey: String): Flow<NostrProfile?> =
        changes.map { queryProfile(pubKey) }.flowOn(Dispatchers.IO)

    fun profiles(pubKeys: List<String>): Flow<Map<String, NostrProfile>> =
        changes.map { pubKeys.mapNotNull(::queryProfile).associateBy { it.pubKey } }
            .flowOn(Dispatchers.IO)

    fun group(chatKey: String): Flow<NostrGroupInfo> =
        changes.map { NostrGroupInfo(chatKey, queryGroupName(chatKey)) }.flowOn(Dispatchers.IO)

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
        if (rowId != -1L) notifyChanged(message.chatKey)
        return rowId != -1L
    }

    fun message(id: String): NostrMessage? {
        return helper.readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE id = ?",
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toMessage() else null }
    }

    fun updateStatus(id: String, status: NostrMessage.Status) {
        helper.writableDatabase.update(
            "messages",
            ContentValues().apply { put("status", status.code) },
            "id = ?",
            arrayOf(id),
        )
        notifyChanged(chatKeyOf(id))
    }

    fun setAttachmentLocalPath(id: String, path: String) {
        helper.writableDatabase.update(
            "messages",
            ContentValues().apply { put("local_path", path) },
            "id = ?",
            arrayOf(id),
        )
        notifyChanged(chatKeyOf(id))
    }

    fun deleteMessage(id: String) {
        val message = message(id)
        message?.attachment?.localPath?.let { File(it).delete() }
        helper.writableDatabase.delete("messages", "id = ?", arrayOf(id))
        notifyChanged(message?.chatKey)
    }

    fun markConversationRead(chatKey: String) {
        val updated = helper.writableDatabase.update(
            "messages",
            ContentValues().apply { put("is_read", 1) },
            "peer = ? AND is_read = 0",
            arrayOf(chatKey),
        )
        if (updated > 0) notifyChanged(chatKey)
    }

    fun setConversationRead(chatKey: String, isRead: Boolean) {
        if (isRead) {
            markConversationRead(chatKey)
            return
        }
        // "Mark as unread" flags the newest incoming message.
        helper.writableDatabase.execSQL(
            "UPDATE messages SET is_read = 0 WHERE id = (" +
                "SELECT id FROM messages WHERE peer = ? AND outgoing = 0 " +
                "ORDER BY created_at DESC LIMIT 1)",
            arrayOf(chatKey),
        )
        notifyChanged(chatKey)
    }

    fun setPinned(chatKey: String, isPinned: Boolean) {
        val db = helper.writableDatabase
        if (isPinned) {
            db.insertWithOnConflict(
                "pinned",
                null,
                ContentValues().apply { put("peer", chatKey) },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        } else {
            db.delete("pinned", "peer = ?", arrayOf(chatKey))
        }
        notifyChanged()
    }

    fun deleteConversation(chatKey: String) {
        val db = helper.writableDatabase
        db.rawQuery(
            "SELECT local_path FROM messages WHERE peer = ? AND local_path IS NOT NULL",
            arrayOf(chatKey),
        ).use { cursor -> while (cursor.moveToNext()) File(cursor.getString(0)).delete() }
        db.delete("messages", "peer = ?", arrayOf(chatKey))
        db.delete("pinned", "peer = ?", arrayOf(chatKey))
        db.delete("groups", "chat_key = ?", arrayOf(chatKey))
        notifyChanged(chatKey)
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
                put("nip05_verified", if (profile.isNip05Verified) 1 else 0)
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

    /**
     * Records a group name. Names arrive as NIP-17 `subject` tags; the newest one wins.
     * [isPendingSend] marks a name the user set here that still needs to go out with the
     * next message.
     */
    fun setGroupName(
        chatKey: String,
        name: String?,
        atSeconds: Long,
        isPendingSend: Boolean = false,
    ) {
        val db = helper.writableDatabase
        val currentAt = db.rawQuery(
            "SELECT name_at FROM groups WHERE chat_key = ?",
            arrayOf(chatKey),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        if (currentAt != null && currentAt > atSeconds && !isPendingSend) return

        db.insertWithOnConflict(
            "groups",
            null,
            ContentValues().apply {
                put("chat_key", chatKey)
                put("name", name?.takeIf { it.isNotBlank() })
                put("name_at", atSeconds)
                put("subject_pending", if (isPendingSend) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        notifyChanged()
    }

    /** A locally set group name that hasn't been sent yet ("" to clear it), or null. */
    fun pendingGroupSubject(chatKey: String): String? {
        helper.readableDatabase.rawQuery(
            "SELECT name FROM groups WHERE chat_key = ? AND subject_pending = 1",
            arrayOf(chatKey),
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) ?: "" else null }
    }

    fun clearPendingGroupSubject(chatKey: String) {
        helper.writableDatabase.update(
            "groups",
            ContentValues().apply { put("subject_pending", 0) },
            "chat_key = ?",
            arrayOf(chatKey),
        )
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
        db.rawQuery("SELECT local_path FROM messages WHERE local_path IS NOT NULL", null)
            .use { cursor -> while (cursor.moveToNext()) File(cursor.getString(0)).delete() }
        listOf("messages", "seen_wraps", "profiles", "pinned", "state", "groups").forEach { table ->
            db.delete(table, null, null)
        }
        notifyChanged()
        chatVersions.values.forEach { it.value += 1 }
    }

    /**
     * Tells readers something changed: the conversation list always, and [chatKey]'s messages
     * when given (null for changes that don't touch messages, like profiles or pins).
     */
    private fun notifyChanged(chatKey: String? = null) {
        changes.value = changes.value + 1
        chatKey?.let { chatVersions[it]?.let { version -> version.value += 1 } }
    }

    private fun chatKeyOf(messageId: String): String? =
        helper.readableDatabase.rawQuery(
            "SELECT peer FROM messages WHERE id = ?",
            arrayOf(messageId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun queryConversations(): List<NostrConversation> {
        val db = helper.readableDatabase
        val pinned = db.rawQuery("SELECT peer FROM pinned", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val profiles = db.rawQuery("SELECT pubkey, name, picture FROM profiles", null)
            .use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(cursor.getString(0), cursor.getString(1) to cursor.getString(2))
                    }
                }
            }
        val groupNames = db.rawQuery("SELECT chat_key, name FROM groups", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }

        val sql = """
            SELECT m.*,
                (SELECT COUNT(*) FROM messages u
                    WHERE u.peer = m.peer AND u.is_read = 0 AND u.outgoing = 0) AS unread
            FROM messages m
            WHERE m.created_at = (SELECT MAX(created_at) FROM messages x WHERE x.peer = m.peer)
            GROUP BY m.peer
            ORDER BY m.created_at DESC
        """.trimIndent()

        return db.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val message = cursor.toMessage()
                    val key = message.chatKey
                    val isGroup = NostrChatKey.isGroup(key)
                    add(
                        NostrConversation(
                            chatKey = key,
                            displayName = if (isGroup) groupNames[key] else profiles[key]?.first,
                            memberNames = NostrChatKey.members(key).map { profiles[it]?.first },
                            pictureUrl = if (isGroup) null else profiles[key]?.second,
                            lastMessage = message,
                            unreadCount = cursor.getInt(cursor.getColumnIndexOrThrow("unread")),
                            isPinned = key in pinned,
                        ),
                    )
                }
            }
        }
    }

    private fun queryMessages(chatKey: String, limit: Int): List<NostrMessage> {
        return helper.readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE peer = ? ORDER BY created_at DESC, rowid DESC LIMIT ?",
            arrayOf(chatKey, limit.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toMessage()) }
        }.asReversed()
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
                isNip05Verified = cursor.getIntOrNull("nip05_verified") == 1,
            )
        }
    }

    private fun queryGroupName(chatKey: String): String? {
        helper.readableDatabase.rawQuery(
            "SELECT name FROM groups WHERE chat_key = ?",
            arrayOf(chatKey),
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun NostrMessage.toValues() = ContentValues().apply {
        put("id", id)
        put("peer", chatKey)
        put("sender", senderPubKey)
        put("content", content)
        put("created_at", createdAtSeconds)
        put("outgoing", if (isOutgoing) 1 else 0)
        put("is_read", if (isRead) 1 else 0)
        put("status", status.code)
        val file = attachment
        if (file == null) {
            put("kind", KIND_TEXT)
        } else {
            put("kind", KIND_FILE)
            put("file_url", file.url)
            put("file_mime", file.mimeType)
            put("file_key", file.keyHex)
            put("file_nonce", file.nonceHex)
            put("file_algo", file.algorithm)
            put("file_hash", file.encryptedHash)
            put("file_size", file.size)
            put("file_width", file.width)
            put("file_height", file.height)
            put("file_name", file.fileName)
            put("local_path", file.localPath)
        }
    }

    private fun Cursor.toMessage(): NostrMessage {
        val isFile = getIntOrNull("kind") == KIND_FILE
        return NostrMessage(
            id = getString(getColumnIndexOrThrow("id")),
            chatKey = getString(getColumnIndexOrThrow("peer")),
            senderPubKey = getString(getColumnIndexOrThrow("sender")),
            content = getString(getColumnIndexOrThrow("content")),
            createdAtSeconds = getLong(getColumnIndexOrThrow("created_at")),
            isOutgoing = getInt(getColumnIndexOrThrow("outgoing")) == 1,
            isRead = getInt(getColumnIndexOrThrow("is_read")) == 1,
            status = NostrMessage.Status.from(getInt(getColumnIndexOrThrow("status"))),
            attachment = if (isFile) {
                NostrAttachment(
                    url = getStringOrNull("file_url").orEmpty(),
                    mimeType = getStringOrNull("file_mime"),
                    keyHex = getStringOrNull("file_key").orEmpty(),
                    nonceHex = getStringOrNull("file_nonce").orEmpty(),
                    algorithm = getStringOrNull("file_algo") ?: "aes-gcm",
                    encryptedHash = getStringOrNull("file_hash"),
                    size = getLongOrNull("file_size"),
                    width = getIntOrNull("file_width"),
                    height = getIntOrNull("file_height"),
                    fileName = getStringOrNull("file_name"),
                    localPath = getStringOrNull("local_path"),
                )
            } else {
                null
            },
        )
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.getIntOrNull(column: String): Int? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getInt(index)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
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
            upgradeToVersion2(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) upgradeToVersion2(db)
        }

        /** Version 2: attachments, group names and NIP-05 verification. */
        private fun upgradeToVersion2(db: SQLiteDatabase) {
            listOf(
                "kind INTEGER NOT NULL DEFAULT $KIND_TEXT",
                "file_url TEXT",
                "file_mime TEXT",
                "file_key TEXT",
                "file_nonce TEXT",
                "file_algo TEXT",
                "file_hash TEXT",
                "file_size INTEGER",
                "file_width INTEGER",
                "file_height INTEGER",
                "file_name TEXT",
                "local_path TEXT",
            ).forEach { column -> db.execSQL("ALTER TABLE messages ADD COLUMN $column") }
            db.execSQL("ALTER TABLE profiles ADD COLUMN nip05_verified INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE TABLE groups (chat_key TEXT PRIMARY KEY, name TEXT, " +
                    "name_at INTEGER NOT NULL DEFAULT 0, subject_pending INTEGER NOT NULL DEFAULT 0)",
            )
            // Profiles were cached without pictures or NIP-05 checks; refetch them.
            db.execSQL("UPDATE profiles SET updated_at = 0")
            // Version 1 skipped group and file messages; forget what was seen so they're fetched.
            db.execSQL("DELETE FROM seen_wraps")
            db.execSQL("DELETE FROM state WHERE key = 'last_sync'")
        }
    }

    private companion object {
        const val DATABASE_NAME = "nostr.db"
        const val DATABASE_VERSION = 2
        const val KIND_TEXT = 14
        const val KIND_FILE = 15
    }
}
