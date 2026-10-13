package com.android.messaging.ui.nostr

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.nostr.NostrAccountStore
import com.android.messaging.nostr.NostrSignerProvider
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * When Amber hasn't pre-approved a request, it needs to show its own approval screen. This
 * lets it do that from whichever Nostr screen is open. Without it, such requests just fail
 * and are retried later.
 */
@Composable
internal fun NostrSignerForegroundHost(signer: NostrSigner?) {
    val external = signer as? NostrSignerExternal ?: return

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.let(external::newResponse)
    }

    DisposableEffect(external) {
        val launch: (Intent) -> Unit = { intent -> launcher.launch(intent) }
        external.registerForegroundLauncher(launch)
        onDispose { external.unregisterForegroundLauncher(launch) }
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
    NostrSignerForegroundHost(
        signer = remember(account) { entryPoint.nostrSignerProvider().signer() },
    )
}
