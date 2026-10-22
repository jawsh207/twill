package com.android.messaging.ui.nostr.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.android.messaging.R
import com.android.messaging.nostr.NostrAccount
import com.android.messaging.nostr.NostrProfile
import com.android.messaging.nostr.NostrSignerProvider
import com.android.messaging.twill.TwillAvatar
import com.android.messaging.twill.TwillDeliveryMode
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.ui.appsettings.common.SettingsCategoryHeader
import com.android.messaging.ui.appsettings.common.SettingsClickableItem
import com.android.messaging.ui.appsettings.common.SettingsGroupBreak
import com.android.messaging.ui.appsettings.common.SettingsGroupTitle
import com.android.messaging.ui.appsettings.common.SettingsLazyColumn
import com.android.messaging.ui.appsettings.common.SettingsSwitchItem
import com.android.messaging.ui.appsettings.common.SettingsTopAppBar
import com.android.messaging.ui.twill.TwillAvatarImage
import com.android.messaging.util.UiUtils

private enum class NostrSettingsDialog { None, PrivateKey, SignOut, AddRelay, MediaServer }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NostrSettingsScreen(
    uiState: NostrSettingsUiState,
    viewModel: NostrSettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf(NostrSettingsDialog.None) }

    val signerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (!viewModel.onExternalSignerResult(result.data)) {
            UiUtils.showToastAtBottom(R.string.nostr_sign_in_failed)
        }
    }

    var notificationsAllowed by remember { mutableStateOf(areNotificationsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = areNotificationsAllowed(context)
        onPauseOrDispose {}
    }
    var openSettingsIfDenied by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = areNotificationsAllowed(context)
        if (!granted && openSettingsIfDenied) openNotificationSettings(context)
    }
    fun requestNotifications(openSettingsOnDenial: Boolean) {
        openSettingsIfDenied = openSettingsOnDenial
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // Permission granted but notifications switched off in system settings.
            if (openSettingsOnDenial) openNotificationSettings(context)
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Ask once, right after signing in on this screen.
    var wasSignedOut by remember { mutableStateOf(uiState.account == null) }
    LaunchedEffect(uiState.account != null) {
        if (uiState.account == null) {
            wasSignedOut = true
        } else if (wasSignedOut) {
            wasSignedOut = false
            if (!areNotificationsAllowed(
                    context
                )
            ) {
                requestNotifications(openSettingsOnDenial = false)
            }
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.nostr_settings_title),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        SettingsLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(key = "account_header", contentType = SettingsGroupTitle) {
                SettingsCategoryHeader(title = stringResource(R.string.nostr_account_header))
            }

            when (val account = uiState.account) {
                null -> {
                    item(key = "signed_out_note") {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.nostr_signed_out_summary),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                    item(key = "sign_in_amber") {
                        SettingsClickableItem(
                            title = stringResource(R.string.nostr_sign_in_amber),
                            summary = stringResource(
                                when {
                                    uiState.isExternalSignerInstalled -> {
                                        R.string.nostr_sign_in_amber_summary
                                    }

                                    else -> R.string.nostr_sign_in_amber_missing
                                },
                            ),
                            enabled = uiState.isExternalSignerInstalled,
                            onClick = {
                                signerLauncher.launch(viewModel.externalSignerLoginIntent())
                            },
                        )
                    }
                    item(key = "sign_in_key") {
                        SettingsClickableItem(
                            title = stringResource(R.string.nostr_sign_in_key),
                            summary = stringResource(R.string.nostr_sign_in_key_summary),
                            onClick = { dialog = NostrSettingsDialog.PrivateKey },
                        )
                    }
                }

                else -> {
                    item(key = "signed_in_as") {
                        OwnProfileRow(
                            pubKeyHex = account.pubKeyHex,
                            profile = uiState.ownProfile,
                            isRefreshing = uiState.isRefreshingProfile,
                            onRefresh = viewModel::refreshOwnProfile,
                            onCopyNpub = {
                                copyToClipboard(
                                    context = context,
                                    text = NostrSignerProvider.npubOf(account.pubKeyHex),
                                    isSensitive = false,
                                )
                                UiUtils.showToastAtBottom(R.string.nostr_npub_copied)
                            },
                        )
                    }
                    if (!notificationsAllowed) {
                        item(key = "notifications_off") {
                            SettingsClickableItem(
                                title = stringResource(R.string.nostr_notifications_off),
                                summary = stringResource(R.string.nostr_notifications_off_summary),
                                onClick = { requestNotifications(openSettingsOnDenial = true) },
                            )
                        }
                    }
                    item(key = "sign_in_method") {
                        SettingsClickableItem(
                            title = stringResource(R.string.nostr_sign_in_method),
                            summary = stringResource(
                                when (account) {
                                    is NostrAccount.ExternalSigner -> R.string.nostr_method_amber
                                    is NostrAccount.LocalKey -> R.string.nostr_method_local
                                },
                            ),
                            enabled = false,
                            onClick = {},
                        )
                    }
                    item(key = "sign_out") {
                        SettingsClickableItem(
                            title = stringResource(R.string.nostr_sign_out),
                            onClick = { dialog = NostrSettingsDialog.SignOut },
                        )
                    }
                }
            }

            item(key = "delivery_divider", contentType = SettingsGroupBreak) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "delivery_header", contentType = SettingsGroupTitle) {
                SettingsCategoryHeader(title = stringResource(R.string.nostr_delivery_header))
            }
            items(items = TwillDeliveryMode.entries, key = { "delivery:" + it.key }) { mode ->
                DeliveryModeRow(
                    mode = mode,
                    isSelected = uiState.deliveryMode == mode,
                    onSelect = { viewModel.setDeliveryMode(mode) },
                )
            }

            item(key = "media_divider", contentType = SettingsGroupBreak) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "media_header", contentType = SettingsGroupTitle) {
                SettingsCategoryHeader(title = stringResource(R.string.nostr_media_header))
            }
            item(key = "load_pictures") {
                SettingsSwitchItem(
                    title = stringResource(R.string.nostr_load_pictures),
                    summary = stringResource(R.string.nostr_load_pictures_summary),
                    checked = uiState.loadProfilePictures,
                    onCheckedChange = viewModel::setLoadProfilePictures,
                )
            }
            item(key = "auto_download") {
                SettingsSwitchItem(
                    title = stringResource(R.string.nostr_auto_download),
                    summary = stringResource(R.string.nostr_auto_download_summary),
                    checked = uiState.autoDownloadMedia,
                    onCheckedChange = viewModel::setAutoDownloadMedia,
                )
            }
            item(key = "media_server") {
                SettingsClickableItem(
                    title = stringResource(R.string.nostr_media_server),
                    summary =
                    uiState.mediaServer + "\n" +
                        stringResource(R.string.nostr_media_server_summary),
                    onClick = { dialog = NostrSettingsDialog.MediaServer },
                )
            }

            item(key = "relays_divider", contentType = SettingsGroupBreak) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "relays_header", contentType = SettingsGroupTitle) {
                SettingsCategoryHeader(title = stringResource(R.string.nostr_relays_header))
            }
            items(items = uiState.relays, key = { "relay:$it" }) { relay ->
                ListItem(
                    headlineContent = { Text(text = relay) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeRelay(relay) }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.nostr_remove_relay),
                            )
                        }
                    },
                )
            }
            item(key = "add_relay") {
                SettingsClickableItem(
                    title = stringResource(R.string.nostr_add_relay),
                    summary = stringResource(R.string.nostr_relays_summary),
                    onClick = { dialog = NostrSettingsDialog.AddRelay },
                )
            }
        }
    }

    when (dialog) {
        NostrSettingsDialog.None -> Unit

        NostrSettingsDialog.PrivateKey -> PrivateKeyDialog(
            onSignIn = { input ->
                if (viewModel.signInWithPrivateKey(input)) {
                    dialog = NostrSettingsDialog.None
                    true
                } else {
                    false
                }
            },
            onCreateNew = {
                viewModel.createNewKey()
                dialog = NostrSettingsDialog.None
            },
            onDismiss = { dialog = NostrSettingsDialog.None },
        )

        NostrSettingsDialog.SignOut -> SignOutDialog(
            isLocalKey = uiState.account is NostrAccount.LocalKey,
            onCopyKey = {
                viewModel.exportLocalNsec()?.let { nsec ->
                    copyToClipboard(context = context, text = nsec, isSensitive = true)
                    UiUtils.showToastAtBottom(R.string.nostr_nsec_copied)
                }
            },
            onConfirm = {
                viewModel.signOut()
                dialog = NostrSettingsDialog.None
            },
            onDismiss = { dialog = NostrSettingsDialog.None },
        )

        NostrSettingsDialog.MediaServer -> MediaServerDialog(
            current = uiState.mediaServer,
            onSave = { input ->
                if (viewModel.setMediaServer(input)) {
                    dialog = NostrSettingsDialog.None
                    true
                } else {
                    false
                }
            },
            onDismiss = { dialog = NostrSettingsDialog.None },
        )

        NostrSettingsDialog.AddRelay -> AddRelayDialog(
            onAdd = { input ->
                if (viewModel.addRelay(input)) {
                    dialog = NostrSettingsDialog.None
                    true
                } else {
                    false
                }
            },
            onDismiss = { dialog = NostrSettingsDialog.None },
        )
    }
}

