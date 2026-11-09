package com.android.messaging.nostr

import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.util.LogUtil
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The people behind the Nostr new-chat suggestions, kept on the phone so typing finds them
 * instantly. Filled from what every Nostr client shares on relays, not just Twill's chats:
 *
 * - the follow list (kind 3) and public mute list (kind 10000, never suggested),
 * - older-style DMs (NIP-04, kind 4) sent or received in other clients (only who, not what),
 * - replies, mentions, reposts, reactions and zaps to or from the user,
 * - people who follow the user,
 *
 * plus their profiles (kind 0). Refreshed in the background every few hours while Twill is
 * connected. With relay search on, [search] also asks NIP-50 search relays for strangers.
 * NIP-05 addresses are only shown once checked, so copycat profiles can't borrow one.
 */
@Singleton
internal class NostrDirectory @Inject constructor(
    private val accountStore: NostrAccountStore,
    private val store: NostrMessageStore,
    private val messenger: NostrMessenger,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) {
    private val isRefreshing = AtomicBoolean(false)
    private val nip05Checks = ConcurrentHashMap.newKeySet<String>()
    private val nip05Permits = Semaphore(NIP05_CHECKS_AT_ONCE)
    private var isInstalled = false

    /** Refreshes whenever Twill connects (at most every few hours). */
    fun install() {
        synchronized(this) {
            if (isInstalled) return
            isInstalled = true
        }
        appScope.launch {
            combine(accountStore.account, messenger.isConnected) { account, connected ->
                account?.pubKeyHex?.takeIf { connected }
            }
                .distinctUntilChanged()
                .collect { me -> if (me != null) refreshIfStale() }
        }
    }

    /** Starts a refresh unless one ran recently (or is running). */
    fun refreshIfStale() {
        val last = store.readState(STATE_REFRESHED_AT)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < REFRESH_INTERVAL_MILLIS) return
        if (!isRefreshing.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO) {
            try {
                refresh()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LogUtil.w(TAG, "Directory refresh failed: ${exception.javaClass.simpleName}")
            } finally {
                isRefreshing.set(false)
            }
        }
    }

    /**
     * Asks the search relays for profiles matching [query] and adds them to the directory, so
     * they show up in the suggestions. Does nothing unless relay search is on.
     */
    suspend fun search(query: String) = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (!accountStore.searchRelays.value || text.length < MIN_SEARCH_LENGTH) return@withContext
        if (accountStore.account.value == null) return@withContext
        val relays = messenger.normalizedRelays(accountStore.searchRelayUrls.value)
        val events = try {
            messenger.fetchAll(
                relays,
                Filter(kinds = listOf(MetadataEvent.KIND), search = text, limit = SEARCH_LIMIT),
                timeoutMillis = SEARCH_TIMEOUT_MILLIS,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            emptyList()
        }
        val profiles = newestProfiles(events)
        if (profiles.isEmpty()) return@withContext
        store.upsertProfiles(profiles)
        store.addPeople(profiles.map { it.pubKey }, NostrPeopleSource.SEARCH)
        // Strangers are where copycats hide: check their addresses straight away.
        checkNip05(profiles.filter { it.nip05 != null }.map { it.pubKey to it.nip05!! })
    }

    /** Checks the NIP-05 addresses of suggestions on screen that haven't been checked. */
    fun checkShown(people: List<NostrPerson>) {
        val unchecked = people.filter { it.isNip05Unchecked }
            .mapNotNull { person -> person.profile?.nip05?.let { person.pubKey to it } }
        if (unchecked.isEmpty()) return
        appScope.launch(Dispatchers.IO) { checkNip05(unchecked) }
    }

    private suspend fun checkNip05(entries: List<Pair<String, String>>) = coroutineScope {
        entries.filter { (pubKey, _) -> nip05Checks.add(pubKey) }
            .map { (pubKey, address) ->
                async {
                    try {
                        val isVerified = nip05Permits.withPermit {
                            messenger.verifyNip05(address, pubKey)
                        }
                        store.markNip05Checked(pubKey, isVerified)
                    } finally {
                        nip05Checks.remove(pubKey)
                    }
                }
            }
            .awaitAll()
    }

    private suspend fun refresh() {
        val me = accountStore.account.value?.pubKeyHex ?: return
        store.addPeople(store.chatPeople(), NostrPeopleSource.CHAT)

        val baseRelays = messenger.normalizedRelays(
            accountStore.relays.value + accountStore.searchRelayUrls.value,
        )
        if (baseRelays.isEmpty()) return

        // The user's own lists: follows, mutes and NIP-65 relays (where their notes live).
        val lists = fetch(
            baseRelays,
            Filter(
                kinds = listOf(KIND_FOLLOWS, KIND_MUTES, KIND_RELAY_LIST),
                authors = listOf(me),
            ),
        ).filter { it.pubKey == me }
        val newest = lists.groupBy { it.kind }.mapValues { (_, events) ->
            events.maxBy { it.createdAt }
        }
        newest[KIND_MUTES]?.let { mutes ->
            store.addPeople(mutes.pTags(), NostrPeopleSource.MUTED, replaceSource = true)
        }
        newest[KIND_FOLLOWS]?.let { follows ->
            store.addPeople(follows.pTags(), NostrPeopleSource.FOLLOW, replaceSource = true)
        }
        val outbox = newest[KIND_RELAY_LIST]?.tags.orEmpty()
            .filter { it.size > 1 && it[0] == "r" }
            .mapNotNull { it[1].normalizeRelayUrlOrNull() }
            .take(MAX_OUTBOX_RELAYS)
        val relays = (baseRelays + outbox).distinct()

        // Everyone the user has dealt with, in any client.
        coroutineScope {
            val toMeTag = mapOf("p" to listOf(me))
            val legacySent = async {
                fetch(relays, recent(listOf(KIND_LEGACY_DM), authors = listOf(me)))
            }
            val legacyReceived = async {
                fetch(relays, recent(listOf(KIND_LEGACY_DM), tags = toMeTag))
            }
            val toMe = async { fetch(relays, recent(INTERACTION_KINDS, tags = toMeTag)) }
            val fromMe = async { fetch(relays, recent(INTERACTION_KINDS, authors = listOf(me))) }
            val followers = async { fetch(relays, recent(listOf(KIND_FOLLOWS), tags = toMeTag)) }

            val legacy = legacySent.await().filter { it.pubKey == me }.flatMap { it.pTags() } +
                legacyReceived.await().map { it.pubKey }
            store.addPeople(legacy.filter { it != me }, NostrPeopleSource.LEGACY_DM)

            val interactions = toMe.await().mapNotNull { it.counterpart() } +
                fromMe.await().filter { it.pubKey == me }.flatMap { it.pTags() }
            val counts = interactions.filter { it != me }.groupingBy { it }.eachCount()
            store.addPeople(counts.keys, NostrPeopleSource.INTERACTION, counts = counts)

            store.addPeople(
                followers.await().map { it.pubKey }.filter { it != me },
                NostrPeopleSource.FOLLOWER,
            )
        }

        fetchProfiles(relays)
        store.writeState(STATE_REFRESHED_AT, System.currentTimeMillis().toString())
    }

    /** Fetches missing or old profiles in batches, the people the user knows first. */
    private suspend fun fetchProfiles(relays: List<NormalizedRelayUrl>) {
        val needed = store.peopleNeedingProfiles(PROFILE_MAX_AGE_MILLIS, MAX_PROFILES)
        needed.chunked(PROFILE_BATCH).forEach { batch ->
            val events = fetch(
                relays,
                Filter(kinds = listOf(MetadataEvent.KIND), authors = batch),
            )
            val found = newestProfiles(events)
            store.upsertProfiles(found)
            // People with no profile on these relays: don't ask for them on every refresh.
            store.markProfilesFetched(batch - found.map { it.pubKey }.toSet())
        }
    }

    private suspend fun fetch(relays: List<NormalizedRelayUrl>, filter: Filter): List<Event> = try {
        messenger.fetchAll(relays, filter, timeoutMillis = FETCH_TIMEOUT_MILLIS)
            .distinctBy { it.id }
            .filter { it.verify() }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        emptyList()
    }

    /** The newest signed kind 0 profile per author in [events]. */
    private fun newestProfiles(events: List<Event>): List<NostrProfile> = events
        .filter { it.kind == MetadataEvent.KIND && it.pubKey.length == PUBKEY_HEX_LENGTH }
        .distinctBy { it.id }
        .filter { it.verify() }
        .groupBy { it.pubKey }
        .map { (pubKey, versions) ->
            val content = runCatching { JSONObject(versions.maxBy { it.createdAt }.content) }
                .getOrNull() ?: JSONObject()
            NostrProfile(
                pubKey = pubKey,
                name = content.cleanString("display_name")
                    ?: content.cleanString("displayName")
                    ?: content.cleanString("name"),
                picture = content.cleanString("picture")
                    ?.takeIf { it.startsWith("https://", ignoreCase = true) },
                nip05 = content.cleanString("nip05"),
            )
        }

    /** A filter for the newest [LIMIT] events of [kinds]. */
    private fun recent(
        kinds: List<Int>,
        authors: List<String>? = null,
        tags: Map<String, List<String>>? = null,
    ) = Filter(kinds = kinds, authors = authors, tags = tags, limit = LIMIT)

    private fun JSONObject.cleanString(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun Event.pTags(): List<String> = tags
        .filter { it.size > 1 && it[0] == "p" && it[1].length == PUBKEY_HEX_LENGTH }
        .map { it[1].lowercase() }
        .distinct()

    /** Who an event aimed at the user came from; for zap receipts, the person who zapped. */
    private fun Event.counterpart(): String? {
        if (kind != KIND_ZAP_RECEIPT) return pubKey
        val request = tags.firstOrNull { it.size > 1 && it[0] == "description" }?.get(1)
            ?: return null
        return runCatching { JSONObject(request).optString("pubkey") }.getOrNull()
            ?.lowercase()
            ?.takeIf { it.length == PUBKEY_HEX_LENGTH && it.all { char -> char in HEX } }
    }

    private companion object {
        const val TAG = "NostrDirectory"
        const val STATE_REFRESHED_AT = "directory_refreshed_at"
        const val REFRESH_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
        const val PROFILE_MAX_AGE_MILLIS = 3 * 24 * 60 * 60 * 1000L
        const val FETCH_TIMEOUT_MILLIS = 12_000L
        const val SEARCH_TIMEOUT_MILLIS = 6_000L
        const val MIN_SEARCH_LENGTH = 3
        const val SEARCH_LIMIT = 20
        const val LIMIT = 500
        const val MAX_PROFILES = 3_000
        const val PROFILE_BATCH = 250
        const val MAX_OUTBOX_RELAYS = 4
        const val NIP05_CHECKS_AT_ONCE = 4
        const val PUBKEY_HEX_LENGTH = 64
        const val KIND_FOLLOWS = 3
        const val KIND_LEGACY_DM = 4
        const val KIND_MUTES = 10_000
        const val KIND_RELAY_LIST = 10_002
        const val KIND_ZAP_RECEIPT = 9_735
        const val HEX = "0123456789abcdef"

        /** Notes (replies, mentions), reposts, reactions and zap receipts. */
        val INTERACTION_KINDS = listOf(1, 6, 7, KIND_ZAP_RECEIPT)
    }
}
