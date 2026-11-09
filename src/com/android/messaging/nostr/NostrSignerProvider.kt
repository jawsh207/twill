package com.android.messaging.nostr

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.android.messaging.nostr.core.Keys
import com.android.messaging.nostr.core.Kind
import com.android.messaging.nostr.core.LocalSigner
import com.android.messaging.nostr.core.Nip19
import com.android.messaging.nostr.core.Signer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the [Signer] for the signed-in account, and helps with signing in. */
@Singleton
internal class NostrSignerProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountStore: NostrAccountStore,
) {
    @Volatile
    private var cached: Pair<NostrAccount, Signer>? = null

    /** The signer for the current account, or null when signed out. */
    fun signer(): Signer? {
        val account = accountStore.account.value ?: return null
        cached?.let { (cachedAccount, signer) ->
            if (cachedAccount == account) return signer
        }

        val signer: Signer = when (account) {
            is NostrAccount.ExternalSigner -> {
                NostrExternalSigner(context, account.pubKeyHex, account.signerPackage)
            }

            is NostrAccount.LocalKey -> {
                val privKey = accountStore.localPrivateKey() ?: return null
                LocalSigner(privKey)
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
    fun externalSignerLoginIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri()).apply {
            putExtra("type", "get_public_key")
            putExtra("permissions", EXTERNAL_SIGNER_PERMISSIONS)
        }

    /** Handles Amber's reply to [externalSignerLoginIntent]. Returns false if sign-in failed. */
    fun completeExternalSignerLogin(data: Intent?, resultCode: Int = Activity.RESULT_OK): Boolean {
        val reply = SignerReply.from(resultCode == Activity.RESULT_OK, data)
        if (!reply.ok || reply.rejected) return false
        // Amber may answer with an npub or a hex key.
        val pubKeyHex = reply.result?.let(Nip19::publicKey) ?: return false
        val packageName = reply.packageName ?: return false
        accountStore.signInWithExternalSigner(pubKeyHex, packageName)
        return true
    }

    /** Signs in with a pasted nsec (or hex) key. Returns false if it isn't a valid key. */
    fun signInWithPrivateKey(input: String): Boolean {
        val privKey = Nip19.secretKey(input.trim()) ?: return false
        accountStore.signInWithLocalKey(privKey)
        return true
    }

    /** Creates a brand-new key and signs in with it. */
    fun createNewKey() {
        accountStore.signInWithLocalKey(Keys.generate())
    }

    /** The local key as an nsec for backing up, or null when using an external signer. */
    fun exportLocalNsec(): String? = accountStore.localPrivateKey()?.let(Nip19::nsec)

    fun signOut() {
        cached = null
        accountStore.signOut()
    }

    companion object {
        private val EXTERNAL_SIGNER_PERMISSIONS = buildString {
            append('[')
            listOf(
                // NIP-17: the user signs the seal; the outer gift wrap uses a throwaway key.
                Kind.SEAL,
                Kind.DM,
                Kind.FILE_DM,
                // Authorizing encrypted attachment uploads to the Blossom media server.
                Kind.BLOSSOM_AUTH,
                // Publishing the user's DM relay list (NIP-17 kind 10050).
                Kind.DM_RELAYS,
                // Zap requests (NIP-57), from the zap button in chats.
                Kind.ZAP_REQUEST,
                // Signing in to relays that ask for it (NIP-42).
                Kind.AUTH,
            ).forEach { append("""{"type":"sign_event","kind":$it},""") }
            append("""{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]""")
        }

        fun npubOf(pubKeyHex: String): String = Nip19.npub(pubKeyHex)

        fun shortNpub(pubKeyHex: String): String {
            val npub = npubOf(pubKeyHex)
            return npub.take(12) + "…" + npub.takeLast(6)
        }

        /** Hex form of a key typed as npub, nprofile or hex; null when it isn't one. */
        fun pubKeyHexOf(text: String): String? = Nip19.publicKey(text.trim())
    }
}
