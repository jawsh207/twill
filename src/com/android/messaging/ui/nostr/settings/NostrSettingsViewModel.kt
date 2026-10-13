package com.android.messaging.ui.nostr.settings

import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.messaging.nostr.NostrAccount
import com.android.messaging.nostr.NostrAccountStore
import com.android.messaging.nostr.NostrDeliveryMode
import com.android.messaging.nostr.NostrMessageStore
import com.android.messaging.nostr.NostrMessenger
import com.android.messaging.nostr.NostrProfile
import com.android.messaging.nostr.NostrSignerProvider
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrlOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
internal data class NostrSettingsUiState(
    val account: NostrAccount? = null,
    val relays: List<String> = emptyList(),
    val isExternalSignerInstalled: Boolean = false,
    val deliveryMode: NostrDeliveryMode = NostrDeliveryMode.WhileOpen,
    val mediaServer: String = "",
    val autoDownloadMedia: Boolean = false,
    val loadProfilePictures: Boolean = false,
    val ownProfile: NostrProfile? = null,
    val isRefreshingProfile: Boolean = false,
)

@HiltViewModel
internal class NostrSettingsViewModel @Inject constructor(
    private val accountStore: NostrAccountStore,
    private val signerProvider: NostrSignerProvider,
    private val messenger: NostrMessenger,
    private val messageStore: NostrMessageStore,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ownProfile = accountStore.account.flatMapLatest { account ->
        if (account == null) {
            flowOf(null to false)
        } else {
            combine(
                messageStore.profile(account.pubKeyHex),
                messenger.refreshingProfiles,
            ) { profile, refreshing -> profile to (account.pubKeyHex in refreshing) }
        }
    }

    private val mediaSettings = combine(
        accountStore.mediaServer,
        accountStore.autoDownloadMedia,
        accountStore.loadProfilePictures,
    ) { server, autoDownload, loadPictures -> Triple(server, autoDownload, loadPictures) }

    val uiState: StateFlow<NostrSettingsUiState> = combine(
        accountStore.account,
        accountStore.relays,
        accountStore.deliveryMode,
        mediaSettings,
        ownProfile,
    ) {
            account,
            relays,
            deliveryMode,
            (server, autoDownload, loadPictures),
            (profile, refreshing),
        ->
        NostrSettingsUiState(
            account = account,
            relays = relays,
            isExternalSignerInstalled = signerProvider.isExternalSignerInstalled(),
            deliveryMode = deliveryMode,
            mediaServer = server,
            autoDownloadMedia = autoDownload,
            loadProfilePictures = loadPictures,
            ownProfile = profile,
            isRefreshingProfile = refreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NostrSettingsUiState(),
    )

    init {
        // Settings can be the first screen opened after sign-in.
        messenger.install()
        accountStore.account.value?.let { messenger.refreshProfile(it.pubKeyHex) }
    }

    /** Fetches the user's own profile again, ignoring the cache. */
    fun refreshOwnProfile() {
        accountStore.account.value?.let { messenger.refreshProfile(it.pubKeyHex, force = true) }
    }

    fun externalSignerLoginIntent(): Intent = signerProvider.externalSignerLoginIntent()

    fun onExternalSignerResult(data: Intent?): Boolean = signerProvider.completeExternalSignerLogin(
        data
    )

    fun signInWithPrivateKey(input: String): Boolean = signerProvider.signInWithPrivateKey(input)

    fun createNewKey() {
        viewModelScope.launch(Dispatchers.IO) { signerProvider.createNewKey() }
    }

    fun exportLocalNsec(): String? = signerProvider.exportLocalNsec()

    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) { signerProvider.signOut() }
    }

    /** Returns false if [input] isn't a valid relay address. */
    fun addRelay(input: String): Boolean {
        val trimmed = input.trim()
        val withScheme = when {
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            else -> "wss://$trimmed"
        }
        val normalized = withScheme.normalizeRelayUrlOrNull() ?: return false
        viewModelScope.launch(Dispatchers.IO) { accountStore.addRelay(normalized.url) }
        return true
    }

    fun setDeliveryMode(mode: NostrDeliveryMode) {
        viewModelScope.launch(Dispatchers.IO) { accountStore.setDeliveryMode(mode) }
    }

    fun setAutoDownloadMedia(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { accountStore.setAutoDownloadMedia(enabled) }
    }

    fun setLoadProfilePictures(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { accountStore.setLoadProfilePictures(enabled) }
    }

    /** Returns false if [input] isn't an https address. */
    fun setMediaServer(input: String): Boolean {
        val trimmed = input.trim().trimEnd('/')
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        if (!withScheme.startsWith("https://") ||
            withScheme.length <= "https://".length
        ) {
            return false
        }
        val host = runCatching { java.net.URI(withScheme).host }.getOrNull()
        if (host.isNullOrBlank() || '.' !in host) return false
        viewModelScope.launch(Dispatchers.IO) { accountStore.setMediaServer(withScheme) }
        return true
    }

    fun removeRelay(url: String) {
        viewModelScope.launch(Dispatchers.IO) { accountStore.removeRelay(url) }
    }
}
