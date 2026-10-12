package com.android.messaging.ui.nostr

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal

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
