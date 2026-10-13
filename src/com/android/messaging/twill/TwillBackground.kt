package com.android.messaging.twill

import android.app.NotificationManager
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
import com.android.messaging.util.LogUtil
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TwillEntryPoint {
    fun twillRuntime(): TwillRuntime

    fun twillProtocols(): TwillProtocols

    fun twillAppVisibility(): TwillAppVisibility

    fun twillSms(): TwillSms
}

internal fun Context.twillEntryPoint(): TwillEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, TwillEntryPoint::class.java)

/** Which protocols want which kind of background delivery right now. */
internal data class TwillDeliveryPlan(
    val alwaysConnected: List<TwillProtocolId> = emptyList(),
    val periodic: List<TwillProtocolId> = emptyList(),
)

/**
 * Starts the protocols and keeps background delivery in line with each protocol's
 * "Message delivery" setting: one foreground service for every "always connected" protocol,
 * and one 15-minute job for every "periodic" one. Nothing runs for protocols that are off.
 */
@Singleton
internal class TwillRuntime @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val protocols: TwillProtocols,
    private val visibility: TwillAppVisibility,
    private val sms: TwillSms,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) {
    private var planJob: Job? = null
    private val _plan = MutableStateFlow(TwillDeliveryPlan())

    val plan: StateFlow<TwillDeliveryPlan> = _plan.asStateFlow()

    /** Safe to call from every screen and background entry point. */
    fun start(markVisible: Boolean) {
        visibility.install(markVisible)
        synchronized(this) {
            if (planJob == null) {
                TwillNotifications.ensureChannels(context)
                sms.sync()
                protocols.installAll()
                // Known right away, so the service and job never act on an empty plan.
                _plan.value = currentPlan()
                planJob = appScope.launch { planFlow().distinctUntilChanged().collect(::apply) }
            }
        }
    }

    private fun currentPlan(): TwillDeliveryPlan {
        fun idsWith(mode: TwillDeliveryMode) = protocols.all
            .filter { it.isEnabled.value && it.deliveryMode.value == mode }
            .map { it.id }
        return TwillDeliveryPlan(
            alwaysConnected = idsWith(TwillDeliveryMode.AlwaysConnected),
            periodic = idsWith(TwillDeliveryMode.Periodic),
        )
    }

    private fun planFlow(): Flow<TwillDeliveryPlan> {
        val perProtocol = protocols.all.map { protocol ->
            combine(protocol.isEnabled, protocol.deliveryMode) { enabled, mode ->
                protocol.id to (if (enabled) mode else null)
            }
        }
        if (perProtocol.isEmpty()) return flowOf(TwillDeliveryPlan())
        return combine(perProtocol) { modes ->
            TwillDeliveryPlan(
                alwaysConnected = modes.filter { it.second == TwillDeliveryMode.AlwaysConnected }
                    .map { it.first },
                periodic = modes.filter { it.second == TwillDeliveryMode.Periodic }
                    .map { it.first },
            )
        }
    }

    private fun apply(plan: TwillDeliveryPlan) {
        _plan.value = plan
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val serviceIntent = Intent(context, TwillConnectionService::class.java)

        if (plan.alwaysConnected.isEmpty()) {
            context.stopService(serviceIntent)
        } else {
            try {
                context.startForegroundService(serviceIntent)
            } catch (exception: Exception) {
                // Android refuses while Twill is in the background; the service starts the
                // next time Twill opens, or after a reboot.
                LogUtil.w(
                    TAG,
                    "Could not start connection service: ${exception.javaClass.simpleName}"
                )
            }
        }

        if (plan.periodic.isEmpty()) {
            scheduler?.cancel(SYNC_JOB_ID)
        } else if (scheduler?.getPendingJob(SYNC_JOB_ID) == null) {
            scheduler?.schedule(
                JobInfo.Builder(
                    SYNC_JOB_ID,
                    ComponentName(context, TwillSyncJobService::class.java)
                )
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(TimeUnit.MINUTES.toMillis(PERIODIC_MINUTES))
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    /** Runs one background check for every "periodic" protocol. */
    suspend fun syncPeriodic() = coroutineScope {
        currentPlan().periodic
            .map { id -> async(Dispatchers.IO) { protocols[id].syncOnce() } }
            .awaitAll()
    }

    private companion object {
        const val TAG = "TwillRuntime"
        const val SYNC_JOB_ID = 0x7417_0002
        const val PERIODIC_MINUTES = 15L
    }
}

/** Keeps every "always connected" protocol receiving, behind one persistent notification. */
class TwillConnectionService : Service() {
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        val entryPoint = twillEntryPoint()
        val runtime = entryPoint.twillRuntime()
        val protocols = entryPoint.twillProtocols()
        runtime.start(markVisible = false)
        startForeground(
            TwillNotifications.CONNECTION_NOTIFICATION_ID,
            TwillNotifications.connectionNotification(
                this,
                runtime.plan.value.alwaysConnected.map { protocols[it] },
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )

        job = CoroutineScope(Dispatchers.Main).launch {
            runtime.plan.collect { plan ->
                protocols.all.forEach { protocol ->
                    protocol.setKeepConnectedInBackground(protocol.id in plan.alwaysConnected)
                }
                val active = plan.alwaysConnected.map { protocols[it] }
                if (active.isEmpty()) {
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(
                        TwillNotifications.CONNECTION_NOTIFICATION_ID,
                        TwillNotifications.connectionNotification(
                            this@TwillConnectionService,
                            active
                        ),
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        job?.cancel()
        twillEntryPoint().twillProtocols().all.forEach { it.setKeepConnectedInBackground(false) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** The "Check every 15 minutes" job: each periodic protocol connects briefly and syncs. */
class TwillSyncJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val runtime = twillEntryPoint().twillRuntime()
        runtime.start(markVisible = false)
        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                runtime.syncPeriodic()
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
class TwillBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                context.twillEntryPoint().twillRuntime().start(markVisible = false)
            }
        }
    }
}
