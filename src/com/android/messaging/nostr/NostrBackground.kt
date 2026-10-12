package com.android.messaging.nostr

import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.android.messaging.di.core.ApplicationCoroutineScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface NostrEntryPoint {
    fun nostrMessenger(): NostrMessenger

    fun nostrDeliveryManager(): NostrDeliveryManager

    fun nostrMedia(): NostrMedia

    fun nostrAccountStore(): NostrAccountStore
}

internal fun Context.nostrEntryPoint(): NostrEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, NostrEntryPoint::class.java)

/**
 * Starts or stops background delivery to match the "Message delivery" setting: nothing, a
 * periodic job, or the always-connected service. Nothing runs while signed out.
 */
@Singleton
internal class NostrDeliveryManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountStore: NostrAccountStore,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        synchronized(this) {
            if (job != null) return
            job = appScope.launch {
                combine(accountStore.account, accountStore.deliveryMode) { account, mode ->
                    if (account == null) null else mode
                }
                    .distinctUntilChanged()
                    .collect { mode -> apply(mode) }
            }
        }
    }

    private fun apply(mode: NostrDeliveryMode?) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val serviceIntent = Intent(context, NostrConnectionService::class.java)

        when (mode) {
            NostrDeliveryMode.AlwaysConnected -> {
                scheduler?.cancel(SYNC_JOB_ID)
                runCatching { context.startForegroundService(serviceIntent) }
            }

            NostrDeliveryMode.Periodic -> {
                context.stopService(serviceIntent)
                scheduler?.schedule(
                    JobInfo.Builder(
                        SYNC_JOB_ID,
                        ComponentName(context, NostrSyncJobService::class.java)
                    )
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(TimeUnit.MINUTES.toMillis(PERIODIC_MINUTES))
                        .setPersisted(true)
                        .build(),
                )
            }

            NostrDeliveryMode.WhileOpen, null -> {
                scheduler?.cancel(SYNC_JOB_ID)
                context.stopService(serviceIntent)
            }
        }
    }

    private companion object {
        const val SYNC_JOB_ID = 0x7417_0002
        const val PERIODIC_MINUTES = 15L
    }
}

/** Keeps the relay connection open while "Always connected" is chosen. */
class NostrConnectionService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NostrNotifications.CONNECTION_NOTIFICATION_ID,
            NostrNotifications.connectionNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        val messenger = nostrEntryPoint().nostrMessenger()
        messenger.install(markAppVisible = false)
        messenger.setKeepConnectedInBackground(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        nostrEntryPoint().nostrMessenger().setKeepConnectedInBackground(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** The "Check every 15 minutes" job: connects briefly, collects new messages, disconnects. */
class NostrSyncJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val entryPoint = nostrEntryPoint()
        val messenger = entryPoint.nostrMessenger()
        messenger.install(markAppVisible = false)
        job = messenger.launchInAppScope {
            try {
                messenger.syncOnce()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}

/** Restores background delivery after a reboot or app update. */
class NostrBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val entryPoint = context.nostrEntryPoint()
                entryPoint.nostrMessenger().install(markAppVisible = false)
                entryPoint.nostrDeliveryManager().start()
            }
        }
    }
}
