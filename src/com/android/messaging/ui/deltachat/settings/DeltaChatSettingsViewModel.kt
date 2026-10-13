package com.android.messaging.ui.deltachat.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.messaging.deltachat.DeltaChatAccountStore
import com.android.messaging.deltachat.DeltaChatProtocol
import com.android.messaging.twill.TwillDeliveryMode
import com.android.messaging.twill.TwillRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
internal data class DeltaChatProfile(
    val displayName: String = "",
    val address: String = "",
    val readReceipts: Boolean = true,
)

@Immutable
internal data class DeltaChatSettingsUiState(
    val isAvailable: Boolean = true,
    val isConfigured: Boolean = false,
    val isWorking: Boolean = false,
    /** 0–1000 while setting up. */
    val progress: Int? = null,
    /** Why the last setup failed, as the core worded it. */
    val error: String? = null,
    val profile: DeltaChatProfile = DeltaChatProfile(),
    val deliveryMode: TwillDeliveryMode = TwillDeliveryMode.WhileOpen,
)

@HiltViewModel
internal class DeltaChatSettingsViewModel @Inject constructor(
    private val protocol: DeltaChatProtocol,
    private val accountStore: DeltaChatAccountStore,
    runtime: TwillRuntime,
) : ViewModel() {

    private val isWorking = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val profile = MutableStateFlow(DeltaChatProfile())

    init {
        runtime.start(markVisible = true)
        viewModelScope.launch {
            combine(accountStore.isConfigured, protocol.changeCount) { configured, _ -> configured }
                .collect { configured -> if (configured) loadProfile() }
        }
    }

    private val working =
        combine(isWorking, protocol.configureProgress, error) { busy, progress, message ->
            Triple(busy, progress, message)
        }

    val uiState: StateFlow<DeltaChatSettingsUiState> = combine(
        accountStore.isConfigured,
        accountStore.deliveryMode,
        working,
        profile,
    ) { configured, mode, (busy, progress, message), currentProfile ->
        DeltaChatSettingsUiState(
            isAvailable = protocol.isAvailable,
            isConfigured = configured,
            isWorking = busy,
            progress = progress,
            error = message,
            profile = currentProfile,
            deliveryMode = mode,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DeltaChatSettingsUiState(isAvailable = protocol.isAvailable),
    )

    private suspend fun loadProfile() {
        profile.value = DeltaChatProfile(
            displayName = protocol.getConfig("displayname").orEmpty(),
            address = protocol.getConfig("configured_addr").orEmpty(),
            readReceipts = protocol.getConfig("mdns_enabled") != "0",
        )
    }

    private fun runSetup(block: suspend () -> Unit) {
        if (isWorking.value) return
        isWorking.value = true
        error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                error.value = exception.message ?: exception.javaClass.simpleName
            } finally {
                isWorking.value = false
            }
        }
    }

    fun createProfile(name: String, server: String) =
        runSetup { protocol.createProfile(displayName = name, server = server) }

    fun useCode(name: String, code: String) =
        runSetup { protocol.setupFromCode(displayName = name, code = code) }

    fun useEmail(name: String, address: String, password: String) =
        runSetup {
            protocol.setupWithEmail(displayName = name, address = address, password = password)
        }

    fun setDisplayName(name: String) {
        viewModelScope.launch { protocol.setConfig("displayname", name.trim()) }
    }

    fun setReadReceipts(enabled: Boolean) {
        profile.value = profile.value.copy(readReceipts = enabled)
        viewModelScope.launch { protocol.setConfig("mdns_enabled", if (enabled) "1" else "0") }
    }

    fun setDeliveryMode(mode: TwillDeliveryMode) = accountStore.setDeliveryMode(mode)

    suspend fun inviteLink(): String? = protocol.inviteLink()

    fun removeProfile() {
        viewModelScope.launch {
            protocol.removeProfile()
            profile.value = DeltaChatProfile()
        }
    }

    fun clearError() {
        error.value = null
    }
}
