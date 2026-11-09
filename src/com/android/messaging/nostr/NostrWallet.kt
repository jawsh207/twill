package com.android.messaging.nostr

import android.content.Context
import com.android.messaging.R
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.twill.TwillActionError
import com.android.messaging.twill.TwillInvoices
import com.android.messaging.twill.TwillWallet
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** What the connected wallet told Twill about itself. */
internal data class NostrWalletInfo(
    val alias: String?,
    val balanceMsats: Long?,
)

/**
 * Nostr Wallet Connect (NIP-47), as in Struthio: the user pastes a connection link from their
 * wallet (Alby Hub, Primal, Coinos, …); Twill keeps it encrypted on the phone and asks the
 * wallet over its relay to pay invoices. Funds and keys stay with the wallet. NIP-44 is used
 * when the wallet offers it, else NIP-04 (older wallets).
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
        secret: ByteArray,
        val methods: Set<String>,
        val useNip44: Boolean,
    ) {
        val signer = NostrSignerInternal(KeyPair(privKey = secret))
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

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

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

    /** Checks [link] with the wallet, then saves it. Throws [TwillActionError] if it's no good. */
    suspend fun connect(link: String) = withContext(Dispatchers.IO) {
        connection = open(link.trim())
        accountStore.setWalletLink(link.trim())
        refresh()
    }

    fun disconnect() {
        connection = null
        accountStore.setWalletLink(null)
        _info.value = null
    }

    /** Asks the wallet for its name and balance (when it allows that). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val current = runCatching { requireConnection() }.getOrNull() ?: return@withContext
        val alias = if ("get_info" in current.methods) {
            runCatching { call("get_info").optString("alias").takeIf { it.isNotBlank() } }
                .getOrNull()
        } else {
            null
        }
        val balance = if ("get_balance" in current.methods) {
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
            if (!pay.optBoolean("allowsNostr") || !pay.optString("nostrPubkey").isHexKey()) {
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
            val lnurl = bech32Encode("lnurl", endpoint.toByteArray())
            val receiptRelays = accountStore.relays.value.take(MAX_RECEIPT_RELAYS)
            val tags = arrayOf(
                arrayOf("relays", *receiptRelays.toTypedArray()),
                arrayOf("amount", msats.toString()),
                arrayOf("lnurl", lnurl),
                arrayOf("p", pubKey),
            )
            val request = try {
                signer.sign<Event>(nowSeconds(), KIND_ZAP_REQUEST, tags, comment)
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

    /** Parses [link] and asks the wallet service what it offers (its kind 13194 info). */
    private suspend fun open(link: String): Connection {
        val uri = parseLink(link) ?: throw TwillActionError(R.string.nostr_wallet_bad_link)
        val relays = messenger.normalizedRelays(uri.relays)
        if (relays.isEmpty()) throw TwillActionError(R.string.nostr_wallet_no_relay)
        val info = messenger.fetchAll(
            relays,
            Filter(kinds = listOf(KIND_INFO), authors = listOf(uri.wallet), limit = 1),
        )
            .filter { it.pubKey == uri.wallet && it.verify() }
            .maxByOrNull { it.createdAt }
            ?: throw TwillActionError(R.string.nostr_wallet_no_answer)
        val encryptions = info.tags.firstOrNull { it.size > 1 && it[0] == "encryption" }
            ?.get(1)
            ?.split(' ')
            .orEmpty()
        return Connection(
            walletPubKey = uri.wallet,
            relays = uri.relays,
            secret = uri.secret,
            methods = info.content.split(' ').filter { it.isNotBlank() }.toSet(),
            // No encryption tag: an older wallet, which only knows NIP-04.
            useNip44 = NIP44 in encryptions,
        )
    }

    private suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = CALL_TIMEOUT_MILLIS,
    ): JSONObject {
        val current = requireConnection()
        val signer = current.signer
        val body = JSONObject().put("method", method).put("params", params).toString()
        val content = if (current.useNip44) {
            signer.nip44Encrypt(body, current.walletPubKey)
        } else {
            signer.nip04Encrypt(body, current.walletPubKey)
        }
        val tags = buildList {
            add(arrayOf("p", current.walletPubKey))
            if (current.useNip44) add(arrayOf("encryption", NIP44))
            add(arrayOf("expiration", (nowSeconds() + REQUEST_EXPIRY_SECONDS).toString()))
        }.toTypedArray()
        val request = signer.sign<Event>(nowSeconds(), KIND_REQUEST, tags, content)
        val response = messenger.request(
            relays = messenger.normalizedRelays(current.relays),
            request = request,
            response = Filter(
                kinds = listOf(KIND_RESPONSE),
                authors = listOf(current.walletPubKey),
                tags = mapOf("e" to listOf(request.id)),
            ),
            timeoutMillis = timeoutMillis,
        ) ?: throw TwillActionError(R.string.nostr_wallet_no_response)
        if (!response.verify()) throw TwillActionError(R.string.nostr_wallet_no_response)
        val plain = runCatching {
            if (current.useNip44) {
                signer.nip44Decrypt(response.content, current.walletPubKey)
            } else {
                signer.nip04Decrypt(response.content, current.walletPubKey)
            }
        }.getOrNull() ?: throw TwillActionError(R.string.nostr_wallet_no_response)
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
        require(wallet.isHexKey() && secret.isHexKey() && relays.isNotEmpty())
        Link(wallet, relays, secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
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
            Filter(kinds = listOf(KIND_METADATA), authors = listOf(pubKey), limit = 1),
        )
            .filter { it.pubKey == pubKey && it.verify() }
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
        return profile.lud06?.let(::bech32DecodeText)?.takeIf { it.startsWith("https://") }
    }

    private fun getJson(url: String): JSONObject = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body.byteStream().use { String(it.readNBytes(MAX_JSON_BYTES)) }
            JSONObject(body)
        }
    } catch (exception: Exception) {
        val host = runCatching { URI(url).host }.getOrNull() ?: url
        throw TwillActionError(R.string.nostr_zap_unreachable, host)
    }

    private companion object {
        const val PREFS_NAME = "nostr_wallet"
        const val KEY_PAID = "paid_invoices"
        const val MAX_PAID = 200
        const val KIND_METADATA = 0
        const val KIND_ZAP_REQUEST = 9734
        const val KIND_INFO = 13194
        const val KIND_REQUEST = 23194
        const val KIND_RESPONSE = 23195
        const val NIP44 = "nip44_v2"
        const val CALL_TIMEOUT_MILLIS = 30_000L
        const val PAY_TIMEOUT_MILLIS = 60_000L
        const val REQUEST_EXPIRY_SECONDS = 60L
        const val HTTP_TIMEOUT_SECONDS = 15L
        const val MAX_JSON_BYTES = 256 * 1024
        const val MAX_RECEIPT_RELAYS = 6
        const val MSATS_PER_SAT = 1000L
        const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

        fun nowSeconds() = System.currentTimeMillis() / 1000

        fun String.isHexKey() = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

        fun polymod(values: IntArray): Int {
            var check = 1
            for (value in values) {
                val top = check ushr 25
                check = ((check and 0x1ffffff) shl 5) xor value
                for (i in 0 until 5) if ((top ushr i) and 1 == 1) check = check xor GEN[i]
            }
            return check
        }

        fun hrpExpand(hrp: String) = IntArray(hrp.length * 2 + 1).also { out ->
            hrp.forEachIndexed { i, c ->
                out[i] = c.code ushr 5
                out[hrp.length + 1 + i] = c.code and 31
            }
        }

        fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
            var accumulator = 0
            var bits = 0
            val out = ByteArrayOutputStream()
            val max = (1 shl to) - 1
            for (byte in data) {
                accumulator = (accumulator shl from) or (byte.toInt() and 0xff)
                bits += from
                while (bits >= to) {
                    bits -= to
                    out.write((accumulator ushr bits) and max)
                }
                accumulator = accumulator and ((1 shl bits) - 1)
            }
            if (pad && bits > 0) out.write((accumulator shl (to - bits)) and max)
            return out.toByteArray()
        }

        /** Bech32 with no length limit (LNURLs are long). */
        fun bech32Encode(hrp: String, data: ByteArray): String {
            val words = convertBits(data, 8, 5, true).map { it.toInt() }.toIntArray()
            val mod = polymod(hrpExpand(hrp) + words + IntArray(6)) xor 1
            val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
            return buildString {
                append(hrp).append('1')
                (words + checksum).forEach { append(CHARSET[it]) }
            }
        }

        /** An LNURL (lud06) back to its URL. */
        fun bech32DecodeText(input: String): String? = runCatching {
            val text = input.lowercase().removePrefix("lightning:")
            val separator = text.lastIndexOf('1')
            val hrp = text.substring(0, separator)
            val values = IntArray(text.length - separator - 1) {
                CHARSET.indexOf(text[separator + 1 + it]).also { value -> require(value >= 0) }
            }
            require(polymod(hrpExpand(hrp) + values) == 1)
            val words = values.copyOf(values.size - 6).map { it.toByte() }.toByteArray()
            String(convertBits(words, 5, 8, false), Charsets.UTF_8)
        }.getOrNull()
    }
}
