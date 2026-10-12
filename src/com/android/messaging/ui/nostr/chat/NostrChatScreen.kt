package com.android.messaging.ui.nostr.chat

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.nostr.NostrAttachment
import com.android.messaging.nostr.NostrChatKey
import com.android.messaging.nostr.NostrError
import com.android.messaging.nostr.NostrMedia
import com.android.messaging.nostr.NostrMessage
import com.android.messaging.nostr.NostrProfile
import com.android.messaging.nostr.NostrSignerProvider
import com.android.messaging.ui.nostr.NostrAvatar
import com.android.messaging.ui.nostr.NostrSignerForegroundHost
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NostrChatScreen(
    chatKey: String,
    viewModel: NostrChatViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var isInfoDialogVisible by rememberSaveable { mutableStateOf(false) }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<NostrMessage?>(null) }
    val listState = rememberLazyListState()

    val isGroup = NostrChatKey.isGroup(chatKey)
    val members = remember(chatKey) { NostrChatKey.members(chatKey) }

    NostrSignerForegroundHost(signer = remember(account) { viewModel.signer() })

    // While this chat is on screen, its messages don't raise notifications.
    DisposableEffect(chatKey) {
        viewModel.bind(chatKey)
        viewModel.setActive(true)
        onDispose { viewModel.setActive(false) }
    }

    // Viewing the chat marks it read, including messages that arrive while it's open.
    LaunchedEffect(messages.size) {
        viewModel.markRead()
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { error ->
            val text = when (error) {
                NostrError.UploadFailed -> R.string.nostr_upload_failed
                NostrError.FileTooLarge -> R.string.nostr_file_too_large
                NostrError.DownloadFailed -> R.string.nostr_download_failed
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

    val peerProfile = profiles[chatKey]
    val title = when {
        isGroup -> group?.name ?: membersTitle(members, profiles)
        else -> nameOf(chatKey, profiles)
    }
    val verifiedNip05 = peerProfile?.takeIf { !isGroup && it.isNip05Verified }?.nip05
    val subtitle = when {
        isGroup -> stringResource(R.string.nostr_group_subtitle, members.size + 1)
        verifiedNip05 != null -> verifiedNip05.removePrefix("_@")
        else -> stringResource(R.string.nostr_chat_subtitle, NostrSignerProvider.shortNpub(chatKey))
    }

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
                    Row(
                        modifier = Modifier.clickable { isInfoDialogVisible = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NostrAvatar(
                            name = title,
                            pictureUrl = if (isGroup) null else peerProfile?.picture,
                            isGroup = isGroup,
                            size = 36.dp,
                        )
                        Column {
                            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (verifiedNip05 != null) {
                                    Icon(
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .size(14.dp),
                                        imageVector = Icons.Rounded.Verified,
                                        contentDescription = stringResource(
                                            R.string.nostr_nip05_verified
                                        ),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
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
                onPickPhoto = {
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
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
            val ordered = messages.asReversed()
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
                    // Reverse layout: the next item in the list is the earlier message.
                    val earlier = ordered.getOrNull(index + 1)
                    val showSender = isGroup &&
                        !message.isOutgoing &&
                        earlier?.senderPubKey != message.senderPubKey
                    NostrMessageBubble(
                        message = message,
                        senderName = if (showSender) {
                            nameOf(
                                message.senderPubKey,
                                profiles
                            )
                        } else {
                            null
                        },
                        senderPicture = profiles[message.senderPubKey]
                            ?.picture
                            ?.takeIf { showSender },
                        media = viewModel.media,
                        isDownloading = message.id in downloading,
                        onRetry = { viewModel.retry(message) },
                        onDownload = { viewModel.download(message) },
                        onOpenImage = { path -> viewerPath = path },
                        onSaveFile = {
                            pendingSave = message
                            val attachment = message.attachment
                            saveFile.launch(
                                attachment?.fileName
                                    ?: (
                                        "nostr-file." +
                                            viewModel.media.mimeExtension(attachment?.mimeType)
                                        ),
                            )
                        },
                    )
                }
            }
        }
    }

    if (isInfoDialogVisible) {
        ChatInfoDialog(
            chatKey = chatKey,
            members = members,
            profiles = profiles,
            groupName = group?.name,
            onRename = viewModel::rename,
            onDismiss = { isInfoDialogVisible = false },
        )
    }

    viewerPath?.let { path ->
        ImageViewer(
            path = path,
            media = viewModel.media,
            onDismiss = { viewerPath = null },
        )
    }
}

private fun membersTitle(members: List<String>, profiles: Map<String, NostrProfile>): String {
    val names = members.map { nameOf(it, profiles) }
    return if (names.size <= 3) {
        names.joinToString(", ")
    } else {
        names.take(3).joinToString(", ") + " +" + (names.size - 3)
    }
}

private fun nameOf(pubKey: String, profiles: Map<String, NostrProfile>): String =
    profiles[pubKey]?.name ?: NostrSignerProvider.shortNpub(pubKey)

private fun NostrAttachment.aspectRatio(): Float {
    val w = width ?: return DEFAULT_PHOTO_RATIO
    val h = height ?: return DEFAULT_PHOTO_RATIO
    return if (w > 0 && h > 0) w.toFloat() / h else DEFAULT_PHOTO_RATIO
}

private const val DEFAULT_PHOTO_RATIO = 4f / 3f

@Composable
private fun NostrMessageBubble(
    message: NostrMessage,
    senderName: String?,
    senderPicture: String?,
    media: NostrMedia,
    isDownloading: Boolean,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onOpenImage: (String) -> Unit,
    onSaveFile: () -> Unit,
) {
    val context = LocalContext.current
    val isFailed = message.status == NostrMessage.Status.Failed

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (senderName != null) {
            NostrAvatar(
                modifier = Modifier.padding(end = 8.dp, bottom = 20.dp),
                name = senderName,
                pictureUrl = senderPicture,
                size = 28.dp,
            )
        }
        Column(
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
        ) {
            if (senderName != null) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    text = senderName,
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
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = when {
                    message.isOutgoing -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
            ) {
                val attachment = message.attachment
                when {
                    attachment == null -> Text(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    attachment.isImage && attachment.isDownloaded -> DownloadedImage(
                        path = requireNotNull(attachment.localPath),
                        fallbackRatio = attachment.aspectRatio(),
                        media = media,
                        onOpen = onOpenImage,
                    )

                    attachment.isImage -> AttachmentPlaceholder(
                        icon = Icons.Rounded.Image,
                        label = stringResource(R.string.nostr_tap_to_load_photo),
                        detail = attachment.size?.let {
                            Formatter.formatShortFileSize(context, it)
                        },
                        isBusy = isDownloading || message.status == NostrMessage.Status.Sending,
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
private fun DownloadedImage(
    path: String,
    fallbackRatio: Float,
    media: NostrMedia,
    onOpen: (String) -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = media.loadPreview(path)?.asImageBitmap()
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
private fun ImageViewer(
    path: String,
    media: NostrMedia,
    onDismiss: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = media.loadPreview(path, maxSide = 2560)?.asImageBitmap()
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
    chatKey: String,
    members: List<String>,
    profiles: Map<String, NostrProfile>,
    groupName: String?,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val isGroup = NostrChatKey.isGroup(chatKey)
    var name by rememberSaveable(groupName) { mutableStateOf(groupName.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (isGroup) R.string.nostr_group_info else R.string.nostr_contact_info,
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
                if (isGroup) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.inbox_group_name_hint)) },
                        supportingText = {
                            Text(text = stringResource(R.string.nostr_group_rename_help))
                        },
                    )
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.nostr_group_members, members.size + 1),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                members.forEach { pubKey ->
                    val profile = profiles[pubKey]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NostrAvatar(
                            name = nameOf(pubKey, profiles),
                            pictureUrl = profile?.picture,
                            size = 40.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = nameOf(pubKey, profiles),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    profile?.nip05 != null && profile.isNip05Verified -> {
                                        "✓ " + profile.nip05.removePrefix("_@")
                                    }

                                    else -> NostrSignerProvider.npubOf(pubKey)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (isGroup) {
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
                    if (isGroup && name.trim() != groupName.orEmpty()) onRename(name)
                    onDismiss()
                },
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun NostrComposer(
    text: String,
    enabled: Boolean,
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
                                contentDescription = null
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
