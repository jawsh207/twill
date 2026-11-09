package com.android.messaging.ui.appsettings.general.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.android.messaging.R
import com.android.messaging.ui.appsettings.common.SettingsCategoryHeader
import com.android.messaging.ui.appsettings.common.SettingsClickableItem
import com.android.messaging.ui.appsettings.common.SettingsGroupBreak
import com.android.messaging.ui.appsettings.common.SettingsGroupTitle
import com.android.messaging.ui.appsettings.common.SettingsLazyColumn
import com.android.messaging.ui.appsettings.common.SettingsSwitchItem
import com.android.messaging.ui.appsettings.common.SettingsTopAppBar
import com.android.messaging.ui.appsettings.general.model.AppSettingsAction as Action
import com.android.messaging.ui.appsettings.general.model.AppSettingsUiState
import com.android.messaging.ui.core.MessagingPreviewTheme
import com.android.messaging.ui.twill.LocalSmsSettingsHeader
import com.android.messaging.ui.twill.TwillSettingsRow
import com.android.messaging.ui.twill.twillSmsSettingsRows

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsScreen(
    appSettings: AppSettingsUiState,
    onAction: (Action) -> Unit,
    onNavigateBack: () -> Unit,
    onPrivacyClick: () -> Unit,
    modifier: Modifier = Modifier,
    isTopLevel: Boolean = false,
    hasAdvancedSettings: Boolean = false,
    onAdvancedClick: () -> Unit = {},
    titleOverride: String? = null,
    showLicenses: Boolean = true,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val title = titleOverride ?: if (isTopLevel) {
        stringResource(R.string.settings_activity_title)
    } else {
        stringResource(R.string.general_settings_activity_title)
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsTopAppBar(
                title = title,
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        // Twill: "Use Twill for SMS/MMS", Archived and Blocked contacts first (SMS Settings).
        val twillHeader = LocalSmsSettingsHeader.current
        SettingsLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            coreSettingsItems(
                appSettings = appSettings,
                twillRows = twillHeader,
                onAction = onAction,
                onPrivacyClick = onPrivacyClick,
                onAdvancedClick = onAdvancedClick.takeIf { isTopLevel && hasAdvancedSettings },
            )

            debugSettingsItems(
                appSettings = appSettings,
                onAction = onAction,
            )

            if (showLicenses) {
                licenseSettingsItems(onAction)
            }
        }
    }
}

/**
 * Twill: grouped as Twill's other settings pages. The SMS app first, then Conversations,
 * Notifications and sounds, and More (privacy, advanced).
 */
private fun LazyListScope.coreSettingsItems(
    appSettings: AppSettingsUiState,
    twillRows: List<TwillSettingsRow>?,
    onAction: (Action) -> Unit,
    onPrivacyClick: () -> Unit,
    onAdvancedClick: (() -> Unit)?,
) {
    twillSmsSettingsRows(twillRows, TwillSettingsRow.Group.Top)
    item(key = "default_sms_app") {
        SettingsClickableItem(
            title = stringResource(R.string.sms_disabled_pref_title),
            summary = appSettings.defaultSmsAppLabel,
            onClick = {
                onAction(Action.DefaultSmsAppClicked(appSettings.isDefaultSmsApp))
            },
        )
    }

    settingsGroup(key = "conversations", title = R.string.twill_settings_conversations)
    twillSmsSettingsRows(twillRows, TwillSettingsRow.Group.Conversations)
    item(key = "unread_on_top") {
        SettingsSwitchItem(
            title = stringResource(R.string.unread_on_top_pref_title),
            summary = stringResource(R.string.unread_on_top_pref_summary),
            checked = appSettings.unreadOnTopEnabled,
            onCheckedChange = {
                onAction(Action.UnreadOnTopChanged(it))
            },
        )
    }

    settingsGroup(key = "sounds", title = R.string.twill_settings_notifications_sounds)
    item(key = "notifications") {
        SettingsClickableItem(
            title = stringResource(R.string.notifications_enabled_conversation_pref_title),
            onClick = {
                onAction(Action.NotificationsClicked)
            },
        )
    }

    item(key = "send_sound") {
        SettingsSwitchItem(
            title = stringResource(R.string.send_sound_pref_title),
            checked = appSettings.sendSoundEnabled,
            onCheckedChange = {
                onAction(Action.SendSoundChanged(it))
            },
        )
    }

    item(key = "in_conversation_sound") {
        SettingsSwitchItem(
            title = stringResource(R.string.in_conversation_sound_pref_title),
            summary = stringResource(R.string.in_conversation_sound_pref_summary),
            checked = appSettings.inConversationSoundEnabled,
            onCheckedChange = {
                onAction(Action.InConversationSoundChanged(it))
            },
        )
    }

    settingsGroup(key = "more", title = R.string.twill_settings_more)
    item(key = "privacy") {
        SettingsClickableItem(
            title = stringResource(R.string.privacy_settings_activity_title),
            onClick = onPrivacyClick,
        )
    }
    if (onAdvancedClick != null) {
        item(key = "advanced_settings") {
            SettingsClickableItem(
                title = stringResource(R.string.advanced_settings),
                onClick = onAdvancedClick,
            )
        }
    }
}

