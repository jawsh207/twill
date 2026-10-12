package com.android.messaging.ui.conversation.scheduled

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.data.conversation.model.ConversationId
import com.android.messaging.data.conversation.model.ParticipantId
import com.android.messaging.data.scheduled.ScheduledMessage
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Lets the user pick a date and time for the message currently in the composer, and lists the
 * messages already scheduled in this conversation so they can be cancelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleMessageDialog(
    conversationId: ConversationId,
    controller: ScheduledMessagesController,
    messageText: String,
    subjectText: String,
    selfParticipantId: ParticipantId?,
    hasAttachments: Boolean,
    onScheduled: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val allScheduled by controller.messages.collectAsStateWithLifecycle()
    val scheduledHere = remember(allScheduled, conversationId) {
        allScheduled.filter { it.conversationId == conversationId }
    }

    var sendAtMillis by rememberSaveable { mutableStateOf(defaultSendAtMillis()) }
    var isDatePickerVisible by rememberSaveable { mutableStateOf(false) }
    var isTimePickerVisible by rememberSaveable { mutableStateOf(false) }

    val hasText = messageText.isNotBlank()
    val isInFuture = sendAtMillis > System.currentTimeMillis()
    val canSchedule = hasText && !hasAttachments && isInFuture

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.schedule_message_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DraftSummary(
                    messageText = messageText,
                    hasText = hasText,
                    hasAttachments = hasAttachments,
                )

                if (hasText && !hasAttachments) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { isDatePickerVisible = true },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CalendarMonth,
                                contentDescription = null
                            )
                            Text(
                                modifier = Modifier.padding(start = 8.dp),
                                text = DateUtils.formatDateTime(
                                    context,
                                    sendAtMillis,
                                    DateUtils.FORMAT_SHOW_DATE or
                                        DateUtils.FORMAT_ABBREV_ALL or
                                        DateUtils.FORMAT_SHOW_WEEKDAY,
                                ),
                                maxLines = 1,
                            )
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { isTimePickerVisible = true },
                        ) {
                            Icon(imageVector = Icons.Rounded.Schedule, contentDescription = null)
                            Text(
                                modifier = Modifier.padding(start = 8.dp),
                                text = DateUtils.formatDateTime(
                                    context,
                                    sendAtMillis,
                                    DateUtils.FORMAT_SHOW_TIME,
                                ),
                                maxLines = 1,
                            )
                        }
                    }

                    if (!isInFuture) {
                        Text(
                            text = stringResource(R.string.schedule_message_time_in_past),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                HorizontalDivider()

                ScheduledList(
                    messages = scheduledHere,
                    onCancel = controller::cancel,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSchedule,
                onClick = {
                    controller.schedule(
                        conversationId = conversationId,
                        selfParticipantId = selfParticipantId,
                        messageText = messageText,
                        subjectText = subjectText,
                        sendAtMillis = sendAtMillis,
                    )
                    onScheduled()
                },
            ) {
                Text(text = stringResource(R.string.schedule_message_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )

    if (isDatePickerVisible) {
        ScheduleDatePicker(
            currentMillis = sendAtMillis,
            onConfirm = { date ->
                sendAtMillis = combine(date = date, time = toLocalTime(sendAtMillis))
                isDatePickerVisible = false
            },
            onDismiss = { isDatePickerVisible = false },
        )
    }

    if (isTimePickerVisible) {
        ScheduleTimePicker(
            currentMillis = sendAtMillis,
            is24Hour = DateFormat.is24HourFormat(context),
            onConfirm = { time ->
                sendAtMillis = combine(date = toLocalDate(sendAtMillis), time = time)
                isTimePickerVisible = false
            },
            onDismiss = { isTimePickerVisible = false },
        )
    }
}

@Composable
private fun DraftSummary(
    messageText: String,
    hasText: Boolean,
    hasAttachments: Boolean,
) {
    when {
        hasAttachments -> {
            Text(
                text = stringResource(R.string.schedule_message_attachments_unsupported),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        !hasText -> {
            Text(
                text = stringResource(R.string.schedule_message_empty_draft),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        else -> {
            Text(
                text = messageText,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScheduledList(
    messages: List<ScheduledMessage>,
    onCancel: (String) -> Unit,
) {
    val context = LocalContext.current

    Text(
        text = stringResource(R.string.schedule_message_scheduled_header),
        style = MaterialTheme.typography.titleSmall,
    )

    if (messages.isEmpty()) {
        Text(
            text = stringResource(R.string.schedule_message_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    messages.forEach { message ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = DateUtils.formatDateTime(
                        context,
                        message.sendAtMillis,
                        DateUtils.FORMAT_SHOW_DATE or
                            DateUtils.FORMAT_SHOW_TIME or
                            DateUtils.FORMAT_SHOW_WEEKDAY or
                            DateUtils.FORMAT_ABBREV_ALL,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = message.messageText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onCancel(message.id) }) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.schedule_message_cancel),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDatePicker(
    currentMillis: Long,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    // DatePicker works in UTC-midnight millis for the selected calendar day.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = toLocalDate(currentMillis)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return !day.isBefore(today)
            }

            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        },
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onConfirm(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    }
                },
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTimePicker(
    currentMillis: Long,
    is24Hour: Boolean,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = toLocalTime(currentMillis)
    val state = rememberTimePickerState(
        initialHour = current.hour,
        initialMinute = current.minute,
        is24Hour = is24Hour,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.schedule_message_pick_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

/** One hour from now, rounded up to the next quarter hour. */
private fun defaultSendAtMillis(): Long {
    val inAnHour = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.MINUTES)
    val roundUp = (15 - inAnHour.minute % 15) % 15
    return inAnHour.plusMinutes(roundUp.toLong())
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

private fun toLocalDate(millis: Long): LocalDate {
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun toLocalTime(millis: Long): LocalTime {
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .truncatedTo(ChronoUnit.MINUTES)
}

private fun combine(date: LocalDate, time: LocalTime): Long {
    return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
