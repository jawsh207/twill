package com.android.messaging.nostr

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.Immutable
import com.android.messaging.twill.TwillMessageHit
import com.android.messaging.twill.TwillSearchHit
import com.android.messaging.twill.escapeLike
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
import kotlinx.coroutines.withContext

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

/** Someone the user might want to message, for the new-chat suggestions. */
@Immutable
internal data class NostrPerson(
    val pubKey: String,
    val npub: String,
    val profile: NostrProfile?,
    /** Where they were found: [NostrPeopleSource] bits. */
    val sources: Int,
    /** The profile's NIP-05 address hasn't been checked yet. */
    val isNip05Unchecked: Boolean,
)

/** Where a person in the directory was found (bits of `people.sources`). */
internal object NostrPeopleSource {
    /** Someone with a chat in Twill. */
    const val CHAT = 1

    /** On the user's follow list (kind 3), shared by every Nostr client. */
    const val FOLLOW = 2

    /** Older-style DMs (NIP-04, kind 4) sent or received in another client. */
    const val LEGACY_DM = 4

    /** Replied to, mentioned, reacted to or zapped the user, or the other way round. */
    const val INTERACTION = 8

    /** Follows the user. */
    const val FOLLOWER = 16

    /** Found by a search relay (only when relay search is on). */
    const val SEARCH = 32

    /** On the user's public mute list: never suggested. */
    const val MUTED = 64

