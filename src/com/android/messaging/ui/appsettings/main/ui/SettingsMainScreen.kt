package com.android.messaging.ui.appsettings.main.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.android.messaging.data.subscription.model.SubId
import com.android.messaging.ui.appsettings.common.SettingsClickableItem
import com.android.messaging.ui.appsettings.common.SettingsGroupBreak
import com.android.messaging.ui.appsettings.common.SettingsLazyColumn
import com.android.messaging.ui.appsettings.common.SettingsTopAppBar
import com.android.messaging.ui.appsettings.subscription.model.SubscriptionUiState
import com.android.messaging.ui.common.text.asLtrText
import com.android.messaging.ui.core.MessagingPreviewTheme
import com.android.messaging.ui.twill.LocalSmsSettingsHeader
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsMainScreen(
    subscriptions: ImmutableList<SubscriptionUiState>,
    onNavigateBack: () -> Unit,
    onGeneralSettingsClick: () -> Unit,
    onSubscriptionClick: (subId: SubId, title: String) -> Unit,
    modifier: Modifier = Modifier,
    titleOverride: String? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsTopAppBar(
                title = titleOverride ?: stringResource(R.string.settings_activity_title),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        // Twill: "Use Twill for SMS/MMS" first, on the SMS Settings page.
        val twillHeader = LocalSmsSettingsHeader.current
        SettingsLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            twillHeader?.let { header -> item(key = "twill_sms_switch") { header() } }
            item(key = "general_settings") {
                SettingsClickableItem(
                    title = stringResource(R.string.general_settings),
                    onClick = onGeneralSettingsClick,
                )
            }

            if (subscriptions.isNotEmpty()) {
                item(key = "subscriptions_divider", contentType = SettingsGroupBreak) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            itemsIndexed(
                items = subscriptions,
                key = { _, item -> item.subId.value },
            ) { _, subscription ->
                SettingsClickableItem(
                    title = subscription.displayName,
                    summary = subscription.displayDetail.asLtrText(),
                    onClick = {
                        onSubscriptionClick(subscription.subId, subscription.displayName)
                    },
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun SettingsMainScreenSingleSimPreview() {
    MessagingPreviewTheme {
        SettingsMainScreen(
            subscriptions = persistentListOf(),
            onNavigateBack = {},
            onGeneralSettingsClick = {},
            onSubscriptionClick = { _, _ -> },
        )
    }
}

@PreviewLightDark
@Composable
private fun SettingsMainScreenMultiSimPreview() {
    MessagingPreviewTheme {
        SettingsMainScreen(
            subscriptions = persistentListOf(
                SubscriptionUiState(
                    subId = SubId(1),
                    displayName = "SIM 1",
                    displayDetail = "+31 6 1234 5678",
                ),
                SubscriptionUiState(
                    subId = SubId(2),
                    displayName = "Travel SIM",
                    displayDetail = "+372 5555 0101",
                ),
            ),
            onNavigateBack = {},
            onGeneralSettingsClick = {},
            onSubscriptionClick = { _, _ -> },
        )
    }
}
