package com.android.messaging.data.appsettings.model

import androidx.annotation.StringRes
import com.android.messaging.R

internal enum class AppBooleanPref(
    @param:StringRes val keyResId: Int,
) {
    SEND_SOUND(R.string.send_sound_pref_key),
    IN_CONVERSATION_SOUND(R.string.in_conversation_sound_pref_key),
    YOUTUBE_LINK_PREVIEWS(R.string.youtube_link_previews_pref_key),
    WEB_LINK_PREVIEWS(R.string.web_link_previews_pref_key),
    SEND_DELAY(R.string.send_delay_pref_key),
    UNREAD_ON_TOP(R.string.unread_on_top_pref_key),
    LONG_SMS_AS_MMS(R.string.long_sms_as_mms_pref_key),
    DUMP_SMS(R.string.dump_sms_pref_key),
    DUMP_MMS(R.string.dump_mms_pref_key),
}
