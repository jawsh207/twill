package com.android.messaging.nostr

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.messaging.nostr.core.Draft
import com.android.messaging.nostr.core.Event
import com.android.messaging.nostr.core.Signer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reply from the signer app after an activity round trip. */
internal class SignerReply(
    val ok: Boolean,
    val rejected: Boolean,
    val result: String?,
    val event: String?,
    val packageName: String?,
) {
    companion object {
        fun from(ok: Boolean, data: Intent?) = SignerReply(
            ok = ok,
            rejected = data?.getBooleanExtra("rejected", false) == true,
            result = data?.getStringExtra("result") ?: data?.getStringExtra("signature"),
            event = data?.getStringExtra("event"),
            packageName = data?.getStringExtra("package"),
        )
    }
}

/**
 * Open Nostr screens plug in here so NIP-55 approval screens can be shown from anywhere.
 * The most recently opened screen wins.
 */
internal object SignerBridge {
    private val launchers = ArrayList<(Intent, CompletableDeferred<SignerReply>) -> Unit>()
    private val mutex = Mutex()

    fun register(launcher: (Intent, CompletableDeferred<SignerReply>) -> Unit) {
        synchronized(launchers) { launchers.add(launcher) }
    }

    fun unregister(launcher: (Intent, CompletableDeferred<SignerReply>) -> Unit) {
        synchronized(launchers) { launchers.remove(launcher) }
    }

    val canAsk: Boolean get() = synchronized(launchers) { launchers.isNotEmpty() }

    suspend fun request(intent: Intent): SignerReply = mutex.withLock {
        val launcher = synchronized(launchers) { launchers.lastOrNull() }
            ?: error("open Twill to approve this in the signer app")
        val reply = CompletableDeferred<SignerReply>()
        withContext(Dispatchers.Main) {
            try {
                launcher(intent, reply)
            } catch (e: Exception) {
                reply.completeExceptionally(e)
            }
        }
        reply.await()
    }
}

/** NIP-55: the key stays in the signer app. Remembered permissions answer silently. */
internal class NostrExternalSigner(
    private val context: Context,
    override val pubkey: String,
    private val packageName: String,
) : Signer {

    private fun resolve(type: String, payload: String, peer: String): String? = try {
        context.contentResolver.query(
            Uri.parse("content://$packageName.$type"),
            arrayOf(payload, peer, pubkey),
            null,
            null,
            null,
        )?.use { c ->
            if (c.getColumnIndex("rejected") > -1) throw SecurityException(REJECTED)
            if (c.moveToFirst()) {
                val result = c.getColumnIndex("result").takeIf { it >= 0 }?.let(c::getString)
                val signature = c.getColumnIndex("signature").takeIf { it >= 0 }
                    ?.let(c::getString)
                result ?: signature
            } else {
                null
            }
        }
    } catch (e: SecurityException) {
        if (e.message == REJECTED) throw e
        null
    } catch (_: Exception) {
        null
    }

    private suspend fun ask(method: String, payload: String, peer: String?): SignerReply {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:" + Uri.encode(payload)))
            .apply {
                `package` = packageName
                putExtra("type", method)
                putExtra("current_user", pubkey)
                peer?.let { putExtra("pubkey", it) }
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        val reply = SignerBridge.request(intent)
        if (reply.rejected) error("request rejected in the signer app")
        if (!reply.ok) error("the signer app failed")
        return reply
    }

    override suspend fun sign(draft: Draft, background: Boolean): Event =
        withContext(Dispatchers.IO) {
            val json = draft.toJson()
            val signature = resolve("SIGN_EVENT", json, "")
                ?: if (background || !SignerBridge.canAsk) {
                    error("allow this in the signer app")
                } else {
                    ask("sign_event", json, null).result
                }
            val event = Event(
                draft.id,
                draft.pubkey,
                draft.createdAt,
                draft.kind,
                draft.tags,
                draft.content,
                signature ?: error("no signature"),
            )
            check(event.isValid()) { "the signer returned an invalid signature" }
            event
        }

    override suspend fun nip44Encrypt(plaintext: String, peer: String): String =
        withContext(Dispatchers.IO) {
            resolve("NIP44_ENCRYPT", plaintext, peer)
                ?: ask("nip44_encrypt", plaintext, peer).result
                ?: error("no ciphertext")
        }

    override suspend fun nip44Decrypt(payload: String, peer: String, background: Boolean): String =
        withContext(Dispatchers.IO) {
            resolve("NIP44_DECRYPT", payload, peer)
                ?: if (background || !SignerBridge.canAsk) {
                    error("allow decryption in the signer app")
                } else {
                    ask("nip44_decrypt", payload, peer).result ?: error("no plaintext")
                }
        }

    private companion object {
        const val REJECTED = "rejected"
    }
}
