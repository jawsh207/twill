package com.android.messaging.nostr

import androidx.compose.runtime.Immutable

/** How the user is signed in to Nostr. Twill never sees the key when Amber is used. */
@Immutable
internal sealed interface NostrAccount {
    /** Hex public key. */
    val pubKeyHex: String

    /** Signed in through an external NIP-55 signer app such as Amber. */
    data class ExternalSigner(
        override val pubKeyHex: String,
        val signerPackage: String,
    ) : NostrAccount

    /** Private key stored on this device, encrypted with the Android Keystore. */
    data class LocalKey(
        override val pubKeyHex: String,
    ) : NostrAccount
}

/** How Twill receives Nostr messages when it isn't on screen. */
internal enum class NostrDeliveryMode(
    val key: String,
) {
    /** Only while Twill is open. No background network use. */
    WhileOpen("while_open"),

    /** A short check about every 15 minutes. */
    Periodic("periodic"),

    /** Stay connected, with a persistent notification. Messages arrive instantly. */
    AlwaysConnected("always"),
    ;

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: WhileOpen
    }
}

internal object NostrDefaults {
    /** Used for sending and receiving DMs until the user changes them. */
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
    )

    /** Package of the Amber signer app. */
    const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"
}
