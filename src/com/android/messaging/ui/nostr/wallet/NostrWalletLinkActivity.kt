package com.android.messaging.ui.nostr.wallet

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.nostr.NostrAccountStore
import com.android.messaging.nostr.NostrWallet
import com.android.messaging.twill.TwillActionError
import com.android.messaging.twill.TwillRuntime
import com.android.messaging.ui.core.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Opens `nostr+walletconnect://` links, as wallets (Alby, Primal, …) hand them to apps, so the
 * user needn't copy and paste: asks first, then connects as Nostr settings would. Any app can
 * send such a link, hence the question; nothing is saved without it.
 */
@AndroidEntryPoint
internal class NostrWalletLinkActivity : ComponentActivity() {
    @Inject lateinit var wallet: NostrWallet

    @Inject lateinit var accountStore: NostrAccountStore

    @Inject lateinit var runtime: TwillRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.dataString
        if (link == null) {
            finish()
            return
        }
        if (accountStore.account.value == null) {
            Toast.makeText(this, R.string.nostr_wallet_signed_out, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        runtime.start(markVisible = true)

        setContent {
            AppTheme {
                val scope = rememberCoroutineScope()
                var isConnecting by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                AlertDialog(
                    onDismissRequest = { if (!isConnecting) finish() },
                    title = { Text(text = stringResource(R.string.nostr_wallet_link_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(text = stringResource(R.string.nostr_wallet_link_text))
                            if (accountStore.hasWallet.value) {
                                Text(text = stringResource(R.string.nostr_wallet_link_replaces))
                            }
                            error?.let {
                                Text(text = it, color = MaterialTheme.colorScheme.error)
                            }
                            if (isConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !isConnecting,
                            onClick = {
                                isConnecting = true
                                error = null
                                scope.launch {
                                    try {
                                        wallet.connect(link)
                                        Toast.makeText(
                                            this@NostrWalletLinkActivity,
                                            R.string.nostr_wallet_link_done,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        finish()
                                    } catch (failure: TwillActionError) {
                                        error = getString(failure.messageRes, *failure.args)
                                    } catch (failure: CancellationException) {
                                        Log.w(TAG, "Connecting cancelled", failure)
                                        throw failure
                                    } catch (failure: Exception) {
                                        // Never leave the dialog hanging: say what went wrong.
                                        Log.w(TAG, "Connecting failed", failure)
                                        error = getString(
                                            R.string.nostr_wallet_error,
                                            failure.message ?: failure.javaClass.simpleName,
                                        )
                                    } finally {
                                        isConnecting = false
                                    }
                                }
                            },
                        ) { Text(text = stringResource(R.string.nostr_wallet_connect_button)) }
                    },
                    dismissButton = {
                        TextButton(enabled = !isConnecting, onClick = ::finish) {
                            Text(text = stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val TAG = "NostrWalletLink"
    }
}
