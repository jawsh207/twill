package com.android.messaging.twill

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide inbox options set in Twill's Settings. [isBottomBarEnabled]: show a bottom bar with
 * a tab per service (SMS, Nostr, Delta Chat) instead of sections, when more than one is on.
 * Off by default for now.
 */
@Singleton
internal class TwillInboxSettings @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val bottomBar = MutableStateFlow(prefs.getBoolean(KEY_BOTTOM_BAR, false))

    val isBottomBarEnabled: StateFlow<Boolean> = bottomBar.asStateFlow()

    fun setBottomBarEnabled(enabled: Boolean) {
        bottomBar.value = enabled
        prefs.edit().putBoolean(KEY_BOTTOM_BAR, enabled).apply()
    }

    private companion object {
        /** Shared with the inbox's own state (collapsed sections). */
        const val PREFS_NAME = "twill_inbox"
        const val KEY_BOTTOM_BAR = "bottom_bar"
    }
}
