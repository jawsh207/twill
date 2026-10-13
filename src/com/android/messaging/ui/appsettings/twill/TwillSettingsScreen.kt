package com.android.messaging.ui.appsettings.twill

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import com.android.messaging.ui.appsettings.common.SettingsClickableItem
import com.android.messaging.ui.appsettings.common.SettingsTopAppBar
import com.android.messaging.ui.core.MessagingPreviewTheme

/**
 * Twill's top-level Settings: one entry per messaging protocol (SMS, Nostr, Delta Chat),
 * then app-wide items such as open source licenses. Protocol-specific settings live one level down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TwillSettingsScreen(
    onNavigateBack: () -> Unit,
    onSmsSettingsClick: () -> Unit,
    onLicensesClick: () -> Unit,
    modifier: Modifier = Modifier,
    onNostrSettingsClick: () -> Unit = {},
    onDeltaChatSettingsClick: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.settings_activity_title),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(key = "sms_settings") {
                SettingsClickableItem(
                    title = stringResource(R.string.sms_settings_title),
                    summary = stringResource(R.string.sms_settings_summary),
                    onClick = onSmsSettingsClick,
                )
            }
            item(key = "nostr_settings") {
                SettingsClickableItem(
                    title = stringResource(R.string.nostr_settings_title),
                    summary = stringResource(R.string.nostr_settings_summary),
                    onClick = onNostrSettingsClick,
                )
            }
            item(key = "deltachat_settings") {
                SettingsClickableItem(
                    title = stringResource(R.string.deltachat_settings_title),
                    summary = stringResource(R.string.deltachat_settings_summary),
                    onClick = onDeltaChatSettingsClick,
                )
            }
            item(key = "licenses_divider") {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "licenses") {
                SettingsClickableItem(
                    title = stringResource(R.string.menu_license),
                    onClick = onLicensesClick,
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun TwillSettingsScreenPreview() {
    MessagingPreviewTheme {
        TwillSettingsScreen(
            onNavigateBack = {},
            onSmsSettingsClick = {},
            onLicensesClick = {},
        )
    }
}
