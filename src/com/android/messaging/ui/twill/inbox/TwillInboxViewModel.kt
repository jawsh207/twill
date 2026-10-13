package com.android.messaging.ui.twill.inbox

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.messaging.data.appsettings.model.AppBooleanPref
import com.android.messaging.data.appsettings.repository.AppPreferenceReader
import com.android.messaging.twill.TwillChat
import com.android.messaging.twill.TwillGroupResult
import com.android.messaging.twill.TwillMember
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.TwillProtocolText
import com.android.messaging.twill.TwillProtocols
import com.android.messaging.twill.TwillRuntime
import com.android.messaging.twill.TwillSms
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** One protocol's part of the conversation list. */
@Immutable
internal data class TwillInboxSection(
    val protocol: TwillProtocolId,
    val text: TwillProtocolText,
    val isCollapsed: Boolean,
    val chats: List<TwillChat>,
)

@Immutable
internal data class TwillInboxUiState(
    /** Twill handles SMS/MMS (see [com.android.messaging.twill.TwillSms]). */
    val isSmsEnabled: Boolean = true,
    val isSmsCollapsed: Boolean = false,
    /** Enabled protocols only. Empty hides the section headers: the list looks like SMS. */
    val sections: List<TwillInboxSection> = emptyList(),
)

/** Actions the conversation list needs from Twill's protocols. */
internal interface TwillInboxController {
    val uiState: StateFlow<TwillInboxUiState>

    fun toggleSmsCollapsed()
    fun toggleCollapsed(protocol: TwillProtocolId)
    fun setPinned(chat: TwillChat, isPinned: Boolean)
    fun setRead(chat: TwillChat, isRead: Boolean)
    fun delete(chat: TwillChat)

    /** A chat id for what the user typed, or null if it isn't valid for [protocol]. */
    suspend fun startChat(protocol: TwillProtocolId, input: String): String?

    suspend fun knownContacts(protocol: TwillProtocolId): List<TwillMember>

    suspend fun createGroup(
        protocol: TwillProtocolId,
        name: String,
        contactIds: List<String>,
        typedEntries: List<String>,
    ): TwillGroupResult
}

/** The inbox plus navigation into a chat. */
internal class TwillInbox(
    val controller: TwillInboxController,
    val onOpenChat: (protocol: TwillProtocolId, chatId: String) -> Unit,
    val onOpenSettings: () -> Unit,
) {
    /** Set by the new-message button when SMS is off: pick a protocol to start a chat in. */
    val isNewChatPickerVisible = mutableStateOf(false)
}

/** Provided only by the real conversation list route; null keeps the upstream list. */
internal val LocalTwillInbox = staticCompositionLocalOf<TwillInbox?> { null }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class TwillInboxViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    private val protocols: TwillProtocols,
    runtime: TwillRuntime,
    private val preferenceReader: AppPreferenceReader,
    private val sms: TwillSms,
) : ViewModel(),
    TwillInboxController {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val smsCollapsed = MutableStateFlow(prefs.getBoolean(KEY_SMS_COLLAPSED, false))
    private val collapsed = MutableStateFlow(
        TwillProtocolId.entries.filter { prefs.getBoolean(collapsedKey(it), false) }.toSet(),
    )

    init {
        runtime.start(markVisible = true)
    }

    private val sections: Flow<List<TwillInboxSection>> = run {
        val perProtocol = protocols.all.map { protocol ->
            protocol.isEnabled.flatMapLatest { enabled ->
                if (!enabled) {
                    flowOf(null)
                } else {
                    combine(protocol.chats, collapsed) { chats, collapsedSet ->
                        TwillInboxSection(
                            protocol = protocol.id,
                            text = protocol.text,
                            isCollapsed = protocol.id in collapsedSet,
                            chats = sortForList(chats),
                        )
                    }
                }
            }
        }
        if (perProtocol.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(perProtocol) { it.filterNotNull() }
        }
    }

    override val uiState: StateFlow<TwillInboxUiState> =
        combine(sms.isEnabled, smsCollapsed, sections) { smsEnabled, isSmsCollapsed, sectionList ->
            TwillInboxUiState(
                isSmsEnabled = smsEnabled,
                isSmsCollapsed = isSmsCollapsed,
                sections = sectionList,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TwillInboxUiState(isSmsEnabled = sms.isEnabled.value),
        )

    /** Pinned first, then unread (when "Keep unread at top" is on), then newest. */
    private fun sortForList(items: List<TwillChat>): List<TwillChat> {
        val unreadOnTop = preferenceReader.isEnabled(AppBooleanPref.UNREAD_ON_TOP)
        return items.sortedWith(
            compareByDescending<TwillChat> { it.isPinned }
                .thenByDescending { unreadOnTop && it.unreadCount > 0 }
                .thenByDescending { it.lastMessage?.timeMillis ?: 0L },
        )
    }

    override fun toggleSmsCollapsed() {
        smsCollapsed.value = !smsCollapsed.value
        prefs.edit().putBoolean(KEY_SMS_COLLAPSED, smsCollapsed.value).apply()
    }

    override fun toggleCollapsed(protocol: TwillProtocolId) {
        val isCollapsed = protocol !in collapsed.value
        collapsed.value =
            if (isCollapsed) collapsed.value + protocol else collapsed.value - protocol
        prefs.edit().putBoolean(collapsedKey(protocol), isCollapsed).apply()
    }

    override fun setPinned(chat: TwillChat, isPinned: Boolean) =
        protocols[chat.protocol].setPinned(chat.chatId, isPinned)

    override fun setRead(chat: TwillChat, isRead: Boolean) =
        protocols[chat.protocol].setRead(chat.chatId, isRead)

    override fun delete(chat: TwillChat) = protocols[chat.protocol].delete(chat.chatId)

    override suspend fun startChat(protocol: TwillProtocolId, input: String): String? =
        protocols[protocol].startChat(input)

    override suspend fun knownContacts(protocol: TwillProtocolId): List<TwillMember> =
        protocols[protocol].knownContacts()

    override suspend fun createGroup(
        protocol: TwillProtocolId,
        name: String,
        contactIds: List<String>,
        typedEntries: List<String>,
    ): TwillGroupResult = protocols[protocol].createGroup(name, contactIds, typedEntries)

    private companion object {
        const val PREFS_NAME = "twill_inbox"
        const val KEY_SMS_COLLAPSED = "sms_collapsed"

        /** "nostr_collapsed" matches what older builds stored. */
        fun collapsedKey(protocol: TwillProtocolId) = protocol.key + "_collapsed"
    }
}
