package com.android.messaging.deltachat

import android.content.Context
import com.android.messaging.twill.TwillDeliveryMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Twill's own Delta Chat settings, in private SharedPreferences. The profile itself (keys,
 * messages, contacts) lives in the core's database; this only remembers which core account
 * Twill uses and whether it finished setup, so nothing starts until the user sets it up.
 */
@Singleton
internal class DeltaChatAccountStore @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _accountId = MutableStateFlow(
        prefs.getInt(KEY_ACCOUNT_ID, 0).takeIf { it > 0 },
    )
    private val _isConfigured = MutableStateFlow(prefs.getBoolean(KEY_CONFIGURED, false))
    private val _deliveryMode = MutableStateFlow(
        TwillDeliveryMode.from(prefs.getString(KEY_DELIVERY_MODE, null)),
    )

    /** The core account Twill uses, once created. */
    val accountId: StateFlow<Int?> = _accountId.asStateFlow()

    /** Setup finished: Delta Chat is on. */
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    val deliveryMode: StateFlow<TwillDeliveryMode> = _deliveryMode.asStateFlow()

    fun setAccountId(id: Int?) {
        val editor = prefs.edit()
        if (id == null) editor.remove(KEY_ACCOUNT_ID) else editor.putInt(KEY_ACCOUNT_ID, id)
        editor.commit()
        _accountId.value = id
    }

    fun setConfigured(configured: Boolean) {
        prefs.edit().putBoolean(KEY_CONFIGURED, configured).commit()
        _isConfigured.value = configured
    }

    fun setDeliveryMode(mode: TwillDeliveryMode) {
        prefs.edit().putString(KEY_DELIVERY_MODE, mode.key).commit()
        _deliveryMode.value = mode
    }

    private companion object {
        const val PREFS_NAME = "deltachat"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_CONFIGURED = "configured"
        const val KEY_DELIVERY_MODE = "delivery_mode"
    }
}
