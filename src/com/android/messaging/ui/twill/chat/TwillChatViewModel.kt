package com.android.messaging.ui.twill.chat

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.messaging.data.appsettings.model.AppBooleanPref
import com.android.messaging.data.appsettings.repository.AppPreferenceReader
import com.android.messaging.data.appsettings.repository.AppSettingsRepository
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.twill.TwillActionError
import com.android.messaging.twill.TwillAppVisibility
import com.android.messaging.twill.TwillChatDetails
import com.android.messaging.twill.TwillError
import com.android.messaging.twill.TwillMessage
import com.android.messaging.twill.TwillNotifications
import com.android.messaging.twill.TwillProtocol
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.TwillProtocols
import com.android.messaging.twill.TwillRuntime
import com.android.messaging.twill.TwillSearchHit
import com.android.messaging.twill.TwillWallet
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One chat of any Twill protocol. Call [bind] before use. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class TwillChatViewModel @Inject constructor(
    private val protocols: TwillProtocols,
    private val runtime: TwillRuntime,
    private val visibility: TwillAppVisibility,
    private val wallet: TwillWallet,
    // A held message is sent even if the chat is closed during the undo window, as in SMS.
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
    private val appSettings: AppSettingsRepository,
) : ViewModel() {

    private val _linkPreviews = MutableStateFlow(TwillLinkPreviews())

    /** SMS's link preview settings (both off by default), which Twill chats follow too. */
    val linkPreviews: StateFlow<TwillLinkPreviews> = _linkPreviews.asStateFlow()

    /** Re-reads the link preview settings, which may have changed while the chat was hidden. */
    fun refreshSettings() {
        viewModelScope.launch {
            _linkPreviews.value = TwillLinkPreviews(
                websites = appSettings.isWebLinkPreviewsEnabled(),
                youTube = appSettings.isYouTubeLinkPreviewsEnabled(),
            )
        }
    }

    val isWalletConnected: StateFlow<Boolean> = wallet.isConnected
    val paidInvoices: StateFlow<Set<String>> = wallet.paidInvoices

    /** Pays an invoice card's invoice; throws [TwillActionError]. */
    suspend fun pay(invoice: String) = wallet.pay(invoice)

    /** Whether the chat's person can be zapped. */
    fun canZap(): Boolean = target.value?.let { it.protocol.canZap(it.chatId) } == true

    /** Zaps the chat's person; see [TwillProtocol.zap]. */
    suspend fun zap(sats: Long, comment: String): String? {
        val current = target.value ?: return null
        return current.protocol.zap(current.chatId, sats, comment)
    }

    /** Floats this chat in a bubble; see [TwillNotifications.bubbleChat]. */
    suspend fun bubble(context: Context): TwillNotifications.BubbleResult {
        val current = target.value ?: return TwillNotifications.BubbleResult.Failed
        val info = details.value ?: return TwillNotifications.BubbleResult.Failed
        return withContext(Dispatchers.IO) {
            val picture = info.avatar.ref?.let { ref ->
                runCatching { current.protocol.loadAvatar(ref) }.getOrNull()
            }
            TwillNotifications.bubbleChat(
                context = context.applicationContext,
                protocol = current.protocol.id,
                chatId = current.chatId,
                title = info.title,
                isGroup = info.isGroup,
                picture = picture,
            )
        }
    }

    private data class Target(
        val protocol: TwillProtocol,
        val chatId: String,
    )

    private val target = MutableStateFlow<Target?>(null)

    /** How many of the newest messages to show; grows as the user scrolls up. */
    private val limit = MutableStateFlow(PAGE_SIZE)

    val messages: StateFlow<List<TwillMessage>> = combine(target, limit) { current, count ->
        current to count
    }
        .flatMapLatest { (current, count) ->
            current?.protocol?.messages(current.chatId, count) ?: flowOf(emptyList())
        }
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
        limit.value = PAGE_SIZE
        target.value = Target(protocol, chatId)
        protocol.onChatOpened(chatId)
    }

    /** While a chat is on screen, its messages don't raise notifications. */
    fun setActive(isActive: Boolean, isInBubble: Boolean = false) {
        val current = target.value ?: return
        visibility.setActiveChat(
            protocol = current.protocol.id,
            chatId = if (isActive) current.chatId else null,
            cancelNotification = !isInBubble,
        )
    }

    private inline fun withTarget(block: (TwillProtocol, String) -> Unit) {
        target.value?.let { block(it.protocol, it.chatId) }
    }

    /** Whether long-pressing a message offers Reply and reactions in this chat. */
    val canReplyAndReact: Boolean
        get() = target.value?.protocol?.canReplyAndReact == true

    fun react(message: TwillMessage, emoji: String) = withTarget { protocol, chatId ->
        // One of each emoji per person: tapping one you've already sent does nothing.
        if (message.reactions.none { it.emoji == emoji && it.isMine }) {
            protocol.react(chatId, message.id, emoji)
        }
    }

    fun sendFile(uri: Uri) = withTarget { protocol, chatId -> protocol.sendFile(chatId, uri) }

    /** The mic records voice messages here (Delta Chat); otherwise the button only sends. */
    val canSendVoice: Boolean
        get() = target.value?.protocol?.canSendVoice == true

    /** A message held by "Delay sending" (Settings), with when the hold began. */
    data class HeldSend(
        val startedAtElapsedMillis: Long,
        val durationMillis: Long,
    )

    private val _heldSend = MutableStateFlow<HeldSend?>(null)
    val heldSend: StateFlow<HeldSend?> = _heldSend.asStateFlow()
    private var heldJob: Job? = null

    /**
     * Sends [text] (answering [replyToId], or as [voice]'s caption), then calls [onSent]. With
     * "Delay sending" on, it is held for a few seconds first, as SMS messages are; [undoSend]
     * during that time cancels it and [onSent] isn't called, so the message stays in the box.
     */
    fun submit(text: String, replyToId: String?, voice: java.io.File?, onSent: () -> Unit) {
        val current = target.value ?: return
        val send = {
            if (voice != null) {
                current.protocol.sendVoice(current.chatId, voice, text.trim())
            } else {
                val trimmed = text.trim()
                when {
                    trimmed.isEmpty() -> Unit
                    replyToId != null ->
                        current.protocol.sendReply(current.chatId, trimmed, replyToId)
                    else -> current.protocol.send(current.chatId, trimmed)
                }
            }
            onSent()
        }
        if (!AppPreferenceReader.Default.isEnabled(AppBooleanPref.SEND_DELAY)) {
            send()
            return
        }
        heldJob?.cancel()
        heldJob = appScope.launch(Dispatchers.Main) {
            _heldSend.value = HeldSend(SystemClock.elapsedRealtime(), SEND_DELAY_MILLIS)
            try {
                delay(SEND_DELAY_MILLIS)
            } finally {
                _heldSend.value = null
            }
            send()
        }
    }

    /** Deletes [message] from this phone only. */
    fun deleteForMe(message: TwillMessage) = withTarget { protocol, chatId ->
        viewModelScope.launch { protocol.deleteMessages(chatId, listOf(message.id)) }
    }

    /** Whether your own messages can be deleted for everyone in this chat. */
    val canDeleteForEveryone: Boolean
        get() = target.value?.protocol?.canDeleteForEveryone == true

    fun deleteForEveryone(message: TwillMessage) = withTarget { protocol, chatId ->
        protocol.deleteForEveryone(chatId, message.id)
    }

    /** Whether this chat can have disappearing messages. */
    val canDisappear: Boolean
        get() = target.value?.protocol?.canDisappear == true

    /** How long new messages here last, in seconds (0: they don't disappear). */
    val disappearAfter: StateFlow<Long> = target
        .flatMapLatest { it?.protocol?.disappearAfter(it.chatId) ?: flowOf(0L) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_MILLIS), 0L)

    fun setDisappearAfter(seconds: Long) = withTarget { protocol, chatId ->
        protocol.setDisappearAfter(chatId, seconds)
    }

    /** Cancels a message held by "Delay sending". */
    fun undoSend() {
        heldJob?.takeIf { it.isActive }?.cancel()
        heldJob = null
    }

    fun download(message: TwillMessage) = withTarget { protocol, _ -> protocol.download(message) }

    fun retry(message: TwillMessage) = withTarget { protocol, _ -> protocol.retry(message) }

    fun rename(name: String) = withTarget { protocol, chatId -> protocol.rename(chatId, name) }

    /** Shows older messages, if the current page is full (so there may be more). */
    fun loadOlder() {
        if (messages.value.size >= limit.value) limit.value += PAGE_SIZE
    }

    /** Messages of this chat containing [query], newest first. */
    suspend fun search(query: String): List<TwillSearchHit> {
        val current = target.value ?: return emptyList()
        return current.protocol.searchMessages(current.chatId, query)
    }

    /** Loads at least the [count] newest messages (to show a search match). */
    fun ensureLoaded(count: Int) {
        if (count > limit.value) limit.value = count + PAGE_SIZE / 2
    }

    fun markRead() = withTarget { protocol, chatId -> protocol.markRead(chatId) }

    fun accept() = withTarget { protocol, chatId -> protocol.acceptRequest(chatId) }

    fun block() = withTarget { protocol, chatId -> protocol.blockRequest(chatId) }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

/** How long "Delay sending" holds a message, the same as for SMS. */
private const val SEND_DELAY_MILLIS = 3_000L

/** How long the chat's flows keep running after the screen stops watching them. */
private const val SUBSCRIBE_MILLIS = 5_000L
