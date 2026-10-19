package com.android.messaging.ui.twill.inbox

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.twill.TwillChat
import com.android.messaging.twill.TwillGroupResult
import com.android.messaging.twill.TwillMember
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.TwillProtocolText
import com.android.messaging.ui.nostr.NostrForegroundHost
import com.android.messaging.ui.twill.TwillAvatarImage
import kotlinx.coroutines.launch

/**
 * Twill's protocol sections for the conversation list. When any protocol is set up, the list
 * shows a collapsible "SMS" section (upstream rows) and one collapsible section per protocol.
 */
internal class ConversationListSections(
    val isSmsCollapsed: Boolean,
    /** SMS is off: no SMS header or rows, whatever the SMS list contains. */
    val isSmsHidden: Boolean,
    val smsHeader: @Composable () -> Unit,
    val trailingItems: LazyListScope.() -> Unit,
)

private sealed interface NewChatDialog {
    data object None : NewChatDialog

    data class Chat(
        val section: TwillInboxSection,
    ) : NewChatDialog

    data class Group(
        val section: TwillInboxSection,
    ) : NewChatDialog
}

/**
 * Builds the sections from the inbox state, or null for the plain upstream SMS list (SMS on
 * and no other protocol set up).
 */
@Composable
internal fun rememberConversationListSections(
    inbox: TwillInbox?,
    state: TwillInboxUiState,
    smsCount: Int,
): ConversationListSections? {
    if (inbox == null || (state.isSmsEnabled && state.sections.isEmpty())) return null

    var dialog by remember { mutableStateOf<NewChatDialog>(NewChatDialog.None) }
    val controller = inbox.controller

    // The new-message button (with SMS off, or long pressed): choose where to start the chat.
    if (inbox.isNewChatPickerVisible.value) {
        NewChatPicker(
            isSmsEnabled = state.isSmsEnabled,
            sections = state.sections,
            onNewSms = {
                inbox.isNewChatPickerVisible.value = false
                inbox.onNewSms()
            },
            onPick = { section, isGroup ->
                inbox.isNewChatPickerVisible.value = false
                dialog = if (isGroup) NewChatDialog.Group(section) else NewChatDialog.Chat(section)
            },
            onOpenSettings = {
                inbox.isNewChatPickerVisible.value = false
                inbox.onOpenSettings()
            },
            onDismiss = { inbox.isNewChatPickerVisible.value = false },
        )
    }

    if (state.sections.any { it.protocol == TwillProtocolId.Nostr }) NostrForegroundHost()

    when (val current = dialog) {
        NewChatDialog.None -> Unit

        is NewChatDialog.Chat -> NewChatDialogContent(
            text = current.section.text,
            startChat = { input -> controller.startChat(current.section.protocol, input) },
            onStarted = { chatId ->
                dialog = NewChatDialog.None
                inbox.onOpenChat(current.section.protocol, chatId)
            },
            onDismiss = { dialog = NewChatDialog.None },
        )

        is NewChatDialog.Group -> NewGroupDialogContent(
            protocol = current.section.protocol,
            text = current.section.text,
            loadContacts = { controller.knownContacts(current.section.protocol) },
            createGroup = { name, ids, typed ->
                controller.createGroup(current.section.protocol, name, ids, typed)
            },
            onCreated = { chatId ->
                dialog = NewChatDialog.None
                inbox.onOpenChat(current.section.protocol, chatId)
            },
            onDismiss = { dialog = NewChatDialog.None },
        )
    }

    return ConversationListSections(
        isSmsCollapsed = state.isSmsCollapsed,
        isSmsHidden = !state.isSmsEnabled,
        smsHeader = {
            InboxSectionHeader(
                title = stringResource(R.string.inbox_section_sms),
                count = smsCount,
                isCollapsed = state.isSmsCollapsed,
                onToggle = controller::toggleSmsCollapsed,
            )
        },
        trailingItems = {
            if (state.sections.isEmpty()) {
                item(key = "twill_setup_hint") {
                    SetupHint(onOpenSettings = inbox.onOpenSettings)
                }
            }
            state.sections.forEach { section ->
                protocolSection(
                    section = section,
                    controller = controller,
                    onOpen = { chat -> inbox.onOpenChat(chat.protocol, chat.chatId) },
                    onNewChat = { dialog = NewChatDialog.Chat(section) },
                    onNewGroup = { dialog = NewChatDialog.Group(section) },
                )
            }
        },
    )
}

