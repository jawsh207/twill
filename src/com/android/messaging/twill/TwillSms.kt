package com.android.messaging.twill

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Twill handles SMS/MMS. With SMS off, Twill is only a Nostr and Delta Chat app:
 * onboarding doesn't ask to become the default SMS app, the inbox has no SMS section, and
 * Twill's SMS components are disabled so Android doesn't offer it as an SMS app.
 *
 * SMS can only be off while another app is the default SMS app: the default SMS app receives
 * every text, so turning SMS off while Twill holds that role would lose messages.
 */
@Singleton
internal class TwillSms @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _isEnabled = MutableStateFlow(isEnabled(context))

    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    fun isDefaultSmsApp(): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true

    /** Turns SMS off. Returns false (and changes nothing) while Twill is the default SMS app. */
    fun disable(): Boolean {
        if (isDefaultSmsApp()) return false
        save(enabled = false)
        applyComponents()
        return true
    }

    /** Turns SMS back on; the user then goes through onboarding to become the default again. */
    fun enable() {
        save(enabled = true)
        applyComponents()
    }

    /**
     * Brings the SMS components in line with the setting. Called at startup. If the user made
     * Twill the default SMS app again from system settings, SMS turns back on.
     */
    fun sync() {
        if (!_isEnabled.value && isDefaultSmsApp()) save(enabled = true)
        applyComponents()
    }

    private fun save(enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit()
        _isEnabled.value = enabled
    }

    private fun applyComponents() {
        // Never disable them while Twill holds the SMS role: Android would revoke it abruptly.
        val enabled = _isEnabled.value || isDefaultSmsApp()
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val packageManager = context.packageManager
        SMS_COMPONENTS.forEach { className ->
            val component = ComponentName(context.packageName, className)
            if (packageManager.getComponentEnabledSetting(component) != state) {
                runCatching {
                    packageManager.setComponentEnabledSetting(
                        component,
                        state,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "twill_sms"
        private const val KEY_ENABLED = "enabled"

        /** What makes Twill an SMS app to Android: receiving, quick replies, sms: links, sharing. */
        private val SMS_COMPONENTS = listOf(
            "com.android.messaging.receiver.SmsDeliverReceiver",
            "com.android.messaging.receiver.MmsWapPushDeliverReceiver",
            "com.android.messaging.datamodel.NoConfirmationSmsSendService",
            "com.android.messaging.ui.conversation.LaunchConversationActivity",
            "com.android.messaging.ui.conversationlist.ShareIntentActivity",
        )

        /** For code without dependency injection. */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
    }
}
