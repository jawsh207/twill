package com.android.messaging.nostr

import android.content.Context
import android.content.SharedPreferences
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Nostr account and relay list, in private SharedPreferences. A local private key is only
 * ever stored encrypted with [KeystoreCipher]. Being signed out means Nostr is off: nothing
 * connects to relays until the user signs in.
 */
@Singleton
internal class NostrAccountStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _account = MutableStateFlow<NostrAccount?>(null)
    private val _relays = MutableStateFlow<List<String>>(emptyList())
    private val _deliveryMode = MutableStateFlow(NostrDeliveryMode.WhileOpen)
    private var isLoaded = false
    private val lock = Any()

    val account: StateFlow<NostrAccount?>
        get() {
            ensureLoaded()
            return _account.asStateFlow()
        }

    val relays: StateFlow<List<String>>
        get() {
            ensureLoaded()
            return _relays.asStateFlow()
        }

    val deliveryMode: StateFlow<NostrDeliveryMode>
        get() {
            ensureLoaded()
            return _deliveryMode.asStateFlow()
        }

    fun setDeliveryMode(mode: NostrDeliveryMode) {
        synchronized(lock) {
            ensureLoaded()
            prefs.edit().putString(KEY_DELIVERY_MODE, mode.key).commit()
            _deliveryMode.value = mode
        }
    }

    fun signInWithExternalSigner(pubKeyHex: String, signerPackage: String) {
        synchronized(lock) {
            clearLocalKey()
            prefs.edit()
                .putString(KEY_METHOD, METHOD_EXTERNAL)
                .putString(KEY_PUBKEY, pubKeyHex)
                .putString(KEY_SIGNER_PACKAGE, signerPackage)
                .commit()
            _account.value = NostrAccount.ExternalSigner(pubKeyHex, signerPackage)
        }
    }

    /** Stores [privKey] encrypted and signs in with it. Returns the public key. */
    fun signInWithLocalKey(privKey: ByteArray): String {
        val pubKeyHex = KeyPair(privKey = privKey).pubKey.toHexKey()
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_METHOD, METHOD_LOCAL)
                .putString(KEY_PUBKEY, pubKeyHex)
                .putString(KEY_ENCRYPTED_PRIVKEY, KeystoreCipher.encrypt(privKey))
                .remove(KEY_SIGNER_PACKAGE)
                .commit()
            _account.value = NostrAccount.LocalKey(pubKeyHex)
        }
        return pubKeyHex
    }

    /** The decrypted local private key, or null when signed in with an external signer. */
    fun localPrivateKey(): ByteArray? {
        val stored = prefs.getString(KEY_ENCRYPTED_PRIVKEY, null) ?: return null
        return runCatching { KeystoreCipher.decrypt(stored) }.getOrNull()
    }

    fun signOut() {
        synchronized(lock) {
            clearLocalKey()
            prefs.edit()
                .remove(KEY_METHOD)
                .remove(KEY_PUBKEY)
                .remove(KEY_SIGNER_PACKAGE)
                .commit()
            _account.value = null
        }
    }

    fun addRelay(url: String) {
        updateRelays { current -> (current + url).distinct() }
    }

    fun removeRelay(url: String) {
        updateRelays { current -> current - url }
    }

    private fun updateRelays(transform: (List<String>) -> List<String>) {
        synchronized(lock) {
            ensureLoaded()
            val updated = transform(_relays.value)
            prefs.edit().putString(KEY_RELAYS, updated.joinToString(RELAY_SEPARATOR)).commit()
            _relays.value = updated
        }
    }

    private fun clearLocalKey() {
        prefs.edit().remove(KEY_ENCRYPTED_PRIVKEY).commit()
        runCatching { KeystoreCipher.deleteKey() }
    }

    private fun ensureLoaded() {
        synchronized(lock) {
            if (isLoaded) return
            _account.value = readAccount()
            _relays.value = prefs.getString(KEY_RELAYS, null)
                ?.split(RELAY_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: NostrDefaults.DEFAULT_RELAYS
            _deliveryMode.value = NostrDeliveryMode.from(prefs.getString(KEY_DELIVERY_MODE, null))
            isLoaded = true
        }
    }

    private fun readAccount(): NostrAccount? {
        val pubKey = prefs.getString(KEY_PUBKEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return when (prefs.getString(KEY_METHOD, null)) {
            METHOD_EXTERNAL -> {
                val signerPackage = prefs.getString(KEY_SIGNER_PACKAGE, null) ?: return null
                NostrAccount.ExternalSigner(pubKey, signerPackage)
            }

            METHOD_LOCAL -> {
                if (prefs.contains(KEY_ENCRYPTED_PRIVKEY)) NostrAccount.LocalKey(pubKey) else null
            }

            else -> null
        }
    }

    private companion object {
        const val PREFS_NAME = "nostr_account"
        const val KEY_METHOD = "method"
        const val KEY_PUBKEY = "pubkey"
        const val KEY_SIGNER_PACKAGE = "signer_package"
        const val KEY_ENCRYPTED_PRIVKEY = "encrypted_privkey"
        const val KEY_RELAYS = "relays"
        const val KEY_DELIVERY_MODE = "delivery_mode"
        const val METHOD_EXTERNAL = "external"
        const val METHOD_LOCAL = "local"
        const val RELAY_SEPARATOR = "\n"
    }
}
