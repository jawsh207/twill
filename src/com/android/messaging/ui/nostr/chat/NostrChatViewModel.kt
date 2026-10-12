package com.android.messaging.ui.nostr.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.messaging.nostr.NostrAccount
import com.android.messaging.nostr.NostrAccountStore
import com.android.messaging.nostr.NostrMessage
import com.android.messaging.nostr.NostrMessageStore
import com.android.messaging.nostr.NostrMessenger
import com.android.messaging.nostr.NostrProfile
import com.android.messaging.nostr.NostrSignerProvider
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class NostrChatViewModel @Inject constructor(
    private val store: NostrMessageStore,
    private val messenger: NostrMessenger,
    private val signerProvider: NostrSignerProvider,
    accountStore: NostrAccountStore,
) : ViewModel() {

    private val peer = MutableStateFlow<String?>(null)

    val account: StateFlow<NostrAccount?> = accountStore.account

    val messages: StateFlow<List<NostrMessage>> = peer
        .flatMapLatest { pubKey -> pubKey?.let(store::messages) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profile: StateFlow<NostrProfile?> = peer
        .filterNotNull()
        .flatMapLatest { pubKey -> store.profile(pubKey) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun bind(peerPubKey: String) {
        if (peer.value == peerPubKey) return
        peer.value = peerPubKey
        messenger.install()
        messenger.refreshProfile(peerPubKey)
    }

    fun signer(): NostrSigner? = signerProvider.signer()

    fun setActive(isActive: Boolean) {
        messenger.setActiveChat(if (isActive) peer.value else null)
    }

    fun send(text: String) {
        val target = peer.value ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        messenger.send(peerPubKey = target, text = trimmed)
    }

    fun retry(message: NostrMessage) {
        messenger.retry(message)
    }

    fun markRead() {
        val target = peer.value ?: return
        viewModelScope.launch(Dispatchers.IO) { store.markConversationRead(target) }
    }
}
