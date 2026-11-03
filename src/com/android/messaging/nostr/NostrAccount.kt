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

internal object NostrDefaults {
    /** Used for sending and receiving DMs until the user changes them. */
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
    )

    /**
     * Where follow lists and profiles are usually found (besides the user's own relays and
     * NIP-65 outbox relays): an index relay for lists and profiles, and big public relays.
     */
    val DIRECTORY_RELAYS = listOf(
        "wss://purplepag.es",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
    )

    /** Relays that answer NIP-50 search for profiles, used when relay search is on. */
    val SEARCH_RELAYS = listOf(
        "wss://relay.nostr.band",
        "wss://search.nos.today",
        "wss://relay.noswhere.com",
    )

    /** Package of the Amber signer app. */
    const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"

    /** Blossom server attachments are uploaded to (encrypted) until the user picks another. */
    const val DEFAULT_MEDIA_SERVER = "https://blossom.primal.net"
}
