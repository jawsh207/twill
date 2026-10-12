package com.android.messaging.ui.nostr.inbox

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.messaging.data.appsettings.model.AppBooleanPref
import com.android.messaging.data.appsettings.repository.AppPreferenceReader
import com.android.messaging.nostr.NostrAccountStore
import com.android.messaging.nostr.NostrConversation
import com.android.messaging.nostr.NostrMessageStore
import com.android.messaging.nostr.NostrMessenger
import com.vitorpamplona.quartz.nip19Bech32.decodePublicKeyAsHexOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
internal data class TwillInboxUiState(
    /** False hides the section headers entirely, so the list looks like plain SMS. */
    val isNostrEnabled: Boolean = false,
    val isSmsCollapsed: Boolean = false,
    val isNostrCollapsed: Boolean = false,
    val nostrConversations: List<NostrConversation> = emptyList(),
)

/** Actions the conversation list needs from the Nostr inbox. */
internal interface TwillInboxController {
    val uiState: StateFlow<TwillInboxUiState>

    fun toggleSmsCollapsed()
    fun toggleNostrCollapsed()
    fun setPinned(peerPubKey: String, isPinned: Boolean)
    fun setRead(peerPubKey: String, isRead: Boolean)
    fun delete(peerPubKey: String)

    /** Returns the hex key for an npub, nprofile or hex key, or null if invalid. */
    fun resolvePeer(input: String): String?
}

/** The Nostr inbox plus navigation into a Nostr chat. */
internal class TwillInbox(
    val controller: TwillInboxController,
    val onOpenNostrChat: (peerPubKey: String) -> Unit,
)

/** Provided only by the real conversation list route; null keeps the upstream list. */
internal val LocalTwillInbox = staticCompositionLocalOf<TwillInbox?> { null }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class TwillInboxViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    accountStore: NostrAccountStore,
    private val store: NostrMessageStore,
    messenger: NostrMessenger,
    private val preferenceReader: AppPreferenceReader,
) : ViewModel(),
    TwillInboxController {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val smsCollapsed = MutableStateFlow(prefs.getBoolean(KEY_SMS_COLLAPSED, false))
    private val nostrCollapsed = MutableStateFlow(prefs.getBoolean(KEY_NOSTR_COLLAPSED, false))

    init {
        messenger.install()
    }

    private val conversations = accountStore.account.flatMapLatest { account ->
        if (account == null) flowOf(emptyList()) else store.conversations().map(::sortForList)
    }

    override val uiState: StateFlow<TwillInboxUiState> = combine(
        accountStore.account,
        smsCollapsed,
        nostrCollapsed,
        conversations,
    ) { account, isSmsCollapsed, isNostrCollapsed, nostrConversations ->
        TwillInboxUiState(
            isNostrEnabled = account != null,
            isSmsCollapsed = isSmsCollapsed,
            isNostrCollapsed = isNostrCollapsed,
            nostrConversations = nostrConversations,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TwillInboxUiState())

    /** Pinned first, then unread (when "Keep unread at top" is on), then newest. */
    private fun sortForList(items: List<NostrConversation>): List<NostrConversation> {
        val unreadOnTop = preferenceReader.isEnabled(AppBooleanPref.UNREAD_ON_TOP)
        return items.sortedWith(
            compareByDescending<NostrConversation> { it.isPinned }
                .thenByDescending { unreadOnTop && it.unreadCount > 0 }
                .thenByDescending { it.lastMessage.createdAtSeconds },
        )
    }

    override fun toggleSmsCollapsed() {
        smsCollapsed.value = !smsCollapsed.value
        prefs.edit().putBoolean(KEY_SMS_COLLAPSED, smsCollapsed.value).apply()
    }

    override fun toggleNostrCollapsed() {
        nostrCollapsed.value = !nostrCollapsed.value
        prefs.edit().putBoolean(KEY_NOSTR_COLLAPSED, nostrCollapsed.value).apply()
    }

    override fun setPinned(peerPubKey: String, isPinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { store.setPinned(peerPubKey, isPinned) }
    }

    override fun setRead(peerPubKey: String, isRead: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { store.setConversationRead(peerPubKey, isRead) }
    }

    override fun delete(peerPubKey: String) {
        viewModelScope.launch(Dispatchers.IO) { store.deleteConversation(peerPubKey) }
    }

    override fun resolvePeer(input: String): String? {
        val trimmed = input.trim().removePrefix("nostr:")
        return decodePublicKeyAsHexOrNull(trimmed)?.takeIf { it.length == PUBKEY_HEX_LENGTH }
    }

    private companion object {
        const val PREFS_NAME = "twill_inbox"
        const val KEY_SMS_COLLAPSED = "sms_collapsed"
        const val KEY_NOSTR_COLLAPSED = "nostr_collapsed"
        const val PUBKEY_HEX_LENGTH = 64
    }
}
