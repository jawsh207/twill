package com.android.messaging.nostr

import android.content.Context
import android.util.Log
import com.android.messaging.R
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.nostr.core.Bech32
import com.android.messaging.nostr.core.Draft
import com.android.messaging.nostr.core.Filter
import com.android.messaging.nostr.core.Hex
import com.android.messaging.nostr.core.Kind
import com.android.messaging.nostr.core.LocalSigner
import com.android.messaging.nostr.core.Nip44
import com.android.messaging.nostr.core.nowSeconds
import com.android.messaging.nostr.net.Http
import com.android.messaging.twill.TwillActionError
import com.android.messaging.twill.TwillInvoices
import com.android.messaging.twill.TwillWallet
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the connected wallet told Twill about itself. */
internal data class NostrWalletInfo(
    val alias: String?,
    val balanceMsats: Long?,
)

/**
 * Nostr Wallet Connect (NIP-47), as in Struthio: the user pastes a connection link from their
 * wallet (Alby Hub, Primal, Coinos, …); Twill keeps it encrypted on the phone and asks the
 * wallet over its relay to pay invoices. Funds and keys stay with the wallet. Requests are
 * encrypted with NIP-44, as in Struthio; wallets that only know NIP-04 aren't supported.
 *
 * Also zaps (NIP-57): a signed zap request to the person's Lightning address, paid with the
 * wallet, or handed to a wallet app when none is connected.
 */
