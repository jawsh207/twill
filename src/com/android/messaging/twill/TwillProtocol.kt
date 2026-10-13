package com.android.messaging.twill

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The messaging protocols Twill speaks besides SMS/MMS (which stays upstream's code).
 * [key] is stored in navigation state and preferences, so never change it.
 */
internal enum class TwillProtocolId(
    val key: String,
) {
    Nostr("nostr"),
    DeltaChat("deltachat"),
    ;

    companion object {
        fun from(key: String): TwillProtocolId? = entries.firstOrNull { it.key == key }
    }
}

/** How a protocol receives messages while Twill isn't on screen. */
internal enum class TwillDeliveryMode(
    val key: String,
) {
    /** Only while Twill is open. No background network use. */
    WhileOpen("while_open"),

    /** A short check about every 15 minutes. */
    Periodic("periodic"),

    /** Stay connected, with one persistent notification for all protocols. */
    AlwaysConnected("always"),
    ;

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: WhileOpen
    }
}

/** What to draw for a person or chat: a picture if [ref] loads, else [name]'s first letter. */
@Immutable
internal data class TwillAvatar(
    val name: String,
    /** Protocol-specific picture reference, loaded with [TwillProtocol.loadAvatar]. */
    val ref: String? = null,
    val isGroup: Boolean = false,
)

@Immutable
internal data class TwillAttachment(
    val mimeType: String?,
    val fileName: String?,
    val size: Long?,
    val width: Int?,
    val height: Int?,
    /** The file on this device, once downloaded. */
    val localPath: String?,
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true

    val isDownloaded: Boolean
        get() = localPath != null && File(localPath).exists()
}

@Immutable
internal data class TwillMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val text: String,
    val timeMillis: Long,
    val isOutgoing: Boolean,
    val status: Status,
    val attachment: TwillAttachment? = null,
    /** Centered notices such as "Alice joined the group". */
    val isInfo: Boolean = false,
) {
    enum class Status { Sending, Sent, Delivered, Read, Failed }
}

@Immutable
internal data class TwillChat(
    val protocol: TwillProtocolId,
    val chatId: String,
    val title: String,
    val avatar: TwillAvatar,
    val isGroup: Boolean,
    val isPinned: Boolean,
    val unreadCount: Int,
    val lastMessage: TwillMessage?,
    /** Shown before the preview in groups ("Alice: "); null for one-to-one chats. */
    val lastSenderName: String? = null,
    /** Someone new wrote; the user hasn't accepted the chat yet. */
    val isRequest: Boolean = false,
)

@Immutable
internal data class TwillMember(
    val id: String,
    val name: String,
    /** Address, key or similar, shown under the name. */
    val detail: String?,
    /** [detail] is a checked address (NIP-05 or a verified key). */
    val isVerified: Boolean = false,
    val avatar: TwillAvatar = TwillAvatar(name),
)

@Immutable
internal data class TwillChatDetails(
    val chatId: String,
    val title: String,
    val subtitle: String,
    val isSubtitleVerified: Boolean,
    val avatar: TwillAvatar,
    val isGroup: Boolean,
    val canRename: Boolean,
    val canSend: Boolean,
    val isRequest: Boolean,
    /** Everyone else in the chat, by [TwillMember.id], for sender names and the info sheet. */
    val members: Map<String, TwillMember>,
)

/** Problems worth a toast. */
internal enum class TwillError { UploadFailed, FileTooLarge, DownloadFailed, SendFailed }

/** Strings and options that differ between protocols in shared screens. */
@Immutable
internal data class TwillProtocolText(
    @StringRes val sectionTitle: Int,
    @StringRes val newChatTitle: Int,
    @StringRes val newChatHint: Int,
    @StringRes val newChatInvalid: Int,
    @StringRes val groupMembersHint: Int,
    @StringRes val groupMembersHelp: Int,
    @StringRes val emptySection: Int,
    @StringRes val composerSignedOut: Int,
    @StringRes val renameHelp: Int?,
    @DrawableRes val notificationIcon: Int,
    /** Whether group members can be typed in (Nostr keys), besides picking known contacts. */
    val canTypeGroupMembers: Boolean,
)

/** Result of creating a group: the new chat, or the typed entries that weren't recognised. */
internal sealed interface TwillGroupResult {
    data class Created(
        val chatId: String,
    ) : TwillGroupResult

    data class Invalid(
        val entries: List<String>,
    ) : TwillGroupResult
}

/**
 * A messaging protocol shown in Twill's inbox. The inbox, chat screen, notifications and
 * background delivery are shared; each protocol only says how to read and send messages.
 * Chat and message ids are protocol-specific strings.
 */
internal interface TwillProtocol {
    val id: TwillProtocolId
    val text: TwillProtocolText

    /** Signed in / set up. A disabled protocol has no inbox section and never connects. */
    val isEnabled: StateFlow<Boolean>

    /** How it receives messages in the background, as chosen in its settings. */
    val deliveryMode: StateFlow<TwillDeliveryMode>

    val chats: Flow<List<TwillChat>>

    fun messages(chatId: String): Flow<List<TwillMessage>>

    fun details(chatId: String): Flow<TwillChatDetails>

    /** Message ids whose attachments are downloading. */
    val downloading: StateFlow<Set<String>>

    val errors: SharedFlow<TwillError>

    /** Starts whatever the protocol needs while signed in. Safe to call repeatedly. */
    fun install()

    /** Called when a chat screen opens, e.g. to refresh profiles. */
    fun onChatOpened(chatId: String) = Unit

    fun send(chatId: String, text: String)

    fun sendFile(chatId: String, uri: Uri)

    fun retry(message: TwillMessage)

    fun download(message: TwillMessage)

    fun markRead(chatId: String)

    fun setRead(chatId: String, isRead: Boolean)

    fun setPinned(chatId: String, isPinned: Boolean)

    fun delete(chatId: String)

    fun rename(chatId: String, name: String)

    fun acceptRequest(chatId: String) = Unit

    fun blockRequest(chatId: String) = Unit

    /** A chat id for what the user typed (address, key, invite link), or null if invalid. */
    suspend fun startChat(input: String): String?

    /** People the user can add to a group without typing anything. */
    suspend fun knownContacts(): List<TwillMember>

    suspend fun createGroup(
        name: String,
        contactIds: List<String>,
        typedEntries: List<String>,
    ): TwillGroupResult

    suspend fun loadAvatar(ref: String): Bitmap?

    /** Collects anything new, for the periodic background check. */
    suspend fun syncOnce()

    /** Keep receiving while Twill is in the background (the shared foreground service). */
    fun setKeepConnectedInBackground(enabled: Boolean)
}
