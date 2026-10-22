package com.android.messaging.widget

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.provider.ContactsContract
import com.android.messaging.R
import com.android.messaging.datamodel.MessagingContentProvider
import com.android.messaging.datamodel.data.ConversationListData
import com.android.messaging.datamodel.data.ConversationListItemData
import com.android.messaging.datamodel.data.ConversationMessageData
import com.android.messaging.twill.TwillChat
import com.android.messaging.twill.TwillMessage
import com.android.messaging.twill.TwillNotifications
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.TwillProtocols
import com.android.messaging.twill.TwillSms
import com.android.messaging.ui.UIIntents
import com.android.messaging.util.ContentType
import com.android.messaging.util.OsUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A conversation shown by a widget: an SMS/MMS conversation (upstream's id) or a chat of one
 * of Twill's protocols. [encode] is what the "Conversation" widget stores for its chat; plain
 * SMS ids stay as they were, so widgets placed before Twill's rebuild keep their conversation.
 */
internal sealed interface WidgetChatKey {
    data class Sms(
        val conversationId: String,
    ) : WidgetChatKey

    data class Twill(
        val protocol: TwillProtocolId,
        val chatId: String,
    ) : WidgetChatKey

    fun encode(): String = when (this) {
        is Sms -> conversationId
        is Twill -> TWILL_PREFIX + protocol.key + ":" + chatId
    }

    /** Extras that make MainActivity open this conversation. */
    fun fillInIntent(): Intent = when (this) {
        is Sms -> Intent().putExtra(UIIntents.UI_INTENT_EXTRA_CONVERSATION_ID, conversationId)

        is Twill -> Intent()
            .putExtra(TwillNotifications.EXTRA_PROTOCOL, protocol.key)
            .putExtra(TwillNotifications.EXTRA_CHAT_ID, chatId)
    }

    companion object {
        private const val TWILL_PREFIX = "twill:"

        fun decode(value: String?): WidgetChatKey? {
            if (value.isNullOrEmpty()) return null
            if (!value.startsWith(TWILL_PREFIX)) return Sms(value)
            val rest = value.removePrefix(TWILL_PREFIX)
            val separator = rest.indexOf(':')
            if (separator <= 0) return null
            val protocol = TwillProtocolId.from(rest.substring(0, separator)) ?: return null
            return Twill(protocol, rest.substring(separator + 1))
        }
    }
}

/** The letter an avatar without a picture shows, or null for the person or group glyph. */
internal fun avatarLetter(name: String, isGroup: Boolean): String? =
    name.firstOrNull()?.takeIf { !isGroup && it.isLetter() }?.uppercase()

internal data class WidgetChat(
    val key: WidgetChatKey,
    val title: String,
    val snippet: String,
    val timeMillis: Long,
    val isUnread: Boolean,
    val isGroup: Boolean,
    val iconRes: Int,
    /** Loads the picture, only for the rows a widget actually draws. */
    val loadPhoto: suspend () -> Bitmap?,
)

internal data class WidgetMessage(
    val id: String,
    val text: String,
    val timeMillis: Long,
    val isOutgoing: Boolean,
    /** Shown above others' messages in groups. */
    val sender: String?,
)

/**
 * Reads what the widgets show: SMS/MMS from upstream's content provider (when SMS is on and
 * permitted) and every other protocol through [TwillProtocols]. Nothing here connects to a
 * network; protocols only read what they already have.
 */
internal class TwillWidgetData(
    private val context: Context,
    private val protocols: TwillProtocols,
) {
    /** SMS is on but Twill can't read it yet (onboarding isn't finished). */
    val smsNeedsSetup: Boolean
        get() = TwillSms.isEnabled(context) && !OsUtil.hasRequiredPermissions()

    private val isSmsReadable: Boolean
        get() = TwillSms.isEnabled(context) && OsUtil.hasRequiredPermissions()

    /** Every conversation, newest first. */
    suspend fun chats(limit: Int = MAX_CHATS): List<WidgetChat> {
        val sms = if (isSmsReadable) smsChats() else emptyList()
        val others = protocols.all.flatMap { protocol ->
            if (!protocol.isEnabled.value) return@flatMap emptyList()
            val chats = withTimeoutOrNull(PROTOCOL_TIMEOUT_MILLIS) { protocol.chats.first() }
            chats.orEmpty().filter { !it.isRequest }.map { it.toWidgetChat() }
        }
        return (sms + others).sortedByDescending { it.timeMillis }.take(limit)
    }

    suspend fun chat(key: WidgetChatKey): WidgetChat? = when (key) {
        is WidgetChatKey.Sms -> if (isSmsReadable) {
            smsChats(
                selection = ConversationListItemData.ConversationListViewColumns._ID + " = ?",
                args = arrayOf(key.conversationId),
            ).firstOrNull()
        } else {
            null
        }

        is WidgetChatKey.Twill -> {
            val protocol = protocols[key.protocol]
            if (!protocol.isEnabled.value) {
                null
            } else {
                withTimeoutOrNull(PROTOCOL_TIMEOUT_MILLIS) { protocol.chats.first() }
                    ?.firstOrNull { it.chatId == key.chatId }
                    ?.toWidgetChat()
            }
        }
    }

    /** The newest messages of [key], oldest first. */
    suspend fun messages(key: WidgetChatKey, isGroup: Boolean): List<WidgetMessage> = when (key) {
        is WidgetChatKey.Sms -> if (isSmsReadable) {
            smsMessages(
                key.conversationId,
                isGroup
            )
        } else {
            emptyList()
        }
        is WidgetChatKey.Twill -> twillMessages(key, isGroup)
    }

    private fun smsChats(
        selection: String = ConversationListData.WHERE_NOT_ARCHIVED,
        args: Array<String>? = null,
    ): List<WidgetChat> {
        val cursor = try {
            context.contentResolver.query(
                MessagingContentProvider.CONVERSATIONS_URI,
                ConversationListItemData.PROJECTION,
                selection,
                args,
                ConversationListData.SORT_ORDER,
            )
        } catch (exception: RuntimeException) {
            null
        } ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext() && size < MAX_CHATS) {
                    val conv = ConversationListItemData()
                    conv.bind(it)
                    add(conv.toWidgetChat())
                }
            }
        }
    }

    private fun ConversationListItemData.toWidgetChat(): WidgetChat {
        val contactId = participantContactId
        val group = isGroup
        val body = snippetText?.takeIf { it.isNotBlank() }?.replace('\n', ' ')
            ?: attachmentLabel(previewContentType)
        val prefix = if (isMessageTypeOutgoing && body.isNotEmpty()) {
            context.getString(R.string.inbox_you_prefix)
        } else {
            ""
        }
        return WidgetChat(
            key = WidgetChatKey.Sms(conversationId),
            title = name.orEmpty(),
            snippet = prefix + body,
            timeMillis = timestamp,
            isUnread = !isRead,
            isGroup = group,
            iconRes = R.drawable.ic_notification_sms,
            loadPhoto = { if (!group && contactId > 0) contactPhoto(contactId) else null },
        )
    }

    private fun TwillChat.toWidgetChat(): WidgetChat {
        val last = lastMessage
        val attachment = last?.attachment
        val body = when {
            last == null -> ""
            last.text.isNotEmpty() -> last.text.replace('\n', ' ')
            attachment?.isImage == true -> context.getString(R.string.nostr_photo)
            attachment != null -> context.getString(R.string.nostr_file)
            else -> ""
        }
        val prefix = when {
            last?.isOutgoing == true -> context.getString(R.string.inbox_you_prefix)
            lastSenderName != null -> "$lastSenderName: "
            else -> ""
        }
        val ref = avatar.ref
        val protocol = protocols[this.protocol]
        return WidgetChat(
            key = WidgetChatKey.Twill(this.protocol, chatId),
            title = title,
            snippet = if (body.isEmpty()) "" else prefix + body,
            timeMillis = last?.timeMillis ?: 0L,
            isUnread = unreadCount > 0,
            isGroup = isGroup,
            iconRes = protocol.text.notificationIcon,
            loadPhoto = {
                ref?.let {
                    withTimeoutOrNull(PHOTO_TIMEOUT_MILLIS) { protocol.loadAvatar(it) }
                }?.let(::circle)
            },
        )
    }

    private fun smsMessages(conversationId: String, isGroup: Boolean): List<WidgetMessage> {
        val uri = MessagingContentProvider.buildConversationMessagesUri(
            conversationId,
            MAX_MESSAGES
        )
        val cursor = try {
            context.contentResolver.query(
                uri,
                ConversationMessageData.getProjection(),
                null,
                null,
                null
            )
        } catch (exception: RuntimeException) {
            null
        } ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val message = ConversationMessageData()
                    message.bind(it)
                    val text = message.text?.takeIf { text -> text.isNotBlank() }
                        ?: message.attachments.firstOrNull()?.let { part ->
                            attachmentLabel(part.contentType)
                        }
                        ?: continue
                    add(
                        WidgetMessage(
                            id = message.messageId,
                            text = text,
                            timeMillis = message.receivedTimeStamp,
                            isOutgoing = !message.isIncoming,
                            sender = message.senderDisplayName?.takeIf {
                                isGroup &&
                                    message.isIncoming
                            },
                        ),
                    )
                }
            }.reversed() // The provider returns the newest first.
        }
    }

    private suspend fun twillMessages(
        key: WidgetChatKey.Twill,
        isGroup: Boolean,
    ): List<WidgetMessage> {
        val protocol = protocols[key.protocol]
        if (!protocol.isEnabled.value) return emptyList()
        val messages = withTimeoutOrNull(PROTOCOL_TIMEOUT_MILLIS) {
            protocol.messages(key.chatId, MAX_MESSAGES).first()
        }.orEmpty()
        val members = if (isGroup) {
            withTimeoutOrNull(PROTOCOL_TIMEOUT_MILLIS) { protocol.details(key.chatId).first() }
                ?.members
                .orEmpty()
        } else {
            emptyMap()
        }
        return messages.filter { !it.isInfo }.mapNotNull { message ->
            val text = message.widgetText() ?: return@mapNotNull null
            WidgetMessage(
                id = message.id,
                text = text,
                timeMillis = message.timeMillis,
                isOutgoing = message.isOutgoing,
                sender = if (isGroup &&
                    !message.isOutgoing
                ) {
                    members[message.senderId]?.name
                } else {
                    null
                },
            )
        }
    }

    private fun TwillMessage.widgetText(): String? = when {
        text.isNotBlank() -> text
        attachment?.isImage == true -> context.getString(R.string.nostr_photo)
        attachment != null -> context.getString(R.string.nostr_file)
        else -> null
    }

    private fun attachmentLabel(contentType: String?): String = context.getString(
        when {
            contentType == null -> return ""
            ContentType.isAudioType(contentType) -> R.string.conversation_list_snippet_audio_clip
            ContentType.isImageType(contentType) -> R.string.conversation_list_snippet_picture
            ContentType.isVideoType(contentType) -> R.string.conversation_list_snippet_video
            ContentType.isVCardType(contentType) -> R.string.conversation_list_snippet_vcard
            else -> R.string.nostr_file
        },
    )

    private fun contactPhoto(contactId: Long): Bitmap? = try {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, uri, false)
            ?.use(BitmapFactory::decodeStream)
            ?.let(::circle)
    } catch (exception: Exception) {
        null
    }

    /** [source] cropped to a circle, small enough for many rows in one widget. */
    private fun circle(source: Bitmap): Bitmap {
        val size = (AVATAR_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = size.toFloat() / minOf(source.width, source.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (size - source.width * scale) / 2f,
                (size - source.height * scale) / 2f,
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(matrix) }
        }
        Canvas(output).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return output
    }

    companion object {
        const val MAX_CHATS = 30
        const val MAX_MESSAGES = 25
        private const val AVATAR_DP = 40
        private const val PROTOCOL_TIMEOUT_MILLIS = 4_000L
        private const val PHOTO_TIMEOUT_MILLIS = 2_000L
    }
}
