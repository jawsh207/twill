package com.android.messaging.nostr

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip17Dm.files.ChatMessageEncryptedFileHeaderEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent
import com.vitorpamplona.quartz.nip19Bech32.decodePrivateKeyAsHexOrNull
import com.vitorpamplona.quartz.nip19Bech32.decodePublicKeyAsHexOrNull
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.PubKeyResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.requests.LoginRequest
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.responses.LoginResponse
import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.results.IntentResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the [NostrSigner] for the signed-in account, and helps with signing in. */
@Singleton
internal class NostrSignerProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountStore: NostrAccountStore,
) {
    @Volatile
    private var cached: Pair<NostrAccount, NostrSigner>? = null

    /** The signer for the current account, or null when signed out. */
    fun signer(): NostrSigner? {
        val account = accountStore.account.value ?: return null
        cached?.let { (cachedAccount, signer) ->
            if (cachedAccount == account) return signer
        }

        val signer: NostrSigner = when (account) {
            is NostrAccount.ExternalSigner -> {
                NostrSignerExternal(
                    pubKey = account.pubKeyHex,
                    packageName = account.signerPackage,
                    contentResolver = context.contentResolver,
                )
            }

            is NostrAccount.LocalKey -> {
                val privKey = accountStore.localPrivateKey() ?: return null
                NostrSignerInternal(KeyPair(privKey = privKey))
            }
        }
        cached = account to signer
        return signer
    }

    /** True when an app that handles NIP-55 `nostrsigner:` requests (such as Amber) is installed. */
    fun isExternalSignerInstalled(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    /**
     * The intent that asks Amber for the user's public key. It also asks for the permissions
     * Twill needs so Amber can approve DMs in the background instead of prompting each time.
     */
    fun externalSignerLoginIntent(): Intent {
        return LoginRequest.assemble(permissions = EXTERNAL_SIGNER_PERMISSIONS)
    }

    /** Handles Amber's reply to [externalSignerLoginIntent]. Returns false if sign-in failed. */
    fun completeExternalSignerLogin(data: Intent?): Boolean {
        data ?: return false
        val parsed = LoginResponse.parse(IntentResult.fromIntent(data))
        val result = (parsed as? SignerResult.RequestAddressed.Successful<PubKeyResult>)
            ?.result
            ?: return false

        // Amber may answer with an npub or a hex key.
        val pubKeyHex = decodePublicKeyAsHexOrNull(result.pubkey) ?: return false
        accountStore.signInWithExternalSigner(pubKeyHex, result.packageName)
        return true
    }

    /** Signs in with a pasted nsec (or hex) key. Returns false if it isn't a valid key. */
    fun signInWithPrivateKey(input: String): Boolean {
        val privKeyHex = decodePrivateKeyAsHexOrNull(input.trim()) ?: return false
        if (privKeyHex.length != PRIVATE_KEY_HEX_LENGTH) return false
        accountStore.signInWithLocalKey(privKeyHex.hexToByteArray())
        return true
    }

    /** Creates a brand-new key and signs in with it. */
    fun createNewKey() {
        val keyPair = KeyPair()
        accountStore.signInWithLocalKey(requireNotNull(keyPair.privKey))
    }

    /** The local key as an nsec for backing up, or null when using an external signer. */
    fun exportLocalNsec(): String? = accountStore.localPrivateKey()?.toNsec()

    fun signOut() {
        cached = null
        accountStore.signOut()
    }

    companion object {
        private const val PRIVATE_KEY_HEX_LENGTH = 64

        private val EXTERNAL_SIGNER_PERMISSIONS = listOf(
            // NIP-17: the user signs the seal; the outer gift wrap uses a throwaway key.
            Permission(CommandType.SIGN_EVENT, SealEvent.KIND),
            Permission(CommandType.SIGN_EVENT, ChatMessageEvent.KIND),
            Permission(CommandType.SIGN_EVENT, ChatMessageEncryptedFileHeaderEvent.KIND),
            // Authorizing encrypted attachment uploads to the Blossom media server.
            Permission(CommandType.SIGN_EVENT, BlossomAuthorizationEvent.KIND),
            // Publishing the user's DM relay list (NIP-17 kind 10050).
            Permission(CommandType.SIGN_EVENT, DmRelayListEvent.KIND),
            Permission(CommandType.NIP44_ENCRYPT),
            Permission(CommandType.NIP44_DECRYPT),
        )

        fun npubOf(pubKeyHex: String): String = pubKeyHex.hexToByteArray().toNpub()

        fun shortNpub(pubKeyHex: String): String {
            val npub = npubOf(pubKeyHex)
            return npub.take(12) + "…" + npub.takeLast(6)
        }
    }
}
