package com.android.messaging.ui.onboarding.screen.model

import kotlinx.collections.immutable.ImmutableList

internal sealed interface OnboardingScreenEffect {

    data class RequestRuntimePermissions(
        val permissions: ImmutableList<String>,
    ) : OnboardingScreenEffect

    data object RequestSmsRole : OnboardingScreenEffect

    data object OpenAppSettings : OnboardingScreenEffect

    data object Redirect : OnboardingScreenEffect

    /** Twill: SMS is off; continue to the app without the SMS setup. */
    data object ContinueWithoutSms : OnboardingScreenEffect

    /** Twill: SMS can't be turned off while Twill is the default SMS app. */
    data object OpenDefaultAppsSettings : OnboardingScreenEffect
}