@Composable
private fun OwnProfileRow(
    pubKeyHex: String,
    profile: NostrProfile?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onCopyNpub: () -> Unit,
) {
    val shortNpub = NostrSignerProvider.shortNpub(pubKeyHex)
    val name = profile?.name
    ListItem(
        modifier = Modifier.clickable(onClick = onCopyNpub),
        leadingContent = {
            TwillAvatarImage(
                avatar = TwillAvatar(name = name ?: shortNpub, ref = profile?.picture),
                protocol = TwillProtocolId.Nostr,
            )
        },
        overlineContent = { Text(text = stringResource(R.string.nostr_signed_in_as)) },
        headlineContent = {
            Text(text = name ?: shortNpub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                val nip05 = profile?.nip05
                if (nip05 != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = nip05.removePrefix("_@"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (profile?.isNip05Verified == true) {
                            Icon(
                                imageVector = Icons.Rounded.Verified,
                                contentDescription = stringResource(R.string.nostr_nip05_verified),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text(text = " · " + stringResource(R.string.nostr_nip05_not_verified))
                        }
                    }
                }
                if (name != null) Text(text = shortNpub)
                if (profile == null && !isRefreshing) {
                    Text(text = stringResource(R.string.nostr_profile_not_found))
                }
            }
        },
        trailingContent = {
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.nostr_refresh_profile),
                    )
                }
            }
        },
    )
}

