package com.android.messaging.ui.twill.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.android.messaging.R

/**
 * Asks how to delete a message: from this phone only, or (for your own sent messages, where
 * the protocol allows it) for everyone in the chat.
 */
@Composable
internal fun TwillDeleteMessageDialog(
    canDeleteForEveryone: Boolean,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.twill_delete_message_title)) },
        text = {
            Text(
                stringResource(
                    if (canDeleteForEveryone) {
                        R.string.twill_delete_message_everyone_explained
                    } else {
                        R.string.twill_delete_message_me_explained
                    },
                ),
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canDeleteForEveryone) {
                    TextButton(onClick = onDeleteForEveryone) {
                        Text(stringResource(R.string.twill_delete_for_everyone))
                    }
                }
                TextButton(onClick = onDeleteForMe) {
                    Text(stringResource(R.string.twill_delete_for_me))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

/** The disappearing-message times offered, in seconds; 0 is off. */
private val DISAPPEARING_CHOICES = listOf(
    0L to R.string.twill_disappearing_off,
    5 * 60L to R.string.twill_disappearing_5_minutes,
    60 * 60L to R.string.twill_disappearing_1_hour,
    24 * 60 * 60L to R.string.twill_disappearing_1_day,
    7 * 24 * 60 * 60L to R.string.twill_disappearing_1_week,
    5 * 7 * 24 * 60 * 60L to R.string.twill_disappearing_5_weeks,
    365 * 24 * 60 * 60L to R.string.twill_disappearing_1_year,
)

/**
 * Picks how long new messages in the chat last. [isShared] says whether the protocol shares
 * the setting with everyone in the chat (Delta Chat) or only applies it to what you send
 * (Nostr), which the dialog explains.
 */
@Composable
internal fun TwillDisappearingDialog(
    current: Long,
    isShared: Boolean,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.twill_disappearing_messages)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(
                    text = stringResource(
                        if (isShared) {
                            R.string.twill_disappearing_explained_shared
                        } else {
                            R.string.twill_disappearing_explained_own
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val choices = DISAPPEARING_CHOICES.map { it.first }
                DISAPPEARING_CHOICES.forEach { (seconds, label) ->
                    // A timer set elsewhere to another length still shows as chosen.
                    val isSelected = seconds == current ||
                        (seconds == 0L && current !in choices && current <= 0)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                onClick = { onPick(seconds) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Text(
                            text = stringResource(label),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
