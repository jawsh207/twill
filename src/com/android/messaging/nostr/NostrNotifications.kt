package com.android.messaging.nostr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.android.messaging.R
import com.android.messaging.ui.MainActivity

/** Notification channels and notifications for Nostr. */
internal object NostrNotifications {
    const val MESSAGES_CHANNEL_ID = "nostr_messages"
    const val CONNECTION_CHANNEL_ID = "nostr_connection"
    const val CONNECTION_NOTIFICATION_ID = 0x7417_0001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGES_CHANNEL_ID,
                context.getString(R.string.nostr_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL_ID,
                context.getString(R.string.nostr_channel_connection),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.nostr_channel_connection_description)
                setShowBadge(false)
            },
        )
    }

    fun connectionNotification(context: Context): Notification {
        ensureChannels(context)
        return Notification.Builder(context, CONNECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms_light)
            .setContentTitle(context.getString(R.string.nostr_connection_title))
            .setContentText(context.getString(R.string.nostr_connection_text))
            .setContentIntent(openAppIntent(context))
            .setOngoing(true)
            .build()
    }

    fun showMessage(
        context: Context,
        senderName: String,
        message: NostrMessage,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)
        val notification = Notification.Builder(context, MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms_light)
            .setContentTitle(senderName)
            .setContentText(message.content)
            .setStyle(Notification.BigTextStyle().bigText(message.content))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setWhen(message.createdAtSeconds * 1000)
            .setShowWhen(true)
            .build()
        // One notification per conversation; newer messages replace older ones.
        manager.notify(notificationTag(message.peerPubKey), 0, notification)
    }

    fun cancelConversation(context: Context, peerPubKey: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(notificationTag(peerPubKey), 0)
    }

    private fun notificationTag(peerPubKey: String) = "nostr:$peerPubKey"

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