/** Shown when SMS is off and no other protocol is set up yet. */
@Composable
private fun SetupHint(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.twill_setup_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenSettings) {
            Text(text = stringResource(R.string.twill_open_settings))
        }
    }
}

/**
 * Where to start a new chat: SMS/MMS (when on) and each set-up service. Picking a service
 * then asks what kind of chat to start (one-to-one or group).
 */
@Composable
private fun NewChatPicker(
    isSmsEnabled: Boolean,
    sections: List<TwillInboxSection>,
    onNewSms: () -> Unit,
    onPick: (TwillInboxSection, isGroup: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<TwillInboxSection?>(null) }
    val service = picked

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (service) {
                    null -> stringResource(R.string.inbox_new_chat)
                    else -> stringResource(
                        R.string.inbox_new_conversation,
                        stringResource(service.text.sectionTitle),
                    )
                },
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (service != null) {
                    PickerRow(
                        icon = rememberVectorPainter(Icons.Rounded.Person),
                        label = stringResource(R.string.inbox_new_chat),
                        onClick = { onPick(service, false) },
                    )
                    PickerRow(
                        icon = rememberVectorPainter(Icons.Rounded.Group),
                        label = stringResource(R.string.inbox_new_group),
                        onClick = { onPick(service, true) },
                    )
                } else {
                    if (sections.isEmpty()) {
                        Text(text = stringResource(R.string.twill_setup_hint))
                    }
                    if (isSmsEnabled) {
                        PickerRow(
                            icon = painterResource(id = R.drawable.ic_notification_sms),
                            label = stringResource(R.string.inbox_new_sms),
                            onClick = onNewSms,
                        )
                    }
                    // Each service with its notification icon.
                    sections.forEach { section ->
                        PickerRow(
                            icon = painterResource(id = section.text.notificationIcon),
                            label = stringResource(
                                R.string.inbox_picker_service,
                                stringResource(section.text.sectionTitle),
                            ),
                            onClick = { picked = section },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (service == null && sections.isEmpty()) {
                TextButton(onClick = onOpenSettings) {
                    Text(text = stringResource(R.string.twill_open_settings))
                }
            }
        },
        dismissButton = {
            if (service != null) {
                TextButton(onClick = { picked = null }) {
                    Text(text = stringResource(R.string.back))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

/** One choice in [NewChatPicker]; icons use the text colour, like the SMS one. */
@Composable
private fun PickerRow(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painter = icon, contentDescription = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun LazyListScope.protocolSection(
    section: TwillInboxSection,
    controller: TwillInboxController,
    onOpen: (TwillChat) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
) {
    val key = section.protocol.key
    item(key = "twill_${key}_header", contentType = "twill_section_header") {
        InboxSectionHeader(
            title = stringResource(section.text.sectionTitle),
            count = section.chats.size,
            isCollapsed = section.isCollapsed,
            onToggle = { controller.toggleCollapsed(section.protocol) },
            addMenu = { onDone ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.inbox_new_chat)) },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    onClick = {
                        onDone()
                        onNewChat()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.inbox_new_group)) },
                    leadingIcon = { Icon(Icons.Rounded.Group, contentDescription = null) },
                    onClick = {
                        onDone()
                        onNewGroup()
                    },
                )
            },
        )
    }
    if (section.isCollapsed) return
    if (section.chats.isEmpty()) {
        item(key = "twill_${key}_empty") {
            Text(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                text = stringResource(section.text.emptySection),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(
        items = section.chats,
        key = { "twill_$key:" + it.chatId },
        contentType = { "twill_chat_row" },
    ) { chat ->
        ChatRow(chat = chat, controller = controller, onOpen = { onOpen(chat) })
    }
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
                        contentDescription = stringResource(R.string.inbox_new_conversation, title),
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
private fun ChatRow(
    chat: TwillChat,
    controller: TwillInboxController,
    onOpen: () -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    val isUnread = chat.unreadCount > 0
    val last = chat.lastMessage
    val attachment = last?.attachment
    val body = when {
        last == null -> ""
        last.text.isNotEmpty() -> last.text.replace('\n', ' ')
        attachment?.isImage == true -> stringResource(R.string.nostr_photo)
        attachment != null -> stringResource(R.string.nostr_file)
        else -> ""
    }
    val prefix = when {
        chat.isRequest -> stringResource(R.string.inbox_request_prefix)
        last?.isOutgoing == true -> stringResource(R.string.inbox_you_prefix)
        chat.lastSenderName != null -> chat.lastSenderName + ": "
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
            TwillAvatarImage(avatar = chat.avatar, protocol = chat.protocol)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chat.isPinned) {
                        Icon(
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(16.dp),
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = stringResource(R.string.inbox_pinned),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (last != null) {
                        Text(
                            modifier = Modifier.padding(start = 8.dp),
                            text = DateUtils.getRelativeTimeSpanString(
                                last.timeMillis,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_ALL,
                            ).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                            Text(text = chat.unreadCount.toString())
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
                            if (chat.isPinned) R.string.inbox_unpin else R.string.inbox_pin,
                        ),
                    )
                },
                onClick = {
                    isMenuExpanded = false
                    controller.setPinned(chat, !chat.isPinned)
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
                    controller.setRead(chat, isUnread)
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.inbox_delete_conversation)) },
                onClick = {
                    isMenuExpanded = false
                    controller.delete(chat)
                },
            )
        }
    }
}

@Composable
private fun NewChatDialogContent(
    text: TwillProtocolText,
    startChat: suspend (String) -> String?,
    onStarted: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var isInvalid by rememberSaveable { mutableStateOf(false) }
    var isLooking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(text.newChatTitle)) },
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
                label = { Text(text = stringResource(text.newChatHint)) },
                isError = isInvalid,
                supportingText = if (isInvalid) {
                    { Text(text = stringResource(text.newChatInvalid)) }
                } else {
                    null
                },
                trailingIcon = if (isLooking) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
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
                        val chatId = startChat(input.trim())
                        isLooking = false
                        if (chatId == null) isInvalid = true else onStarted(chatId)
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
private fun NewGroupDialogContent(
    protocol: TwillProtocolId,
    text: TwillProtocolText,
    loadContacts: suspend () -> List<TwillMember>,
    createGroup: suspend (String, List<String>, List<String>) -> TwillGroupResult,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var membersInput by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var invalidEntries by remember { mutableStateOf<List<String>>(emptyList()) }
    var isWorking by remember { mutableStateOf(false) }
    val contacts by produceState<List<TwillMember>?>(initialValue = null) {
        value = loadContacts()
    }

    val typed = if (text.canTypeGroupMembers) {
        membersInput.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    } else {
        emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.inbox_new_group)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    enabled = !isWorking,
                    label = { Text(text = stringResource(R.string.inbox_group_name_hint)) },
                )
                val list = contacts
                when {
                    list == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))

                    list.isEmpty() && !text.canTypeGroupMembers -> Text(
                        text = stringResource(R.string.inbox_group_no_contacts),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> list.forEach { member ->
                        val isChecked = member.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isWorking) {
                                    selected =
                                        if (isChecked) {
                                            selected - member.id
                                        } else {
                                            selected +
                                                member.id
                                        }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = null)
                            TwillAvatarImage(
                                avatar = member.avatar,
                                protocol = protocol,
                                size = 32.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = member.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                member.detail?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                if (text.canTypeGroupMembers) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = membersInput,
                        onValueChange = {
                            membersInput = it
                            invalidEntries = emptyList()
                        },
                        enabled = !isWorking,
                        minLines = 2,
                        label = { Text(text = stringResource(text.groupMembersHint)) },
                        isError = invalidEntries.isNotEmpty(),
                        supportingText = {
                            Text(
                                text = when {
                                    invalidEntries.isNotEmpty() -> stringResource(
                                        R.string.inbox_group_members_invalid,
                                        invalidEntries.joinToString(", "),
                                    )

                                    else -> stringResource(text.groupMembersHelp)
                                },
                            )
                        },
                    )
                }
                if (selected.isNotEmpty() || typed.isNotEmpty()) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = stringResource(
                                    R.string.inbox_group_member_count,
                                    selected.size + typed.size,
                                ),
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (selected.isNotEmpty() || typed.isNotEmpty()) && !isWorking,
                onClick = {
                    isWorking = true
                    scope.launch {
                        val result = createGroup(name.trim(), selected.toList(), typed)
                        isWorking = false
                        when (result) {
                            is TwillGroupResult.Created -> onCreated(result.chatId)
                            is TwillGroupResult.Invalid -> invalidEntries = result.entries
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
