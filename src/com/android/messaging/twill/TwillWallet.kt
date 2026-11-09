package com.android.messaging.twill

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.StateFlow

/**
 * A Lightning wallet Twill can pay with (Nostr Wallet Connect, set up in Nostr settings).
 * Any chat's invoice cards use it, not only Nostr chats.
 */
internal interface TwillWallet {
    val isConnected: StateFlow<Boolean>

    /** Invoices paid from Twill (lowercase, without "lightning:"). */
    val paidInvoices: StateFlow<Set<String>>

    /** Pays [invoice]; throws [TwillActionError] when it can't. */
    suspend fun pay(invoice: String)
}

/** A failure to show the user: [messageRes], formatted with [args]. */
internal class TwillActionError(
    @StringRes val messageRes: Int,
    vararg val args: String,
) : Exception()
