package com.android.messaging.ui.twill

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.android.messaging.R
import com.android.messaging.ui.appsettings.common.SettingsLazyColumn
import com.android.messaging.ui.appsettings.common.SettingsTopAppBar

/** A row Twill adds to upstream's SMS Settings list, in [group]. */
internal class TwillSettingsRow(
    val key: String,
    val contentType: Any? = null,
    val group: Group = Group.Top,
    val content: @Composable () -> Unit,
) {
    enum class Group {
        /** First, with the default SMS app row ("Use Twill for SMS/MMS"). */
        Top,

        /** Under "Conversations" (Archived, Blocked contacts). */
        Conversations,
    }
}

/**
 * The first rows of the SMS Settings page ("Use Twill for SMS/MMS", Archived, Blocked
 * contacts). Provided by the SMS Settings route and drawn at the top of upstream's settings
 * lists ([twillSmsSettingsRows]), which read it before building their LazyColumn.
 */
internal val LocalSmsSettingsHeader = staticCompositionLocalOf<List<TwillSettingsRow>?> { null }

/** Adds [rows] (from [LocalSmsSettingsHeader]) to a settings list, only those in [group]. */
internal fun LazyListScope.twillSmsSettingsRows(
    rows: List<TwillSettingsRow>?,
    group: TwillSettingsRow.Group? = null,
) {
    rows?.filter { group == null || it.group == group }
        ?.forEach { row -> item(key = row.key, contentType = row.contentType) { row.content() } }
}

/** SMS Settings while SMS is off: only the switch to turn it back on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmsOffSettingsScreen(
    onNavigateBack: () -> Unit,
    header: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.sms_settings_title),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        SettingsLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item(key = "twill_sms_switch") { header() }
        }
    }
}
