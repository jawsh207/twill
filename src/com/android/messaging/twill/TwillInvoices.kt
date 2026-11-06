package com.android.messaging.twill

import android.content.Context
import androidx.compose.runtime.Immutable
import com.android.messaging.R
import java.util.Locale

/** What a Bitcoin mainnet BOLT-11 invoice asks for. The amount is null when the payer chooses. */
@Immutable
internal data class TwillInvoice(
    /** The invoice, lowercase, without a "lightning:" prefix. */
    val text: String,
    val msats: Long?,
    val memo: String?,
    /** Unix seconds. */
    val expiresAt: Long,
)

/**
 * Lightning invoices in message text, shown as cards in chats and summed up in one line in
 * notifications and previews, the way Struthio does (same parsing and wording).
 */
internal object TwillInvoices {
    private val INVOICE = Regex("""(?<![\p{L}\p{N}])(?i:(?:lightning:)?lnbc[a-z0-9]{50,})""")
    private val HRP = Regex("lnbc(\\d+[munp]?)?")
    private val AMOUNT = Regex("^lnbc(\\d+)?([munp])?1")
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private const val SIGNATURE_WORDS = 104
    private const val TIMESTAMP_WORDS = 7
    private const val CHECKSUM_LENGTH = 6
    private const val DEFAULT_EXPIRY = 3600L
    private const val TAG_DESCRIPTION = 13
    private const val TAG_EXPIRY = 6
    private const val MAX_EXPIRY_WORDS = 8

    /** Quick check before the (costlier) parsing. */
    fun mayContain(text: String): Boolean = text.contains("lnbc", ignoreCase = true)

    fun invoicesIn(text: String): List<TwillInvoice> = if (!mayContain(text)) {
        emptyList()
    } else {
        INVOICE.findAll(text).mapNotNull { parse(it.value) }.toList()
    }

    /** [text] with each invoice swapped for [with], leaving anything that only looks like one. */
    fun replace(text: String, with: (TwillInvoice) -> String): String = if (!mayContain(text)) {
        text
    } else {
        INVOICE.replace(text) { match -> parse(match.value)?.let(with) ?: match.value }
    }

    /** For one line: notifications, previews ("Lightning invoice for 100 sats"). */
    fun summarize(context: Context, text: String): String = replace(text) { invoice ->
        invoice.msats?.let { context.getString(R.string.twill_invoice_summary, formatSats(it)) }
            ?: context.getString(R.string.twill_invoice_title)
    }

    fun formatSats(msats: Long): String = if (msats % 1000 == 0L) {
        "%,d sats".format(Locale.US, msats / 1000)
    } else {
        "%,.3f sats".format(Locale.US, msats / 1000.0)
    }

    /** "2d", "3h", "5m". */
    fun timeLeft(seconds: Long): String = when {
        seconds >= 86_400 -> "${seconds / 86_400}d"
        seconds >= 3_600 -> "${seconds / 3_600}h"
        else -> "${maxOf(1, seconds / 60)}m"
    }

    /** Null unless [raw] is a whole mainnet invoice with a valid checksum. */
    fun parse(raw: String): TwillInvoice? {
        val text = raw.trim().lowercase().removePrefix("lightning:")
        val (hrp, words) = decodeWords(text) ?: return null
        if (!HRP.matches(hrp) || words.size < TIMESTAMP_WORDS + SIGNATURE_WORDS) return null
        val msats = if (hrp == "lnbc") null else amountMsats(text) ?: return null

        fun number(from: Int, count: Int): Long {
            var value = 0L
            for (i in from until from + count) value = value * 32 + words[i]
            return value
        }

        val created = number(0, TIMESTAMP_WORDS)
        val end = words.size - SIGNATURE_WORDS
        var memo: String? = null
        var expiry = DEFAULT_EXPIRY
        var at = TIMESTAMP_WORDS
        while (at + 3 <= end) {
            val tag = words[at]
            val length = number(at + 1, 2).toInt()
            val start = at + 3
            if (start + length > end) return null
            when (tag) {
                TAG_DESCRIPTION -> memo = String(wordsToBytes(words, start, length), Charsets.UTF_8)
                TAG_EXPIRY -> if (length <= MAX_EXPIRY_WORDS) expiry = number(start, length)
            }
            at = start + length
        }
        return TwillInvoice(text, msats, memo?.trim()?.takeIf { it.isNotEmpty() }, created + expiry)
    }

    /** The amount in millisatoshis, from the human-readable part. */
    private fun amountMsats(invoice: String): Long? {
        val match = AMOUNT.find(invoice) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        return when (match.groupValues[2]) {
            "" -> amount * 100_000_000_000L
            "m" -> amount * 100_000_000L
            "u" -> amount * 100_000L
            "n" -> amount * 100L
            "p" -> if (amount % 10 == 0L) amount / 10 else null
            else -> null
        }
    }

    /** Bech32 without the length limit (invoices are long): the 5-bit words, checksum checked. */
    private fun decodeWords(text: String): Pair<String, IntArray>? {
        val separator = text.lastIndexOf('1')
        if (separator < 1 || separator + CHECKSUM_LENGTH + 1 > text.length) return null
        val hrp = text.substring(0, separator)
        val values = IntArray(text.length - separator - 1) {
            CHARSET.indexOf(text[separator + 1 + it]).takeIf { value -> value >= 0 } ?: return null
        }
        val expanded = IntArray(hrp.length * 2 + 1).also { out ->
            hrp.forEachIndexed { i, c ->
                out[i] = c.code ushr 5
                out[hrp.length + 1 + i] = c.code and 31
            }
        }
        if (polymod(expanded + values) != 1) return null
        return hrp to values.copyOf(values.size - CHECKSUM_LENGTH)
    }

    private fun polymod(values: IntArray): Int {
        var check = 1
        for (value in values) {
            val top = check ushr 25
            check = ((check and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) check = check xor GEN[i]
        }
        return check
    }

    private fun wordsToBytes(words: IntArray, start: Int, length: Int): ByteArray {
        var accumulator = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (i in start until start + length) {
            accumulator = (accumulator shl 5) or words[i]
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((accumulator ushr bits) and 0xff)
            }
            accumulator = accumulator and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }
}
