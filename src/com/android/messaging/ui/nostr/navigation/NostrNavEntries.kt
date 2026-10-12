package com.android.messaging.ui.nostr.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.android.messaging.R
import com.android.messaging.ui.navigation.LocalNavigator
import com.android.messaging.ui.navigation.paneTitleMetadata
import com.android.messaging.ui.nostr.chat.NostrChatScreen
import com.android.messaging.ui.nostr.chat.NostrChatViewModel
import kotlinx.serialization.Serializable

/** A one-to-one Nostr DM conversation with [chatKey] (hex). */
@Serializable
internal data class NostrChatNavKey(
    val chatKey: String,
) : NavKey

internal fun EntryProviderScope<NavKey>.nostrEntries() {
    entry<NostrChatNavKey>(
        metadata = paneTitleMetadata(R.string.inbox_section_nostr),
        content = nostrChatRouteContent(),
    )
}

private fun nostrChatRouteContent(): @Composable (NostrChatNavKey) -> Unit {
    return { navKey ->
        val navigator = LocalNavigator.current

        NostrChatScreen(
            chatKey = navKey.chatKey,
            viewModel = hiltViewModel<NostrChatViewModel>(key = "nostr-chat-" + navKey.chatKey),
            onNavigateBack = navigator::back,
        )
    }
}