private fun areNotificationsAllowed(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() != false

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun DeliveryModeRow(
    mode: TwillDeliveryMode,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val (title, summary) = when (mode) {
        TwillDeliveryMode.WhileOpen -> {
            R.string.nostr_delivery_while_open to R.string.nostr_delivery_while_open_summary
        }

        TwillDeliveryMode.Periodic -> {
            R.string.nostr_delivery_periodic to R.string.nostr_delivery_periodic_summary
        }

        TwillDeliveryMode.AlwaysConnected -> {
            R.string.nostr_delivery_always to R.string.nostr_delivery_always_summary
        }
    }
    ListItem(
        modifier = Modifier.selectable(
            selected = isSelected,
            role = Role.RadioButton,
            onClick = onSelect,
        ),
        leadingContent = { RadioButton(selected = isSelected, onClick = null) },
        headlineContent = { Text(text = stringResource(title)) },
        supportingContent = { Text(text = stringResource(summary)) },
    )
}

@Composable
private fun PrivateKeyDialog(
    onSignIn: (String) -> Boolean,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var isInvalid by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.nostr_sign_in_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.nostr_private_key_explainer))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = input,
                    onValueChange = {
                        input = it
                        isInvalid = false
                    },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.nostr_private_key_hint)) },
                    isError = isInvalid,
                    supportingText = if (isInvalid) {
                        { Text(text = stringResource(R.string.nostr_private_key_invalid)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                TextButton(onClick = onCreateNew) {
                    Text(text = stringResource(R.string.nostr_create_new_key))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = { isInvalid = !onSignIn(input) },
            ) {
                Text(text = stringResource(R.string.nostr_sign_in))
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
private fun SignOutDialog(
    isLocalKey: Boolean,
    onCopyKey: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.nostr_sign_out)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        when {
                            isLocalKey -> R.string.nostr_sign_out_local_warning
                            else -> R.string.nostr_sign_out_amber_note
                        },
                    ),
                )
                if (isLocalKey) {
                    TextButton(onClick = onCopyKey) {
                        Text(text = stringResource(R.string.nostr_copy_nsec))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.nostr_sign_out))
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
private fun MediaServerDialog(
    current: String,
    onSave: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(current) }
    var isInvalid by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.nostr_media_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.nostr_media_server_explainer))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = input,
                    onValueChange = {
                        input = it
                        isInvalid = false
                    },
                    singleLine = true,
                    placeholder = { Text(text = "https://blossom.example.com") },
                    isError = isInvalid,
                    supportingText = if (isInvalid) {
                        { Text(text = stringResource(R.string.nostr_media_server_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = { isInvalid = !onSave(input) },
            ) {
                Text(text = stringResource(R.string.nostr_save))
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
private fun AddRelayDialog(
    onAdd: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var isInvalid by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.nostr_add_relay)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = input,
                onValueChange = {
                    input = it
                    isInvalid = false
                },
                singleLine = true,
                placeholder = { Text(text = "wss://relay.example.com") },
                isError = isInvalid,
                supportingText = if (isInvalid) {
                    { Text(text = stringResource(R.string.nostr_relay_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = { isInvalid = !onAdd(input) },
            ) {
                Text(text = stringResource(R.string.nostr_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun copyToClipboard(
    context: Context,
    text: String,
    isSensitive: Boolean,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("nostr", text)
    if (isSensitive) {
        // Hides the key from clipboard previews.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
