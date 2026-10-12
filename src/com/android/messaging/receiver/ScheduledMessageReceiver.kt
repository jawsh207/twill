package com.android.messaging.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.messaging.di.extras.ScheduledMessageEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Fires when a scheduled message is due, and after boot or an app update so the pending alarm
 * (which the system clears in both cases) is restored and anything overdue is sent.
 */
class ScheduledMessageReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_SEND_DUE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit

            else -> return
        }

        val scheduler = EntryPointAccessors
            .fromApplication(context.applicationContext, ScheduledMessageEntryPoint::class.java)
            .scheduledMessageScheduler()

        val pendingResult = goAsync()
        scheduler.dispatchDue(onComplete = pendingResult::finish)
    }

    companion object {
        const val ACTION_SEND_DUE = "com.android.messaging.action.SEND_SCHEDULED_MESSAGES"
    }
}
