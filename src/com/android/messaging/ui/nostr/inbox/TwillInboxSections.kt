package com.android.messaging.ui.nostr.inbox

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.nostr.NostrConversation
import com.android.messaging.nostr.NostrSignerProvider

/**
 * Twill's protocol sections for the conversation list. When Nostr is signed in, the list
 * shows a collapsible "SMS" section (upstream rows) and a collapsible "Nostr" section.
 */
internal class ConversationListSections(
    val isSmsCollapsed: Boolean,
    val smsHeader: @Composable () -> Unit,
    val trailingItems: LazyListScope.() -> Unit,
)

/** Builds the sections from the inbox state, or null when Nostr is off. */
@Composable
internal fun rememberConversationListSections(
    inbox: TwillInbox?,
    state: TwillInboxUiState,
    smsCount: Int,
): ConversationListSections? {
    if (inbox == null || !state.isNostrEnabled) return null

    var isNewChatDialogVisible by rememberSaveable { mutableStateOf(false) }

    if (isNewChatDialogVisible) {
        NewNostrChatDialog(
            resolvePeer = inbox.controller::resolvePeer,
            onStart = { peer ->
                isNewChatDialogVisible = false
                inbox.onOpenNostrChat(peer)
            },
            onDismiss = { isNewChatDialogVisible = false },
        )
    }

    return ConversationListSections(
        isSmsCollapsed = state.isSmsCollapsed,
        smsHeader = {
            InboxSectionHeader(
                title = stringResource(R.string.inbox_section_sms),
                count = smsCount,
                isCollapsed = state.isSmsCollapsed,
                onToggle = inbox.controller::toggleSmsCollapsed,
            )
        },
        trailingItems = {
            item(key = "twill_nostr_header", contentType = "twill_section_header") {
                InboxSectionHeader(
                    title = stringResource(R.string.inbox_section_nostr),
                    count = state.nostrConversations.size,
                    isCollapsed = state.isNostrCollapsed,
                    onToggle = inbox.controller::toggleNostrCollapsed,
                    onAdd = { isNewChatDialogVisible = true },
                )
            }
            if (!state.isNostrCollapsed) {
                if (state.nostrConversations.isEmpty()) {
                    item(key = "twill_nostr_empty") {
                        Text(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            text = stringResource(R.string.inbox_nostr_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(
                    items = state.nostrConversations,
                    key = { "twill_nostr:" + it.peerPubKey },
                    contentType = { "twill_nostr_row" },
                ) { conversation ->
                    NostrConversationRow(
                        conversation = conversation,
                        controller = inbox.controller,
                        onOpen = { inbox.onOpenNostrChat(conversation.peerPubKey) },
                    )
                }
            }
        },
    )
}

@Composable
private fun InboxSectionHeader(
    title: String,
    count: Int,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    onAdd: (() -> Unit)? = null,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isCollapsed) -90f else 0f,
        label = "inbox_section_chevron",
    )
    val stateLabel = stringResource(
        if (isCollapsed) R.string.inbox_section_expand else R.string.inbox_section_collapse,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stateLabel, role = Role.Button, onClick = onToggle)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.rotate(rotation),
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
            text = "$title · $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.inbox_new_nostr_chat),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NostrConversationRow(
    conversation: NostrConversation,
    controller: TwillInboxController,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    var isMenuExpanded by remember { mutableStateOf(false) }
    val isUnread = conversation.unreadCount > 0
    val name = conversation.displayName
        ?: remember(conversation.peerPubKey) {
            NostrSignerProvider.shortNpub(conversation.peerPubKey)
        }
    val snippet = buildString {
        if (conversation.lastMessage.isOutgoing) append(stringResource(R.string.inbox_you_prefix))
        append(conversation.lastMessage.content.replace('\n', ' '))
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { isMenuExpanded = true },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conversation.isPinned) {
                        Icon(
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(16.dp),
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = stringResource(R.string.inbox_pinned),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = DateUtils.getRelativeTimeSpanString(
                            conversation.lastMessage.createdAtSeconds * 1000,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_ALL,
                        ).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isUnread) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isUnread) {
                        Badge(modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = conversation.unreadCount.toString())
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = isMenuExpanded,
            onDismissRequest = { isMenuExpanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(
                            if (conversation.isPinned) R.string.inbox_unpin else R.string.inbox_pin,
                        ),
                    )
                },
                onClick = {
                    isMenuExpanded = false
                    controller.setPinned(conversation.peerPubKey, !conversation.isPinned)
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(
                            if (isUnread) R.string.inbox_mark_read else R.string.inbox_mark_unread,
                        ),
                    )
                },
                onClick = {
                    isMenuExpanded = false
                    controller.setRead(conversation.peerPubKey, isUnread)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.inbox_delete_conversation)) },
                onClick = {
                    isMenuExpanded = false
                    controller.delete(conversation.peerPubKey)
                },
            )
        }
    }
}

@Composable
private fun NewNostrChatDialog(
    resolvePeer: (String) -> String?,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var isInvalid by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.inbox_new_nostr_chat)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = input,
                onValueChange = {
                    input = it
                    isInvalid = false
                },
                singleLine = true,
                label = { Text(text = stringResource(R.string.inbox_new_nostr_chat_hint)) },
                isError = isInvalid,
                supportingText = if (isInvalid) {
                    { Text(text = stringResource(R.string.inbox_new_nostr_chat_invalid)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = {
                    val peer = resolvePeer(input)
                    if (peer == null) isInvalid = true else onStart(peer)
                },
            ) {
                Text(text = stringResource(R.string.inbox_start_chat))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
