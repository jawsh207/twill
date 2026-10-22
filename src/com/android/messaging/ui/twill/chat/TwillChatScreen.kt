package com.android.messaging.ui.twill.chat

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.twill.TwillAttachment
import com.android.messaging.twill.TwillChatDetails
import com.android.messaging.twill.TwillError
import com.android.messaging.twill.TwillImages
import com.android.messaging.twill.TwillMember
import com.android.messaging.twill.TwillMessage
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.TwillProtocolText
import com.android.messaging.ui.nostr.NostrForegroundHost
import com.android.messaging.ui.twill.TwillAvatarImage
import com.android.messaging.ui.twill.search.LocalTwillSearch
import com.android.messaging.ui.twill.search.TwillSearchAction
import com.android.messaging.ui.twill.search.TwillSearchScrollEffect
import com.android.messaging.ui.twill.search.TwillSearchTopBar
import com.android.messaging.ui.twill.search.rememberTwillConversationSearch
import com.android.messaging.ui.twill.search.withTwillSearchHighlight
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TwillChatScreen(
    protocol: TwillProtocolId,
    protocolText: TwillProtocolText,
    chatId: String,
    viewModel: TwillChatViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var isInfoDialogVisible by rememberSaveable { mutableStateOf(false) }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<TwillMessage?>(null) }
    val listState = rememberLazyListState()
    val search = rememberTwillConversationSearch(key = protocol to chatId) { query ->
        viewModel.search(query)
    }

    if (protocol == TwillProtocolId.Nostr) NostrForegroundHost()

    // While this chat is on screen, its messages don't raise notifications.
    DisposableEffect(protocol, chatId) {
        viewModel.bind(protocol, chatId)
        viewModel.setActive(true)
        onDispose { viewModel.setActive(false) }
    }

    // Near the oldest loaded message (the top, in this reversed list), load an older page.
    val isNearOldest by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - LOAD_OLDER_THRESHOLD
        }
    }
    LaunchedEffect(isNearOldest) {
        if (isNearOldest) viewModel.loadOlder()
    }

    // Viewing the chat marks it read, including messages that arrive while it's open.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (details?.isRequest != true) viewModel.markRead()
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { error ->
            val text = when (error) {
                TwillError.UploadFailed -> protocolText.uploadFailed
                TwillError.FileTooLarge -> R.string.nostr_file_too_large
                TwillError.DownloadFailed -> R.string.nostr_download_failed
                TwillError.SendFailed -> R.string.twill_send_failed
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::sendFile) }
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::sendFile) }
    val saveFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val path = pendingSave?.attachment?.localPath
        pendingSave = null
        if (uri != null && path != null) copyToUri(context, File(path), uri)
    }

    val info = details
    val title = info?.title.orEmpty()
    val isGroup = info?.isGroup == true

    CompositionLocalProvider(LocalTwillSearch provides search.takeIf { it.isActive }) {
        Scaffold(
            modifier = modifier,
            topBar = {
                if (search.isActive) {
                    TwillSearchTopBar(search = search)
                } else {
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
                            if (info != null) {
                                ChatTitle(
                                    details = info,
                                    protocol = protocol,
                                    onClick = { isInfoDialogVisible = true },
                                )
                            }
                        },
                        actions = {
                            if (messages.isNotEmpty()) TwillSearchAction(onClick = search::open)
                        },
                    )
                }
            },
            bottomBar = {
                when {
                    info?.isRequest == true -> RequestBar(
                        onAccept = viewModel::accept,
                        onBlock = {
                            viewModel.block()
                            onNavigateBack()
                        },
                    )

                    else -> Composer(
                        text = draft,
                        enabled = info?.canSend == true,
                        hint = protocolText.composerHint,
                        disabledHint = protocolText.composerSignedOut,
                        onTextChange = { draft = it },
                        onSend = {
                            viewModel.send(draft)
                            draft = ""
                        },
                        onPickPhoto = {
                            pickPhoto.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                ),
                            )
                        },
                        onPickFile = { pickFile.launch(arrayOf("*/*")) },
                    )
                }
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
                        text = stringResource(
                            when {
                                info?.isEncrypted == false -> protocolText.emptyChatUnencrypted
                                else -> protocolText.emptyChat
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val ordered = messages.asReversed()
                val members = info?.members.orEmpty()
                TwillSearchScrollEffect(
                    messageIds = messages.map { it.id },
                    listState = listState,
                    onLoadOlder = viewModel::ensureLoaded,
                )
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
                    items(items = ordered.indices.toList(), key = { ordered[it].id }) { index ->
                        val message = ordered[index]
                        if (message.isInfo) {
                            InfoMessage(message.text)
                            return@items
                        }
                        // Reverse layout: the next item in the list is the earlier message.
                        val earlier = ordered.getOrNull(index + 1)
                        val showSender = isGroup &&
                            !message.isOutgoing &&
                            (
                                earlier == null ||
                                    earlier.isInfo ||
                                    earlier.senderId != message.senderId
                                )
                        val sender = members[message.senderId]
                        MessageBubble(
                            message = message,
                            protocol = protocol,
                            sender = if (showSender) {
                                sender ?: TwillMember(
                                    message.senderId,
                                    "?",
                                    null
                                )
                            } else {
                                null
                            },
                            isDownloading = message.id in downloading,
                            onRetry = { viewModel.retry(message) },
                            onDownload = { viewModel.download(message) },
                            onOpenImage = { path -> viewerPath = path },
                            onSaveFile = {
                                pendingSave = message
                                val attachment = message.attachment
                                saveFile.launch(
                                    attachment?.fileName
                                        ?: ("file." + extensionOf(attachment?.mimeType)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (isInfoDialogVisible && info != null) {
        ChatInfoDialog(
            details = info,
            protocol = protocol,
            renameHelp = protocolText.renameHelp,
            onRename = viewModel::rename,
            onDismiss = { isInfoDialogVisible = false },
        )
    }

    viewerPath?.let { path -> ImageViewer(path = path, onDismiss = { viewerPath = null }) }
}

@Composable
private fun ChatTitle(details: TwillChatDetails, protocol: TwillProtocolId, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TwillAvatarImage(avatar = details.avatar, protocol = protocol, size = 36.dp)
        Column {
            Text(text = details.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (details.isSubtitleVerified) {
                    Icon(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(14.dp),
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = stringResource(R.string.twill_verified),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = details.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun extensionOf(mimeType: String?): String =
    mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"

private fun TwillAttachment.aspectRatio(): Float {
    val w = width ?: return DEFAULT_PHOTO_RATIO
    val h = height ?: return DEFAULT_PHOTO_RATIO
    return if (w > 0 && h > 0) w.toFloat() / h else DEFAULT_PHOTO_RATIO
}

private const val DEFAULT_PHOTO_RATIO = 4f / 3f

/** Load older messages when this close to the oldest one shown. */
private const val LOAD_OLDER_THRESHOLD = 10

@Composable
private fun InfoMessage(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 4.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceBright,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageBubble(
    message: TwillMessage,
    protocol: TwillProtocolId,
    sender: TwillMember?,
    isDownloading: Boolean,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onOpenImage: (String) -> Unit,
    onSaveFile: () -> Unit,
) {
    val context = LocalContext.current
    val isFailed = message.status == TwillMessage.Status.Failed

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (sender != null) {
            TwillAvatarImage(
                modifier = Modifier.padding(end = 8.dp, bottom = 20.dp),
                avatar = sender.avatar,
                protocol = protocol,
                size = 28.dp,
            )
        }
        Column(
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
        ) {
            if (sender != null) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    text = sender.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Surface(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .then(if (isFailed) Modifier.clickable(onClick = onRetry) else Modifier),
                shape = RoundedCornerShape(18.dp),
                color = when {
                    message.isOutgoing -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceBright
                },
                contentColor = when {
                    message.isOutgoing -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
            ) {
                Column {
                    val attachment = message.attachment
                    when {
                        attachment == null -> Unit

                        attachment.isImage && attachment.isDownloaded -> DownloadedImage(
                            path = requireNotNull(attachment.localPath),
                            fallbackRatio = attachment.aspectRatio(),
                            onOpen = onOpenImage,
                        )

                        attachment.isImage -> AttachmentPlaceholder(
                            icon = Icons.Rounded.Image,
                            label = stringResource(R.string.nostr_tap_to_load_photo),
                            detail = attachment.size?.let {
                                Formatter.formatShortFileSize(context, it)
                            },
                            isBusy = isDownloading || message.status == TwillMessage.Status.Sending,
                            aspectRatio = attachment.aspectRatio(),
                            onClick = onDownload,
                        )

                        else -> FileRow(
                            name = attachment.fileName
                                ?: attachment.mimeType
                                ?: stringResource(R.string.nostr_file),
                            detail = attachment.size?.let {
                                Formatter.formatShortFileSize(context, it)
                            },
                            isDownloaded = attachment.isDownloaded,
                            isBusy = isDownloading,
                            onClick = if (attachment.isDownloaded) onSaveFile else onDownload,
                        )
                    }
                    if (message.text.isNotEmpty()) {
                        Text(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            text = message.text.withTwillSearchHighlight(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Text(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                text = statusLine(context, message),
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
private fun statusLine(context: Context, message: TwillMessage): String {
    val time = DateUtils.formatDateTime(
        context,
        message.timeMillis,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
    )
    return when (message.status) {
        TwillMessage.Status.Sending -> stringResource(R.string.nostr_status_sending)
        TwillMessage.Status.Failed -> stringResource(R.string.nostr_status_failed)
        TwillMessage.Status.Sent -> time
        TwillMessage.Status.Delivered -> "$time ✓"
        TwillMessage.Status.Read -> "$time ✓✓"
    }
}

@Composable
private fun DownloadedImage(
    path: String,
    fallbackRatio: Float,
    onOpen: (String) -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = TwillImages.loadPreview(path)?.asImageBitmap()
    }
    val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: fallbackRatio
    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(ratio.coerceIn(0.5f, 2f))
            .clickable { onOpen(path) },
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.nostr_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AttachmentPlaceholder(
    icon: ImageVector,
    label: String,
    detail: String?,
    isBusy: Boolean,
    aspectRatio: Float,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(aspectRatio.coerceIn(0.5f, 2f))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = !isBusy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            } else {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(32.dp))
                Text(
                    modifier = Modifier.padding(top = 6.dp),
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                )
                detail?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    name: String,
    detail: String?,
    isDownloaded: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(enabled = !isBusy, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null)
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    detail,
                    stringResource(
                        when {
                            isDownloaded -> R.string.nostr_tap_to_save
                            else -> R.string.nostr_tap_to_download
                        },
                    ),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        when {
            isBusy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            !isDownloaded -> Icon(imageVector = Icons.Rounded.Download, contentDescription = null)
        }
    }
}

@Composable
private fun ImageViewer(path: String, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = TwillImages.loadPreview(path, maxSide = TwillImages.VIEWER_MAX_SIDE)
            ?.asImageBitmap()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.nostr_photo),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ChatInfoDialog(
    details: TwillChatDetails,
    protocol: TwillProtocolId,
    renameHelp: Int?,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val groupName = if (details.isGroup) details.title else ""
    var name by rememberSaveable(groupName) { mutableStateOf(groupName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (details.isGroup) R.string.nostr_group_info else R.string.nostr_contact_info,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (details.canRename) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.inbox_group_name_hint)) },
                        supportingText = renameHelp?.let { help ->
                            { Text(text = stringResource(help)) }
                        },
                    )
                }
                if (details.isGroup) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(
                            R.string.nostr_group_members,
                            details.members.size + 1,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                details.members.values.forEach { member ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TwillAvatarImage(avatar = member.avatar, protocol = protocol, size = 40.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = member.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            member.detail?.let { detail ->
                                Text(
                                    text = if (member.isVerified) "✓ $detail" else detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (details.isGroup) {
                    Text(
                        text = stringResource(R.string.nostr_group_you),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (details.canRename && name.trim() != groupName && name.isNotBlank()) {
                        onRename(name.trim())
                    }
                    onDismiss()
                },
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun RequestBar(onAccept: () -> Unit, onBlock: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.twill_request_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = onBlock) {
                    Text(text = stringResource(R.string.twill_request_block))
                }
                Button(modifier = Modifier.weight(1f), onClick = onAccept) {
                    Text(text = stringResource(R.string.twill_request_accept))
                }
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    enabled: Boolean,
    hint: Int,
    disabledHint: Int,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    var isAttachMenuExpanded by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                IconButton(enabled = enabled, onClick = { isAttachMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.AttachFile,
                        contentDescription = stringResource(R.string.nostr_attach),
                    )
                }
                DropdownMenu(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                    expanded = isAttachMenuExpanded,
                    onDismissRequest = { isAttachMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.nostr_attach_photo)) },
                        leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                        onClick = {
                            isAttachMenuExpanded = false
                            onPickPhoto()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.nostr_attach_file)) },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            isAttachMenuExpanded = false
                            onPickFile()
                        },
                    )
                }
            }
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = text,
                onValueChange = onTextChange,
                enabled = enabled,
                placeholder = {
                    Text(
                        text = stringResource(
                            if (enabled) hint else disabledHint,
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

private fun copyToUri(context: Context, source: File, target: Uri) {
    runCatching {
        context.contentResolver.openOutputStream(target)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
    }.onSuccess {
        Toast.makeText(context, R.string.nostr_file_saved, Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, R.string.nostr_file_save_failed, Toast.LENGTH_LONG).show()
    }
}
