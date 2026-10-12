package com.android.messaging.ui.conversation.scheduled

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import com.android.messaging.data.conversation.model.ConversationId
import com.android.messaging.data.conversation.model.ParticipantId
import com.android.messaging.data.scheduled.ScheduledMessage
import com.android.messaging.data.scheduled.ScheduledMessageScheduler
import com.android.messaging.di.core.ApplicationCoroutineScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the conversation screen needs to schedule and manage scheduled messages. */
internal interface ScheduledMessagesController {
    val messages: StateFlow<List<ScheduledMessage>>

    fun schedule(
        conversationId: ConversationId,
        selfParticipantId: ParticipantId?,
        messageText: String,
        subjectText: String,
        sendAtMillis: Long,
    )

    fun cancel(id: String)
}

/**
 * Provided by the real conversation route. Left null in previews and UI tests, which hides the
 * "Schedule message" menu item.
 */
internal val LocalScheduledMessagesController =
    staticCompositionLocalOf<ScheduledMessagesController?> { null }

@HiltViewModel
internal class ScheduledMessagesViewModel @Inject constructor(
    private val scheduler: ScheduledMessageScheduler,
    // Writes outlive the screen so leaving right after scheduling never drops a message.
    @param:ApplicationCoroutineScope private val applicationScope: CoroutineScope,
) : ViewModel(),
    ScheduledMessagesController {

    override val messages: StateFlow<List<ScheduledMessage>> = scheduler.messages

    override fun schedule(
        conversationId: ConversationId,
        selfParticipantId: ParticipantId?,
        messageText: String,
        subjectText: String,
        sendAtMillis: Long,
    ) {
        applicationScope.launch(Dispatchers.IO) {
            scheduler.schedule(
                conversationId = conversationId,
                selfParticipantId = selfParticipantId,
                messageText = messageText,
                subjectText = subjectText,
                sendAtMillis = sendAtMillis,
            )
        }
    }

    override fun cancel(id: String) {
        applicationScope.launch(Dispatchers.IO) {
            scheduler.cancel(id)
        }
    }
}
