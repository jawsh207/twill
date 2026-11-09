package com.android.messaging.nostr

import android.content.Context
import android.net.Uri
import com.android.messaging.R
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.nostr.core.Draft
import com.android.messaging.nostr.core.Event
import com.android.messaging.nostr.core.Filter
import com.android.messaging.nostr.core.Hex
import com.android.messaging.nostr.core.Keys
import com.android.messaging.nostr.core.Kind
import com.android.messaging.nostr.core.LocalSigner
import com.android.messaging.nostr.core.Nip19
import com.android.messaging.nostr.core.Nip44
import com.android.messaging.nostr.core.Signer
import com.android.messaging.nostr.core.Tags
import com.android.messaging.nostr.core.normalizeRelay
import com.android.messaging.nostr.core.nowSeconds
import com.android.messaging.nostr.core.secureRandom
import com.android.messaging.nostr.core.sha256
import com.android.messaging.nostr.core.values
import com.android.messaging.nostr.net.Http
import com.android.messaging.nostr.net.RelayPool
import com.android.messaging.nostr.net.Subscription
import com.android.messaging.twill.TwillAppVisibility
import com.android.messaging.twill.TwillError
import com.android.messaging.twill.TwillNotificationMessage
import com.android.messaging.twill.TwillNotifications
import com.android.messaging.twill.TwillProtocolId
import com.android.messaging.util.LogUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val pool: RelayPool by lazy {
        RelayPool(CoroutineScope(Dispatchers.IO + SupervisorJob()), signerProvider::signer)
    }

    @Volatile
    private var liveSub: Subscription? = null

    /** Gift wraps received and still being opened, so a one-off sync can wait for them. */
    private val openingWraps = MutableStateFlow(0)

    private val processMutex = Mutex()
    private val dmRelayCache = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private val profileRequests = ConcurrentHashMap.newKeySet<String>()
    private val profileFailures = ConcurrentHashMap<String, Long>()

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
            Nip19.publicKey(cleaned) ?: nip05Owner(cleaned)
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

        val me = account.pubKeyHex
        pool.whileConnected {
            pool.query(relays.toSet(), listOf(dmFilter(me)), timeoutMillis) { event, _ ->
                onWrap(event, me)
            }
            withTimeoutOrNull(timeoutMillis) { openingWraps.first { it == 0 } }
            // Let profile lookups started by new messages finish before hanging up.
            withTimeoutOrNull(FETCH_TIMEOUT_MILLIS * 2) {
                refreshingProfiles.first { it.isEmpty() }
            }
        }
    }

    /** Sends [text] to everyone in [chatKey]. The message shows immediately as sending. */
    fun send(chatKey: String, text: String) {
        appScope.launch(Dispatchers.IO) { sendText(chatKey = chatKey, text = text) }
    }

    /**
     * Sends [text] typed in a notification and returns once the relays answered, connecting
     * for the moment if Twill isn't connected.
     */
    suspend fun sendNow(chatKey: String, text: String) = withContext(Dispatchers.IO) {
        pool.whileConnected { sendText(chatKey = chatKey, text = text) }
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

        val filter = Filter(kinds = listOf(Kind.METADATA), authors = listOf(pubKey))
        val events = fetchAll(relays, filter)
        val newest = events
            .filter { it.kind == Kind.METADATA && it.pubkey == pubKey }
            .maxByOrNull { it.createdAt }
            ?: return null
        return runCatching { JSONObject(newest.content) }.getOrNull() ?: JSONObject()
    }

    /**
     * Collects every event matching [filter] from [relays] until each relay has finished
     * (EOSE, closed or unreachable) or the timeout passes.
     */
    suspend fun fetchAll(
        relays: List<String>,
        filter: Filter,
        timeoutMillis: Long = FETCH_TIMEOUT_MILLIS,
    ): List<Event> = withContext(Dispatchers.IO) {
        val events = Collections.synchronizedList(mutableListOf<Event>())
        pool.whileConnected {
            pool.query(relays.toSet(), listOf(filter), timeoutMillis) { event, _ ->
                events.add(event)
            }
        }
        synchronized(events) { events.toList() }
    }

    /**
     * Publishes [request] to [relays] and waits for the first event matching [response]
     * (a Nostr Wallet Connect reply, say). Null if no relay took the request, or no answer
     * came within [timeoutMillis]. Connects for the moment if Twill isn't connected.
     */
    suspend fun request(
        relays: List<String>,
        request: Event,
        response: Filter,
        timeoutMillis: Long,
    ): Event? = withContext(Dispatchers.IO) {
        pool.whileConnected {
            val answer = CompletableDeferred<Event>()
            val sub = pool.subscribe(relays.toSet(), listOf(response)) { event, _ ->
                answer.complete(event)
            }
            try {
                val sent = pool.publishFirst(request, relays.toSet())
                LogUtil.i(
                    TAG,
                    "Request kind ${request.kind} to ${relays.size} relays: " +
                        if (sent.ok) "accepted" else "refused (${sent.message})",
                )
                if (!sent.ok) return@whileConnected null
                withTimeoutOrNull(timeoutMillis) { answer.await() }
            } finally {
                pool.close(sub)
            }
        }
    }

    /**
     * Checks that [address] (name@domain) lists [pubKey], by fetching
     * https://domain/.well-known/nostr.json?name=name (NIP-05).
     */
    suspend fun verifyNip05(address: String, pubKey: String): Boolean =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(FETCH_TIMEOUT_MILLIS) {
                nip05Owner(address).equals(pubKey, ignoreCase = true)
            } ?: false
        }

    /** Who a NIP-05 address (name@domain, or just domain) belongs to, according to its domain. */
    private fun nip05Owner(address: String): String? {
        val trimmed = address.trim().lowercase()
        val at = trimmed.lastIndexOf('@')
        val name = if (at > 0) trimmed.substring(0, at) else "_"
        val domain = if (at >= 0) trimmed.substring(at + 1) else trimmed
        if (domain.isBlank() || '.' !in domain || domain.any { it in "/?#@ " }) return null
        return runCatching {
            val url = "https://$domain/.well-known/nostr.json?name=" +
                URLEncoder.encode(name, "UTF-8")
            // NIP-05: clients must not follow redirects.
            val names = Http.json(url, followRedirects = false).getJSONObject("names")
            names.keys().asSequence()
                .firstOrNull { it.equals(name, ignoreCase = true) }
                ?.let { names.optString(it) }
                ?.lowercase()
                ?.takeIf(Hex::isKey)
        }.getOrNull()
    }

    private fun JSONObject.optCleanString(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private suspend fun sendText(chatKey: String, text: String) {
        val signer = signerProvider.signer() ?: return
        val members = NostrChatKey.members(chatKey)
        val subject = if (NostrChatKey.isGroup(chatKey)) {
            store.pendingGroupSubject(chatKey)
        } else {
            null
        }

        val rumor = Draft(
            pubkey = signer.pubkey,
            createdAt = nowSeconds(),
            kind = Kind.DM,
            tags = members.map { listOf("p", it) } +
                listOfNotNull(subject?.let { listOf("subject", it) }),
            content = text,
        )

        store.insertMessage(
            NostrMessage(
                id = rumor.id,
                chatKey = chatKey,
                senderPubKey = signer.pubkey,
                content = text,
                createdAtSeconds = rumor.createdAt,
                isOutgoing = true,
                isRead = true,
                status = NostrMessage.Status.Sending,
            ),
        )
        val delivered = deliver(rumor, members, signer)
        if (delivered && subject != null) store.clearPendingGroupSubject(chatKey)
        store.updateStatus(
            id = rumor.id,
            status = if (delivered) NostrMessage.Status.Sent else NostrMessage.Status.Failed,
        )
        members.forEach(::refreshProfile)
        store.addPeople(members, NostrPeopleSource.CHAT)
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
            keyHex = Hex.encode(prepared.key),
            nonceHex = Hex.encode(prepared.nonce),
            algorithm = FILE_ALGORITHM,
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
                senderPubKey = signer.pubkey,
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
            val encryptedHash = Hex.encode(sha256(prepared.encrypted))
            val tags = ArrayList<List<String>>()
            members.forEach { tags += listOf("p", it) }
            tags += listOf("file-type", prepared.mimeType)
            tags += listOf("encryption-algorithm", FILE_ALGORITHM)
            tags += listOf("decryption-key", Hex.encode(prepared.key))
            tags += listOf("decryption-nonce", Hex.encode(prepared.nonce))
            tags += listOf("x", encryptedHash)
            tags += listOf("ox", prepared.originalHash)
            tags += listOf("size", prepared.encrypted.size.toString())
            if (prepared.width != null && prepared.height != null) {
                tags += listOf("dim", "${prepared.width}x${prepared.height}")
            }
            Triple(
                url,
                encryptedHash,
                Draft(signer.pubkey, nowSeconds(), Kind.FILE_DM, tags, url),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LogUtil.w(TAG, "Upload failed: ${exception.javaClass.simpleName}: ${exception.message}")
            _errors.tryEmit(TwillError.UploadFailed)
            store.updateStatus(placeholderId, NostrMessage.Status.Failed)
            return
        }

        val (url, encryptedHash, rumor) = result
        // Swap the placeholder for the real message (same local file).
        store.insertMessage(
            NostrMessage(
                id = rumor.id,
                chatKey = chatKey,
                senderPubKey = signer.pubkey,
                content = "",
                createdAtSeconds = rumor.createdAt,
                isOutgoing = true,
                isRead = true,
                status = NostrMessage.Status.Sending,
                attachment = pendingAttachment.copy(
                    url = url,
                    encryptedHash = encryptedHash,
                ),
            ),
        )
        removePlaceholderKeepingFile(placeholderId)

        val delivered = deliver(rumor, members, signer)
        store.updateStatus(
            id = rumor.id,
            status = if (delivered) NostrMessage.Status.Sent else NostrMessage.Status.Failed,
        )
    }

    private fun removePlaceholderKeepingFile(placeholderId: String) {
        // deleteMessage removes the local file, which the real message now points at.
        store.setAttachmentLocalPath(placeholderId, "")
        store.deleteMessage(placeholderId)
    }

    /**
     * Seals [rumor] for each member and for ourselves (NIP-59), and publishes each gift wrap to
     * its recipient's DM relays. True if any other member's relays took it.
     */
    private suspend fun deliver(rumor: Draft, members: List<String>, signer: Signer): Boolean {
        val me = signer.pubkey
        val myRelays = normalizedRelays(accountStore.relays.value)
        val json = rumor.toJson()
        val others = members.filter { it != me }.distinct()
        val ownCopy = try {
            wrapFor(json, me, signer)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LogUtil.w(TAG, "Could not seal message: ${exception.javaClass.simpleName}")
            return false
        }
        // Our own copy comes back through the subscription; no need to open it.
        store.markWrapSeen(ownCopy.id)

        var deliveredToSomeone = false
        for (recipient in others) {
            val wrap = try {
                wrapFor(json, recipient, signer)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LogUtil.w(TAG, "Could not seal message: ${exception.javaClass.simpleName}")
                continue
            }
            val targets = dmRelaysOf(recipient).ifEmpty { myRelays }
            val result = pool.publishFirst(wrap, targets.toSet())
            if (result.ok) deliveredToSomeone = true
        }
        val ownResult = pool.publishFirst(ownCopy, myRelays.toSet())
        // Notes to self only have our own wrap.
        return if (others.isEmpty()) ownResult.ok else deliveredToSomeone
    }

    /** A NIP-59 gift wrap of [rumorJson] for [recipient]: sealed by us, wrapped by a throwaway key. */
    private suspend fun wrapFor(rumorJson: String, recipient: String, signer: Signer): Event {
        val sealContent = signer.nip44Encrypt(rumorJson, recipient)
        val seal = signer.sign(
            Draft(signer.pubkey, pastTime(), Kind.SEAL, emptyList(), sealContent),
        )
        val ephemeral = Keys.generate()
        val wrapper = LocalSigner(ephemeral)
        val content = Nip44.encrypt(seal.toJson(), Nip44.conversationKey(ephemeral, recipient))
        return wrapper.signNow(
            Draft(
                wrapper.pubkey,
                pastTime(),
                Kind.GIFT_WRAP,
                listOf(listOf("p", recipient)),
                content,
            ),
        )
    }

    /** Seals and wraps carry a random time in the last two days, hiding when it was sent. */
    private fun pastTime() = nowSeconds() - secureRandom.nextInt(TWO_DAYS_SECONDS)

    /** The relays [pubKey] reads DMs from (NIP-17 kind 10050), cached for an hour. */
    private suspend fun dmRelaysOf(pubKey: String): List<String> {
        dmRelayCache[pubKey]?.let { (fetchedAt, relays) ->
            if (System.currentTimeMillis() - fetchedAt < DM_RELAY_CACHE_MILLIS) return relays
        }
        val lookup = normalizedRelays(
            accountStore.relays.value + accountStore.searchRelayUrls.value,
        )
        if (lookup.isEmpty()) return emptyList()

        val filter = Filter(kinds = listOf(Kind.DM_RELAYS), authors = listOf(pubKey))
        val events = try {
            fetchAll(lookup, filter)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            emptyList()
        }
        val relays = events
            .filter { it.kind == Kind.DM_RELAYS && it.pubkey == pubKey }
            .maxByOrNull { it.createdAt }
            ?.tags
            ?.values("relay")
            ?.mapNotNull(::normalizeRelay)
            ?.distinct()
            .orEmpty()
        dmRelayCache[pubKey] = System.currentTimeMillis() to relays
        return relays
    }

    private suspend fun applyPlan(plan: ConnectionPlan) {
        liveSub?.let(pool::close)
        liveSub = null
        val relays = normalizedRelays(plan.relays)
        if (!plan.shouldConnect || plan.account == null || relays.isEmpty()) {
            pool.setActive(false)
            _isConnected.value = false
            return
        }

        val me = plan.account.pubKeyHex
        pool.setActive(true)
        liveSub = pool.subscribe(relays.toSet(), listOf(dmFilter(me))) { event, _ ->
            onWrap(event, me)
        }
        _isConnected.value = true

        publishOwnDmRelayListIfNeeded(relays)
    }

    private fun dmFilter(me: String): Filter {
        val now = System.currentTimeMillis() / 1000
        // Gift wraps carry a randomized timestamp up to two days in the past.
        val since = store.lastSyncSeconds()?.minus(GIFT_WRAP_TIME_SKEW_SECONDS)
            ?: (now - FIRST_SYNC_LOOKBACK_SECONDS)
        return Filter(
            kinds = listOf(Kind.GIFT_WRAP),
            tags = mapOf("p" to listOf(me)),
            since = since,
        )
    }

    /** Tells other clients which relays to send our DMs to (NIP-17 kind 10050). */
    private fun publishOwnDmRelayListIfNeeded(relays: List<String>) {
        val key = relays.joinToString(",")
        if (store.readState(STATE_PUBLISHED_RELAYS) == key) return

        appScope.launch(Dispatchers.IO) {
            try {
                val signer = signerProvider.signer() ?: return@launch
                val event = signer.sign(
                    Draft(
                        pubkey = signer.pubkey,
                        createdAt = nowSeconds(),
                        kind = Kind.DM_RELAYS,
                        tags = relays.map { listOf("relay", it) },
                        content = "",
                    ),
                    background = true,
                )
                val results = pool.whileConnected { pool.publish(event, relays.toSet()) }
                if (results.values.any { it.ok }) {
                    store.writeState(STATE_PUBLISHED_RELAYS, key)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LogUtil.w(TAG, "Could not publish DM relay list: ${exception.javaClass.simpleName}")
            }
        }
    }

    /** Opens a received gift wrap off the socket thread, one at a time. */
    private fun onWrap(event: Event, me: String) {
        if (event.kind != Kind.GIFT_WRAP) return
        openingWraps.update { it + 1 }
        appScope.launch(Dispatchers.IO) {
            try {
                processMutex.withLock { processWrap(wrap = event, me = me) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LogUtil.w(TAG, "Could not read message: ${exception.javaClass.simpleName}")
            } finally {
                openingWraps.update { it - 1 }
            }
        }
    }

    private suspend fun processWrap(wrap: Event, me: String) {
        if (store.hasSeenWrap(wrap.id)) return
        val signer = signerProvider.signer() ?: return

        // If decryption fails because the signer isn't available (e.g. Amber needs approval),
        // leave the wrap unseen so it is retried next time.
        val sealJson = try {
            signer.nip44Decrypt(wrap.content, wrap.pubkey)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (signer is LocalSigner) store.markWrapSeen(wrap.id)
            return
        }

        val seal = Event.parse(sealJson)
            ?.takeIf { it.kind == Kind.SEAL && it.tags.isEmpty() && it.isValid() }
        val rumor = seal?.let {
            try {
                Event.parseRumor(signer.nip44Decrypt(it.content, it.pubkey))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                null
            }
        }?.takeIf { it.pubkey == seal.pubkey }

        store.markWrapSeen(wrap.id)
        store.setLastSyncSeconds(wrap.createdAt)
        if (rumor == null) return
        if (rumor.kind != Kind.DM && rumor.kind != Kind.FILE_DM) {
            return
        }

        val recipients = rumor.tags.valuesOf("p")
        val chatKey = NostrChatKey.forParticipants(
            participants = recipients + rumor.pubkey,
            me = me
        )
        val isOutgoing = rumor.pubkey == me
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

        val attachment = if (rumor.kind == Kind.FILE_DM) {
            parseAttachment(rumor) ?: return
        } else {
            null
        }

        val message = NostrMessage(
            id = rumor.id,
            chatKey = chatKey,
            senderPubKey = rumor.pubkey,
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
        refreshProfile(rumor.pubkey)
        NostrChatKey.members(chatKey).forEach(::refreshProfile)
        store.addPeople(NostrChatKey.members(chatKey), NostrPeopleSource.CHAT)
    }

    /** Reads a kind 15 file message. Returns null if it can't be decrypted. */
    private fun parseAttachment(rumor: Draft): NostrAttachment? {
        val tags = rumor.tags
        val algorithm = tags.valuesOf("encryption-algorithm").firstOrNull() ?: FILE_ALGORITHM
        if (!algorithm.equals(FILE_ALGORITHM, ignoreCase = true)) return null
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

    private fun Tags.valuesOf(name: String): List<String> =
        filter { tag -> tag.size > 1 && tag[0] == name }.map { tag -> tag[1] }.distinct()

    private fun notifySinceSeconds(): Long =
        store.readState(STATE_NOTIFY_SINCE)?.toLongOrNull() ?: Long.MAX_VALUE

    /** Posts a notification for [message] unless its chat is open on screen. */
    private suspend fun notify(message: NostrMessage) {
        // Uses the real on-screen state, not the delayed one that keeps relays connected.
        if (visibility.isViewing(TwillProtocolId.Nostr, message.chatKey)) return
        val attachment = message.attachment
        val sender = message.senderPubKey
        val picture = if (accountStore.loadProfilePictures.value) {
            store.profile(sender).first()?.picture?.let { url ->
                runCatching { media.loadAvatar(url) }.getOrNull()
            }
        } else {
            null
        }
        TwillNotifications.showMessage(
            context = context,
            protocol = TwillProtocolId.Nostr,
            icon = R.drawable.ic_notification_nostr,
            chatId = message.chatKey,
            title = chatTitle(message.chatKey),
            isGroup = NostrChatKey.isGroup(message.chatKey),
            chatPicture = null,
            message = TwillNotificationMessage(
                id = message.id,
                senderKey = sender,
                senderName = nameOf(sender),
                senderPicture = picture,
                text = when {
                    attachment?.isImage == true -> context.getString(R.string.nostr_photo)
                    attachment != null -> context.getString(R.string.nostr_file)
                    else -> message.content
                },
                timeMillis = message.createdAtSeconds * 1000,
            ),
        )
    }

    private suspend fun nameOf(pubKey: String): String =
        store.profile(pubKey).first()?.name ?: NostrSignerProvider.shortNpub(pubKey)

    private suspend fun chatTitle(chatKey: String): String {
        if (!NostrChatKey.isGroup(chatKey)) return nameOf(chatKey)
        store.group(chatKey).first().name?.let { return it }
        return NostrChatKey.members(chatKey).take(3).map { nameOf(it) }.joinToString(", ")
    }

    fun normalizedRelays(urls: List<String>): List<String> =
        urls.mapNotNull(::normalizeRelay).distinct()

    private data class ConnectionPlan(
        val account: NostrAccount?,
        val relays: List<String>,
        val shouldConnect: Boolean,
    )

    private companion object {
        const val TAG = "NostrMessenger"
        const val STATE_PUBLISHED_RELAYS = "published_dm_relays"
        const val STATE_NOTIFY_SINCE = "notify_since"
        const val PROFILE_RETRY_MILLIS = 10 * 60 * 1000L
        const val PUBKEY_HEX_LENGTH = 64
        const val FILE_ALGORITHM = "aes-gcm"
        const val TWO_DAYS_SECONDS = 2 * 24 * 60 * 60
        const val FETCH_TIMEOUT_MILLIS = 8_000L
        const val ONE_SHOT_SYNC_TIMEOUT_MILLIS = 25_000L
        const val DM_RELAY_CACHE_MILLIS = 60 * 60 * 1000L
        const val PROFILE_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
        const val GIFT_WRAP_TIME_SKEW_SECONDS = 2 * 24 * 60 * 60L + 3600L
        const val FIRST_SYNC_LOOKBACK_SECONDS = 30 * 24 * 60 * 60L
    }
}