    /** Sources that mean the user knows them, ranked above the rest. */
    const val CLOSE = CHAT or FOLLOW or LEGACY_DM
}

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

    /**
     * People whose name, NIP-05 address, npub or hex key contains [query], best matches first:
     * names and addresses starting with it, then people the user knows (chats, follows, DMs),
     * then the most interactions. Muted people are left out. Re-read when the store changes.
     */
    fun suggestPeople(query: String, limit: Int): Flow<List<NostrPerson>> =
        changes.map { queryPeople(query.trim(), limit) }.flowOn(Dispatchers.IO)

    /**
     * Adds [pubKeys] to the directory with the [source] bit, adding [counts] (interactions)
     * to their score. With [replaceSource], everyone else loses that bit (a new follow list).
     */
    fun addPeople(
        pubKeys: Collection<String>,
        source: Int,
        counts: Map<String, Int> = emptyMap(),
        replaceSource: Boolean = false,
    ) {
        val valid = pubKeys.filter { it.length == PUBKEY_HEX_LENGTH }.distinct()
        if (valid.isEmpty() && !replaceSource) return
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            if (replaceSource) {
                db.execSQL("UPDATE people SET sources = sources & ~$source")
            }
            valid.forEach { pubKey -> insertPerson(db, pubKey, source, counts[pubKey] ?: 0, now) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    private fun insertPerson(
        db: SQLiteDatabase,
        pubKey: String,
        source: Int,
        count: Int,
        now: Long,
    ) {
        val npub = runCatching { NostrSignerProvider.npubOf(pubKey) }.getOrNull() ?: return
        db.execSQL(
            "INSERT OR IGNORE INTO people (pubkey, npub, sources, score, seen_at) " +
                "VALUES (?, ?, 0, 0, ?)",
            arrayOf<Any>(pubKey, npub, now),
        )
        db.execSQL(
            "UPDATE people SET sources = sources | ?, score = score + ?, seen_at = ? " +
                "WHERE pubkey = ?",
            arrayOf<Any>(source, count, now, pubKey),
        )
    }

    /**
     * Directory people with no profile, or one older than [maxAgeMillis]: the people the user
     * knows first. At most [limit].
     */
    fun peopleNeedingProfiles(maxAgeMillis: Long, limit: Int): List<String> {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        helper.readableDatabase.rawQuery(
            "SELECT p.pubkey FROM people p LEFT JOIN profiles pr ON pr.pubkey = p.pubkey " +
                "WHERE (p.sources & ${NostrPeopleSource.MUTED}) = 0 " +
                "AND (pr.pubkey IS NULL OR pr.updated_at < ?) " +
                "ORDER BY (p.sources & ${NostrPeopleSource.CLOSE}) = 0, p.score DESC LIMIT ?",
            arrayOf(cutoff.toString(), limit.toString()),
        ).use { cursor ->
            return buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    /**
     * Saves profiles fetched in bulk. A NIP-05 address that changed is marked unchecked
     * (shown once [markNip05Checked] confirms it); an unchanged one keeps its earlier check.
     */
    fun upsertProfiles(profiles: Collection<NostrProfile>) {
        if (profiles.isEmpty()) return
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            profiles.forEach { profile ->
                val previous = db.rawQuery(
                    "SELECT nip05, nip05_verified, nip05_checked FROM profiles WHERE pubkey = ?",
                    arrayOf(profile.pubKey),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        Triple(cursor.getString(0), cursor.getInt(1) == 1, cursor.getInt(2) == 1)
                    } else {
                        null
                    }
                }
                val isSameAddress = previous != null &&
                    previous.first.equals(profile.nip05, ignoreCase = true)
                db.insertWithOnConflict(
                    "profiles",
                    null,
                    ContentValues().apply {
                        put("pubkey", profile.pubKey)
                        put("name", profile.name)
                        put("picture", profile.picture)
                        put("nip05", profile.nip05)
                        put("nip05_verified", if (isSameAddress && previous.second) 1 else 0)
                        put(
                            "nip05_checked",
                            if (profile.nip05 == null ||
                                (isSameAddress && previous.third)
                            ) {
                                1
                            } else {
                                0
                            },
                        )
                        put("updated_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    /**
     * Notes that [pubKeys] were looked for in bulk without finding a profile, so the
     * directory doesn't ask for them again for a few days. The time is set a day back, so
     * opening a chat with one of them still tries again (see NostrMessenger.refreshProfile).
     */
    fun markProfilesFetched(pubKeys: Collection<String>) {
        if (pubKeys.isEmpty()) return
        val db = helper.writableDatabase
        val at = System.currentTimeMillis() - BULK_MISS_AGE_MILLIS
        db.beginTransaction()
        try {
            pubKeys.forEach { pubKey ->
                db.execSQL(
                    "INSERT OR IGNORE INTO profiles (pubkey, updated_at, nip05_checked) " +
                        "VALUES (?, ?, 1)",
                    arrayOf<Any>(pubKey, at),
                )
                db.execSQL(
                    "UPDATE profiles SET updated_at = max(updated_at, ?) WHERE pubkey = ?",
                    arrayOf<Any>(at, pubKey),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Records the result of checking [pubKey]'s NIP-05 address. */
    fun markNip05Checked(pubKey: String, isVerified: Boolean) {
        helper.writableDatabase.update(
            "profiles",
            ContentValues().apply {
                put("nip05_verified", if (isVerified) 1 else 0)
                put("nip05_checked", 1)
            },
            "pubkey = ?",
            arrayOf(pubKey),
        )
        notifyChanged()
    }

    /** Everyone with a chat (one-to-one or group member), for seeding the directory. */
    fun chatPeople(): List<String> {
        helper.readableDatabase.rawQuery("SELECT DISTINCT peer FROM messages", null).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) addAll(NostrChatKey.members(cursor.getString(0)))
            }.distinct()
        }
    }

    private fun queryPeople(query: String, limit: Int): List<NostrPerson> {
        if (query.isEmpty()) return emptyList()
        val lowered = query.lowercase().removePrefix("nostr:")
        val contains = "%" + escapeLike(lowered) + "%"
        val prefix = escapeLike(lowered) + "%"
        helper.readableDatabase.rawQuery(
            "SELECT p.pubkey, p.npub, p.sources, pr.pubkey AS has_profile, pr.name, " +
                "pr.picture, pr.nip05, pr.nip05_verified, pr.nip05_checked " +
                "FROM people p LEFT JOIN profiles pr ON pr.pubkey = p.pubkey " +
                "WHERE (p.sources & ${NostrPeopleSource.MUTED}) = 0 AND (" +
                "lower(pr.name) LIKE ?1 ESCAPE '\\' OR lower(pr.nip05) LIKE ?1 ESCAPE '\\' " +
                "OR p.npub LIKE ?2 ESCAPE '\\' OR p.pubkey LIKE ?2 ESCAPE '\\') " +
                "ORDER BY " +
                "(lower(pr.name) LIKE ?2 ESCAPE '\\' " +
                "OR lower(pr.nip05) LIKE ?2 ESCAPE '\\') DESC, " +
                "(p.sources & ${NostrPeopleSource.CLOSE}) = 0, " +
                "(p.sources & ${NostrPeopleSource.SEARCH}) = p.sources, " +
                "p.score DESC, lower(pr.name) LIMIT ?3",
            arrayOf(contains, prefix, limit.toString()),
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) {
                    val pubKey = cursor.getString(0)
                    val hasProfile = cursor.getStringOrNull("has_profile") != null
                    val nip05 = cursor.getStringOrNull("nip05")
                    add(
                        NostrPerson(
                            pubKey = pubKey,
                            npub = cursor.getString(1),
                            sources = cursor.getInt(2),
                            profile = if (hasProfile) {
                                NostrProfile(
                                    pubKey = pubKey,
                                    name = cursor.getStringOrNull("name"),
                                    picture = cursor.getStringOrNull("picture"),
                                    nip05 = nip05,
                                    isNip05Verified = cursor.getIntOrNull("nip05_verified") == 1,
                                )
                            } else {
                                null
                            },
                            isNip05Unchecked = nip05 != null &&
                                cursor.getIntOrNull("nip05_checked") == 0,
                        ),
                    )
                }
            }
        }
    }

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
                put("nip05_checked", 1)
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
        listOf(
            "messages",
            "seen_wraps",
            "profiles",
            "pinned",
            "state",
            "groups",
            "people"
        ).forEach { table ->
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

    /**
     * Messages of [chatKey] whose text contains [query] (ignoring ASCII case), newest first, each
     * with how many of the chat's newest messages must be loaded to include it.
     */
    suspend fun search(chatKey: String, query: String): List<TwillSearchHit> =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery(
                """
                SELECT m.id,
                    (SELECT COUNT(*) FROM messages n WHERE n.peer = m.peer AND
                        (n.created_at > m.created_at OR
                            (n.created_at = m.created_at AND n.rowid >= m.rowid)))
                FROM messages m
                WHERE m.peer = ? AND m.content LIKE ? ESCAPE '\'
                ORDER BY m.created_at DESC, m.rowid DESC
                LIMIT $MAX_SEARCH_HITS
                """.trimIndent(),
                arrayOf(chatKey, "%" + escapeLike(query) + "%"),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(TwillSearchHit(cursor.getString(0), cursor.getInt(1)))
                    }
                }
            }
        }

    /** Messages in any chat whose text contains [query] (ignoring ASCII case), newest first. */
    suspend fun searchAll(query: String): List<TwillMessageHit> = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            """
            SELECT id, peer, content, created_at, outgoing FROM messages
            WHERE content LIKE ? ESCAPE '\'
            ORDER BY created_at DESC, rowid DESC
            LIMIT $MAX_GLOBAL_SEARCH_HITS
            """.trimIndent(),
            arrayOf("%" + escapeLike(query) + "%"),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TwillMessageHit(
                            chatId = cursor.getString(1),
                            messageId = cursor.getString(0),
                            text = cursor.getString(2),
                            timeMillis = cursor.getLong(3) * 1000,
                            isOutgoing = cursor.getInt(4) != 0,
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
            upgradeToVersion3(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) upgradeToVersion2(db)
            if (oldVersion < 3) upgradeToVersion3(db)
        }

        /**
         * Version 3: the people directory behind new-chat suggestions (filled from chats,
         * follows and other clients' activity; see NostrDirectory).
         */
        private fun upgradeToVersion3(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE people (pubkey TEXT PRIMARY KEY, npub TEXT NOT NULL, " +
                    "sources INTEGER NOT NULL DEFAULT 0, score INTEGER NOT NULL DEFAULT 0, " +
                    "seen_at INTEGER NOT NULL DEFAULT 0)",
            )
            // Profiles saved so far had their NIP-05 address checked when fetched.
            db.execSQL("ALTER TABLE profiles ADD COLUMN nip05_checked INTEGER NOT NULL DEFAULT 1")
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
        const val DATABASE_VERSION = 3
        const val PUBKEY_HEX_LENGTH = 64
        const val BULK_MISS_AGE_MILLIS = 24 * 60 * 60 * 1000L + 60_000L
        const val MAX_SEARCH_HITS = 500
        const val MAX_GLOBAL_SEARCH_HITS = 100
        const val KIND_TEXT = 14
        const val KIND_FILE = 15
    }
}
