package com.android.messaging.data.appsettings.repository

import com.android.messaging.Factory
import com.android.messaging.data.appsettings.model.AppBooleanPref
import com.android.messaging.util.BuglePrefs

/**
 * Synchronous reader for app-level boolean preferences, for callers that cannot suspend
 * (list sorting, send pipeline decisions). SharedPreferences reads are in-memory after the
 * first load, so this is cheap.
 */
internal fun interface AppPreferenceReader {
    fun isEnabled(pref: AppBooleanPref): Boolean

    companion object {
        /**
         * Reads straight from [BuglePrefs]. Falls back to `false` when the app [Factory] is not
         * initialized (plain JVM unit tests), which matches every default these prefs use.
         */
        val Default: AppPreferenceReader = AppPreferenceReader { pref ->
            try {
                val context = Factory.get().applicationContext
                BuglePrefs.getApplicationPrefs().getBoolean(
                    context.getString(pref.keyResId),
                    false,
                )
            } catch (exception: RuntimeException) {
                false
            }
        }
    }
}
