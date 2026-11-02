package com.android.messaging.ui.twill.inbox

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.android.messaging.R
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.ui.core.LocalBarContainerColor

/**
 * The inbox's bottom bar (a Settings option, shown with two or more services): a tab per
 * service, as in Dialtone and Contacts. Material 3's ShortNavigationBar in its default colour
 * (the scheme's surface container), handling the gesture bar and keyboard insets itself.
 * Icons are outlined when unselected and filled when selected; the Nostr logo stays solid.
 * Badges count the conversations with unread messages.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TwillInboxBottomBar(
    tabs: List<TwillInboxTab>,
    selected: TwillInboxTab,
    unreadCount: (TwillInboxTab) -> Int,
    onSelect: (TwillInboxTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barColor = LocalBarContainerColor.current
    ShortNavigationBar(
        modifier = modifier,
        containerColor = when {
            barColor.isSpecified -> barColor
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        windowInsets = ShortNavigationBarDefaults.windowInsets.union(WindowInsets.ime),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            val label = tab.label()
            ShortNavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = {
                    val icon = painterResource(tab.iconRes(isSelected))
                    val unread = unreadCount(tab)
                    if (unread > 0) {
                        BadgedBox(badge = { Badge { Text(text = unread.toString()) } }) {
                            Icon(painter = icon, contentDescription = label)
                        }
                    } else {
                        Icon(painter = icon, contentDescription = label)
                    }
                },
                label = { Text(text = label) },
            )
        }
    }
}

@Composable
private fun TwillInboxTab.label(): String = stringResource(
    when (this) {
        TwillInboxTab.Sms -> R.string.inbox_section_sms
        is TwillInboxTab.Service -> when (protocol) {
            TwillProtocolId.Nostr -> R.string.inbox_section_nostr
            TwillProtocolId.DeltaChat -> R.string.deltachat_section
        }
    },
)

private fun TwillInboxTab.iconRes(isSelected: Boolean): Int = when (this) {
    TwillInboxTab.Sms -> if (isSelected) {
        R.drawable.ic_twill_tab_sms
    } else {
        R.drawable.ic_twill_tab_sms_outline
    }
    is TwillInboxTab.Service -> when (protocol) {
        TwillProtocolId.Nostr -> R.drawable.ic_twill_tab_nostr
        TwillProtocolId.DeltaChat -> if (isSelected) {
            R.drawable.ic_twill_tab_deltachat
        } else {
            R.drawable.ic_twill_tab_deltachat_outline
        }
    }
}
