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
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.provider.Settings
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
import com.android.messaging.ui.conversation.ConversationActivity
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
        val senderAvatar =
            avatarBitmap(context, message.senderPicture, message.senderName, message.senderKey)
        val sender = Person.Builder()
            .setName(message.senderName)
            .setKey(protocol.key + ":" + message.senderKey)
            .setIcon(senderAvatar?.let(IconCompat::createWithBitmap))
            .build()
        // Earlier messages from the same sender take their current name and picture (a
        // profile can arrive after the first message, which then showed a key).
        val style = MessagingStyle(selfPerson(context))
        active?.let(MessagingStyle::extractMessagingStyleFromNotification)
            ?.messages
            ?.forEach { earlier ->
                val person = if (earlier.person?.key == sender.key) sender else earlier.person
                style.addMessage(
                    MessagingStyle.Message(earlier.text, earlier.timestamp, person),
                )
            }
        // Invoices in one line, as in Struthio ("Lightning invoice for 100 sats").
        val text = TwillInvoices.summarize(context, message.text)
        style.addMessage(MessagingStyle.Message(text, message.timeMillis, sender))
        style.setGroupConversation(isGroup)
        style.setConversationTitle(if (isGroup) title else null)
        val ids = (shownIds + message.id).takeLast(MAX_MESSAGES)

        val chatAvatar = if (isGroup) avatarBitmap(context, chatPicture, title, chatId) else null
        val conversationPerson = if (isGroup) {
            Person.Builder()
                .setName(title)
                .setKey(key)
                .setIcon(chatAvatar?.let(IconCompat::createWithBitmap))
                .build()
        } else {
            sender
        }
        val avatar = if (isGroup) chatAvatar else senderAvatar
        pushShortcut(context, protocol, chatId, key, title, conversationPerson, avatar)
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
            // A bubble, as on SMS: the shortcut's screen in a floating window.
            .setBubbleMetadata(bubbleMetadata(key))
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

    /**
     * Removes [chatId]'s notification, except while the chat is a bubble; see
     * [cancelKeepingBubble].
     */
    fun cancelChat(context: Context, protocol: TwillProtocolId, chatId: String) {
        cancelKeepingBubble(context, tag(protocol, chatId), 0)
    }

    /**
     * Removes a conversation notification (any protocol, SMS included), except while it is a
     * bubble: Android closes a bubble along with its notification, so then the notification
     * is only hidden from the shade and the bubble stays open.
     */
    @JvmStatic
    fun cancelKeepingBubble(context: Context, tag: String?, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }
            ?.notification
        val bubble = active?.bubbleMetadata
        val shortcutId = bubble?.shortcutId
        if (active == null || active.flags and Notification.FLAG_BUBBLE == 0 ||
            shortcutId == null
        ) {
            manager.cancel(tag, id)
            return
        }
        if (bubble.isNotificationSuppressed) return
        val hidden = Notification.Builder.recoverBuilder(context, active)
            .setOnlyAlertOnce(true)
            .setBubbleMetadata(
                Notification.BubbleMetadata.Builder(shortcutId)
                    .setDesiredHeight(BUBBLE_HEIGHT_DP)
                    .setSuppressNotification(true)
                    .build(),
            )
            .build()
        runCatching { manager.notify(tag, id, hidden) }
    }

    /** What [bubbleChat] and [postBubble] did. */
    enum class BubbleResult { Opened, NotAllowed, Failed }

    /**
     * Opens [chatId] in a floating bubble now, from the chat's menu. Android only shows it if
     * Twill may bubble this conversation (Settings > Notifications > Bubbles); otherwise
     * nothing is posted and the caller can send the user there. Call off the main thread.
     */
    fun bubbleChat(
        context: Context,
        protocol: TwillProtocolId,
        chatId: String,
        title: String,
        isGroup: Boolean,
        picture: Bitmap?,
    ): BubbleResult {
        if (!canBubbleAtAll(context)) return BubbleResult.NotAllowed
        ensureChannels(context)
        val key = conversationKey(protocol, chatId)
        val avatar = avatarBitmap(context, picture, title, chatId)
        val person = Person.Builder()
            .setName(title)
            .setKey(if (isGroup) key else protocol.key + ":" + chatId)
            .setIcon(avatar?.let(IconCompat::createWithBitmap))
            .build()
        pushShortcut(context, protocol, chatId, key, title, person, avatar)
        ensureConversationChannel(context, protocol, key, title)
        return postBubble(
            context = context,
            tag = tag(protocol, chatId),
            id = 0,
            channelId = key,
            shortcutId = key,
            person = person,
            title = title,
            isGroup = isGroup,
            smallIcon = protocolIcon(protocol),
            contentIntent = openIntent(context, protocol, chatId),
        )
    }

    /** Notifications and bubbles are on for Twill at all. */
    @JvmStatic
    fun canBubbleAtAll(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled() && manager.areBubblesEnabled()
    }

    /**
     * Shows a conversation (its shortcut [shortcutId] and channel [channelId] already set up)
     * as an expanded bubble: its current notification if there is one, else an empty
     * conversation notification. Hidden from the shade either way.
     */
    @JvmStatic
    fun postBubble(
        context: Context,
        tag: String?,
        id: Int,
        channelId: String,
        shortcutId: String,
        person: Person,
        title: String,
        isGroup: Boolean,
        @DrawableRes smallIcon: Int,
        contentIntent: PendingIntent,
    ): BubbleResult {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return BubbleResult.Failed
        val allowed = when (manager.bubblePreference) {
            NotificationManager.BUBBLE_PREFERENCE_ALL -> true
            NotificationManager.BUBBLE_PREFERENCE_SELECTED ->
                manager.getNotificationChannel(channelId)?.canBubble() == true
            else -> false
        }
        if (!allowed) return BubbleResult.NotAllowed

        val metadata = NotificationCompat.BubbleMetadata.Builder(shortcutId)
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            .setAutoExpandBubble(true)
            .setSuppressNotification(true)
            .build()
        val active = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }
            ?.notification
        val builder = if (active != null) {
            NotificationCompat.Builder(context, active)
        } else {
            val style = MessagingStyle(selfPerson(context))
                .setGroupConversation(isGroup)
                .setConversationTitle(if (isGroup) title else null)
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(smallIcon)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentTitle(title)
                .setContentIntent(contentIntent)
                .setShortcutId(shortcutId)
                .setLocusId(LocusIdCompat(shortcutId))
                .addPerson(person)
        }
        builder.setBubbleMetadata(metadata).setOnlyAlertOnce(true).setSilent(true)
        return try {
            NotificationManagerCompat.from(context).notify(tag, id, builder.build())
            BubbleResult.Opened
        } catch (exception: SecurityException) {
            BubbleResult.NotAllowed
        } catch (exception: RuntimeException) {
            LogUtil.w(TAG, "Could not bubble chat: ${exception.javaClass.simpleName}")
            BubbleResult.Failed
        }
    }

    /** Where the user lets Twill's conversations bubble. */
    @JvmStatic
    fun bubbleSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @DrawableRes
    private fun protocolIcon(protocol: TwillProtocolId) = when (protocol) {
        TwillProtocolId.Nostr -> R.drawable.ic_notification_nostr
        TwillProtocolId.DeltaChat -> R.drawable.ic_notification_deltachat
    }

    /**
     * A bubble as on SMS: the shortcut's screen in a floating window. The height is in dp
     * rather than a resource id, since Android keeps bubbles across app updates, which can
     * renumber resources (a stale id leaves the bubble at Android's small default height).
     */
    private fun bubbleMetadata(key: String) = NotificationCompat.BubbleMetadata.Builder(key)
        .setDesiredHeight(BUBBLE_HEIGHT_DP)
        .build()

    /** Bubble height for every protocol, SMS included. */
    const val BUBBLE_HEIGHT_DP = 600

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
    private fun avatarBitmap(
        context: Context,
        picture: Bitmap?,
        name: String,
        identifier: String,
    ): Bitmap? = picture?.let { circular(context, it) }
        ?: runCatching {
            BugleNotifications.getAvatarBitmap(
                context,
                AvatarUriUtil.createAvatarUri(null, name, identifier, null),
            )
        }.getOrNull()

    /**
     * The chat's conversation icon (bubble, Conversations section): the avatar inside a ring
     * in the protocol's colour, where Android puts a white one around SMS avatars.
     */
    private fun ringedIcon(protocol: TwillProtocolId, avatar: Bitmap): IconCompat {
        // An adaptive icon: Android shows the middle 72 of its 108 units, cut to a circle.
        val side = RING_ICON_SIZE
        val visible = side * 72f / 108f
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(ringColor(protocol))
        val inner = visible * RING_AVATAR_FRACTION
        val scale = inner / minOf(avatar.width, avatar.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (side - avatar.width * scale) / 2f,
                (side - avatar.height * scale) / 2f,
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(matrix) }
        }
        canvas.drawCircle(side / 2f, side / 2f, inner / 2f, paint)
        return IconCompat.createWithAdaptiveBitmap(output)
    }

    private fun ringColor(protocol: TwillProtocolId) = when (protocol) {
        TwillProtocolId.Nostr -> Color.rgb(0x8E, 0x30, 0xEB)
        TwillProtocolId.DeltaChat -> Color.rgb(0x9E, 0x9E, 0x9E)
    }

    private const val RING_ICON_SIZE = 288
    private const val RING_AVATAR_FRACTION = 0.78f

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
        avatar: Bitmap?,
    ) {
        runCatching {
            val shortcut = ShortcutInfoCompat.Builder(context, key)
                .setShortLabel(title.ifBlank { context.getString(messagesChannelName(protocol)) })
                .setLocusId(LocusIdCompat(key))
                .setLongLived(true)
                .setPerson(person)
                .setIcon(
                    avatar?.let { ringedIcon(protocol, it) }
                        ?: IconCompat.createWithResource(
                            context,
                            R.drawable.ic_launcher_foreground
                        ),
                )
                .setIntent(bubbleIntent(context, protocol, chatId))
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

    /**
     * The chat in the screen SMS bubbles use (`ConversationActivity`), the only one Android
     * can embed in a bubble. Also what the chat's launcher shortcut opens, as for SMS.
     */
    private fun bubbleIntent(context: Context, protocol: TwillProtocolId, chatId: String) =
        Intent(context, ConversationActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_PROTOCOL, protocol.key)
            .putExtra(EXTRA_CHAT_ID, chatId)

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
