package com.android.messaging.data.scheduled

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.android.messaging.R
import com.android.messaging.data.conversation.model.ConversationId
import com.android.messaging.data.conversation.model.ParticipantId
import com.android.messaging.data.conversation.model.draft.ConversationDraft
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.domain.conversation.usecase.draft.SendConversationDraft
import com.android.messaging.receiver.ScheduledMessageReceiver
import com.android.messaging.util.LogUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Schedules, cancels and dispatches scheduled messages. A single exact alarm is kept for the
 * soonest pending message; when it fires, every message that is due is sent and the alarm is
 * re-armed for the next one.
 */
@Singleton
internal class ScheduledMessageScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: ScheduledMessageStore,
    private val sendConversationDraft: SendConversationDraft,
    @param:ApplicationCoroutineScope private val applicationScope: CoroutineScope,
) {
    private val dispatchMutex = Mutex()

    val messages = store.messages

    fun schedule(
        conversationId: ConversationId,
        selfParticipantId: ParticipantId?,
        messageText: String,
        subjectText: String,
        sendAtMillis: Long,
    ): ScheduledMessage {
        val message = store.add(
            conversationId = conversationId,
            selfParticipantId = selfParticipantId,
            messageText = messageText,
            subjectText = subjectText,
            sendAtMillis = sendAtMillis,
        )
        rearmAlarm()
        return message
    }

    fun cancel(id: String) {
        store.remove(id)
        rearmAlarm()
    }

    /** Re-registers the alarm, e.g. after a reboot or app update cleared it. */
    fun rearmAlarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = alarmPendingIntent()
        val next = store.all().minByOrNull { it.sendAtMillis }

        if (next == null) {
            alarmManager.cancel(pendingIntent)
            return
        }

        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.sendAtMillis,
                pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.sendAtMillis,
                pendingIntent,
            )
        }
    }

    /** Sends everything that is due. Safe to call repeatedly. */
    fun dispatchDue(onComplete: () -> Unit) {
        applicationScope.launch {
            try {
                dispatchMutex.withLock {
                    val now = System.currentTimeMillis()
                    store.all()
                        .filter { it.sendAtMillis <= now + DUE_TOLERANCE_MILLIS }
                        .forEach { message -> send(message) }
                }
            } finally {
                rearmAlarm()
                onComplete()
            }
        }
    }

    private suspend fun send(message: ScheduledMessage) {
        // Remove first so a crash mid-send can never cause a duplicate send.
        store.remove(message.id) ?: return

        try {
            sendConversationDraft(
                conversationId = message.conversationId,
                draft = ConversationDraft(
                    messageText = message.messageText,
                    subjectText = message.subjectText,
                    selfParticipantId = message.selfParticipantId,
                ),
                ignoreMessageSizeLimit = true,
            ).collect()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LogUtil.e(TAG, "Scheduled message failed to send", exception)
            notifyFailure(message)
        }
    }

    private fun notifyFailure(message: ScheduledMessage) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
            ?: return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                FAILURE_CHANNEL_ID,
                context.getString(R.string.schedule_message_failed_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        val notification = android.app.Notification.Builder(context, FAILURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms_light)
            .setContentTitle(context.getString(R.string.schedule_message_send_failed))
            .setContentText(message.messageText)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message.messageText))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(message.id.hashCode(), notification)
    }

    private fun alarmPendingIntent(): PendingIntent {
        val intent = Intent(context, ScheduledMessageReceiver::class.java)
            .setAction(ScheduledMessageReceiver.ACTION_SEND_DUE)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TAG = "ScheduledMessages"
        const val FAILURE_CHANNEL_ID = "scheduled_message_failures"

        // Inexact fallback alarms can fire slightly early; treat anything this close as due.
        const val DUE_TOLERANCE_MILLIS = 30_000L
    }
}
