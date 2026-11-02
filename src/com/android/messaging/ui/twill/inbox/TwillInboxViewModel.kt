package com.android.messaging.ui.twill.inbox

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.messaging.R
import com.android.messaging.data.appsettings.model.AppBooleanPref
import com.android.messaging.data.appsettings.repository.AppPreferenceReader
import com.android.messaging.twill.SmsGlobalSearch
import com.android.messaging.twill.TwillChat
import com.android.messaging.twill.TwillGroupResult
import com.android.messaging.twill.TwillInboxSettings
import com.android.messaging.twill.TwillMember
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.TwillProtocolText
import com.android.messaging.twill.TwillProtocols
import com.android.messaging.twill.TwillRuntime
import com.android.messaging.twill.TwillSms
import com.android.messaging.ui.twill.search.TwillInboxSearch
import com.android.messaging.ui.twill.search.TwillPendingSearch
import com.android.messaging.util.OsUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

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
    /** The bottom bar option (Settings); the bar shows only with two or more services. */
    val isBottomBarEnabled: Boolean = false,
) {
    /** The tabs of the bottom bar: SMS (when on) and each set-up service. */
    val tabs: List<TwillInboxTab>
        get() = buildList {
            if (isSmsEnabled) add(TwillInboxTab.Sms)
            sections.forEach { add(TwillInboxTab.Service(it.protocol)) }
        }

    val isBottomBarVisible: Boolean
        get() = isBottomBarEnabled && tabs.size >= 2
}

/** A tab of the inbox's bottom bar. */
@Immutable
internal sealed interface TwillInboxTab {
    data object Sms : TwillInboxTab

    data class Service(
        val protocol: TwillProtocolId,
    ) : TwillInboxTab
}

/** Where a search result leads: an SMS/MMS conversation or another protocol's chat. */
@Immutable
internal sealed interface TwillSearchTarget {
    data class Sms(
        val conversationId: String,
    ) : TwillSearchTarget

    data class Chat(
        val protocol: TwillProtocolId,
        val chatId: String,
    ) : TwillSearchTarget
}

/** A conversation (name match) or message found by the inbox search. */
@Immutable
internal data class TwillInboxSearchItem(
    val target: TwillSearchTarget,
    val title: String,
    /** The message text; empty for a conversation whose name matched. */
    val text: String,
    val timeMillis: Long,
    val isOutgoing: Boolean,
    /** The message to select when the conversation opens; null for name matches. */
    val messageId: String?,
    @param:DrawableRes val iconRes: Int,
)

