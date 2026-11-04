package com.android.messaging.twill

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.android.messaging.R
import com.android.messaging.datamodel.BugleNotifications
import com.android.messaging.ui.MainActivity
import com.android.messaging.util.AvatarUriUtil
import com.android.messaging.util.LogUtil
import java.security.MessageDigest

/** A message to show in a Twill chat's notification. */
internal data class TwillNotificationMessage(
    /** The protocol's id for the message, used by the Delete button. */
    val id: String,
    /** Stays the same for one sender (a public key, a contact id). */
    val senderKey: String,
    val senderName: String,
    /** The sender's picture, if there is one to show; otherwise a letter avatar like SMS. */
    val senderPicture: Bitmap?,
    val text: String,
    val timeMillis: Long,
)

/**
 * Notifications for Twill's protocols, built like SMS/MMS ones (`BugleNotifications`): a
 * conversation notification per chat (MessagingStyle, sender avatars, a conversation shortcut
 * and its own channel under the protocol's), with Mark as read, Reply and Delete. Plus one
 * shared low-priority channel for the "always connected" service.
 */
internal object TwillNotifications {
    const val CONNECTION_CHANNEL_ID = "twill_connection"
    const val CONNECTION_NOTIFICATION_ID = 0x7417_0001

    const val EXTRA_PROTOCOL = "app.twill.extra.PROTOCOL"
    const val EXTRA_CHAT_ID = "app.twill.extra.CHAT_ID"
    const val EXTRA_MESSAGE_IDS = "app.twill.extra.MESSAGE_IDS"
    const val EXTRA_REPLY = "app.twill.extra.REPLY"

    const val ACTION_MARK_READ = "app.twill.action.NOTIFICATION_MARK_READ"
    const val ACTION_REPLY = "app.twill.action.NOTIFICATION_REPLY"
    const val ACTION_DELETE = "app.twill.action.NOTIFICATION_DELETE"

    private const val TAG = "TwillNotifications"
    private const val SELF_KEY = "twill:self"

    /** Android keeps at most this many messages in a MessagingStyle. */
    private const val MAX_MESSAGES = MessagingStyle.MAXIMUM_RETAINED_MESSAGES

    /** Channels from older builds, replaced by [CONNECTION_CHANNEL_ID]. */
    private val OBSOLETE_CHANNELS = listOf("nostr_connection")

    private fun messagesChannelId(protocol: TwillProtocolId) = protocol.key + "_messages"

    @StringRes
    private fun messagesChannelName(protocol: TwillProtocolId) = when (protocol) {
        TwillProtocolId.Nostr -> R.string.nostr_channel_messages
        TwillProtocolId.DeltaChat -> R.string.deltachat_channel_messages
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        TwillProtocolId.entries.forEach { protocol ->
            manager.createNotificationChannel(
                NotificationChannel(
                    messagesChannelId(protocol),
                    context.getString(messagesChannelName(protocol)),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL_ID,
                context.getString(R.string.twill_channel_connection),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.twill_channel_connection_description)
                setShowBadge(false)
            },
        )
        OBSOLETE_CHANNELS.forEach(manager::deleteNotificationChannel)
    }

