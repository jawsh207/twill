package com.android.messaging.ui.twill.navigation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.android.messaging.R
import com.android.messaging.twill.TwillNotifications
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.twillEntryPoint
import com.android.messaging.ui.navigation.LocalNavigator
import com.android.messaging.ui.navigation.paneTitleMetadata
import com.android.messaging.ui.twill.chat.TwillChatScreen
import com.android.messaging.ui.twill.chat.TwillChatViewModel
import kotlinx.serialization.Serializable

/** A chat of one of Twill's protocols; [protocol] is a [TwillProtocolId.key]. */
@Serializable
internal data class TwillChatNavKey(
    val protocol: String,
    val chatId: String,
) : NavKey

/** The chat a Twill notification should open, if [intent] came from one. */
internal fun twillChatRoute(intent: Intent?): NavKey? {
    val protocol = intent?.getStringExtra(TwillNotifications.EXTRA_PROTOCOL) ?: return null
    val chatId = intent.getStringExtra(TwillNotifications.EXTRA_CHAT_ID) ?: return null
    if (TwillProtocolId.from(protocol) == null) return null
    return TwillChatNavKey(protocol = protocol, chatId = chatId)
}

internal fun EntryProviderScope<NavKey>.twillEntries() {
    entry<TwillChatNavKey>(
        metadata = paneTitleMetadata(R.string.twill_chat_pane_title),
        content = twillChatRouteContent(),
    )
}

private fun twillChatRouteContent(): @Composable (TwillChatNavKey) -> Unit {
    return { navKey ->
        val navigator = LocalNavigator.current
        val context = LocalContext.current
        val protocol = remember(context, navKey.protocol) {
            context.twillEntryPoint().twillProtocols().byKey(navKey.protocol)
        }
        if (protocol != null) {
            TwillChatScreen(
                protocol = protocol.id,
                protocolText = protocol.text,
                chatId = navKey.chatId,
                viewModel = hiltViewModel<TwillChatViewModel>(
                    key = "twill-chat-" + navKey.protocol + ":" + navKey.chatId,
                ),
                onNavigateBack = navigator::back,
            )
        }
    }
}
