package com.android.messaging.nostr.core

import java.io.ByteArrayOutputStream

/** Bech32 (BIP-173), without the 90 character limit, which NIP-19 entities exceed. */
internal object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray = IntArray(hrp.length * 2 + 1).also { out ->
        hrp.forEachIndexed { i, c ->
            out[i] = c.code ushr 5
            out[hrp.length + 1 + i] = c.code and 31
        }
    }

    private fun convert(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ByteArrayOutputStream()
        val max = (1 shl to) - 1
        for (b in data) {
            acc = (acc shl from) or (b.toInt() and 0xff)
            bits += from
            while (bits >= to) {
                bits -= to
                out.write((acc ushr bits) and max)
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (pad) {
            if (bits > 0) out.write((acc shl (to - bits)) and max)
        } else {
            require(bits < from && (acc shl (to - bits)) and max == 0) { "invalid padding" }
        }
        return out.toByteArray()
    }

    fun encode(hrp: String, data: ByteArray): String {
        val five = convert(data, 8, 5, true).map { it.toInt() }.toIntArray()
        val values = hrpExpand(hrp) + five + IntArray(6)
        val mod = polymod(values) xor 1
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return buildString {
            append(hrp).append('1')
            (five + checksum).forEach { append(CHARSET[it]) }
        }
    }

    /** The checked 5-bit words, for formats that aren't bytes underneath, such as BOLT-11. */
    fun decodeWords(input: String): Pair<String, ByteArray> {
        val text = input.lowercase()
        require(input == text || input == input.uppercase()) { "mixed case" }
        val sep = text.lastIndexOf('1')
        require(sep >= 1 && sep + 7 <= text.length) { "invalid separator" }
        val hrp = text.substring(0, sep)
        val values = IntArray(text.length - sep - 1) {
            CHARSET.indexOf(text[sep + 1 + it]).also { v ->
                require(v >= 0) { "invalid character" }
            }
        }
        require(polymod(hrpExpand(hrp) + values) == 1) { "invalid checksum" }
        return hrp to ByteArray(values.size - 6) { values[it].toByte() }
    }

    fun decode(input: String): Pair<String, ByteArray> {
        val (hrp, words) = decodeWords(input)
        return hrp to convert(words, 5, 8, false)
    }
}

/** NIP-19 keys: npub, nsec and nprofile. */
internal object Nip19 {
    fun npub(hex: String) = Bech32.encode("npub", Hex.decode(hex))

    fun nsec(key: ByteArray) = Bech32.encode("nsec", key)

    /** The hex public key in an npub, an nprofile or a hex key; null if it's none of those. */
    fun publicKey(text: String): String? {
        val input = text.trim().removePrefix("nostr:")
        if (Hex.isKey(input.lowercase())) return input.lowercase()
        return runCatching {
            val (hrp, data) = Bech32.decode(input)
            when (hrp) {
                "npub" -> data.takeIf { it.size == 32 }?.let(Hex::encode)
                "nprofile" -> tlvSpecial(data)?.takeIf { it.size == 32 }?.let(Hex::encode)
                else -> null
            }
        }.getOrNull()
    }

    /** The private key in an nsec or a hex key; null if it's neither. */
    fun secretKey(text: String): ByteArray? {
        val input = text.trim()
        val key = if (Hex.isKey(input.lowercase())) {
            Hex.decode(input.lowercase())
        } else {
            runCatching { Bech32.decode(input) }.getOrNull()
                ?.takeIf { it.first == "nsec" }
                ?.second
        }
        return key?.takeIf { Keys.isValidSecret(it) }
    }

    private fun tlvSpecial(data: ByteArray): ByteArray? {
        var i = 0
        while (i + 2 <= data.size) {
            val type = data[i].toInt() and 0xff
            val length = data[i + 1].toInt() and 0xff
            if (i + 2 + length > data.size) return null
            if (type == 0) return data.copyOfRange(i + 2, i + 2 + length)
            i += 2 + length
        }
        return null
    }
}
