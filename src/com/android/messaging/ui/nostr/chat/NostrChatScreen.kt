package com.android.messaging.ui.nostr.chat

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.android.messaging.nostr.NostrMessage
import com.android.messaging.nostr.NostrSignerProvider
import com.android.messaging.ui.nostr.NostrSignerForegroundHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NostrChatScreen(
    peerPubKey: String,
    viewModel: NostrChatViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(peerPubKey) { viewModel.bind(peerPubKey) }

    // While this chat is on screen, its messages don't raise notifications.
    DisposableEffect(peerPubKey) {
        viewModel.bind(peerPubKey)
        viewModel.setActive(true)
        onDispose { viewModel.setActive(false) }
    }

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    NostrSignerForegroundHost(signer = remember(account) { viewModel.signer() })

    // Viewing the chat marks it read, including messages that arrive while it's open.
    LaunchedEffect(messages.size) {
        viewModel.markRead()
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    val shortNpub = remember(peerPubKey) { NostrSignerProvider.shortNpub(peerPubKey) }
    val title = profile?.name ?: shortNpub

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                title = {
                    Column {
                        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = stringResource(R.string.nostr_chat_subtitle, shortNpub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NostrComposer(
                text = draft,
                enabled = account != null,
                onTextChange = { draft = it },
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
            )
        },
    ) { contentPadding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.nostr_chat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = messages.asReversed(), key = { it.id }) { message ->
                    NostrMessageBubble(
                        message = message,
                        onRetry = { viewModel.retry(message) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NostrMessageBubble(
    message: NostrMessage,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val isFailed = message.status == NostrMessage.Status.Failed

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .then(if (isFailed) Modifier.clickable(onClick = onRetry) else Modifier),
                shape = RoundedCornerShape(18.dp),
                color = when {
                    message.isOutgoing -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = when {
                    message.isOutgoing -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                text = when (message.status) {
                    NostrMessage.Status.Sending -> stringResource(R.string.nostr_status_sending)
                    NostrMessage.Status.Failed -> stringResource(R.string.nostr_status_failed)
                    NostrMessage.Status.Sent -> DateUtils.formatDateTime(
                        context,
                        message.createdAtSeconds * 1000,
                        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isFailed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun NostrComposer(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                placeholder = {
                    Text(
                        text = stringResource(
                            when {
                                enabled -> R.string.nostr_composer_hint
                                else -> R.string.nostr_composer_signed_out
                            },
                        ),
                    )
                },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
            )
            FilledIconButton(
                enabled = enabled && text.isNotBlank(),
                onClick = onSend,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(R.string.sendButtonContentDescription),
                )
            }
        }
    }
}
