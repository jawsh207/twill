package com.android.messaging.twill

import android.content.Context
import android.net.Uri
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.android.messaging.R
import com.android.messaging.datamodel.BugleNotifications
import com.android.messaging.ui.UIIntents
import com.android.messaging.util.AvatarUriUtil
import com.android.messaging.util.NotificationChannelUtil

/**
 * Opens an SMS/MMS conversation in a bubble from its menu, set up the way its notifications
 * set up bubbles (`BugleNotifications.setUpShortcuts`): same shortcut, channel and tag, so a
 * later message notification simply updates it. Call off the main thread.
 */
internal object TwillSmsBubble {

    fun open(
        context: Context,
        conversationId: String,
        title: String,
        isGroup: Boolean,
        photoUri: String?,
        destination: String?,
    ): TwillNotifications.BubbleResult {
        if (!TwillNotifications.canBubbleAtAll(context)) {
            return TwillNotifications.BubbleResult.NotAllowed
        }
        val avatarUri = if (isGroup) {
            AvatarUriUtil.createAvatarUri(null, title, conversationId, null)
        } else {
            AvatarUriUtil.createAvatarUri(
                photoUri?.let(Uri::parse),
                title,
                destination ?: conversationId,
                null,
            )
        }
        val avatar = runCatching { BugleNotifications.getAvatarBitmap(context, avatarUri) }
            .getOrNull()
        val person = Person.Builder()
            .setName(title)
            .setKey(conversationId)
            .setIcon(avatar?.let(IconCompat::createWithBitmap))
            .build()

        val shortcut = ShortcutInfoCompat.Builder(context, conversationId)
            .setShortLabel(title.ifBlank { context.getString(R.string.app_name) })
            .setLocusId(LocusIdCompat(conversationId))
            .setLongLived(true)
            .setPerson(person)
            .setIcon(
                person.icon
                    ?: IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground),
            )
            .setIntent(
                UIIntents.get().getShortcutIntentForConversationActivity(context, conversationId),
            )
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        NotificationChannelUtil.createConversationChannelForRuntime(conversationId, title)

        return TwillNotifications.postBubble(
            context = context,
            tag = BugleNotifications.messageNotificationTag(conversationId),
            id = SMS_NOTIFICATION_ID,
            channelId = conversationId,
            shortcutId = conversationId,
            person = person,
            title = title,
            isGroup = isGroup,
            smallIcon = R.drawable.ic_notification_sms,
            contentIntent = UIIntents.get()
                .getPendingIntentForConversationActivity(context, conversationId, null),
        )
    }

    private const val SMS_NOTIFICATION_ID =
        com.android.messaging.util.PendingIntentConstants.SMS_NOTIFICATION_ID
}
