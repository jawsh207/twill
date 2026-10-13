package com.android.messaging.ui.host

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import com.android.messaging.domain.onboarding.usecase.SelfPhoneNumberPermissionPrompt
import com.android.messaging.twill.TwillSms

@Composable
internal fun SelfPhoneNumberPermissionEffect(
    backStack: List<NavKey>,
    shouldShowOnboarding: () -> Boolean,
    selfPhoneNumberPermissionPrompt: SelfPhoneNumberPermissionPrompt,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            selfPhoneNumberPermissionPrompt.onGranted()
        }
    }

    val context = LocalContext.current
    LaunchedEffect(backStack.lastOrNull()) {
        // Twill: the phone number is only for SMS.
        if (!TwillSms.isEnabled(context)) return@LaunchedEffect
        if (shouldShowOnboarding() || !selfPhoneNumberPermissionPrompt.consume()) {
            return@LaunchedEffect
        }

        launcher.launch(Manifest.permission.READ_PHONE_NUMBERS)
    }
}