/** A divider and a category header, starting a group of rows. */
private fun LazyListScope.settingsGroup(key: String, @StringRes title: Int) {
    item(key = key + "_divider", contentType = SettingsGroupBreak) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    item(key = key + "_header", contentType = SettingsGroupTitle) {
        SettingsCategoryHeader(title = stringResource(title))
    }
}

private fun LazyListScope.debugSettingsItems(
    appSettings: AppSettingsUiState,
    onAction: (Action) -> Unit,
) {
    if (!appSettings.isDebugEnabled) return

    item(key = "debug_divider", contentType = SettingsGroupBreak) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    item(key = "debug_category_header", contentType = SettingsGroupTitle) {
        SettingsCategoryHeader(
            title = stringResource(R.string.debug_category_pref_title),
        )
    }
    item(key = "dump_sms") {
        SettingsSwitchItem(
            title = stringResource(R.string.dump_sms_pref_title),
            summary = stringResource(R.string.dump_sms_pref_summary),
            checked = appSettings.dumpSmsEnabled,
            onCheckedChange = {
                onAction(Action.DumpSmsChanged(it))
            },
        )
    }
    item(key = "dump_mms") {
        SettingsSwitchItem(
            title = stringResource(R.string.dump_mms_pref_title),
            summary = stringResource(R.string.dump_mms_pref_summary),
            checked = appSettings.dumpMmsEnabled,
            onCheckedChange = {
                onAction(Action.DumpMmsChanged(it))
            },
        )
    }
}

private fun LazyListScope.licenseSettingsItems(
    onAction: (Action) -> Unit,
) {
    item(key = "licenses_divider", contentType = SettingsGroupBreak) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    item(key = "licenses") {
        SettingsClickableItem(
            title = stringResource(R.string.menu_license),
            onClick = {
                onAction(Action.LicensesClicked)
            },
        )
    }
}

@PreviewLightDark
@Composable
private fun AppSettingsScreenTopLevelPreview() {
    MessagingPreviewTheme {
        AppSettingsScreen(
            appSettings = AppSettingsUiState(
                isDefaultSmsApp = true,
                defaultSmsAppLabel = "Messaging",
                sendSoundEnabled = true,
            ),
            onAction = {},
            onNavigateBack = {},
            onPrivacyClick = {},
            isTopLevel = true,
            hasAdvancedSettings = true,
            onAdvancedClick = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun AppSettingsScreenDebugPreview() {
    MessagingPreviewTheme {
        AppSettingsScreen(
            appSettings = AppSettingsUiState(
                isDefaultSmsApp = false,
                defaultSmsAppLabel = "Phone",
                sendSoundEnabled = false,
                isDebugEnabled = true,
                dumpSmsEnabled = true,
                dumpMmsEnabled = false,
            ),
            onAction = {},
            onNavigateBack = {},
            onPrivacyClick = {},
        )
    }
}
