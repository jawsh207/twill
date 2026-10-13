package com.android.messaging.ui.onboarding.screen.model

internal sealed interface OnboardingAction {

    data object NextClicked : OnboardingAction

    data object SettingsClicked : OnboardingAction

    /** Twill: use Twill only for Nostr and Delta Chat. */
    data object WithoutSmsClicked : OnboardingAction
}