    /** The persistent notification of the shared "always connected" service. */
    fun connectionNotification(context: Context, protocols: List<TwillProtocol>): Notification {
        ensureChannels(context)
        val names = protocols.joinToString(", ") { context.getString(it.text.sectionTitle) }
        val icon = protocols.singleOrNull()?.text?.notificationIcon
            ?: R.drawable.ic_notification_sms
        return Notification.Builder(context, CONNECTION_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(context.getString(R.string.twill_connection_title, names))
            .setContentText(context.getString(R.string.twill_connection_text))
            .setContentIntent(openIntent(context, protocol = null, chatId = null))
            .setOngoing(true)
            .build()
    }

    /**
     * Adds [message] to [chatId]'s notification (posting it if there is none), as SMS does:
     * earlier messages still shown stay, up to Android's limit. Call off the main thread.
     */
    fun showMessage(
        context: Context,
        protocol: TwillProtocolId,
        @DrawableRes icon: Int,
        chatId: String,
        title: String,
        isGroup: Boolean,
        /** The group's picture; null for a letter avatar. Unused for one-to-one chats. */
        chatPicture: Bitmap?,
        message: TwillNotificationMessage,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannels(context)
        val parent = manager.getNotificationChannel(messagesChannelId(protocol))
        if (parent?.importance == NotificationManager.IMPORTANCE_NONE) return

        val key = conversationKey(protocol, chatId)
        val tag = tag(protocol, chatId)
        val active = activeNotification(context, tag)
        val shownIds = active?.extras?.getStringArray(EXTRA_MESSAGE_IDS)?.toList().orEmpty()
        if (message.id in shownIds) return
        val style = active?.let(MessagingStyle::extractMessagingStyleFromNotification)
            ?: MessagingStyle(selfPerson(context))

        val sender = Person.Builder()
            .setName(message.senderName)
            .setKey(protocol.key + ":" + message.senderKey)
            .setIcon(
                avatarIcon(context, message.senderPicture, message.senderName, message.senderKey)
            )
            .build()
        style.addMessage(MessagingStyle.Message(message.text, message.timeMillis, sender))
        style.setGroupConversation(isGroup)
        style.setConversationTitle(if (isGroup) title else null)
        val ids = (shownIds + message.id).takeLast(MAX_MESSAGES)

        val conversationPerson = if (isGroup) {
            Person.Builder()
                .setName(title)
                .setKey(key)
                .setIcon(avatarIcon(context, chatPicture, title, chatId))
                .build()
        } else {
            sender
        }
        pushShortcut(context, protocol, chatId, key, title, conversationPerson)
        ensureConversationChannel(context, protocol, key, title)

        val builder = NotificationCompat.Builder(context, key)
            .setSmallIcon(icon)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(context, protocol, chatId))
            .setAutoCancel(true)
            .setWhen(message.timeMillis)
            .setShowWhen(true)
            .setShortcutId(key)
            .setLocusId(LocusIdCompat(key))
            .addPerson(sender)
            .addExtras(Bundle().apply { putStringArray(EXTRA_MESSAGE_IDS, ids.toTypedArray()) })
            .addAction(markReadAction(context, protocol, chatId, ids))
            .addAction(replyAction(context, protocol, chatId))
            .addAction(deleteAction(context, protocol, chatId, ids))
        post(manager, tag, builder.build())
    }

    /**
     * Shows a reply sent from the notification in it, as SMS does, without sounding again.
     */
    fun showReply(context: Context, protocol: TwillProtocolId, chatId: String, text: String) {
        val tag = tag(protocol, chatId)
        val active = activeNotification(context, tag) ?: return
        val style = MessagingStyle.extractMessagingStyleFromNotification(active) ?: return
        style.addMessage(
            MessagingStyle.Message(text, System.currentTimeMillis(), selfPerson(context)),
        )
        val builder = NotificationCompat.Builder(context, active)
            .setStyle(style)
            .setOnlyAlertOnce(true)
        post(NotificationManagerCompat.from(context), tag, builder.build())
    }

