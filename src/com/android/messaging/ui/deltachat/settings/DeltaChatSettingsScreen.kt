package com.android.messaging.ui.deltachat.settings

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.twill.TwillDeliveryMode
import com.android.messaging.ui.appsettings.common.SettingsCategoryHeader
import com.android.messaging.ui.appsettings.common.SettingsClickableItem
import com.android.messaging.ui.appsettings.common.SettingsGroupBreak
import com.android.messaging.ui.appsettings.common.SettingsGroupTitle
import com.android.messaging.ui.appsettings.common.SettingsLazyColumn
import com.android.messaging.ui.appsettings.common.SettingsSwitchItem
import com.android.messaging.ui.appsettings.common.SettingsTopAppBar
import com.android.messaging.util.UiUtils
import kotlinx.coroutines.launch

private enum class DeltaChatDialog { None, NewProfile, Code, Email, Name, Remove }

private const val DEFAULT_CHATMAIL_SERVER = "nine.testrun.org"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeltaChatSettingsScreen(
    viewModel: DeltaChatSettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf(DeltaChatDialog.None) }

    // Notifications: Twill only gets the permission automatically as the default SMS app.
    var notificationsAllowed by remember { mutableStateOf(areNotificationsAllowed(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = areNotificationsAllowed(context)
        onPauseOrDispose {}
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsAllowed = areNotificationsAllowed(context) }
    var wasConfigured by remember { mutableStateOf(uiState.isConfigured) }
    LaunchedEffect(uiState.isConfigured) {
        if (uiState.isConfigured && !wasConfigured && !areNotificationsAllowed(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        wasConfigured = uiState.isConfigured
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.deltachat_settings_title),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        SettingsLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item(key = "profile_header", contentType = SettingsGroupTitle) {
                SettingsCategoryHeader(title = stringResource(R.string.deltachat_profile_header))
            }

            when {
                !uiState.isAvailable -> item(
                    key = "unavailable",
                    contentType = SettingsGroupTitle
                ) {
                    NoteItem(text = stringResource(R.string.deltachat_unavailable))
                }

                !uiState.isConfigured -> {
                    item(key = "off_note", contentType = SettingsGroupTitle) {
                        NoteItem(text = stringResource(R.string.deltachat_off_summary))
                    }
                    if (uiState.isWorking) {
                        item(key = "progress") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(text = stringResource(R.string.deltachat_setting_up))
                                val progress = uiState.progress
                                if (progress != null) {
                                    LinearProgressIndicator(
                                        progress = { progress / 1000f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                    uiState.error?.let { message ->
                        item(key = "error") {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.deltachat_setup_failed),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                supportingContent = { Text(text = message) },
                            )
                        }
                    }
                    item(key = "new_profile") {
                        SettingsClickableItem(
                            title = stringResource(R.string.deltachat_new_profile),
                            summary = stringResource(R.string.deltachat_new_profile_summary),
                            enabled = !uiState.isWorking,
                            onClick = { dialog = DeltaChatDialog.NewProfile },
                        )
                    }
                    item(key = "use_code") {
                        SettingsClickableItem(
                            title = stringResource(R.string.deltachat_use_code),
                            summary = stringResource(R.string.deltachat_use_code_summary),
                            enabled = !uiState.isWorking,
                            onClick = { dialog = DeltaChatDialog.Code },
                        )
                    }
                    item(key = "use_email") {
                        SettingsClickableItem(
                            title = stringResource(R.string.deltachat_use_email),
                            summary = stringResource(R.string.deltachat_use_email_summary),
                            enabled = !uiState.isWorking,
                            onClick = { dialog = DeltaChatDialog.Email },
                        )
                    }
                }

                else -> {
                    item(key = "name") {
                        SettingsClickableItem(
                            title = stringResource(R.string.deltachat_display_name),
                            summary = uiState.profile.displayName.ifBlank {
                                stringResource(R.string.deltachat_display_name_unset)
                            },
                            onClick = { dialog = DeltaChatDialog.Name },
                        )
                    }
                    item(key = "address") {
                        SettingsClickableItem(
                            title = stringResource(R.string.deltachat_address),
                            summary = uiState.profile.address,
                            onClick = {
                                copy(context, uiState.profile.address)
                                UiUtils.showToastAtBottom(R.string.deltachat_copied)
                            },
                        )
                    }
                    item(key = "invite") {
                        SettingsClickableItem(
                            title = stringResource(R.string.deltachat_share_invite),
                            summary = stringResource(R.string.deltachat_share_invite_summary),
                            onClick = {
                                scope.launch {
                                    val link = viewModel.inviteLink() ?: return@launch
                                    shareText(context, link)
                                }
                            },
                        )
                    }
                    item(key = "copy_invite") {
                        SettingsClickableItem(
                            title = stringResource(R.string.deltachat_copy_invite),
                            onClick = {
                                scope.launch {
                                    val link = viewModel.inviteLink() ?: return@launch
                                    copy(context, link)
                                    UiUtils.showToastAtBottom(R.string.deltachat_copied)
                                }
                            },
                        )
                    }
                    if (!notificationsAllowed) {
                        item(key = "notifications_off") {
                            SettingsClickableItem(
                                title = stringResource(R.string.nostr_notifications_off),
                                summary = stringResource(
                                    R.string.deltachat_notifications_off_summary
                                ),
                                onClick = {
                                    if (context.checkSelfPermission(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) ==
                                        PackageManager.PERMISSION_GRANTED
                                    ) {
                                        openNotificationSettings(context)
                                    } else {
                                        permissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    }
                                },
                            )
                        }
                    }
                    item(key = "remove") {
                        SettingsClickableItem(
                            title = stringResource(R.string.deltachat_remove_profile),
                            onClick = { dialog = DeltaChatDialog.Remove },
                        )
                    }

                    item(key = "delivery_divider", contentType = SettingsGroupBreak) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item(key = "delivery_header", contentType = SettingsGroupTitle) {
                        SettingsCategoryHeader(
                            title = stringResource(R.string.nostr_delivery_header)
                        )
                    }
                    items(items = TwillDeliveryMode.entries, key = {
                        "delivery:" + it.key
                    }) { mode ->
                        DeliveryModeRow(
                            mode = mode,
                            isSelected = uiState.deliveryMode == mode,
                            onSelect = { viewModel.setDeliveryMode(mode) },
                        )
                    }

                    item(key = "privacy_divider", contentType = SettingsGroupBreak) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item(key = "privacy_header", contentType = SettingsGroupTitle) {
                        SettingsCategoryHeader(
                            title = stringResource(R.string.deltachat_privacy_header)
                        )
                    }
                    item(key = "read_receipts") {
                        SettingsSwitchItem(
                            title = stringResource(R.string.deltachat_read_receipts),
                            summary = stringResource(R.string.deltachat_read_receipts_summary),
                            checked = uiState.profile.readReceipts,
                            onCheckedChange = viewModel::setReadReceipts,
                        )
                    }
                }
            }
        }
    }

    val dismiss = { dialog = DeltaChatDialog.None }
    when (dialog) {
        DeltaChatDialog.None -> Unit

        DeltaChatDialog.NewProfile -> FormDialog(
            title = stringResource(R.string.deltachat_new_profile),
            explainer = stringResource(R.string.deltachat_new_profile_explainer),
            fields = listOf(
                FormField(stringResource(R.string.deltachat_display_name)),
                FormField(
                    stringResource(R.string.deltachat_server),
                    initial = DEFAULT_CHATMAIL_SERVER,
                    keyboard = KeyboardType.Uri,
                ),
            ),
            confirm = stringResource(R.string.deltachat_create),
            onConfirm = { values ->
                viewModel.createProfile(values[0], values[1].ifBlank { DEFAULT_CHATMAIL_SERVER })
                dismiss()
            },
            onDismiss = dismiss,
        )

        DeltaChatDialog.Code -> FormDialog(
            title = stringResource(R.string.deltachat_use_code),
            explainer = stringResource(R.string.deltachat_use_code_explainer),
            fields = listOf(
                FormField(stringResource(R.string.deltachat_display_name)),
                FormField(stringResource(R.string.deltachat_code), keyboard = KeyboardType.Uri),
            ),
            confirm = stringResource(R.string.deltachat_continue),
            requiredFields = setOf(1),
            onConfirm = { values ->
                viewModel.useCode(values[0], values[1])
                dismiss()
            },
            onDismiss = dismiss,
        )

        DeltaChatDialog.Email -> FormDialog(
            title = stringResource(R.string.deltachat_use_email),
            explainer = stringResource(R.string.deltachat_use_email_explainer),
            fields = listOf(
                FormField(stringResource(R.string.deltachat_display_name)),
                FormField(stringResource(R.string.deltachat_email), keyboard = KeyboardType.Email),
                FormField(
                    stringResource(R.string.deltachat_password),
                    keyboard = KeyboardType.Password,
                    isSecret = true,
                ),
            ),
            confirm = stringResource(R.string.deltachat_continue),
            requiredFields = setOf(1, 2),
            onConfirm = { values ->
                viewModel.useEmail(values[0], values[1], values[2])
                dismiss()
            },
            onDismiss = dismiss,
        )

        DeltaChatDialog.Name -> FormDialog(
            title = stringResource(R.string.deltachat_display_name),
            explainer = stringResource(R.string.deltachat_display_name_explainer),
            fields = listOf(
                FormField(
                    stringResource(R.string.deltachat_display_name),
                    initial = uiState.profile.displayName,
                ),
            ),
            confirm = stringResource(R.string.nostr_save),
            onConfirm = { values ->
                viewModel.setDisplayName(values[0])
                dismiss()
            },
            onDismiss = dismiss,
        )

        DeltaChatDialog.Remove -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(text = stringResource(R.string.deltachat_remove_profile)) },
            text = { Text(text = stringResource(R.string.deltachat_remove_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeProfile()
                        dismiss()
                    },
                ) {
                    Text(text = stringResource(R.string.deltachat_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun NoteItem(text: String) {
    ListItem(
        headlineContent = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun DeliveryModeRow(
    mode: TwillDeliveryMode,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val (title, summary) = when (mode) {
        TwillDeliveryMode.WhileOpen ->
            R.string.nostr_delivery_while_open to R.string.nostr_delivery_while_open_summary
        TwillDeliveryMode.Periodic ->
            R.string.nostr_delivery_periodic to R.string.nostr_delivery_periodic_summary
        TwillDeliveryMode.AlwaysConnected ->
            R.string.nostr_delivery_always to R.string.nostr_delivery_always_summary
    }
    ListItem(
        modifier = Modifier.selectable(
            selected = isSelected,
            role = Role.RadioButton,
            onClick = onSelect
        ),
        leadingContent = { RadioButton(selected = isSelected, onClick = null) },
        headlineContent = { Text(text = stringResource(title)) },
        supportingContent = { Text(text = stringResource(summary)) },
    )
}

private class FormField(
    val label: String,
    val initial: String = "",
    val keyboard: KeyboardType = KeyboardType.Text,
    val isSecret: Boolean = false,
)

@Composable
private fun FormDialog(
    title: String,
    explainer: String,
    fields: List<FormField>,
    confirm: String,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    requiredFields: Set<Int> = emptySet(),
) {
    var values by rememberSaveable { mutableStateOf(fields.map { it.initial }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = explainer)
                fields.forEachIndexed { index, field ->
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = values[index],
                        onValueChange = { input ->
                            values = values.toMutableList().also { it[index] = input }
                        },
                        singleLine = true,
                        label = { Text(text = field.label) },
                        visualTransformation = if (field.isSecret) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = field.keyboard),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = requiredFields.all { values[it].isNotBlank() },
                onClick = { onConfirm(values.map { it.trim() }) },
            ) {
                Text(text = confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun areNotificationsAllowed(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED &&
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() !=
        false

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("deltachat", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(
        Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
