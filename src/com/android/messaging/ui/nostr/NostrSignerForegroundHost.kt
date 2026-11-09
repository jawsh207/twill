package com.android.messaging.ui.nostr

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.nostr.NostrAccount
import com.android.messaging.nostr.NostrAccountStore
import com.android.messaging.nostr.NostrSignerProvider
import com.android.messaging.nostr.SignerBridge
import com.android.messaging.nostr.SignerReply
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred

/**
 * When Amber hasn't pre-approved a request, it needs to show its own approval screen. This
 * lets it do that from whichever Nostr screen is open. Without it, such requests just fail
 * and are retried later.
 */
@Composable
internal fun NostrSignerForegroundHost() {
    val pending = remember { arrayOfNulls<CompletableDeferred<SignerReply>>(1) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        pending[0]?.complete(
            SignerReply.from(result.resultCode == Activity.RESULT_OK, result.data),
        )
        pending[0] = null
    }

    DisposableEffect(launcher) {
        val launch: (Intent, CompletableDeferred<SignerReply>) -> Unit = { intent, reply ->
            pending[0]?.completeExceptionally(IllegalStateException("superseded"))
            pending[0] = reply
            launcher.launch(intent)
        }
        SignerBridge.register(launch)
        onDispose {
            SignerBridge.unregister(launch)
            pending[0]?.completeExceptionally(IllegalStateException("screen closed"))
            pending[0] = null
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface NostrUiEntryPoint {
    fun nostrSignerProvider(): NostrSignerProvider

    fun nostrAccountStore(): NostrAccountStore
}

/** [NostrSignerForegroundHost] for the current account; a no-op when signed out or local key. */
@Composable
internal fun NostrForegroundHost() {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            NostrUiEntryPoint::class.java
        )
    }
    val account by entryPoint.nostrAccountStore().account.collectAsStateWithLifecycle()
    if (account is NostrAccount.ExternalSigner) NostrSignerForegroundHost()
}
