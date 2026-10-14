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
 * Turning SMS off takes effect immediately in Twill. Twill's SMS components are only
 * disabled once another app is the default SMS app (disabling them while Twill holds the role
 * would make Android revoke it abruptly), which is re-checked every time Twill opens.
 */
@Singleton
internal class TwillSms @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _isEnabled = MutableStateFlow(isEnabled(context))

    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    fun isDefaultSmsApp(): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true

    /**
     * Turns SMS off. If Twill is still the default SMS app, the user should pick another one
     * (see [isDefaultSmsApp]); until then Twill keeps its SMS components so no text is lost.
     */
    fun disable() {
        save(enabled = false)
        applyComponents(context)
    }

    /** Turns SMS back on; the user then goes through onboarding to become the default again. */
    fun enable() {
        save(enabled = true)
        applyComponents(context)
    }

    /** Brings the SMS components in line with the setting. Called at startup and on resume. */
    fun sync() = applyComponents(context)

    private fun save(enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit()
        _isEnabled.value = enabled
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

        /**
         * Enables or disables the SMS components to match the setting. They are never disabled
         * while Twill holds the SMS role: Android would revoke it abruptly.
         */
        fun applyComponents(context: Context) {
            val isDefault = context.getSystemService(RoleManager::class.java)
                ?.isRoleHeld(RoleManager.ROLE_SMS) == true
            val enabled = isEnabled(context) || isDefault
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
    }
}