@Immutable
internal data class TwillInboxSearchResults(
    val conversations: List<TwillInboxSearchItem> = emptyList(),
    val messages: List<TwillInboxSearchItem> = emptyList(),
) {
    val isEmpty: Boolean get() = conversations.isEmpty() && messages.isEmpty()
}

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

    /** Conversations and messages of every protocol matching [query] (the inbox search). */
    suspend fun search(query: String): TwillInboxSearchResults

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
    /** Upstream's new SMS/MMS conversation screen. */
    val onNewSms: () -> Unit,
    /** Opens an SMS/MMS conversation by id. */
    val onOpenSms: (conversationId: String) -> Unit,
) {
    /** The "Search messages" bar at the top of the inbox. */
    val search = TwillInboxSearch(controller::search)

    /** The bottom bar's selected tab (when the bar is shown). */
    val selectedTab = mutableStateOf<TwillInboxTab>(TwillInboxTab.Sms)

    /** A service to open the new-chat picker on, skipping the service choice. */
    val pickerService = mutableStateOf<TwillProtocolId?>(null)

    /** The tab in use, or null when the bar isn't shown. */
    fun currentTab(state: TwillInboxUiState): TwillInboxTab? {
        if (!state.isBottomBarVisible) return null
        val tabs = state.tabs
        return selectedTab.value.takeIf { it in tabs } ?: tabs.first()
    }

    /** Start chat: in the selected tab's service when the bar is shown. */
    fun startChat(state: TwillInboxUiState) {
        when (val tab = currentTab(state)) {
            is TwillInboxTab.Service -> {
                pickerService.value = tab.protocol
                isNewChatPickerVisible.value = true
            }

            TwillInboxTab.Sms -> onNewSms()

            null -> if (state.isSmsEnabled) onNewSms() else isNewChatPickerVisible.value = true
        }
    }

    /** Opens a search result; a message result opens its conversation searching for it. */
    fun openSearchResult(item: TwillInboxSearchItem) {
        val query = search.query.trim()
        when (val target = item.target) {
            is TwillSearchTarget.Sms -> {
                if (item.messageId != null) {
                    TwillPendingSearch.set(
                        TwillPendingSearch.smsKey(target.conversationId),
                        query,
                        item.messageId,
                    )
                }
                onOpenSms(target.conversationId)
            }

            is TwillSearchTarget.Chat -> {
                if (item.messageId != null) {
                    TwillPendingSearch.set(
                        TwillPendingSearch.chatKey(target.protocol.key, target.chatId),
                        query,
                        item.messageId,
                    )
                }
                onOpenChat(target.protocol, target.chatId)
            }
        }
    }

    /**
     * Shows the "where to start a chat" picker: set by the new-message button when SMS is off,
     * and by a long press on it otherwise.
     */
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
    private val inboxSettings: TwillInboxSettings,
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
        combine(
            sms.isEnabled,
            smsCollapsed,
            sections,
            inboxSettings.isBottomBarEnabled,
        ) { smsEnabled, isSmsCollapsed, sectionList, bottomBar ->
            TwillInboxUiState(
                isSmsEnabled = smsEnabled,
                isSmsCollapsed = isSmsCollapsed,
                sections = sectionList,
                isBottomBarEnabled = bottomBar,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TwillInboxUiState(
                isSmsEnabled = sms.isEnabled.value,
                isBottomBarEnabled = inboxSettings.isBottomBarEnabled.value,
            ),
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

    override suspend fun search(query: String): TwillInboxSearchResults = coroutineScope {
        val smsHits = async {
            if (sms.isEnabled.value && OsUtil.hasRequiredPermissions()) {
                runCatching { SmsGlobalSearch.search(query) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
        val others = protocols.all.filter { it.isEnabled.value }.map { protocol ->
            async {
                val chats = withTimeoutOrNull(PROTOCOL_TIMEOUT_MILLIS) { protocol.chats.first() }
                    .orEmpty()
                val titles = chats.associate { it.chatId to it.title }
                val named = chats
                    .filter { it.title.contains(query, ignoreCase = true) }
                    .map { chat ->
                        TwillInboxSearchItem(
                            target = TwillSearchTarget.Chat(protocol.id, chat.chatId),
                            title = chat.title,
                            text = "",
                            timeMillis = chat.lastMessage?.timeMillis ?: 0L,
                            isOutgoing = false,
                            messageId = null,
                            iconRes = protocol.text.notificationIcon,
                        )
                    }
                val messages = protocol.searchAllMessages(query).map { hit ->
                    TwillInboxSearchItem(
                        target = TwillSearchTarget.Chat(protocol.id, hit.chatId),
                        title = titles[hit.chatId].orEmpty(),
                        text = hit.text,
                        timeMillis = hit.timeMillis,
                        isOutgoing = hit.isOutgoing,
                        messageId = hit.messageId,
                        iconRes = protocol.text.notificationIcon,
                    )
                }
                named to messages
            }
        }
        val smsItems = smsHits.await().map { hit ->
            TwillInboxSearchItem(
                target = TwillSearchTarget.Sms(hit.conversationId),
                title = hit.conversationName,
                text = hit.text,
                timeMillis = hit.timeMillis,
                isOutgoing = hit.isOutgoing,
                messageId = hit.messageId,
                iconRes = R.drawable.ic_notification_sms,
            )
        }
        val protocolResults = others.awaitAll()
        TwillInboxSearchResults(
            conversations = (
                smsItems.filter { it.messageId == null } +
                    protocolResults.flatMap { it.first }
                ).sortedByDescending { it.timeMillis },
            messages = (
                smsItems.filter { it.messageId != null } +
                    protocolResults.flatMap { it.second }
                ).sortedByDescending { it.timeMillis }.take(MAX_MESSAGE_RESULTS),
        )
    }

    private companion object {
        const val PROTOCOL_TIMEOUT_MILLIS = 4_000L
        const val MAX_MESSAGE_RESULTS = 200
        const val PREFS_NAME = "twill_inbox"
        const val KEY_SMS_COLLAPSED = "sms_collapsed"

        /** "nostr_collapsed" matches what older builds stored. */
        fun collapsedKey(protocol: TwillProtocolId) = protocol.key + "_collapsed"
    }
}
