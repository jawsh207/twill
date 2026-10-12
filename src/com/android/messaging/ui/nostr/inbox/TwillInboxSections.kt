package com.android.messaging.ui.nostr.inbox

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.nostr.NostrChatKey
import com.android.messaging.nostr.NostrConversation
import com.android.messaging.nostr.NostrSignerProvider
import com.android.messaging.ui.nostr.NostrAvatar
import kotlinx.coroutines.launch

/**
 * Twill's protocol sections for the conversation list. When Nostr is signed in, the list
 * shows a collapsible "SMS" section (upstream rows) and a collapsible "Nostr" section.
 */
internal class ConversationListSections(
    val isSmsCollapsed: Boolean,
    val smsHeader: @Composable () -> Unit,
    val trailingItems: LazyListScope.() -> Unit,
)

private enum class NewChatDialog { None, Chat, Group }

/** A conversation's title: its name, or members' names, or short npubs. */
internal fun nostrConversationTitle(conversation: NostrConversation): String {
    conversation.displayName?.let { return it }
    val members = NostrChatKey.members(conversation.chatKey)
    val names = members.mapIndexed { index, pubKey ->
        conversation.memberNames.getOrNull(index) ?: NostrSignerProvider.shortNpub(pubKey)
    }
    return when {
        names.size <= 3 -> names.joinToString(", ")
        else -> names.take(3).joinToString(", ") + " +" + (names.size - 3)
    }
}

/** Builds the sections from the inbox state, or null when Nostr is off. */
@Composable
internal fun rememberConversationListSections(
    inbox: TwillInbox?,
    state: TwillInboxUiState,
    smsCount: Int,
): ConversationListSections? {
    if (inbox == null || !state.isNostrEnabled) return null

    var dialog by rememberSaveable { mutableStateOf(NewChatDialog.None) }

    when (dialog) {
        NewChatDialog.None -> Unit

        NewChatDialog.Chat -> NewNostrChatDialog(
            resolvePeer = inbox.controller::resolvePeer,
            onStart = { peer ->
                dialog = NewChatDialog.None
                inbox.onOpenNostrChat(peer)
            },
            onDismiss = { dialog = NewChatDialog.None },
        )

        NewChatDialog.Group -> NewNostrGroupDialog(
            resolvePeer = inbox.controller::resolvePeer,
            onCreate = { members, name ->
                inbox.controller.createGroup(members, name)?.let { chatKey ->
                    dialog = NewChatDialog.None
                    inbox.onOpenNostrChat(chatKey)
                }
            },
            onDismiss = { dialog = NewChatDialog.None },
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
                    addMenu = { onDone ->
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.inbox_new_nostr_chat)) },
                            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            onClick = {
                                onDone()
                                dialog = NewChatDialog.Chat
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.inbox_new_nostr_group)) },
                            leadingIcon = { Icon(Icons.Rounded.Group, contentDescription = null) },
                            onClick = {
                                onDone()
                                dialog = NewChatDialog.Group
                            },
                        )
                    },
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
                    key = { "twill_nostr:" + it.chatKey },
                    contentType = { "twill_nostr_row" },
                ) { conversation ->
                    NostrConversationRow(
                        conversation = conversation,
                        controller = inbox.controller,
                        onOpen = { inbox.onOpenNostrChat(conversation.chatKey) },
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
    addMenu: (@Composable (onDone: () -> Unit) -> Unit)? = null,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isCollapsed) -90f else 0f,
        label = "inbox_section_chevron",
    )
    val stateLabel = stringResource(
        if (isCollapsed) R.string.inbox_section_expand else R.string.inbox_section_collapse,
    )
    var isMenuExpanded by remember { mutableStateOf(false) }

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
        if (addMenu != null) {
            Box {
                IconButton(onClick = { isMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.inbox_new_nostr_conversation),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false },
                ) {
                    addMenu { isMenuExpanded = false }
                }
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
    var isMenuExpanded by remember { mutableStateOf(false) }
    val isUnread = conversation.unreadCount > 0
    val name = remember(conversation) { nostrConversationTitle(conversation) }
    val last = conversation.lastMessage
    val body = when {
        last.attachment?.isImage == true -> stringResource(R.string.nostr_photo)
        last.attachment != null -> stringResource(R.string.nostr_file)
        else -> last.content.replace('\n', ' ')
    }
    val senderIndex = NostrChatKey.members(conversation.chatKey).indexOf(last.senderPubKey)
    val prefix = when {
        last.isOutgoing -> stringResource(R.string.inbox_you_prefix)
        conversation.isGroup -> {
            val sender = conversation.memberNames.getOrNull(senderIndex)
                ?: NostrSignerProvider.shortNpub(last.senderPubKey)
            "$sender: "
        }

        else -> ""
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
            NostrAvatar(
                name = name,
                pictureUrl = conversation.pictureUrl,
                isGroup = conversation.isGroup,
            )
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
                            last.createdAtSeconds * 1000,
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
                        text = prefix + body,
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
                    controller.setPinned(conversation.chatKey, !conversation.isPinned)
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
                    controller.setRead(conversation.chatKey, isUnread)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.inbox_delete_conversation)) },
                onClick = {
                    isMenuExpanded = false
                    controller.delete(conversation.chatKey)
                },
            )
        }
    }
}

