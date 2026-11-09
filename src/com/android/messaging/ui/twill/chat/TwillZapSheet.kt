package com.android.messaging.ui.twill.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.twill.TwillActionError
import kotlinx.coroutines.launch

/**
 * Zapping the person in a chat, as Struthio's zap sheet: preset amounts, an amount field,
 * an optional comment. Paid from the connected wallet, or handed to a wallet app.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TwillZapSheet(
    name: String,
    isWalletConnected: Boolean,
    /** Returns null once paid, or the invoice for a wallet app; throws [TwillActionError]. */
    onZap: suspend (sats: Long, comment: String) -> String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var amount by rememberSaveable {
        mutableStateOf(prefs.getLong(KEY_DEFAULT_AMOUNT, DEFAULT_SATS).toString())
    }
    var comment by rememberSaveable { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.nostr_zap_title, name),
                style = MaterialTheme.typography.titleLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { value ->
                    FilterChip(
                        selected = amount == value.toString(),
                        onClick = { amount = value.toString() },
                        label = { Text(text = compact(value)) },
                    )
                }
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = amount,
                onValueChange = { amount = it.filter(Char::isDigit).take(MAX_DIGITS) },
                label = { Text(text = stringResource(R.string.nostr_zap_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = comment,
                onValueChange = { comment = it },
                label = { Text(text = stringResource(R.string.nostr_zap_comment)) },
            )
            if (!isWalletConnected) {
                Text(
                    text = stringResource(R.string.nostr_zap_no_wallet_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                enabled = !isBusy && (amount.toLongOrNull() ?: 0) > 0,
                onClick = {
                    val sats = amount.toLongOrNull() ?: return@Button
                    isBusy = true
                    scope.launch {
                        try {
                            prefs.edit().putLong(KEY_DEFAULT_AMOUNT, sats).apply()
                            val invoice = onZap(sats, comment.trim())
                            if (invoice != null) {
                                openInWallet(context, invoice)
                            } else {
                                Toast.makeText(context, R.string.nostr_zap_sent, Toast.LENGTH_SHORT)
                                    .show()
                            }
                            onDismiss()
                        } catch (error: TwillActionError) {
                            Toast.makeText(context, error.text(context), Toast.LENGTH_LONG).show()
                        } finally {
                            isBusy = false
                        }
                    }
                },
            ) {
                Icon(imageVector = Icons.Rounded.Bolt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (isBusy) R.string.nostr_zap_zapping else R.string.nostr_zap,
                    ),
                )
            }
        }
    }
}

/** 21, 100, 500, 1k, 5k. */
private fun compact(sats: Long): String = when {
    sats >= 1000 && sats % 1000 == 0L -> "${sats / 1000}k"
    else -> sats.toString()
}

private const val PREFS_NAME = "nostr_zaps"
private const val KEY_DEFAULT_AMOUNT = "default_amount"
private const val DEFAULT_SATS = 100L
private const val MAX_DIGITS = 9
private val PRESETS = listOf(21L, 100L, 500L, 1000L, 5000L)
