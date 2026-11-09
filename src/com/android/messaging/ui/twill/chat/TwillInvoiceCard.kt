package com.android.messaging.ui.twill.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.twill.TwillActionError
import com.android.messaging.twill.TwillInvoice
import com.android.messaging.twill.TwillInvoices
import com.android.messaging.ui.core.LocalBarContainerColor
import kotlinx.coroutines.launch

/**
 * A Lightning invoice in a message, as Struthio shows one: amount, expiry, memo, and buttons to
 * pay it (with the connected wallet, after asking) or open it in a wallet app, and to copy it.
 */
@Composable
internal fun TwillInvoiceCard(
    invoice: TwillInvoice,
    isWalletConnected: Boolean,
    isPaid: Boolean,
    /** Pays from the connected wallet; throws [TwillActionError] on failure. */
    onPay: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isConfirming by remember(invoice.text) { mutableStateOf(false) }
    var isPaying by remember(invoice.text) { mutableStateOf(false) }
    val left = invoice.expiresAt - System.currentTimeMillis() / 1000
    val amount = invoice.msats?.let(TwillInvoices::formatSats)
    val canPayHere = isWalletConnected && invoice.msats != null
    val barColor = LocalBarContainerColor.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        // The real surface container (pages use a remapped one).
        color = if (barColor.isSpecified) barColor else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    modifier = Modifier.weight(1f),
                    text = amount ?: stringResource(R.string.twill_invoice_any_amount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        isPaid -> stringResource(R.string.twill_invoice_paid)
                        left <= 0 -> stringResource(R.string.twill_invoice_expired)
                        else -> stringResource(
                            R.string.twill_invoice_expires_in,
                            TwillInvoices.timeLeft(left),
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.twill_invoice_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            invoice.memo?.let { memo ->
                Text(text = memo, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isPaid && left > 0) {
                    FilledTonalButton(
                        enabled = !isPaying,
                        onClick = {
                            if (canPayHere) {
                                isConfirming = true
                            } else {
                                openInWallet(context, invoice.text)
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(
                                when {
                                    isPaying -> R.string.twill_invoice_paying
                                    canPayHere -> R.string.twill_invoice_pay
                                    else -> R.string.twill_invoice_open
                                },
                            ),
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                            ClipData.newPlainText(
                                context.getString(R.string.twill_invoice_title),
                                invoice.text,
                            ),
                        )
                        Toast.makeText(context, R.string.twill_invoice_copied, Toast.LENGTH_SHORT)
                            .show()
                    },
                ) { Text(text = stringResource(R.string.twill_invoice_copy)) }
            }
        }
    }

    if (isConfirming) {
        AlertDialog(
            onDismissRequest = { isConfirming = false },
            title = {
                Text(text = stringResource(R.string.twill_invoice_pay_title, amount.orEmpty()))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    invoice.memo?.let { Text(text = it) }
                    Text(text = stringResource(R.string.twill_invoice_pay_warning))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirming = false
                        isPaying = true
                        scope.launch {
                            try {
                                onPay()
                                val paid = context.getString(
                                    R.string.twill_invoice_paid_amount,
                                    amount.orEmpty(),
                                )
                                Toast.makeText(context, paid, Toast.LENGTH_SHORT).show()
                            } catch (error: TwillActionError) {
                                Toast.makeText(context, error.text(context), Toast.LENGTH_LONG)
                                    .show()
                            } finally {
                                isPaying = false
                            }
                        }
                    },
                ) { Text(text = stringResource(R.string.twill_invoice_pay)) }
            },
            dismissButton = {
                TextButton(onClick = { isConfirming = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** Hands [invoice] to a Lightning wallet app, or says there is none. */
internal fun openInWallet(context: Context, invoice: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$invoice")))
    } catch (exception: ActivityNotFoundException) {
        Toast.makeText(context, R.string.twill_invoice_no_wallet, Toast.LENGTH_SHORT).show()
    }
}

/** The message to show for [this] failure. */
internal fun TwillActionError.text(context: Context): String = context.getString(messageRes, *args)
