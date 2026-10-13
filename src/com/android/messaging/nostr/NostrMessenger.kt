package com.android.messaging.nostr

import android.content.Context
import android.net.Uri
import com.android.messaging.R
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.twill.TwillAppVisibility
import com.android.messaging.twill.TwillError
import com.android.messaging.twill.TwillNotifications
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.util.LogUtil
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirm
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrlOrNull
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Client
import com.vitorpamplona.quartz.nip05DnsIdentifiers.OkHttpNip05Fetcher
import com.vitorpamplona.quartz.nip05DnsIdentifiers.resolveUserHexOrNull
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.files.ChatMessageEncryptedFileHeaderEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.messages.changeSubject
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nip94FileMetadata.tags.DimensionTag
import com.vitorpamplona.quartz.utils.sha256.sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Sends and receives NIP-17 private messages (NIP-44 encryption inside NIP-59 gift wraps):
 * one-to-one and group chats, text (kind 14) and encrypted files (kind 15).
 *
 * Relays are only contacted while signed in, and then only while Twill is on screen or when
 * background delivery is on (see [setKeepConnectedInBackground]).
 */
@Singleton
internal class NostrMessenger @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountStore: NostrAccountStore,
    private val signerProvider: NostrSignerProvider,
    private val store: NostrMessageStore,
    private val media: NostrMedia,
    private val visibility: TwillAppVisibility,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) {
    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * For relay connections opened while Twill is in the background ("always connected"):
     * pinging less often keeps the radio asleep longer. Shares [okHttp]'s connection pool.
     */
    private val backgroundOkHttp: OkHttpClient by lazy {
        okHttp.newBuilder()
            .pingInterval(BACKGROUND_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val client: NostrClient by lazy {
        NostrClient(
            // Chosen each time a relay connection is opened.
            websocketBuilder = BasicOkHttpWebSocket.Builder {
                if (visibility.isRecentlyVisible.value) okHttp else backgroundOkHttp
            },
            parentScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    private val nip05Client: Nip05Client by lazy {
        Nip05Client(fetcher = OkHttpNip05Fetcher { okHttp })
    }

    private val processMutex = Mutex()
    private val dmRelayCache = ConcurrentHashMap<String, Pair<Long, List<NormalizedRelayUrl>>>()
    private val profileRequests = ConcurrentHashMap.newKeySet<String>()
    private val profileFailures = ConcurrentHashMap<String, Long>()
    private val fetchCounter = AtomicLong()

    /** Profiles whose fetch is running, so the settings page can show progress. */
    val refreshingProfiles = MutableStateFlow<Set<String>>(emptySet())
    private val downloadsInFlight = ConcurrentHashMap.newKeySet<String>()

    private val keepConnectedInBackground = MutableStateFlow(false)
    private var isInstalled = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _errors = MutableSharedFlow<TwillError>(extraBufferCapacity = 8)

    /** Problems worth telling the user about, such as a failed upload. */
    val errors: SharedFlow<TwillError> = _errors.asSharedFlow()

    /** Message ids whose attachments are downloading. */
    val downloading = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Starts watching the account and app visibility (see [TwillAppVisibility]). Safe to call
     * more than once. Background delivery is run by the shared
     * [com.android.messaging.twill.TwillRuntime].
     */
    fun install() {
        synchronized(this) {
            if (isInstalled) return
            isInstalled = true
        }

        appScope.launch {
            combine(
                accountStore.account,
                accountStore.relays,
                visibility.isRecentlyVisible,
                keepConnectedInBackground,
            ) { account, relays, visible, keepInBackground ->
                ConnectionPlan(
                    account = account,
                    relays = relays,
                    shouldConnect = account != null && (visible || keepInBackground),
                )
            }
                .distinctUntilChanged()
                .collectLatest { plan -> applyPlan(plan) }
        }

        // Leaving Twill with "always connected" on: reopen the relay connections once, so
        // they use the slower background ping. Subscriptions carry over.
        appScope.launch {
            visibility.isRecentlyVisible.drop(1).collect { visible ->
                if (!visible && keepConnectedInBackground.value && _isConnected.value) {
                    client.reconnect(onlyIfChanged = false, ignoreRetryDelays = true)
                }
            }
        }

        appScope.launch {
            accountStore.account.collect { account ->
                if (account == null) {
                    withContext(Dispatchers.IO) { store.clearAll() }
                    dmRelayCache.clear()
                    profileFailures.clear()
                } else {
                    withContext(Dispatchers.IO) {
                        // Messages older than the sign-in (the first sync fetches a month
                        // of history) are not news, so they don't raise notifications.
                        if (store.readState(STATE_NOTIFY_SINCE) == null) {
                            store.writeState(
                                STATE_NOTIFY_SINCE,
                                (System.currentTimeMillis() / 1000).toString(),
                            )
                        }
                    }
                    refreshProfile(account.pubKeyHex)
                }
            }
        }
    }

    fun launchInAppScope(block: suspend CoroutineScope.() -> Unit): Job =
        appScope.launch(Dispatchers.IO, block = block)

    fun setKeepConnectedInBackground(enabled: Boolean) {
        keepConnectedInBackground.value = enabled
    }

    /**
     * Turns what the user typed into a hex public key: an npub, nprofile, hex key or a
     * NIP-05 address (name@domain, looked up over HTTPS). Returns null if it can't.
     */
    suspend fun resolveIdentifier(input: String): String? = withContext(Dispatchers.IO) {
        val cleaned = input.trim().removePrefix("nostr:")
        val pubKey = try {
            resolveUserHexOrNull(cleaned, nip05Client)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            null
        }
        pubKey?.takeIf { it.length == PUBKEY_HEX_LENGTH }
    }

    /**
     * Connects briefly, collects anything new and disconnects again. Used by the periodic
     * background check. Returns when the relays have sent everything stored or after a timeout.
     */
    suspend fun syncOnce(timeoutMillis: Long = ONE_SHOT_SYNC_TIMEOUT_MILLIS) {
        val account = accountStore.account.value ?: return
        val relays = normalizedRelays(accountStore.relays.value)
        if (relays.isEmpty()) return

        val finishedRelays = MutableStateFlow(0)
        val listener = WrapListener(account.pubKeyHex) { finishedRelays.value += 1 }
        client.connect()
        client.subscribe(
            SYNC_SUB_ID,
            relays.associateWith { listOf(dmFilter(account.pubKeyHex)) },
            listener,
        )
        withTimeoutOrNull(timeoutMillis) {
            finishedRelays.first { count -> count >= relays.size }
        }
        client.unsubscribe(SYNC_SUB_ID)
        // Let profile lookups started by new messages finish before hanging up.
        withTimeoutOrNull(FETCH_TIMEOUT_MILLIS * 2) {
            refreshingProfiles.first { it.isEmpty() }
        }
        if (!_isConnected.value) client.disconnect()
    }

    /** Sends [text] to everyone in [chatKey]. The message shows immediately as sending. */
    fun send(chatKey: String, text: String) {
        appScope.launch(Dispatchers.IO) { sendText(chatKey = chatKey, text = text) }
    }

    /** Encrypts, uploads and sends the file at [uri] to everyone in [chatKey]. */
    fun sendFile(chatKey: String, uri: Uri) {
        appScope.launch(Dispatchers.IO) { sendAttachment(chatKey = chatKey, uri = uri) }
    }

    /** Sends a failed message again. */
    fun retry(message: NostrMessage) {
        appScope.launch(Dispatchers.IO) {
            val local = message.attachment?.localPath?.let(::File)?.takeIf { it.exists() }
            when {
                message.attachment == null -> {
                    store.deleteMessage(message.id)
                    sendText(chatKey = message.chatKey, text = message.content)
                }

                local != null -> {
                    // Keep the local copy: deleteMessage would remove it.
                    val copy = File(local.parentFile, "retry-" + System.nanoTime())
                    local.copyTo(copy)
                    store.deleteMessage(message.id)
                    sendAttachment(chatKey = message.chatKey, uri = Uri.fromFile(copy))
                    copy.delete()
                }
            }
        }
    }

    /** Downloads and decrypts a received attachment. */
    fun download(message: NostrMessage) {
        val attachment = message.attachment ?: return
        if (attachment.isDownloaded || !downloadsInFlight.add(message.id)) return
        downloading.value = downloadsInFlight.toSet()

        appScope.launch(Dispatchers.IO) {
            try {
                val file = media.download(message.id, attachment)
                store.setAttachmentLocalPath(message.id, file.absolutePath)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LogUtil.w(TAG, "Download failed: ${exception.javaClass.simpleName}")
                _errors.tryEmit(TwillError.DownloadFailed)
            } finally {
                downloadsInFlight.remove(message.id)
                downloading.value = downloadsInFlight.toSet()
            }
        }
    }

    /** Names a group. Sent to the other members with the next message. */
    fun renameGroup(chatKey: String, name: String) {
        appScope.launch(Dispatchers.IO) {
            store.setGroupName(
                chatKey = chatKey,
                name = name.trim(),
                atSeconds = System.currentTimeMillis() / 1000,
                isPendingSend = true,
            )
        }
    }

    /**
     * Fetches [pubKey]'s profile (name, picture, NIP-05) if it's missing or more than a day
     * old (or always, with [force]), and checks the NIP-05 address really belongs to them.
     * A failed lookup is retried after a few minutes rather than cached.
     */
    fun refreshProfile(pubKey: String, force: Boolean = false) {
        if (pubKey.length != PUBKEY_HEX_LENGTH) return
        if (!profileRequests.add(pubKey)) return
        refreshingProfiles.value = profileRequests.toSet()
        appScope.launch(Dispatchers.IO) {
            try {
                if (!force && !isProfileStale(pubKey)) return@launch
                val metadata = fetchNewestMetadata(pubKey)
                if (metadata == null) {
                    profileFailures[pubKey] = System.currentTimeMillis()
                    return@launch
                }
                profileFailures.remove(pubKey)
                val nip05 = metadata.optCleanString("nip05")
                store.upsertProfile(
                    NostrProfile(
                        pubKey = pubKey,
                        name = metadata.optCleanString("display_name")
                            ?: metadata.optCleanString("displayName")
                            ?: metadata.optCleanString("name"),
                        picture = metadata.optCleanString("picture")
                            ?.takeIf { it.startsWith("https://", ignoreCase = true) },
                        nip05 = nip05,
                        isNip05Verified = nip05 != null && verifyNip05(nip05, pubKey),
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                profileFailures[pubKey] = System.currentTimeMillis()
                LogUtil.w(TAG, "Profile fetch failed: ${exception.javaClass.simpleName}")
            } finally {
                profileRequests.remove(pubKey)
                refreshingProfiles.value = profileRequests.toSet()
            }
        }
    }

    private fun isProfileStale(pubKey: String): Boolean {
        profileFailures[pubKey]?.let { failedAt ->
            if (System.currentTimeMillis() - failedAt < PROFILE_RETRY_MILLIS) return false
        }
        val updatedAt = store.profileUpdatedAt(pubKey) ?: return true
        return System.currentTimeMillis() - updatedAt >= PROFILE_MAX_AGE_MILLIS
    }

    /**
     * Asks the user's relays, plus the person's own DM relays when known, for their kind 0
     * profile and returns the newest valid one's content. Null if no relay had one.
     */
    private suspend fun fetchNewestMetadata(pubKey: String): JSONObject? {
        val relays = (
            normalizedRelays(accountStore.relays.value) +
                dmRelayCache[pubKey]?.second.orEmpty()
            ).distinct()
        if (relays.isEmpty()) return null

        val filter = Filter(kinds = listOf(MetadataEvent.KIND), authors = listOf(pubKey))
        val events = fetchAll(relays, filter)
        val newest = events
            .filter { it.kind == MetadataEvent.KIND && it.pubKey == pubKey && it.verify() }
            .maxByOrNull { it.createdAt }
            ?: return null
        return runCatching { JSONObject(newest.content) }.getOrNull() ?: JSONObject()
    }

    /**
     * Collects every event matching [filter] from [relays] until each relay has finished
     * (EOSE, closed or unreachable) or the timeout passes.
     */
    private suspend fun fetchAll(
        relays: List<NormalizedRelayUrl>,
        filter: Filter,
    ): List<Event> {
        val events = Collections.synchronizedList(mutableListOf<Event>())
        val finished = MutableStateFlow<Set<NormalizedRelayUrl>>(emptySet())
        val subscriptionId = "twill-fetch-" + fetchCounter.incrementAndGet()
        val listener = object : SubscriptionListener {
            override suspend fun onEvent(
                event: Event,
                isLive: Boolean,
                relay: NormalizedRelayUrl,
                forFilters: List<Filter>?,
            ) {
                events.add(event)
            }

            override fun onEose(relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                finished.value += relay
            }

            override fun onClosed(
                message: String,
                relay: NormalizedRelayUrl,
                forFilters: List<Filter>?,
            ) {
                finished.value += relay
            }

            override fun onCannotConnect(
                relay: NormalizedRelayUrl,
                message: String,
                forFilters: List<Filter>?,
            ) {
                finished.value += relay
            }
        }
        try {
            client.subscribe(subscriptionId, relays.associateWith { listOf(filter) }, listener)
            withTimeoutOrNull(FETCH_TIMEOUT_MILLIS) {
                finished.first { it.size >= relays.size }
            }
        } finally {
            client.unsubscribe(subscriptionId)
        }
        return events.toList()
    }

    /**
     * Checks that [address] (name@domain) lists [pubKey], by fetching
     * https://domain/.well-known/nostr.json?name=name (NIP-05).
     */
    private suspend fun verifyNip05(address: String, pubKey: String): Boolean {
        val trimmed = address.trim().lowercase()
        val at = trimmed.lastIndexOf('@')
        val name = if (at > 0) trimmed.substring(0, at) else "_"
        val domain = if (at >= 0) trimmed.substring(at + 1) else trimmed
        if (domain.isBlank() || '.' !in domain || '/' in domain) return false

        val url = runCatching {
            HttpUrl.Builder()
                .scheme("https")
                .host(domain.substringBefore(':'))
                .apply { domain.substringAfter(':', "").toIntOrNull()?.let(::port) }
                .addPathSegments(".well-known/nostr.json")
                .addQueryParameter("name", name)
                .build()
        }.getOrNull() ?: return false
        return withTimeoutOrNull(FETCH_TIMEOUT_MILLIS) {
            runCatching {
                // NIP-05: clients must not follow redirects.
                okHttp.newBuilder().followRedirects(false).followSslRedirects(false).build()
                    .newCall(Request.Builder().url(url).get().build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) return@use false
                        val body = response.body.byteStream().use { stream ->
                            String(stream.readNBytes(NIP05_MAX_BYTES), Charsets.UTF_8)
                        }
                        val names = JSONObject(body).optJSONObject("names")
                            ?: return@use false
                        val listed = names.keys().asSequence()
                            .firstOrNull { it.equals(name, ignoreCase = true) }
                            ?.let { names.optString(it) }
                        listed.equals(pubKey, ignoreCase = true)
                    }
            }.getOrDefault(false)
        } ?: false
    }

    private fun JSONObject.optCleanString(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private suspend fun sendText(chatKey: String, text: String) {
        val signer = signerProvider.signer() ?: return
        val members = NostrChatKey.members(chatKey)
        val subject = if (NostrChatKey.isGroup(
                chatKey
            )
        ) {
            store.pendingGroupSubject(chatKey)
        } else {
            null
        }

        val result = try {
            NIP17Factory().createMessageNIP17(
                template = ChatMessageEvent.build(
                    msg = text,
                    to = members.map { PTag(it) },
                ) {
                    if (subject != null) changeSubject(subject)
                },
                signer = signer,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LogUtil.w(TAG, "Could not build message: ${exception.javaClass.simpleName}")
            insertFailed(chatKey = chatKey, me = signer.pubKey, text = text)
            return
        }

        store.insertMessage(
            NostrMessage(
                id = result.msg.id,
                chatKey = chatKey,
                senderPubKey = signer.pubKey,
                content = text,
                createdAtSeconds = result.msg.createdAt,
                isOutgoing = true,
                isRead = true,
                status = NostrMessage.Status.Sending,
            ),
        )
        val delivered = publishWraps(result.wraps, me = signer.pubKey)
        if (delivered && subject != null) store.clearPendingGroupSubject(chatKey)
        store.updateStatus(
            id = result.msg.id,
            status = if (delivered) NostrMessage.Status.Sent else NostrMessage.Status.Failed,
        )
        members.forEach(::refreshProfile)
    }

    private suspend fun sendAttachment(chatKey: String, uri: Uri) {
        val signer = signerProvider.signer() ?: return
        val members = NostrChatKey.members(chatKey)
        val placeholderId = "upload-" + System.nanoTime()

        val prepared = try {
            media.prepare(uri)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            _errors.tryEmit(
                if (exception.message ==
                    "File is too large"
                ) {
                    TwillError.FileTooLarge
                } else {
                    TwillError.UploadFailed
                },
            )
            return
        }

        val pendingAttachment = NostrAttachment(
            url = "",
            mimeType = prepared.mimeType,
            keyHex = prepared.cipher.keyBytes.toHexKey(),
            nonceHex = prepared.cipher.nonce.toHexKey(),
            algorithm = prepared.cipher.name(),
            encryptedHash = null,
            size = prepared.encrypted.size.toLong(),
            width = prepared.width,
            height = prepared.height,
            fileName = prepared.fileName,
            localPath = prepared.localCopy.absolutePath,
        )
        store.insertMessage(
            NostrMessage(
                id = placeholderId,
                chatKey = chatKey,
                senderPubKey = signer.pubKey,
                content = "",
                createdAtSeconds = System.currentTimeMillis() / 1000,
                isOutgoing = true,
                isRead = true,
                status = NostrMessage.Status.Sending,
                attachment = pendingAttachment,
            ),
        )

        val result = try {
            val url = media.upload(
                encrypted = prepared.encrypted,
                server = accountStore.mediaServer.value,
                signer = signer,
            )
            val template = ChatMessageEncryptedFileHeaderEvent.build(
                to = members.map { PTag(it) },
                url = url,
                cipher = prepared.cipher,
                mimeType = prepared.mimeType,
                hash = sha256(prepared.encrypted).toHexKey(),
                size = prepared.encrypted.size,
                dimension = if (prepared.width != null && prepared.height != null) {
                    DimensionTag(prepared.width, prepared.height)
                } else {
                    null
                },
                originalHash = prepared.originalHash,
            )
            url to NIP17Factory().createEncryptedFileNIP17(template = template, signer = signer)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LogUtil.w(TAG, "Upload failed: ${exception.javaClass.simpleName}: ${exception.message}")
            _errors.tryEmit(TwillError.UploadFailed)
            store.updateStatus(placeholderId, NostrMessage.Status.Failed)
            return
        }

        val (url, built) = result
        // Swap the placeholder for the real message (same local file).
        store.insertMessage(
            NostrMessage(
                id = built.msg.id,
                chatKey = chatKey,
                senderPubKey = signer.pubKey,
                content = "",
                createdAtSeconds = built.msg.createdAt,
                isOutgoing = true,
                isRead = true,
                status = NostrMessage.Status.Sending,
                attachment = pendingAttachment.copy(
                    url = url,
                    encryptedHash = sha256(prepared.encrypted).toHexKey(),
                ),
            ),
        )
        removePlaceholderKeepingFile(placeholderId)

        val delivered = publishWraps(built.wraps, me = signer.pubKey)
        store.updateStatus(
            id = built.msg.id,
            status = if (delivered) NostrMessage.Status.Sent else NostrMessage.Status.Failed,
        )
    }

    private fun removePlaceholderKeepingFile(placeholderId: String) {
        // deleteMessage removes the local file, which the real message now points at.
        store.setAttachmentLocalPath(placeholderId, "")
        store.deleteMessage(placeholderId)
    }

    private fun insertFailed(chatKey: String, me: String, text: String) {
        store.insertMessage(
            NostrMessage(
                id = "failed-" + System.nanoTime(),
                chatKey = chatKey,
                senderPubKey = me,
                content = text,
                createdAtSeconds = System.currentTimeMillis() / 1000,
                isOutgoing = true,
                isRead = true,
                status = NostrMessage.Status.Failed,
            ),
        )
    }

    /** Publishes each gift wrap to its recipient's DM relays. True if any other member got it. */
    private suspend fun publishWraps(wraps: List<GiftWrapEvent>, me: String): Boolean {
        val myRelays = normalizedRelays(accountStore.relays.value)
        var deliveredToSomeone = false
        for (wrap in wraps) {
            val recipient = wrap.recipientPubKey()
            if (recipient == me) {
                // Our own copy comes back through the subscription; no need to decrypt it.
                store.markWrapSeen(wrap.id)
            }
            val targets = when (recipient) {
                me, null -> myRelays
                else -> dmRelaysOf(recipient).ifEmpty { myRelays }
            }
            val accepted = try {
                client.publishAndConfirm(event = wrap, relayList = targets.toSet())
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                false
            }
            if (recipient != me && accepted) deliveredToSomeone = true
        }
        // Notes to self only have our own wrap.
        return deliveredToSomeone || wraps.all { it.recipientPubKey() == me }
    }

    /** The relays [pubKey] reads DMs from (NIP-17 kind 10050), cached for an hour. */
    private suspend fun dmRelaysOf(pubKey: String): List<NormalizedRelayUrl> {
        dmRelayCache[pubKey]?.let { (fetchedAt, relays) ->
            if (System.currentTimeMillis() - fetchedAt < DM_RELAY_CACHE_MILLIS) return relays
        }
        val myRelays = normalizedRelays(accountStore.relays.value)
        if (myRelays.isEmpty()) return emptyList()

        val filter = Filter(kinds = listOf(DmRelayListEvent.KIND), authors = listOf(pubKey))
        val events = try {
            fetchAll(myRelays, filter)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            emptyList()
        }
        val relays = events
            .filterIsInstance<DmRelayListEvent>()
            .filter { it.pubKey == pubKey && it.verify() }
            .maxByOrNull { it.createdAt }
            ?.relays()
            .orEmpty()
        dmRelayCache[pubKey] = System.currentTimeMillis() to relays
        return relays
    }

    private suspend fun applyPlan(plan: ConnectionPlan) {
        if (!plan.shouldConnect || plan.account == null) {
            client.unsubscribe(LIVE_SUB_ID)
            client.disconnect()
            _isConnected.value = false
            return
        }

        val relays = normalizedRelays(plan.relays)
        if (relays.isEmpty()) return

        client.connect()
        client.subscribe(
            LIVE_SUB_ID,
            relays.associateWith { listOf(dmFilter(plan.account.pubKeyHex)) },
            WrapListener(plan.account.pubKeyHex) {},
        )
        _isConnected.value = true

        publishOwnDmRelayListIfNeeded(relays)
    }

    private fun dmFilter(me: String): Filter {
        val now = System.currentTimeMillis() / 1000
        // Gift wraps carry a randomized timestamp up to two days in the past.
        val since = store.lastSyncSeconds()?.minus(GIFT_WRAP_TIME_SKEW_SECONDS)
            ?: (now - FIRST_SYNC_LOOKBACK_SECONDS)
        return Filter(
            kinds = listOf(GiftWrapEvent.KIND),
            tags = mapOf("p" to listOf(me)),
            since = since,
        )
    }

    /** Tells other clients which relays to send our DMs to (NIP-17 kind 10050). */
    private fun publishOwnDmRelayListIfNeeded(relays: List<NormalizedRelayUrl>) {
        val key = relays.joinToString(",") { it.url }
        if (store.readState(STATE_PUBLISHED_RELAYS) == key) return

        appScope.launch(Dispatchers.IO) {
            try {
                val signer = signerProvider.signer() ?: return@launch
                val event = DmRelayListEvent.create(relays = relays, signer = signer)
                if (client.publishAndConfirm(event = event, relayList = relays.toSet())) {
                    store.writeState(STATE_PUBLISHED_RELAYS, key)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LogUtil.w(TAG, "Could not publish DM relay list: ${exception.javaClass.simpleName}")
            }
        }
    }

    private inner class WrapListener(
        private val me: String,
        private val onEose: () -> Unit,
    ) : SubscriptionListener {
        override suspend fun onEvent(
            event: Event,
            isLive: Boolean,
            relay: NormalizedRelayUrl,
            forFilters: List<Filter>?,
        ) {
            processMutex.withLock { processWrap(event = event, me = me) }
        }

        override fun onEose(
            relay: NormalizedRelayUrl,
            forFilters: List<Filter>?,
        ) {
            onEose()
        }

        override fun onCannotConnect(
            relay: NormalizedRelayUrl,
            message: String,
            forFilters: List<Filter>?,
        ) {
            onEose()
        }
    }

    private suspend fun processWrap(event: Event, me: String) {
        val wrap = event as? GiftWrapEvent ?: return
        if (store.hasSeenWrap(wrap.id)) return
        if (!wrap.verify()) return

        val signer = signerProvider.signer() ?: return

        // If decryption fails because the signer isn't available (e.g. Amber needs approval),
        // leave the wrap unseen so it is retried next time.
        val seal = try {
            wrap.unwrapThrowing(signer)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            return
        }

        val rumor = when {
            seal !is SealEvent || !seal.verify() -> null
            else -> try {
                seal.unsealThrowing(signer)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                null
            }
        }

        store.markWrapSeen(wrap.id)
        store.setLastSyncSeconds(wrap.createdAt)
        if (rumor == null) return
        if (rumor.kind != ChatMessageEvent.KIND &&
            rumor.kind != ChatMessageEncryptedFileHeaderEvent.KIND
        ) {
            return
        }

        val recipients = rumor.tags.valuesOf("p")
        val chatKey = NostrChatKey.forParticipants(
            participants = recipients + rumor.pubKey,
            me = me
        )
        val isOutgoing = rumor.pubKey == me
        val isNew = rumor.createdAt >= notifySinceSeconds()
        // Notes to self written in another client count as news; Twill's own sends are
        // already stored by the time their echo arrives, so they never get here as new.
        val isFromOtherDevice = isOutgoing && chatKey == me

        // A subject tag names (or renames) the chat.
        rumor.tags.valuesOf("subject").firstOrNull()?.let { subject ->
            if (NostrChatKey.isGroup(chatKey)) {
                store.setGroupName(chatKey = chatKey, name = subject, atSeconds = rumor.createdAt)
            }
        }

        val attachment = if (rumor.kind == ChatMessageEncryptedFileHeaderEvent.KIND) {
            parseAttachment(rumor) ?: return
        } else {
            null
        }

        val message = NostrMessage(
            id = rumor.id,
            chatKey = chatKey,
            senderPubKey = rumor.pubKey,
            content = if (attachment != null) "" else rumor.content,
            createdAtSeconds = rumor.createdAt,
            isOutgoing = isOutgoing,
            isRead = isOutgoing && !(isFromOtherDevice && isNew),
            status = NostrMessage.Status.Sent,
            attachment = attachment,
        )
        if (store.insertMessage(message)) {
            if ((!isOutgoing || isFromOtherDevice) && isNew) notify(message)
            if (attachment != null && !isOutgoing && accountStore.autoDownloadMedia.value) {
                download(message)
            }
        }
        refreshProfile(rumor.pubKey)
        NostrChatKey.members(chatKey).forEach(::refreshProfile)
    }

    /** Reads a kind 15 file message. Returns null if it can't be decrypted. */
    private fun parseAttachment(rumor: Event): NostrAttachment? {
        val tags = rumor.tags
        val algorithm = tags.valuesOf("encryption-algorithm").firstOrNull() ?: "aes-gcm"
        if (!algorithm.equals("aes-gcm", ignoreCase = true)) return null
        val key = tags.valuesOf("decryption-key").firstOrNull() ?: return null
        val nonce = tags.valuesOf("decryption-nonce").firstOrNull() ?: return null
        val url = rumor.content.trim().takeIf { it.startsWith("https://", ignoreCase = true) }
            ?: return null
        val dimensions = tags.valuesOf("dim").firstOrNull()
            ?.split("x")
            ?.mapNotNull { it.trim().toFloatOrNull()?.toInt() }
            ?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }

        return NostrAttachment(
            url = url,
            mimeType = tags.valuesOf("file-type").firstOrNull(),
            keyHex = key,
            nonceHex = nonce,
            algorithm = algorithm,
            encryptedHash = tags.valuesOf("x").firstOrNull(),
            size = tags.valuesOf("size").firstOrNull()?.toLongOrNull(),
            width = dimensions?.get(0),
            height = dimensions?.get(1),
            fileName = null,
            localPath = null,
        )
    }

    private fun Array<Array<String>>.valuesOf(name: String): List<String> =
        filter { tag -> tag.size > 1 && tag[0] == name }.map { tag -> tag[1] }.distinct()

    private fun notifySinceSeconds(): Long =
        store.readState(STATE_NOTIFY_SINCE)?.toLongOrNull() ?: Long.MAX_VALUE

    /** Posts a notification for [message] unless its chat is open on screen. */
    private suspend fun notify(message: NostrMessage) {
        // Uses the real on-screen state, not the delayed one that keeps relays connected.
        if (visibility.isViewing(TwillProtocolId.Nostr, message.chatKey)) return
        val attachment = message.attachment
        TwillNotifications.showMessage(
            context = context,
            protocol = TwillProtocolId.Nostr,
            icon = R.drawable.ic_notification_nostr,
            chatId = message.chatKey,
            title = chatTitle(message.chatKey),
            senderName = when {
                NostrChatKey.isGroup(message.chatKey) -> nameOf(message.senderPubKey)
                else -> null
            },
            body = when {
                attachment?.isImage == true -> context.getString(R.string.nostr_photo)
                attachment != null -> context.getString(R.string.nostr_file)
                else -> message.content
            },
            timeMillis = message.createdAtSeconds * 1000,
        )
    }

    private suspend fun nameOf(pubKey: String): String =
        store.profile(pubKey).first()?.name ?: NostrSignerProvider.shortNpub(pubKey)

    private suspend fun chatTitle(chatKey: String): String {
        if (!NostrChatKey.isGroup(chatKey)) return nameOf(chatKey)
        store.group(chatKey).first().name?.let { return it }
        return NostrChatKey.members(chatKey).take(3).map { nameOf(it) }.joinToString(", ")
    }

    private fun normalizedRelays(urls: List<String>): List<NormalizedRelayUrl> =
        urls.mapNotNull { it.normalizeRelayUrlOrNull() }.distinct()

    private data class ConnectionPlan(
        val account: NostrAccount?,
        val relays: List<String>,
        val shouldConnect: Boolean,
    )

    private companion object {
        const val TAG = "NostrMessenger"
        const val LIVE_SUB_ID = "twill-dms"
        const val SYNC_SUB_ID = "twill-dms-sync"
        const val STATE_PUBLISHED_RELAYS = "published_dm_relays"
        const val STATE_NOTIFY_SINCE = "notify_since"
        const val PROFILE_RETRY_MILLIS = 10 * 60 * 1000L
        const val NIP05_MAX_BYTES = 512 * 1024
        const val PUBKEY_HEX_LENGTH = 64
        const val PING_INTERVAL_SECONDS = 30L
        const val BACKGROUND_PING_INTERVAL_SECONDS = 180L
        const val FETCH_TIMEOUT_MILLIS = 8_000L
        const val ONE_SHOT_SYNC_TIMEOUT_MILLIS = 25_000L
        const val DM_RELAY_CACHE_MILLIS = 60 * 60 * 1000L
        const val PROFILE_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
        const val GIFT_WRAP_TIME_SKEW_SECONDS = 2 * 24 * 60 * 60L + 3600L
        const val FIRST_SYNC_LOOKBACK_SECONDS = 30 * 24 * 60 * 60L
    }
}
