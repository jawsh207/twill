package com.android.messaging.twill

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.android.messaging.R
import com.android.messaging.ui.MainActivity

/**
 * Notifications for Twill's protocols: one channel per protocol for messages (each with its
 * own small icon), and one shared low-priority channel for the "always connected" service.
 */
internal object TwillNotifications {
    const val CONNECTION_CHANNEL_ID = "twill_connection"
    const val CONNECTION_NOTIFICATION_ID = 0x7417_0001

    const val EXTRA_PROTOCOL = "app.twill.extra.PROTOCOL"
    const val EXTRA_CHAT_ID = "app.twill.extra.CHAT_ID"

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
            ?: R.drawable.ic_sms_light
        return Notification.Builder(context, CONNECTION_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(context.getString(R.string.twill_connection_title, names))
            .setContentText(context.getString(R.string.twill_connection_text))
            .setContentIntent(openIntent(context, protocol = null, chatId = null))
            .setOngoing(true)
            .build()
    }

    /** One notification per chat; a newer message replaces the older one. */
    fun showMessage(
        context: Context,
        protocol: TwillProtocolId,
        @DrawableRes icon: Int,
        chatId: String,
        title: String,
        senderName: String?,
        body: String,
        timeMillis: Long,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        ensureChannels(context)
        val text = if (senderName != null) "$senderName: $body" else body
        val notification = Notification.Builder(context, messagesChannelId(protocol))
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(context, protocol, chatId))
            .setAutoCancel(true)
            .setWhen(timeMillis)
            .setShowWhen(true)
            .build()
        try {
            manager.notify(tag(protocol, chatId), 0, notification)
        } catch (exception: SecurityException) {
            // Notification permission was revoked.
        }
    }

    fun cancelChat(context: Context, protocol: TwillProtocolId, chatId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tag(protocol, chatId), 0)
    }

    private fun tag(protocol: TwillProtocolId, chatId: String) = protocol.key + ":" + chatId

    private fun openIntent(
        context: Context,
        protocol: TwillProtocolId?,
        chatId: String?,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (protocol != null && chatId != null) {
            intent.putExtra(EXTRA_PROTOCOL, protocol.key).putExtra(EXTRA_CHAT_ID, chatId)
        }
        return PendingIntent.getActivity(
            context,
            // A request code per chat, so each notification opens its own chat.
            if (protocol != null && chatId != null) tag(protocol, chatId).hashCode() else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
