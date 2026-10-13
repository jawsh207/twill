package com.android.messaging.ui.twill.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.messaging.twill.TwillAppVisibility
import com.android.messaging.twill.TwillChatDetails
import com.android.messaging.twill.TwillError
import com.android.messaging.twill.TwillMessage
import com.android.messaging.twill.TwillProtocol
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.TwillProtocols
import com.android.messaging.twill.TwillRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** One chat of any Twill protocol. Call [bind] before use. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class TwillChatViewModel @Inject constructor(
    private val protocols: TwillProtocols,
    private val runtime: TwillRuntime,
    private val visibility: TwillAppVisibility,
) : ViewModel() {

    private data class Target(
        val protocol: TwillProtocol,
        val chatId: String,
    )

    private val target = MutableStateFlow<Target?>(null)

    val messages: StateFlow<List<TwillMessage>> = target
        .flatMapLatest { it?.protocol?.messages(it.chatId) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val details: StateFlow<TwillChatDetails?> = target
        .flatMapLatest { it?.protocol?.details(it.chatId) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloading: StateFlow<Set<String>> = target
        .flatMapLatest { it?.protocol?.downloading ?: flowOf(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val errors = target.flatMapLatest { it?.protocol?.errors ?: emptyFlow<TwillError>() }

    fun bind(protocolId: TwillProtocolId, chatId: String) {
        val current = target.value
        if (current?.protocol?.id == protocolId && current.chatId == chatId) return
        runtime.start(markVisible = true)
        val protocol = protocols[protocolId]
        target.value = Target(protocol, chatId)
        protocol.onChatOpened(chatId)
    }

    /** While a chat is on screen, its messages don't raise notifications. */
    fun setActive(isActive: Boolean) {
        val current = target.value ?: return
        visibility.setActiveChat(current.protocol.id, if (isActive) current.chatId else null)
    }

    private inline fun withTarget(block: (TwillProtocol, String) -> Unit) {
        target.value?.let { block(it.protocol, it.chatId) }
    }

    fun send(text: String) = withTarget { protocol, chatId ->
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) protocol.send(chatId, trimmed)
    }

    fun sendFile(uri: Uri) = withTarget { protocol, chatId -> protocol.sendFile(chatId, uri) }

    fun download(message: TwillMessage) = withTarget { protocol, _ -> protocol.download(message) }

    fun retry(message: TwillMessage) = withTarget { protocol, _ -> protocol.retry(message) }

    fun rename(name: String) = withTarget { protocol, chatId -> protocol.rename(chatId, name) }

    fun markRead() = withTarget { protocol, chatId -> protocol.markRead(chatId) }

    fun accept() = withTarget { protocol, chatId -> protocol.acceptRequest(chatId) }

    fun block() = withTarget { protocol, chatId -> protocol.blockRequest(chatId) }
}
