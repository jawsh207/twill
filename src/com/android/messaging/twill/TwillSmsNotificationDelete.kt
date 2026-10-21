package com.android.messaging.twill

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.android.messaging.R
import com.android.messaging.datamodel.action.DeleteMessageAction
import com.android.messaging.datamodel.action.MarkAsReadAction

/**
 * The "Delete" button on SMS/MMS message notifications: deletes the messages the notification
 * shows, then marks the conversation read, which clears the notification.
 */
internal object TwillSmsNotificationDelete {
    const val ACTION = "app.twill.action.DELETE_NOTIFIED_MESSAGES"
    const val EXTRA_CONVERSATION_ID = "app.twill.extra.CONVERSATION_ID"
    const val EXTRA_MESSAGE_IDS = "app.twill.extra.MESSAGE_IDS"

    /** The notification action, or null when there is nothing to delete. */
    @JvmStatic
    fun action(
        context: Context,
        conversationId: String,
        messageIds: List<String>,
    ): NotificationCompat.Action? {
        if (messageIds.isEmpty()) return null
        val intent = Intent(context, TwillSmsNotificationDeleteReceiver::class.java)
            .setAction(ACTION)
            // One pending intent per conversation; updated with the messages now shown.
            .setIdentifier(conversationId)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)
            .putExtra(EXTRA_MESSAGE_IDS, messageIds.toTypedArray())
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.action_delete),
            pendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_DELETE)
            .setShowsUserInterface(false)
            .build()
    }
}

class TwillSmsNotificationDeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TwillSmsNotificationDelete.ACTION) return
        val conversationId =
            intent.getStringExtra(TwillSmsNotificationDelete.EXTRA_CONVERSATION_ID) ?: return
        intent.getStringArrayExtra(TwillSmsNotificationDelete.EXTRA_MESSAGE_IDS)
            ?.forEach(DeleteMessageAction::deleteMessage)
        MarkAsReadAction.markAsRead(conversationId, true)
    }
}