    fun cancelChat(context: Context, protocol: TwillProtocolId, chatId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tag(protocol, chatId), 0)
    }

    private fun post(manager: NotificationManagerCompat, tag: String, notification: Notification) {
        try {
            manager.notify(tag, 0, notification)
        } catch (exception: SecurityException) {
            // Notification permission was revoked.
        } catch (exception: RuntimeException) {
            LogUtil.w(TAG, "Could not post notification: ${exception.javaClass.simpleName}")
        }
    }

    private fun activeNotification(context: Context, tag: String): Notification? =
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.firstOrNull { it.tag == tag && it.id == 0 }
            ?.notification

    private fun selfPerson(context: Context) = Person.Builder()
        .setName(context.getString(R.string.unknown_self_participant))
        .setKey(SELF_KEY)
        .build()

    /** A circular picture, or SMS's letter avatar for [name] when there is no picture. */
    private fun avatarIcon(
        context: Context,
        picture: Bitmap?,
        name: String,
        identifier: String,
    ): IconCompat? {
        val bitmap = picture?.let { circular(context, it) }
            ?: runCatching {
                BugleNotifications.getAvatarBitmap(
                    context,
                    AvatarUriUtil.createAvatarUri(null, name, identifier, null),
                )
            }.getOrNull()
        return bitmap?.let(IconCompat::createWithBitmap)
    }

    private fun circular(context: Context, source: Bitmap): Bitmap {
        val side = context.resources.getDimensionPixelSize(R.dimen.contact_icon_view_normal_size)
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val scale = side.toFloat() / minOf(source.width, source.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (side - source.width * scale) / 2f,
                (side - source.height * scale) / 2f,
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(matrix) }
        }
        Canvas(output).drawCircle(side / 2f, side / 2f, side / 2f, paint)
        return output
    }

    /**
     * A long-lived shortcut for the chat, which makes Android treat the notification as a
     * conversation (Conversations section, priority and bubble settings), as SMS does.
     */
    private fun pushShortcut(
        context: Context,
        protocol: TwillProtocolId,
        chatId: String,
        key: String,
        title: String,
        person: Person,
    ) {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, key)
                .setShortLabel(title.ifBlank { context.getString(messagesChannelName(protocol)) })
                .setLocusId(LocusIdCompat(key))
                .setLongLived(true)
                .setPerson(person)
                .setIcon(
                    person.icon
                        ?: IconCompat.createWithResource(
                            context,
                            R.drawable.ic_launcher_foreground
                        ),
                )
                .setIntent(chatIntent(context, protocol, chatId).setAction(Intent.ACTION_VIEW))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }.onFailure { LogUtil.w(TAG, "Shortcut failed: ${it.javaClass.simpleName}") }
    }

    /** A channel for the chat under its protocol's, as SMS has under "Conversations". */
    private fun ensureConversationChannel(
        context: Context,
        protocol: TwillProtocolId,
        key: String,
        title: String,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(key) != null) return
        val parent = manager.getNotificationChannel(messagesChannelId(protocol)) ?: return
        val channel = NotificationChannel(
            key,
            title.ifBlank { context.getString(messagesChannelName(protocol)) },
            parent.importance,
        ).apply {
            setSound(parent.sound, parent.audioAttributes)
            enableVibration(parent.shouldVibrate())
            setConversationId(parent.id, key)
        }
        manager.createNotificationChannel(channel)
    }

    private fun markReadAction(
        context: Context,
        protocol: TwillProtocolId,
        chatId: String,
        messageIds: List<String>,
    ) = NotificationCompat.Action.Builder(
        0,
        context.getString(R.string.mark_as_read),
        actionIntent(context, ACTION_MARK_READ, protocol, chatId, messageIds, isMutable = false),
    )
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
        .setShowsUserInterface(false)
        .build()

    private fun replyAction(
        context: Context,
        protocol: TwillProtocolId,
        chatId: String,
    ): NotificationCompat.Action {
        val label = context.getString(R.string.notification_reply_prompt)
        return NotificationCompat.Action.Builder(
            0,
            label,
            actionIntent(context, ACTION_REPLY, protocol, chatId, emptyList(), isMutable = true),
        )
            .addRemoteInput(RemoteInput.Builder(EXTRA_REPLY).setLabel(label).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun deleteAction(
        context: Context,
        protocol: TwillProtocolId,
        chatId: String,
        messageIds: List<String>,
    ) = NotificationCompat.Action.Builder(
        0,
        context.getString(R.string.action_delete),
        actionIntent(context, ACTION_DELETE, protocol, chatId, messageIds, isMutable = false),
    )
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_DELETE)
        .setShowsUserInterface(false)
        .build()

    private fun actionIntent(
        context: Context,
        action: String,
        protocol: TwillProtocolId,
        chatId: String,
        messageIds: List<String>,
        isMutable: Boolean,
    ): PendingIntent {
        val intent = Intent(context, TwillNotificationActionReceiver::class.java)
            .setAction(action)
            // One pending intent per chat and action; updated with the messages now shown.
            .setIdentifier(tag(protocol, chatId))
            .putExtra(EXTRA_PROTOCOL, protocol.key)
            .putExtra(EXTRA_CHAT_ID, chatId)
            .putExtra(EXTRA_MESSAGE_IDS, messageIds.toTypedArray())
        val mutability = if (isMutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            mutability or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun tag(protocol: TwillProtocolId, chatId: String) = protocol.key + ":" + chatId

    /**
     * The chat's shortcut, channel and locus id: "twill:<protocol>:<chat>", with long chat ids
     * (Nostr groups list every member's key) shortened to a hash.
     */
    private fun conversationKey(protocol: TwillProtocolId, chatId: String): String {
        val id = if (chatId.length <= MAX_PLAIN_ID_LENGTH) {
            chatId
        } else {
            MessageDigest.getInstance("SHA-256").digest(chatId.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(HASHED_ID_LENGTH)
        }
        return "twill:" + protocol.key + ":" + id
    }

    private const val MAX_PLAIN_ID_LENGTH = 64
    private const val HASHED_ID_LENGTH = 32

    private fun chatIntent(context: Context, protocol: TwillProtocolId?, chatId: String?): Intent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (protocol != null && chatId != null) {
            intent.putExtra(EXTRA_PROTOCOL, protocol.key).putExtra(EXTRA_CHAT_ID, chatId)
        }
        return intent
    }

    private fun openIntent(
        context: Context,
        protocol: TwillProtocolId?,
        chatId: String?,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        // A request code per chat, so each notification opens its own chat.
        if (protocol != null && chatId != null) tag(protocol, chatId).hashCode() else 0,
        chatIntent(context, protocol, chatId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
