package com.android.messaging.ui.conversation.scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.data.conversation.model.ConversationId
import com.android.messaging.data.scheduled.ScheduledMessage

/**
 * Extra actions Twill places in the conversation top bar, between the call button and the
 * 3-dot menu. Null keeps the upstream top bar unchanged (previews, tests).
 */
internal val LocalConversationTopBarExtraActions =
    compositionLocalOf<(@Composable () -> Unit)?> { null }

/** Pending scheduled messages for one conversation, soonest first. */
@Composable
internal fun rememberScheduledMessagesFor(
    controller: ScheduledMessagesController,
    conversationId: ConversationId,
): List<ScheduledMessage> {
    val all by controller.messages.collectAsStateWithLifecycle()
    return remember(all, conversationId) {
        all.filter { it.conversationId == conversationId }
    }
}

/** Clock icon with a count badge, shown only while this conversation has scheduled messages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledMessagesTopBarAction(
    count: Int,
    onClick: () -> Unit,
) {
    if (count <= 0) return

    val description = pluralStringResource(
        R.plurals.scheduled_messages_indicator_description,
        count,
        count,
    )

    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                Badge { Text(text = count.toString()) }
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                contentDescription = description,
            )
        }
    }
}

/** Lists this conversation's scheduled messages, each with a cancel button. */
@Composable
internal fun ScheduledMessagesListDialog(
    messages: List<ScheduledMessage>,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.scheduled_messages_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScheduledList(
                    messages = messages,
                    onCancel = onCancel,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
    )
}
