package com.android.messaging.nostr

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the local Nostr private key, the wallet connection) with AES keys
 * that live in the Android Keystore and never leave it. The ciphertext is safe to keep in app
 * storage. Each secret has its own key ([alias]), so deleting one doesn't lose the other.
 */
internal object KeystoreCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    const val LOCAL_KEY_ALIAS = "twill_nostr_local_key"
    const val WALLET_ALIAS = "twill_nostr_wallet"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val SEPARATOR = ":"

    fun encrypt(plaintext: ByteArray, alias: String = LOCAL_KEY_ALIAS): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        val ciphertext = cipher.doFinal(plaintext)
        return encode(cipher.iv) + SEPARATOR + encode(ciphertext)
    }

    fun decrypt(stored: String, alias: String = LOCAL_KEY_ALIAS): ByteArray {
        val (iv, ciphertext) = stored.split(SEPARATOR, limit = 2).let { parts ->
            require(parts.size == 2) { "Malformed ciphertext" }
            decode(parts[0]) to decode(parts[1])
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun deleteKey(alias: String = LOCAL_KEY_ALIAS) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