@Singleton
internal class NostrWallet @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountStore: NostrAccountStore,
    private val messenger: NostrMessenger,
    private val signerProvider: NostrSignerProvider,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) : TwillWallet {
    private class Connection(
        val walletPubKey: String,
        val relays: List<String>,
        val secret: ByteArray,
        /** What the wallet's info event offers; null when it published none. */
        val methods: Set<String>?,
    ) {
        fun offers(method: String) = methods == null || method in methods

        val signer = LocalSigner(secret)
        val conversationKey: ByteArray by lazy { Nip44.conversationKey(secret, walletPubKey) }
    }

    private var connection: Connection? = null
    private val _isConnected = MutableStateFlow(false)
    private val _info = MutableStateFlow<NostrWalletInfo?>(null)
    private val _paidInvoices = MutableStateFlow<Set<String>>(emptySet())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    override val paidInvoices: StateFlow<Set<String>> = _paidInvoices.asStateFlow()

    /** Balance and name, when the wallet shares them. */
    val info: StateFlow<NostrWalletInfo?> = _info.asStateFlow()

    init {
        _paidInvoices.value = prefs.getStringSet(KEY_PAID, emptySet()).orEmpty()
        // A saved connection is used straight away; the wallet is asked about itself later.
        _isConnected.value = accountStore.hasWallet.value
        appScope.launch(Dispatchers.IO) {
            accountStore.hasWallet.collect { has ->
                if (!has) {
                    connection = null
                    _info.value = null
                }
                _isConnected.value = has
            }
        }
    }

    /**
     * Checks [link] with the wallet (it must answer a request), then saves it. Throws
     * [TwillActionError] if it's no good.
     */
    suspend fun connect(link: String) = withContext(Dispatchers.IO) {
        val opened = open(link.trim())
        probe(opened)
        connection = opened
        accountStore.setWalletLink(link.trim())
        refresh()
    }

    /**
     * Asks the wallet for its info, so a wrong or offline wallet shows up now rather than at the
     * first payment. Any answer, even an error, will do.
     */
    private suspend fun probe(opened: Connection) {
        try {
            call("get_info", connection = opened, timeoutMillis = PROBE_TIMEOUT_MILLIS)
        } catch (error: TwillActionError) {
            if (error.messageRes == R.string.nostr_wallet_no_response) {
                throw TwillActionError(R.string.nostr_wallet_no_answer)
            }
        }
    }

    fun disconnect() {
        connection = null
        accountStore.setWalletLink(null)
        _info.value = null
    }

    /** Asks the wallet for its name and balance (when it allows that). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val current = runCatching { requireConnection() }.getOrNull() ?: return@withContext
        val alias = if (current.offers("get_info")) {
            runCatching { call("get_info").optString("alias").takeIf { it.isNotBlank() } }
                .getOrNull()
        } else {
            null
        }
        val balance = if (current.offers("get_balance")) {
            runCatching { call("get_balance").optLong("balance") }.getOrNull()
        } else {
            null
        }
        _info.value = NostrWalletInfo(alias = alias, balanceMsats = balance)
    }

    override suspend fun pay(invoice: String) = withContext(Dispatchers.IO) {
        val text = invoice.trim().lowercase().removePrefix("lightning:")
        call("pay_invoice", JSONObject().put("invoice", text), PAY_TIMEOUT_MILLIS)
        markPaid(text)
        runCatching { refresh() }
        Unit
    }

    /**
     * Zaps [pubKey] [sats] with [comment]. Returns null once paid from the connected wallet,
     * or the invoice to hand to a wallet app when none is connected.
     */
    suspend fun zap(pubKey: String, name: String, sats: Long, comment: String): String? =
        withContext(Dispatchers.IO) {
            val msats = sats * MSATS_PER_SAT
            val profile = profileOf(pubKey)
                ?: throw TwillActionError(R.string.nostr_zap_no_profile, name)
            val endpoint = lnurlEndpoint(profile)
                ?: throw TwillActionError(R.string.nostr_zap_no_address, name)
            val pay = getJson(endpoint)
            // NIP-57: the address must accept zap requests and name the key it signs receipts with.
            if (!pay.optBoolean("allowsNostr") || !Hex.isKey(pay.optString("nostrPubkey"))) {
                throw TwillActionError(R.string.nostr_zap_not_supported, name)
            }
            val callback = pay.optString("callback").takeIf { it.startsWith("https://") }
                ?: throw TwillActionError(R.string.nostr_zap_no_invoice)
            val min = pay.optLong("minSendable")
            val max = pay.optLong("maxSendable")
            if (msats < min || (max > 0 && msats > max)) {
                throw TwillActionError(R.string.nostr_zap_out_of_range)
            }

            val signer = signerProvider.signer()
                ?: throw TwillActionError(R.string.nostr_wallet_signed_out)
            val lnurl = Bech32.encode("lnurl", endpoint.toByteArray())
            val receiptRelays = accountStore.relays.value.take(MAX_RECEIPT_RELAYS)
            val tags = listOf(
                listOf("relays") + receiptRelays,
                listOf("amount", msats.toString()),
                listOf("lnurl", lnurl),
                listOf("p", pubKey),
            )
            val request = try {
                signer.sign(Draft(signer.pubkey, nowSeconds(), Kind.ZAP_REQUEST, tags, comment))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw TwillActionError(R.string.nostr_zap_sign_failed)
            }
            val separator = if ('?' in callback) '&' else '?'
            val url = callback + separator + "amount=$msats" +
                "&nostr=" + URLEncoder.encode(request.toJson(), "UTF-8") +
                "&lnurl=" + lnurl
            val answer = getJson(url)
            val invoice = answer.optString("pr").takeIf { it.isNotBlank() }
                ?: answer.optString("reason").takeIf { it.isNotBlank() }
                    ?.let { throw TwillActionError(R.string.nostr_zap_refused, it) }
                ?: throw TwillActionError(R.string.nostr_zap_no_invoice)
            if (TwillInvoices.parse(invoice)?.msats != msats) {
                throw TwillActionError(R.string.nostr_zap_amount_mismatch)
            }
            if (!_isConnected.value) return@withContext invoice
            pay(invoice)
            null
        }

    private fun markPaid(invoice: String) {
        val updated = (_paidInvoices.value + invoice).toList().takeLast(MAX_PAID).toSet()
        prefs.edit().putStringSet(KEY_PAID, updated).apply()
        _paidInvoices.value = updated
    }

    // Nostr Wallet Connect

    private suspend fun requireConnection(): Connection {
        connection?.let { return it }
        val link = accountStore.walletLink()
            ?: throw TwillActionError(R.string.nostr_wallet_not_connected)
        return open(link).also { connection = it }
    }

    /** Parses [link] and looks for what the wallet service offers (its kind 13194 info). */
    private suspend fun open(link: String): Connection {
        val uri = parseLink(link) ?: throw TwillActionError(R.string.nostr_wallet_bad_link)
        val relays = messenger.normalizedRelays(uri.relays)
        if (relays.isEmpty()) throw TwillActionError(R.string.nostr_wallet_no_relay)
        val info = messenger.fetchAll(
            relays,
            Filter(kinds = listOf(Kind.NWC_INFO), authors = listOf(uri.wallet), limit = 1),
        )
            .filter { it.kind == Kind.NWC_INFO && it.pubkey == uri.wallet }
            .maxByOrNull { it.createdAt }
        Log.i(TAG, "open: ${relays.size} relays, info=${info != null}")
        val encryptions = info?.tags?.firstOrNull { it.size > 1 && it[0] == "encryption" }
            ?.get(1)
            ?.split(' ')
        // An info event without nip44_v2 is an older wallet that only speaks NIP-04.
        if (info != null && (encryptions == null || NIP44 !in encryptions)) {
            throw TwillActionError(R.string.nostr_wallet_nip04_only)
        }
        return Connection(
            walletPubKey = uri.wallet,
            relays = relays,
            secret = uri.secret,
            methods = info?.content?.split(' ')?.filter { it.isNotBlank() }?.toSet(),
        )
    }

    private suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = CALL_TIMEOUT_MILLIS,
        connection: Connection? = null,
    ): JSONObject {
        val current = connection ?: requireConnection()
        val signer = current.signer
        val body = JSONObject().put("method", method).put("params", params).toString()
        val content = Nip44.encrypt(body, current.conversationKey)
        val tags = listOf(
            listOf("p", current.walletPubKey),
            listOf("encryption", NIP44),
            listOf("expiration", (nowSeconds() + REQUEST_EXPIRY_SECONDS).toString()),
        )
        val request = signer.signNow(
            Draft(signer.pubkey, nowSeconds(), Kind.NWC_REQUEST, tags, content),
        )
        Log.i(TAG, "call $method: request ${request.id.take(8)}")
        val response = messenger.request(
            relays = current.relays,
            request = request,
            response = Filter(
                kinds = listOf(Kind.NWC_RESPONSE),
                authors = listOf(current.walletPubKey),
                tags = mapOf("e" to listOf(request.id)),
            ),
            timeoutMillis = timeoutMillis,
        ) ?: run {
            Log.i(TAG, "call $method: no response")
            throw TwillActionError(R.string.nostr_wallet_no_response)
        }
        Log.i(TAG, "call $method: response ${response.id.take(8)}")
        if (response.pubkey != current.walletPubKey) {
            throw TwillActionError(R.string.nostr_wallet_no_response)
        }
        val plain = runCatching { Nip44.decrypt(response.content, current.conversationKey) }
            .getOrNull() ?: throw TwillActionError(R.string.nostr_wallet_no_response)
        val json = JSONObject(plain)
        json.optJSONObject("error")?.let { error ->
            val message = error.optString("message").ifBlank { error.optString("code") }
            throw TwillActionError(R.string.nostr_wallet_error, message)
        }
        return json.optJSONObject("result") ?: JSONObject()
    }

    private class Link(
        val wallet: String,
        val relays: List<String>,
        val secret: ByteArray,
    )

    /** `nostr+walletconnect://<wallet key>?relay=…&secret=…` */
    private fun parseLink(text: String): Link? = runCatching {
        val normalized = text.replace("nostr+walletconnect:", "nwc:").replace("nostrnwc:", "nwc:")
        val uri = URI(normalized)
        require(uri.scheme == "nwc")
        val wallet = (uri.host ?: uri.rawAuthority ?: uri.rawSchemeSpecificPart.removePrefix("//"))
            .substringBefore('?')
            .lowercase()
        val query = uri.rawQuery ?: uri.rawSchemeSpecificPart.substringAfter('?', "")
        val params = query.split('&').filter { '=' in it }.map {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val relays = params.filter { it.first == "relay" }.map { it.second }
        val secret = params.first { it.first == "secret" }.second.lowercase()
        require(Hex.isKey(wallet) && Hex.isKey(secret) && relays.isNotEmpty())
        Link(wallet, relays, Hex.decode(secret))
    }.getOrNull()

    // Zaps

    private class Profile(
        val lud16: String?,
        val lud06: String?,
    )

    /** The newest kind 0 of [pubKey], from the message and search relays. */
    private suspend fun profileOf(pubKey: String): Profile? {
        val relays = messenger.normalizedRelays(
            accountStore.relays.value + accountStore.searchRelayUrls.value,
        )
        val newest = messenger.fetchAll(
            relays,
            Filter(kinds = listOf(Kind.METADATA), authors = listOf(pubKey), limit = 1),
        )
            .filter { it.kind == Kind.METADATA && it.pubkey == pubKey }
            .maxByOrNull { it.createdAt }
            ?: return null
        val content = runCatching { JSONObject(newest.content) }.getOrNull() ?: return null
        return Profile(
            lud16 = content.optString("lud16").trim().takeIf { '@' in it },
            lud06 = content.optString("lud06").trim().takeIf { it.isNotEmpty() },
        )
    }

    private fun lnurlEndpoint(profile: Profile): String? {
        profile.lud16?.let { address ->
            val at = address.lastIndexOf('@')
            val user = address.substring(0, at)
            val domain = address.substring(at + 1)
            if (user.isNotBlank() && '.' in domain && '/' !in domain) {
                return "https://$domain/.well-known/lnurlp/$user"
            }
        }
        return profile.lud06?.let(::lnurlText)?.takeIf { it.startsWith("https://") }
    }

    /** An LNURL (lud06) back to its URL. */
    private fun lnurlText(input: String): String? = runCatching {
        String(Bech32.decode(input.trim().removePrefix("lightning:")).second, Charsets.UTF_8)
    }.getOrNull()

    private fun getJson(url: String): JSONObject = try {
        Http.json(url)
    } catch (exception: Exception) {
        val host = runCatching { URI(url).host }.getOrNull() ?: url
        throw TwillActionError(R.string.nostr_zap_unreachable, host)
    }

    private companion object {
        const val TAG = "TwillWallet"
        const val PREFS_NAME = "nostr_wallet"
        const val KEY_PAID = "paid_invoices"
        const val MAX_PAID = 200
        const val NIP44 = "nip44_v2"
        const val CALL_TIMEOUT_MILLIS = 30_000L
        const val PROBE_TIMEOUT_MILLIS = 15_000L
        const val PAY_TIMEOUT_MILLIS = 60_000L
        const val REQUEST_EXPIRY_SECONDS = 60L
        const val MAX_RECEIPT_RELAYS = 6
        const val MSATS_PER_SAT = 1000L
    }
}
