package com.android.messaging.nostr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.android.messaging.R
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.twill.TwillAttachment
import com.android.messaging.twill.TwillAvatar
import com.android.messaging.twill.TwillChat
import com.android.messaging.twill.TwillChatDetails
import com.android.messaging.twill.TwillDeliveryMode
import com.android.messaging.twill.TwillError
import com.android.messaging.twill.TwillGroupResult
import com.android.messaging.twill.TwillMember
import com.android.messaging.twill.TwillMessage
import com.android.messaging.twill.TwillProtocol
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.TwillProtocolText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nostr private messages (NIP-17) as one of Twill's protocols. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class NostrProtocol @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountStore: NostrAccountStore,
    private val store: NostrMessageStore,
    private val messenger: NostrMessenger,
    private val media: NostrMedia,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) : TwillProtocol {

    override val id = TwillProtocolId.Nostr

    override val text = TwillProtocolText(
        sectionTitle = R.string.inbox_section_nostr,
        newChatTitle = R.string.inbox_new_nostr_chat,
        newChatHint = R.string.inbox_new_nostr_chat_hint,
        newChatInvalid = R.string.inbox_new_nostr_chat_invalid,
        groupMembersHint = R.string.inbox_group_members_hint,
        groupMembersHelp = R.string.inbox_group_members_help,
        emptySection = R.string.inbox_nostr_empty,
        composerSignedOut = R.string.nostr_composer_signed_out,
        composerHint = R.string.nostr_composer_hint,
        emptyChat = R.string.nostr_chat_empty,
        emptyChatUnencrypted = R.string.nostr_chat_empty,
        uploadFailed = R.string.nostr_upload_failed,
        renameHelp = R.string.nostr_group_rename_help,
        notificationIcon = R.drawable.ic_notification_nostr,
        canTypeGroupMembers = true,
    )

    override val isEnabled: StateFlow<Boolean> = accountStore.account
        .map { it != null }
        .stateIn(appScope, SharingStarted.Eagerly, accountStore.account.value != null)

    override val deliveryMode: StateFlow<TwillDeliveryMode> = accountStore.deliveryMode

    override val downloading: StateFlow<Set<String>> = messenger.downloading

    override val errors: SharedFlow<TwillError> = messenger.errors

    override val chats: Flow<List<TwillChat>> = accountStore.account.flatMapLatest { account ->
        if (account == null) {
            flowOf(emptyList())
        } else {
            combine(store.conversations(), accountStore.loadProfilePictures) { list, pictures ->
                list.map { it.toTwill(showPictures = pictures) }
            }
        }
    }

    override fun messages(chatId: String, limit: Int): Flow<List<TwillMessage>> =
        store.messages(chatId, limit).map { list -> list.map { it.toTwill() } }

    override fun details(chatId: String): Flow<TwillChatDetails> {
        val members = NostrChatKey.members(chatId)
        val isGroup = NostrChatKey.isGroup(chatId)
        return combine(
            store.profiles(members),
            store.group(chatId),
            accountStore.loadProfilePictures,
            accountStore.account,
        ) { profiles, group, pictures, account ->
            fun nameOf(pubKey: String) =
                profiles[pubKey]?.name ?: NostrSignerProvider.shortNpub(pubKey)

            val memberMap = members.associateWith { pubKey ->
                val profile = profiles[pubKey]
                val verified = profile?.takeIf { it.isNip05Verified }?.nip05
                TwillMember(
                    id = pubKey,
                    name = nameOf(pubKey),
                    detail = verified?.removePrefix("_@") ?: NostrSignerProvider.npubOf(pubKey),
                    isVerified = verified != null,
                    avatar = TwillAvatar(
                        name = nameOf(pubKey),
                        ref = profile?.picture?.takeIf { pictures },
                    ),
                )
            }
            val title = when {
                isGroup -> group.name ?: joinNames(members.map(::nameOf))
                else -> nameOf(chatId)
            }
            val peer = memberMap[chatId]
            TwillChatDetails(
                chatId = chatId,
                title = title,
                subtitle = when {
                    isGroup -> context.getString(R.string.nostr_group_subtitle, members.size + 1)
                    peer?.isVerified == true -> peer.detail.orEmpty()
                    else -> context.getString(
                        R.string.nostr_chat_subtitle,
                        NostrSignerProvider.shortNpub(chatId),
                    )
                },
                isSubtitleVerified = !isGroup && peer?.isVerified == true,
                avatar = TwillAvatar(
                    name = title,
                    ref = if (isGroup) null else peer?.avatar?.ref,
                    isGroup = isGroup,
                ),
                isGroup = isGroup,
                canRename = isGroup,
                canSend = account != null,
                isRequest = false,
                members = memberMap,
            )
        }
    }

    override fun install() = messenger.install()

    override fun onChatOpened(chatId: String) {
        NostrChatKey.members(chatId).forEach { messenger.refreshProfile(it) }
    }

    override fun send(chatId: String, text: String) = messenger.send(chatKey = chatId, text = text)

    override fun sendFile(chatId: String, uri: Uri) = messenger.sendFile(
        chatKey = chatId,
        uri = uri
    )

    override fun retry(message: TwillMessage) {
        appScope.launch(Dispatchers.IO) { store.message(message.id)?.let(messenger::retry) }
    }

    override fun download(message: TwillMessage) {
        appScope.launch(Dispatchers.IO) { store.message(message.id)?.let(messenger::download) }
    }

    override fun markRead(chatId: String) {
        appScope.launch(Dispatchers.IO) { store.markConversationRead(chatId) }
    }

    override fun setRead(chatId: String, isRead: Boolean) {
        appScope.launch(Dispatchers.IO) { store.setConversationRead(chatId, isRead) }
    }

    override fun setPinned(chatId: String, isPinned: Boolean) {
        appScope.launch(Dispatchers.IO) { store.setPinned(chatId, isPinned) }
    }

    override fun delete(chatId: String) {
        appScope.launch(Dispatchers.IO) { store.deleteConversation(chatId) }
    }

    override fun rename(chatId: String, name: String) = messenger.renameGroup(chatId, name)

    override suspend fun startChat(input: String): String? = messenger.resolveIdentifier(input)

    override suspend fun knownContacts(): List<TwillMember> = withContext(Dispatchers.IO) {
        val pictures = accountStore.loadProfilePictures.value
        store.conversations().first()
            .filter { !it.isGroup }
            .map { conversation ->
                val name = conversationTitle(conversation)
                TwillMember(
                    id = conversation.chatKey,
                    name = name,
                    detail = NostrSignerProvider.shortNpub(conversation.chatKey),
                    avatar = TwillAvatar(name, conversation.pictureUrl?.takeIf { pictures }),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun createGroup(
        name: String,
        contactIds: List<String>,
        typedEntries: List<String>,
    ): TwillGroupResult {
        val me = accountStore.account.value?.pubKeyHex
            ?: return TwillGroupResult.Invalid(emptyList())
        val resolved = typedEntries.map { entry -> entry to startChat(entry) }
        val invalid = resolved.filter { it.second == null }.map { it.first }
        if (invalid.isNotEmpty()) return TwillGroupResult.Invalid(invalid)
        val members = (contactIds + resolved.mapNotNull { it.second }).distinct()
        if (members.isEmpty()) return TwillGroupResult.Invalid(emptyList())
        val chatKey = NostrChatKey.forParticipants(participants = members, me = me)
        if (name.isNotBlank() && NostrChatKey.isGroup(chatKey)) messenger.renameGroup(chatKey, name)
        return TwillGroupResult.Created(chatKey)
    }

    override suspend fun loadAvatar(ref: String): Bitmap? =
        if (accountStore.loadProfilePictures.value) media.loadAvatar(ref) else null

    override suspend fun syncOnce() = messenger.syncOnce()

    override fun setKeepConnectedInBackground(enabled: Boolean) =
        messenger.setKeepConnectedInBackground(enabled)

    private fun NostrConversation.toTwill(showPictures: Boolean): TwillChat {
        val title = conversationTitle(this)
        val last = lastMessage
        val senderIndex = NostrChatKey.members(chatKey).indexOf(last.senderPubKey)
        return TwillChat(
            protocol = id,
            chatId = chatKey,
            title = title,
            avatar = TwillAvatar(
                name = title,
                ref = pictureUrl?.takeIf { showPictures && !isGroup },
                isGroup = isGroup,
            ),
            isGroup = isGroup,
            isPinned = isPinned,
            unreadCount = unreadCount,
            lastMessage = last.toTwill(),
            lastSenderName = when {
                !isGroup || last.isOutgoing -> null
                else -> memberNames.getOrNull(senderIndex)
                    ?: NostrSignerProvider.shortNpub(last.senderPubKey)
            },
        )
    }

    private fun NostrMessage.toTwill() = TwillMessage(
        id = id,
        chatId = chatKey,
        senderId = senderPubKey,
        text = content,
        timeMillis = createdAtSeconds * 1000,
        isOutgoing = isOutgoing,
        status = when (status) {
            NostrMessage.Status.Sending -> TwillMessage.Status.Sending
            NostrMessage.Status.Failed -> TwillMessage.Status.Failed
            NostrMessage.Status.Sent -> TwillMessage.Status.Sent
        },
        attachment = attachment?.let {
            TwillAttachment(
                mimeType = it.mimeType,
                fileName = it.fileName,
                size = it.size,
                width = it.width,
                height = it.height,
                localPath = it.localPath?.takeIf { path -> path.isNotEmpty() },
            )
        },
    )

    companion object {
        /** A conversation's title: its name, or members' names, or short npubs. */
        fun conversationTitle(conversation: NostrConversation): String {
            conversation.displayName?.let { return it }
            val members = NostrChatKey.members(conversation.chatKey)
            return joinNames(
                members.mapIndexed { index, pubKey ->
                    conversation.memberNames.getOrNull(index)
                        ?: NostrSignerProvider.shortNpub(pubKey)
                },
            )
        }

        private fun joinNames(names: List<String>): String = when {
            names.size <= 3 -> names.joinToString(", ")
            else -> names.take(3).joinToString(", ") + " +" + (names.size - 3)
        }
    }
}
