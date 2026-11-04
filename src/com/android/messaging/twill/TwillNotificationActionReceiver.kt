package com.android.messaging.twill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.util.LogUtil
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TwillNotificationEntryPoint {
    fun runtime(): TwillRuntime

    fun protocols(): TwillProtocols

    @ApplicationCoroutineScope
    fun appScope(): CoroutineScope
}

/**
 * The buttons on Nostr and Delta Chat message notifications, as on SMS ones:
 * - Mark as read: marks the chat read (read receipts where the protocol has them) and clears
 *   the notification.
 * - Reply: sends the typed reply, connecting briefly if Twill isn't connected, and shows it in
 *   the notification.
 * - Delete: deletes the messages the notification shows, then marks the chat read.
 */
class TwillNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val protocolKey = intent.getStringExtra(TwillNotifications.EXTRA_PROTOCOL) ?: return
        val chatId = intent.getStringExtra(TwillNotifications.EXTRA_CHAT_ID) ?: return
        val messageIds = intent.getStringArrayExtra(TwillNotifications.EXTRA_MESSAGE_IDS)
            ?.toList()
            .orEmpty()
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            TwillNotificationEntryPoint::class.java,
        )
        entryPoint.runtime().start(markVisible = false)
        val protocol = entryPoint.protocols().byKey(protocolKey) ?: return
        val appContext = context.applicationContext

        val work: suspend () -> Unit = when (intent.action) {
            TwillNotifications.ACTION_MARK_READ -> suspend {
                protocol.markReadFromNotification(chatId, messageIds)
                TwillNotifications.cancelChat(appContext, protocol.id, chatId)
            }

            TwillNotifications.ACTION_DELETE -> suspend {
                protocol.deleteMessages(chatId, messageIds)
                protocol.markReadFromNotification(chatId, messageIds)
                TwillNotifications.cancelChat(appContext, protocol.id, chatId)
            }

            TwillNotifications.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(TwillNotifications.EXTRA_REPLY)
                    ?.toString()
                    ?.trim()
                if (text.isNullOrEmpty()) return
                TwillNotifications.showReply(appContext, protocol.id, chatId, text)
                suspend { protocol.sendFromNotification(chatId, text) }
            }

            else -> return
        }

        // The work carries on in the app's scope; the broadcast is held open while it runs,
        // up to the time Android allows.
        val pending = goAsync()
        val job = entryPoint.appScope().launch(Dispatchers.IO) {
            try {
                work()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LogUtil.w(TAG, "Notification action failed: ${exception.javaClass.simpleName}")
            }
        }
        entryPoint.appScope().launch {
            try {
                withTimeoutOrNull(HOLD_MILLIS) { job.join() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TwillNotificationAction"
        const val HOLD_MILLIS = 9_000L
    }
}
