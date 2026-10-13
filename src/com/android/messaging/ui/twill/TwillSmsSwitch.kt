package com.android.messaging.ui.twill

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.twill.twillEntryPoint
import com.android.messaging.ui.appsettings.common.SettingsSwitchItem

private enum class SmsSwitchDialog { None, ChooseOtherApp, ConfirmOff }

/**
 * "Use Twill for SMS/MMS". Turning it off needs another default SMS app first; this sends the
 * user to pick one and finishes turning SMS off when they come back. Turning it on calls
 * [onTurnedOn], which should start onboarding (default SMS app and permissions).
 */
@Composable
internal fun TwillSmsSwitch(onTurnedOn: () -> Unit) {
    val context = LocalContext.current
    val sms = remember(context) { context.twillEntryPoint().twillSms() }
    val isEnabled by sms.isEnabled.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(SmsSwitchDialog.None) }
    var isWaitingForOtherApp by rememberSaveable { mutableStateOf(false) }

    // Back from the default-apps screen: finish if another app is now the SMS app.
    LifecycleResumeEffect(Unit) {
        if (isWaitingForOtherApp && !sms.isDefaultSmsApp()) {
            isWaitingForOtherApp = false
            sms.disable()
        }
        onPauseOrDispose {}
    }

    SettingsSwitchItem(
        title = stringResource(R.string.twill_use_for_sms),
        summary = stringResource(
            if (isEnabled) R.string.twill_use_for_sms_on else R.string.twill_use_for_sms_off,
        ),
        checked = isEnabled,
        onCheckedChange = { checked ->
            when {
                checked -> {
                    sms.enable()
                    onTurnedOn()
                }

                sms.isDefaultSmsApp() -> dialog = SmsSwitchDialog.ChooseOtherApp
                else -> dialog = SmsSwitchDialog.ConfirmOff
            }
        },
    )

    when (dialog) {
        SmsSwitchDialog.None -> Unit

        SmsSwitchDialog.ChooseOtherApp -> AlertDialog(
            onDismissRequest = { dialog = SmsSwitchDialog.None },
            title = { Text(text = stringResource(R.string.twill_turn_off_sms)) },
            text = { Text(text = stringResource(R.string.twill_sms_choose_other_app)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = SmsSwitchDialog.None
                        isWaitingForOtherApp = true
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.twill_choose_sms_app))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = SmsSwitchDialog.None }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )

        SmsSwitchDialog.ConfirmOff -> AlertDialog(
            onDismissRequest = { dialog = SmsSwitchDialog.None },
            title = { Text(text = stringResource(R.string.twill_turn_off_sms)) },
            text = { Text(text = stringResource(R.string.twill_turn_off_sms_explainer)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = SmsSwitchDialog.None
                        sms.disable()
                    },
                ) {
                    Text(text = stringResource(R.string.twill_turn_off))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = SmsSwitchDialog.None }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
