package com.android.messaging.ui.nostr.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.nostr.NostrAccount
import com.android.messaging.nostr.NostrDeliveryMode
import com.android.messaging.nostr.NostrSignerProvider
import com.android.messaging.ui.appsettings.common.SettingsCategoryHeader
import com.android.messaging.ui.appsettings.common.SettingsClickableItem
import com.android.messaging.ui.appsettings.common.SettingsTopAppBar
import com.android.messaging.util.UiUtils

private enum class NostrSettingsDialog { None, PrivateKey, SignOut, AddRelay }

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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(key = "account_header") {
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
                        SettingsClickableItem(
                            title = stringResource(R.string.nostr_signed_in_as),
                            summary = NostrSignerProvider.shortNpub(account.pubKeyHex),
                            onClick = {
                                copyToClipboard(
                                    context = context,
                                    text = NostrSignerProvider.npubOf(account.pubKeyHex),
                                    isSensitive = false,
                                )
                                UiUtils.showToastAtBottom(R.string.nostr_npub_copied)
                            },
                        )
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

            item(key = "delivery_divider") {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "delivery_header") {
                SettingsCategoryHeader(title = stringResource(R.string.nostr_delivery_header))
            }
            items(items = NostrDeliveryMode.entries, key = { "delivery:" + it.key }) { mode ->
                DeliveryModeRow(
                    mode = mode,
                    isSelected = uiState.deliveryMode == mode,
                    onSelect = { viewModel.setDeliveryMode(mode) },
                )
            }

            item(key = "relays_divider") {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "relays_header") {
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
private fun DeliveryModeRow(
    mode: NostrDeliveryMode,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val (title, summary) = when (mode) {
        NostrDeliveryMode.WhileOpen -> {
            R.string.nostr_delivery_while_open to R.string.nostr_delivery_while_open_summary
        }

        NostrDeliveryMode.Periodic -> {
            R.string.nostr_delivery_periodic to R.string.nostr_delivery_periodic_summary
        }

        NostrDeliveryMode.AlwaysConnected -> {
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
