package com.android.messaging.deltachat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.android.messaging.R
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.twill.TwillAppVisibility
import com.android.messaging.twill.TwillAttachment
import com.android.messaging.twill.TwillAvatar
import com.android.messaging.twill.TwillChat
import com.android.messaging.twill.TwillChatDetails
import com.android.messaging.twill.TwillDeliveryMode
import com.android.messaging.twill.TwillError
import com.android.messaging.twill.TwillGroupResult
import com.android.messaging.twill.TwillImages
import com.android.messaging.twill.TwillMember
import com.android.messaging.twill.TwillMessage
import com.android.messaging.twill.TwillNotifications
import com.android.messaging.twill.TwillProtocol
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.twill.TwillProtocolText
import com.android.messaging.util.LogUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Delta Chat (chatmail) as one of Twill's protocols, backed by the official core over
 * JSON-RPC ([DeltaChatRpc]). The core does the hard parts: email transport, end-to-end
 * encryption, groups, multi-device and its own database; this class maps it onto Twill's
 * shared inbox and chat screen and turns core events into notifications.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class DeltaChatProtocol @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val rpc: DeltaChatRpc,
    private val accountStore: DeltaChatAccountStore,
    private val visibility: TwillAppVisibility,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) : TwillProtocol {

    override val id = TwillProtocolId.DeltaChat

    override val text = TwillProtocolText(
        sectionTitle = R.string.deltachat_section,
        newChatTitle = R.string.deltachat_new_chat,
        newChatHint = R.string.deltachat_new_chat_hint,
        newChatInvalid = R.string.deltachat_new_chat_invalid,
        groupMembersHint = R.string.inbox_group_members_hint,
        groupMembersHelp = R.string.inbox_group_members_help,
        emptySection = R.string.deltachat_empty,
        composerSignedOut = R.string.deltachat_cannot_send,
        renameHelp = null,
        notificationIcon = R.drawable.ic_notification_deltachat,
        canTypeGroupMembers = false,
    )

    override val isEnabled: StateFlow<Boolean> = accountStore.isConfigured

    override val deliveryMode: StateFlow<TwillDeliveryMode> = accountStore.deliveryMode

    /** True when this build includes the Delta Chat core. */
    val isAvailable: Boolean
        get() = rpc.isAvailable

    private val changes = MutableStateFlow(0L)
    private val keepConnectedInBackground = MutableStateFlow(false)
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    private val _errors = MutableSharedFlow<TwillError>(extraBufferCapacity = 8)
    private val _configureProgress = MutableStateFlow<Int?>(null)
    private val setupMutex = Mutex()
    private var isInstalled = false

    override val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    override val errors: SharedFlow<TwillError> = _errors.asSharedFlow()

    /** Setup progress from 0 to 1000 while configuring, else null. */
    val configureProgress: StateFlow<Int?> = _configureProgress.asStateFlow()

    /** Bumped whenever anything the UI shows may have changed. */
    val changeCount: StateFlow<Long> = changes.asStateFlow()

    override fun install() {
        synchronized(this) {
            if (isInstalled) return
            isInstalled = true
        }

        appScope.launch {
            rpc.events.collect { event -> onEvent(event) }
        }

        // Run the core's network (IO) while Twill is open or "always connected" is on.
        appScope.launch {
            combine(
                accountStore.isConfigured,
                accountStore.accountId,
                visibility.isRecentlyVisible,
                keepConnectedInBackground,
                rpc.starts,
            ) { configured, accountId, visible, keep, _ ->
                if (configured && accountId != null &&
                    rpc.isAvailable
                ) {
                    accountId to (visible || keep)
                } else {
                    null
                }
            }
                .distinctUntilChanged()
                .collect { plan ->
                    val (accountId, shouldRun) = plan ?: return@collect
                    runCatching {
                        if (shouldRun) {
                            rpc.call("start_io", accountId)
                            rpc.call("maybe_network")
                        } else {
                            rpc.call("stop_io", accountId)
                        }
                    }.onFailure { LogUtil.w(TAG, "IO change failed: ${it.message}") }
                }
        }
    }

    private suspend fun account(): Int =
        accountStore.accountId.value ?: throw DeltaChatRpcException("No Delta Chat profile")

    private fun onEvent(event: DeltaChatEvent) {
        if (event.accountId != accountStore.accountId.value) return
        when (event.kind) {
            "ConfigureProgress" -> {
                val progress = event.int("progress") ?: 0
                _configureProgress.value = if (progress in 1..999) progress else null
            }

            "IncomingMsg" -> {
                bump()
                val chatId = event.int("chatId") ?: return
                val msgId = event.int("msgId") ?: return
                appScope.launch(Dispatchers.IO) { notify(chatId, msgId) }
            }

            "MsgsChanged", "MsgsNoticed", "MsgDelivered", "MsgFailed", "MsgRead",
            "MsgDeleted", "ChatModified", "ChatDeleted", "ChatlistChanged",
            "ChatlistItemChanged", "ContactsChanged", "IncomingMsgBunch",
            "SelfavatarChanged", "ConfigSynced", "ReactionsChanged",
            -> bump()
        }
        // Read on another device: drop the notification here too.
        if (event.kind == "MsgsNoticed") {
            event.int("chatId")?.let { TwillNotifications.cancelChat(context, id, it.toString()) }
        }
    }

    private fun bump() {
        changes.value += 1
    }

    private suspend fun notify(chatId: Int, msgId: Int) {
        if (visibility.isViewing(id, chatId.toString())) return
        try {
            val account = account()
            if (rpc.call("is_chat_muted", account, chatId) == true) return
            val info = rpc.callObject("get_message_notification_info", account, msgId)
            val prefix = info.optStringOrNull("summaryPrefix")?.takeIf { it.isNotBlank() }
            TwillNotifications.showMessage(
                context = context,
                protocol = id,
                icon = text.notificationIcon,
                chatId = chatId.toString(),
                title = info.optString("chatName"),
                senderName = prefix,
                body = info.optString("summaryText"),
                timeMillis = System.currentTimeMillis(),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LogUtil.w(TAG, "Notification failed: ${exception.javaClass.simpleName}")
        }
    }

    // ---- Shared inbox ----

    override val chats: Flow<List<TwillChat>> = accountStore.isConfigured.flatMapLatest { on ->
        if (!on) {
            flowOf(emptyList())
        } else {
            changes.conflate().mapLatest { loadChats() }.flowOn(Dispatchers.IO)
        }
    }

    private suspend fun loadChats(): List<TwillChat> = try {
        val account = account()
        val entries = rpc.callArray("get_chatlist_entries", account, DC_GCL_NO_SPECIALS, null, null)
        if (entries.length() == 0) {
            emptyList()
        } else {
            val items = rpc.callObject("get_chatlist_items_by_entries", account, entries)
            entries.ints().mapNotNull { chatId -> items.optJSONObject(chatId.toString())?.toChat() }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        emptyList()
    }

    private fun JSONObject.toChat(): TwillChat? {
        if (optString("kind") != "ChatListItem") return null
        val chatId = optInt("id")
        val name = optString("name")
        val isGroup = optString("chatType") != "Single"
        val status = optInt("summaryStatus")
        val isOutgoing = status in OUTGOING_STATES
        val senderLabel = optString("summaryText1")
        return TwillChat(
            protocol = id,
            chatId = chatId.toString(),
            title = name,
            avatar = TwillAvatar(
                name = name,
                ref = optStringOrNull("avatarPath"),
                isGroup = isGroup
            ),
            isGroup = isGroup,
            isPinned = optBoolean("isPinned"),
            unreadCount = optInt("freshMessageCounter"),
            lastMessage = TwillMessage(
                id = optInt("lastMessageId").toString(),
                chatId = chatId.toString(),
                senderId = "",
                text = optString("summaryText2"),
                timeMillis = optLong("lastUpdated"),
                isOutgoing = isOutgoing,
                status = stateToStatus(status, isOutgoing),
            ),
            lastSenderName = senderLabel.takeIf { isGroup && !isOutgoing && it.isNotBlank() },
            isRequest = optBoolean("isContactRequest"),
        )
    }

    override fun messages(chatId: String): Flow<List<TwillMessage>> =
        changes.conflate().mapLatest { loadMessages(chatId.toInt()) }.flowOn(Dispatchers.IO)

    private suspend fun loadMessages(chatId: Int): List<TwillMessage> = try {
        val account = account()
        val ids = rpc.callArray("get_message_ids", account, chatId, false, false).ints()
            .takeLast(MAX_MESSAGES)
        if (ids.isEmpty()) {
            emptyList()
        } else {
            val loaded = rpc.callObject("get_messages", account, ids)
            val messages = ids.mapNotNull { msgId ->
                loaded.optJSONObject(msgId.toString())?.takeIf { it.optString("kind") == "Message" }
            }
            val inProgress = messages.filter { it.optString("downloadState") == "InProgress" }
                .map { it.optInt("id").toString() }
            _downloading.value = (_downloading.value - ids.map(Int::toString).toSet()) + inProgress
            messages.map { it.toMessage() }
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        emptyList()
    }

    private fun JSONObject.toMessage(): TwillMessage {
        val fromId = optInt("fromId")
        val isOutgoing = fromId == DC_CONTACT_ID_SELF
        val file = optStringOrNull("file")
        val downloadState = optString("downloadState")
        return TwillMessage(
            id = optInt("id").toString(),
            chatId = optInt("chatId").toString(),
            senderId = fromId.toString(),
            text = optString("text"),
            timeMillis = optLong("timestamp") * 1000,
            isOutgoing = isOutgoing,
            status = stateToStatus(optInt("state"), isOutgoing),
            isInfo = optBoolean("isInfo"),
            attachment = when {
                file != null || downloadState == "Available" -> TwillAttachment(
                    mimeType = optStringOrNull("fileMime"),
                    fileName = optStringOrNull("fileName"),
                    size = optLong("fileBytes").takeIf { it > 0 },
                    width = optInt("dimensionsWidth").takeIf { it > 0 },
                    height = optInt("dimensionsHeight").takeIf { it > 0 },
                    localPath = file.takeIf { downloadState == "Done" },
                )

                else -> null
            },
        )
    }

    override fun details(chatId: String): Flow<TwillChatDetails> =
        changes.conflate().mapLatest { loadDetails(chatId.toInt()) }.flowOn(Dispatchers.IO)

    private suspend fun loadDetails(chatId: Int): TwillChatDetails = try {
        val account = account()
        val chat = rpc.callObject("get_full_chat_by_id", account, chatId)
        val isGroup = chat.optString("chatType") != "Single"
        val contactIds = chat.optJSONArray("contactIds")?.ints().orEmpty()
            .filter { it != DC_CONTACT_ID_SELF }
        val members = loadContacts(account, contactIds)
        val title = chat.optString("name")
        val peer = members.values.firstOrNull()
        TwillChatDetails(
            chatId = chatId.toString(),
            title = title,
            subtitle = when {
                chat.optBoolean(
                    "isSelfTalk"
                ) -> context.getString(R.string.deltachat_saved_subtitle)
                chat.optBoolean(
                    "isDeviceChat"
                ) -> context.getString(R.string.deltachat_device_subtitle)
                isGroup -> context.getString(R.string.nostr_group_subtitle, members.size + 1)
                else -> peer?.detail.orEmpty()
            },
            isSubtitleVerified = false,
            avatar = TwillAvatar(
                name = title,
                ref = chat.optStringOrNull("profileImage"),
                isGroup = isGroup,
            ),
            isGroup = isGroup,
            canRename = isGroup && chat.optBoolean("selfInGroup"),
            canSend = chat.optBoolean("canSend"),
            isRequest = chat.optBoolean("isContactRequest"),
            members = members,
        )
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        TwillChatDetails(
            chatId = chatId.toString(),
            title = "",
            subtitle = "",
            isSubtitleVerified = false,
            avatar = TwillAvatar(""),
            isGroup = false,
            canRename = false,
            canSend = false,
            isRequest = false,
            members = emptyMap(),
        )
    }

    private suspend fun loadContacts(account: Int, ids: List<Int>): Map<String, TwillMember> {
        if (ids.isEmpty()) return emptyMap()
        val contacts = rpc.callObject("get_contacts_by_ids", account, ids)
        return ids.mapNotNull { contactId ->
            contacts.optJSONObject(contactId.toString())?.toMember()
        }.associateBy { it.id }
    }

    private fun JSONObject.toMember(): TwillMember {
        val name = optString("displayName").ifBlank { optString("address") }
        return TwillMember(
            id = optInt("id").toString(),
            name = name,
            detail = optStringOrNull("address"),
            avatar = TwillAvatar(name = name, ref = optStringOrNull("profileImage")),
        )
    }

    override fun send(chatId: String, text: String) = launchCall(TwillError.SendFailed) { account ->
        rpc.call("misc_send_text_message", account, chatId.toInt(), text)
    }

    override fun sendFile(chatId: String, uri: Uri) = launchCall(
        TwillError.UploadFailed
    ) { account ->
        val copy = copyToCache(uri)
        try {
            val mime = context.contentResolver.getType(uri)
            rpc.call(
                "send_msg",
                account,
                chatId.toInt(),
                mapOf(
                    "viewtype" to if (mime?.startsWith("image/") == true) "Image" else "File",
                    "file" to copy.absolutePath,
                    "filename" to copy.name,
                ),
            )
        } finally {
            // The core copies the file into its own storage.
            copy.delete()
        }
    }

    private fun copyToCache(uri: Uri): File {
        val name =
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?.replace('/', '_')
                ?: "file"
        val dir = File(context.cacheDir, "deltachat-outgoing/" + System.nanoTime()).apply {
            mkdirs()
        }
        val target = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw DeltaChatRpcException("Can't read the file")
        return target
    }

    override fun retry(message: TwillMessage) = launchCall(TwillError.SendFailed) { account ->
        rpc.call("resend_messages", account, listOf(message.id.toInt()))
    }

    override fun download(message: TwillMessage) {
        _downloading.value += message.id
        launchCall(TwillError.DownloadFailed) { account ->
            rpc.call("download_full_message", account, message.id.toInt())
        }
    }

    override fun markRead(chatId: String) = launchCall(null) { account ->
        // markseen sends read receipts (if enabled) for what the user has now seen.
        val ids = rpc.callArray("get_message_ids", account, chatId.toInt(), false, false).ints()
            .takeLast(MAX_MESSAGES)
        if (ids.isNotEmpty()) rpc.call("markseen_msgs", account, ids)
        rpc.call("marknoticed_chat", account, chatId.toInt())
    }

    override fun setRead(chatId: String, isRead: Boolean) = launchCall(null) { account ->
        rpc.call(if (isRead) "marknoticed_chat" else "markfresh_chat", account, chatId.toInt())
    }

    override fun setPinned(chatId: String, isPinned: Boolean) = launchCall(null) { account ->
        rpc.call(
            "set_chat_visibility",
            account,
            chatId.toInt(),
            if (isPinned) "Pinned" else "Normal"
        )
    }

    override fun delete(chatId: String) = launchCall(null) { account ->
        rpc.call("delete_chat", account, chatId.toInt())
    }

    override fun rename(chatId: String, name: String) = launchCall(null) { account ->
        rpc.call("set_chat_name", account, chatId.toInt(), name)
    }

    override fun acceptRequest(chatId: String) = launchCall(null) { account ->
        rpc.call("accept_chat", account, chatId.toInt())
    }

    override fun blockRequest(chatId: String) = launchCall(null) { account ->
        rpc.call("block_chat", account, chatId.toInt())
    }

    private fun launchCall(error: TwillError?, block: suspend (Int) -> Unit) {
        appScope.launch(Dispatchers.IO) {
            try {
                block(account())
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LogUtil.w(TAG, "Call failed: ${exception.message}")
                error?.let(_errors::tryEmit)
            }
            bump()
        }
    }

    /**
     * Opens a chat for an invite link (i.delta.chat or OPENPGP4FPR), a contact QR, or an
     * email address (an unencrypted chat until both sides have exchanged keys).
     */
    override suspend fun startChat(input: String): String? = withContext(Dispatchers.IO) {
        try {
            val account = account()
            val qr = runCatching { rpc.callObject("check_qr", account, input) }.getOrNull()
            val chatId = when (qr?.optString("kind")) {
                "askVerifyContact", "askVerifyGroup", "askJoinBroadcast" ->
                    rpc.callInt("secure_join", account, input)

                "fprOk", "addr" -> rpc.callInt(
                    "create_chat_by_contact_id",
                    account,
                    qr?.optInt("contactId") ?: return@withContext null,
                )

                else -> {
                    if (!EMAIL.matches(input)) return@withContext null
                    val contactId = rpc.callInt("create_contact", account, input, null)
                    rpc.callInt("create_chat_by_contact_id", account, contactId)
                }
            }
            bump()
            chatId.toString()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            null
        }
    }

    override suspend fun knownContacts(): List<TwillMember> = withContext(Dispatchers.IO) {
        try {
            val account = account()
            val ids = rpc.callArray("get_contact_ids", account, 0, null).ints()
                .filter { it != DC_CONTACT_ID_SELF }
            loadContacts(account, ids).values.sortedBy { it.name.lowercase() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            emptyList()
        }
    }

    override suspend fun createGroup(
        name: String,
        contactIds: List<String>,
        typedEntries: List<String>,
    ): TwillGroupResult = withContext(Dispatchers.IO) {
        try {
            val account = account()
            val groupName = name.ifBlank {
                context.getString(R.string.deltachat_default_group_name)
            }
            val chatId = rpc.callInt("create_group_chat", account, groupName, true)
            contactIds.forEach { contactId ->
                rpc.call("add_contact_to_chat", account, chatId, contactId.toInt())
            }
            bump()
            TwillGroupResult.Created(chatId.toString())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            TwillGroupResult.Invalid(emptyList())
        }
    }

    override suspend fun loadAvatar(ref: String): Bitmap? = TwillImages.loadLocalAvatar(ref)

    override suspend fun syncOnce() {
        if (!accountStore.isConfigured.value || !rpc.isAvailable) return
        runCatching {
            withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) {
                rpc.call("background_fetch", BACKGROUND_FETCH_SECONDS)
            }
        }
    }

    override fun setKeepConnectedInBackground(enabled: Boolean) {
        keepConnectedInBackground.value = enabled
    }

    // ---- Setup and profile, for Delta Chat Settings ----

    /** Creates the core account Twill uses, or returns the existing one. */
    private suspend fun ensureAccount(): Int {
        accountStore.accountId.value?.let { return it }
        val existing = rpc.callArray("get_all_account_ids").ints().firstOrNull()
        val created = existing ?: rpc.callInt("add_account")
        accountStore.setAccountId(created)
        return created
    }

    /** Signs up for a new address on the chatmail server [server] (e.g. nine.testrun.org). */
    suspend fun createProfile(displayName: String, server: String) =
        setup(displayName) { account ->
            val host = server.trim().removePrefix("https://").trimEnd('/')
            rpc.call("add_transport_from_qr", account, "dcaccount:$host")
        }

    /** Uses a dcaccount: or dclogin: code (from a chatmail server's page or another app). */
    suspend fun setupFromCode(displayName: String, code: String) =
        setup(displayName) { account -> rpc.call("add_transport_from_qr", account, code.trim()) }

    /** Uses an existing email account; servers are found automatically. */
    suspend fun setupWithEmail(displayName: String, address: String, password: String) =
        setup(displayName) { account ->
            rpc.call(
                "add_or_update_transport",
                account,
                mapOf("addr" to address.trim(), "password" to password),
            )
        }

    private suspend fun setup(displayName: String, configure: suspend (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            setupMutex.withLock {
                rpc.start()
                val account = ensureAccount()
                if (displayName.isNotBlank()) {
                    rpc.call("set_config", account, "displayname", displayName.trim())
                }
                _configureProgress.value = 0
                try {
                    configure(account)
                } finally {
                    _configureProgress.value = null
                }
                accountStore.setConfigured(true)
                bump()
            }
        }

    suspend fun getConfig(key: String): String? = withContext(Dispatchers.IO) {
        runCatching { rpc.call("get_config", account(), key) as? String }.getOrNull()
    }

    suspend fun setConfig(key: String, value: String?) = withContext(Dispatchers.IO) {
        runCatching { rpc.call("set_config", account(), key, value) }
        bump()
    }

    /** The user's invite link, which others open or paste to start an encrypted chat. */
    suspend fun inviteLink(): String? = withContext(Dispatchers.IO) {
        runCatching {
            rpc.call("get_chat_securejoin_qr_code", account(), null) as? String
        }.getOrNull()
    }

    /** Deletes the profile, its keys and all Delta Chat messages from this phone. */
    suspend fun removeProfile() = withContext(Dispatchers.IO) {
        val account = accountStore.accountId.value
        accountStore.setConfigured(false)
        if (account != null) {
            runCatching { rpc.call("stop_io", account) }
            runCatching { rpc.call("remove_account", account) }
        }
        accountStore.setAccountId(null)
        rpc.stop()
        bump()
    }

    private fun stateToStatus(state: Int, isOutgoing: Boolean): TwillMessage.Status = when {
        !isOutgoing -> TwillMessage.Status.Sent
        state == DC_STATE_OUT_FAILED -> TwillMessage.Status.Failed
        state == DC_STATE_OUT_MDN_RCVD -> TwillMessage.Status.Read
        state == DC_STATE_OUT_DELIVERED -> TwillMessage.Status.Delivered
        state == DC_STATE_OUT_PENDING -> TwillMessage.Status.Sending
        state == DC_STATE_OUT_PREPARING -> TwillMessage.Status.Sending
        else -> TwillMessage.Status.Sent
    }

    private fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null

    private companion object {
        const val TAG = "DeltaChatProtocol"
        const val DC_GCL_NO_SPECIALS = 0x02
        const val DC_CONTACT_ID_SELF = 1
        const val DC_STATE_OUT_PREPARING = 18
        const val DC_STATE_OUT_PENDING = 20
        const val DC_STATE_OUT_FAILED = 24
        const val DC_STATE_OUT_DELIVERED = 26
        const val DC_STATE_OUT_MDN_RCVD = 28
        val OUTGOING_STATES = 18..28
        const val MAX_MESSAGES = 500
        const val BACKGROUND_FETCH_SECONDS = 20.0
        const val SYNC_TIMEOUT_MILLIS = 30_000L
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
