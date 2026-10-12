package com.android.messaging.ui.nostr.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.messaging.nostr.NostrAccount
import com.android.messaging.nostr.NostrAccountStore
import com.android.messaging.nostr.NostrChatKey
import com.android.messaging.nostr.NostrError
import com.android.messaging.nostr.NostrGroupInfo
import com.android.messaging.nostr.NostrMedia
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
import kotlinx.coroutines.flow.SharedFlow
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
    val media: NostrMedia,
    accountStore: NostrAccountStore,
) : ViewModel() {

    private val chat = MutableStateFlow<String?>(null)

    val account: StateFlow<NostrAccount?> = accountStore.account

    val messages: StateFlow<List<NostrMessage>> = chat
        .flatMapLatest { key -> key?.let(store::messages) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Profiles of everyone else in the chat, by public key. */
    val profiles: StateFlow<Map<String, NostrProfile>> = chat
        .filterNotNull()
        .flatMapLatest { key -> store.profiles(NostrChatKey.members(key)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val group: StateFlow<NostrGroupInfo?> = chat
        .filterNotNull()
        .flatMapLatest { key -> store.group(key) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloading: StateFlow<Set<String>> = messenger.downloading

    val errors: SharedFlow<NostrError> = messenger.errors

    fun bind(chatKey: String) {
        if (chat.value == chatKey) return
        chat.value = chatKey
        messenger.install()
        NostrChatKey.members(chatKey).forEach(messenger::refreshProfile)
    }

    fun signer(): NostrSigner? = signerProvider.signer()

    fun setActive(isActive: Boolean) {
        messenger.setActiveChat(if (isActive) chat.value else null)
    }

    fun send(text: String) {
        val target = chat.value ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        messenger.send(chatKey = target, text = trimmed)
    }

    fun sendFile(uri: Uri) {
        val target = chat.value ?: return
        messenger.sendFile(chatKey = target, uri = uri)
    }

    fun download(message: NostrMessage) {
        messenger.download(message)
    }

    fun rename(name: String) {
        val target = chat.value ?: return
        messenger.renameGroup(target, name)
    }

    fun retry(message: NostrMessage) {
        messenger.retry(message)
    }

    fun markRead() {
        val target = chat.value ?: return
        viewModelScope.launch(Dispatchers.IO) { store.markConversationRead(target) }
    }
}
