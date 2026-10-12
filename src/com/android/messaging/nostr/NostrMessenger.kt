package com.android.messaging.nostr

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.util.LogUtil
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchFirst
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirm
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrlOrNull
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.NIP17Factory
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * Sends and receives NIP-17 private DMs (NIP-44 encryption inside NIP-59 gift wraps).
 *
 * Relays are only contacted while signed in and while [shouldStayConnected] says so: by
 * default while Twill is on screen, or always when background delivery is on (see
 * [setKeepConnectedInBackground]).
 */
@Singleton
internal class NostrMessenger @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountStore: NostrAccountStore,
    private val signerProvider: NostrSignerProvider,
    private val store: NostrMessageStore,
    private val deliveryManager: NostrDeliveryManager,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) {
    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val client: NostrClient by lazy {
        NostrClient(
            websocketBuilder = BasicOkHttpWebSocket.Builder { okHttp },
            parentScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    private val processMutex = Mutex()
    private val dmRelayCache = ConcurrentHashMap<String, Pair<Long, List<NormalizedRelayUrl>>>()
    private val profileRequests = ConcurrentHashMap.newKeySet<String>()

    private val visibleActivities = MutableStateFlow(0)
    private val isAppVisible = MutableStateFlow(false)
    private val keepConnectedInBackground = MutableStateFlow(false)
    private var visibilityJob: Job? = null
    private var isInstalled = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _incoming = MutableSharedFlow<NostrMessage>(extraBufferCapacity = 64)

    /** New incoming messages as they are decrypted; used for notifications. */
    val incoming: SharedFlow<NostrMessage> = _incoming.asSharedFlow()

    @Volatile
    private var activeChatPeer: String? = null

    /**
     * Starts watching app visibility and the account. Safe to call more than once. Screens
     * call it with [markAppVisible] true; background components pass false.
     */
    fun install(markAppVisible: Boolean = true) {
        synchronized(this) {
            if (isInstalled) {
                if (markAppVisible && visibleActivities.value == 0) {
                    visibleActivities.value = 1
                    visibilityJob?.cancel()
                    isAppVisible.value = true
                }
                return
            }
            isInstalled = true
        }

        if (markAppVisible) {
            visibleActivities.value = 1
            isAppVisible.value = true
        }
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            VisibilityCallbacks(),
        )
        NostrNotifications.ensureChannels(context)
        deliveryManager.start()

        // Notify about new messages unless that chat is open on screen.
        appScope.launch {
            _incoming.collect { message ->
                val isViewing = isAppVisible.value && activeChatPeer == message.peerPubKey
                if (!isViewing) {
                    val name = store.profile(message.peerPubKey).first()?.name
                        ?: NostrSignerProvider.shortNpub(message.peerPubKey)
                    NostrNotifications.showMessage(context, name, message)
                }
            }
        }

        appScope.launch {
            combine(
                accountStore.account,
                accountStore.relays,
                isAppVisible,
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
                }
            }
        }
    }

    /** The chat currently on screen, so its messages don't raise notifications. */
    fun setActiveChat(peerPubKey: String?) {
        activeChatPeer = peerPubKey
        if (peerPubKey != null) NostrNotifications.cancelConversation(context, peerPubKey)
    }

    fun launchInAppScope(block: suspend CoroutineScope.() -> Unit): Job =
        appScope.launch(Dispatchers.IO, block = block)

    fun setKeepConnectedInBackground(enabled: Boolean) {
        keepConnectedInBackground.value = enabled
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
            relays.associateWith {
                listOf(dmFilter(account.pubKeyHex))
            },
            listener
        )
        withTimeoutOrNull(timeoutMillis) {
            finishedRelays.first { count -> count >= relays.size }
        }
        client.unsubscribe(SYNC_SUB_ID)
        if (!_isConnected.value) client.disconnect()
    }

    /** Sends [text] to [peerPubKey]. The message shows immediately as sending. */
    fun send(peerPubKey: String, text: String) {
        appScope.launch(Dispatchers.IO) {
            sendNow(peerPubKey = peerPubKey, text = text)
        }
    }

    /** Sends a failed message again. */
    fun retry(message: NostrMessage) {
        appScope.launch(Dispatchers.IO) {
            store.deleteMessage(message.id)
            sendNow(peerPubKey = message.peerPubKey, text = message.content)
        }
    }

    /** Fetches [pubKey]'s profile name if it's missing or more than a day old. */
    fun refreshProfile(pubKey: String) {
        if (!profileRequests.add(pubKey)) return
        appScope.launch(Dispatchers.IO) {
            try {
                val updatedAt = store.profileUpdatedAt(pubKey)
                if (updatedAt != null &&
                    System.currentTimeMillis() - updatedAt < PROFILE_MAX_AGE_MILLIS
                ) {
                    return@launch
                }
                val relays = normalizedRelays(accountStore.relays.value)
                if (relays.isEmpty()) return@launch
                val filter =
                    Filter(kinds = listOf(MetadataEvent.KIND), authors = listOf(pubKey), limit = 1)
                val event = withTimeoutOrNull(FETCH_TIMEOUT_MILLIS) {
                    client.fetchFirst(filters = relays.associateWith { listOf(filter) })
                }
                val metadata = (event as? MetadataEvent)
                    ?.takeIf { it.verify() }
                    ?.contactMetaData()
                store.upsertProfile(
                    NostrProfile(
                        pubKey = pubKey,
                        name = metadata?.bestName()?.takeIf { it.isNotBlank() },
                        picture = null,
                        nip05 = metadata?.nip05(),
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LogUtil.w(TAG, "Profile fetch failed: ${exception.javaClass.simpleName}")
            } finally {
                profileRequests.remove(pubKey)
            }
        }
    }

    private suspend fun sendNow(peerPubKey: String, text: String) {
        val signer = signerProvider.signer() ?: return
        val me = signer.pubKey
        val myRelays = normalizedRelays(accountStore.relays.value)

        val result = try {
            NIP17Factory().createMessageNIP17(
                template = ChatMessageEvent.build(msg = text, to = listOf(PTag(peerPubKey))),
                signer = signer,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LogUtil.w(TAG, "Could not build message: ${exception.javaClass.simpleName}")
            store.insertMessage(
                NostrMessage(
                    id = "failed-" + System.nanoTime(),
                    peerPubKey = peerPubKey,
                    senderPubKey = me,
                    content = text,
                    createdAtSeconds = System.currentTimeMillis() / 1000,
                    isOutgoing = true,
                    isRead = true,
                    status = NostrMessage.Status.Failed,
                ),
            )
            return
        }

        val messageId = result.msg.id
        store.insertMessage(
            NostrMessage(
                id = messageId,
                peerPubKey = peerPubKey,
                senderPubKey = me,
                content = text,
                createdAtSeconds = result.msg.createdAt,
                isOutgoing = true,
                isRead = true,
                status = NostrMessage.Status.Sending,
            ),
        )

        var deliveredToPeer = false
        for (wrap in result.wraps) {
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
            if (recipient == peerPubKey && accepted) deliveredToPeer = true
        }

        store.updateStatus(
            id = messageId,
            status = if (deliveredToPeer) NostrMessage.Status.Sent else NostrMessage.Status.Failed,
        )
        refreshProfile(peerPubKey)
    }

    /** The relays [pubKey] reads DMs from (NIP-17 kind 10050), cached for an hour. */
    private suspend fun dmRelaysOf(pubKey: String): List<NormalizedRelayUrl> {
        dmRelayCache[pubKey]?.let { (fetchedAt, relays) ->
            if (System.currentTimeMillis() - fetchedAt < DM_RELAY_CACHE_MILLIS) return relays
        }
        val myRelays = normalizedRelays(accountStore.relays.value)
        if (myRelays.isEmpty()) return emptyList()

        val filter =
            Filter(kinds = listOf(DmRelayListEvent.KIND), authors = listOf(pubKey), limit = 1)
        val event = try {
            withTimeoutOrNull(FETCH_TIMEOUT_MILLIS) {
                client.fetchFirst(filters = myRelays.associateWith { listOf(filter) })
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            null
        }
        val relays = (event as? DmRelayListEvent)
            ?.takeIf { it.verify() }
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
        if (rumor == null || rumor.kind != ChatMessageEvent.KIND) return

        val recipients = rumor.tags
            .filter { tag -> tag.size > 1 && tag[0] == "p" }
            .map { tag -> tag[1] }
            .distinct()
        // Group chats aren't supported yet; only one-to-one conversations are shown.
        if ((recipients + rumor.pubKey).toSet().size > 2) return

        val isOutgoing = rumor.pubKey == me
        val peer = when {
            isOutgoing -> recipients.firstOrNull { it != me } ?: me
            else -> rumor.pubKey
        }

        val message = NostrMessage(
            id = rumor.id,
            peerPubKey = peer,
            senderPubKey = rumor.pubKey,
            content = rumor.content,
            createdAtSeconds = rumor.createdAt,
            isOutgoing = isOutgoing,
            isRead = isOutgoing,
            status = NostrMessage.Status.Sent,
        )
        if (store.insertMessage(message) && !isOutgoing) {
            _incoming.tryEmit(message)
        }
        refreshProfile(peer)
    }

    private fun normalizedRelays(urls: List<String>): List<NormalizedRelayUrl> =
        urls.mapNotNull { it.normalizeRelayUrlOrNull() }.distinct()

    private data class ConnectionPlan(
        val account: NostrAccount?,
        val relays: List<String>,
        val shouldConnect: Boolean,
    )

    private inner class VisibilityCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibleActivities.value += 1
            visibilityJob?.cancel()
            isAppVisible.value = true
        }

        override fun onActivityStopped(activity: Activity) {
            visibleActivities.value = (visibleActivities.value - 1).coerceAtLeast(0)
            if (visibleActivities.value == 0) {
                visibilityJob?.cancel()
                // Stay connected briefly so quick app switches don't drop the connection.
                visibilityJob = appScope.launch {
                    delay(BACKGROUND_GRACE_MILLIS)
                    isAppVisible.value = false
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val TAG = "NostrMessenger"
        const val LIVE_SUB_ID = "twill-dms"
        const val SYNC_SUB_ID = "twill-dms-sync"
        const val STATE_PUBLISHED_RELAYS = "published_dm_relays"
        const val PING_INTERVAL_SECONDS = 30L
        const val FETCH_TIMEOUT_MILLIS = 8_000L
        const val ONE_SHOT_SYNC_TIMEOUT_MILLIS = 25_000L
        const val BACKGROUND_GRACE_MILLIS = 30_000L
        const val DM_RELAY_CACHE_MILLIS = 60 * 60 * 1000L
        const val PROFILE_MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
        const val GIFT_WRAP_TIME_SKEW_SECONDS = 2 * 24 * 60 * 60L + 3600L
        const val FIRST_SYNC_LOOKBACK_SECONDS = 30 * 24 * 60 * 60L
    }
}