@Composable
private fun NewNostrChatDialog(
    resolvePeer: suspend (String) -> String?,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var isInvalid by rememberSaveable { mutableStateOf(false) }
    var isLooking by remember { mutableStateOf(false) }

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
                enabled = !isLooking,
                label = { Text(text = stringResource(R.string.inbox_new_nostr_chat_hint)) },
                isError = isInvalid,
                supportingText = if (isInvalid) {
                    { Text(text = stringResource(R.string.inbox_new_nostr_chat_invalid)) }
                } else {
                    null
                },
                trailingIcon = if (isLooking) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank() && !isLooking,
                onClick = {
                    isLooking = true
                    scope.launch {
                        val peer = resolvePeer(input)
                        isLooking = false
                        if (peer == null) isInvalid = true else onStart(peer)
                    }
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

@Composable
private fun NewNostrGroupDialog(
    resolvePeer: suspend (String) -> String?,
    onCreate: (members: List<String>, name: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var membersInput by rememberSaveable { mutableStateOf("") }
    var invalidEntries by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLooking by remember { mutableStateOf(false) }

    val entries = membersInput.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.inbox_new_nostr_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    enabled = !isLooking,
                    label = { Text(text = stringResource(R.string.inbox_group_name_hint)) },
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = membersInput,
                    onValueChange = {
                        membersInput = it
                        invalidEntries = emptyList()
                    },
                    enabled = !isLooking,
                    minLines = 3,
                    label = { Text(text = stringResource(R.string.inbox_group_members_hint)) },
                    isError = invalidEntries.isNotEmpty(),
                    supportingText = {
                        Text(
                            text = when {
                                invalidEntries.isNotEmpty() -> stringResource(
                                    R.string.inbox_group_members_invalid,
                                    invalidEntries.joinToString(", "),
                                )

                                else -> stringResource(R.string.inbox_group_members_help)
                            },
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = entries.size >= 2 && !isLooking,
                onClick = {
                    isLooking = true
                    scope.launch {
                        val resolved = entries.map { entry -> entry to resolvePeer(entry) }
                        isLooking = false
                        val invalid = resolved.filter { it.second == null }.map { it.first }
                        if (invalid.isNotEmpty()) {
                            invalidEntries = invalid
                        } else {
                            onCreate(resolved.mapNotNull { it.second }.distinct(), name.trim())
                        }
                    }
                },
            ) {
                Text(text = stringResource(R.string.inbox_create_group))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}
