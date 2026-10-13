package com.android.messaging.twill

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.android.messaging.deltachat.DeltaChatProtocol
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.nostr.NostrProtocol
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every protocol Twill knows, in inbox order. */
@Singleton
internal class TwillProtocols @Inject constructor(
    protocols: Set<@JvmSuppressWildcards TwillProtocol>,
) {
    val all: List<TwillProtocol> = protocols.sortedBy { it.id.ordinal }

    operator fun get(id: TwillProtocolId): TwillProtocol = all.first { it.id == id }

    fun byKey(key: String): TwillProtocol? = TwillProtocolId.from(key)?.let(::get)

    /** Starts every protocol (each does nothing until it is set up). */
    fun installAll() = all.forEach { it.install() }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TwillProtocolsModule {
    @Binds
    @IntoSet
    abstract fun bindNostr(protocol: NostrProtocol): TwillProtocol

    @Binds
    @IntoSet
    abstract fun bindDeltaChat(protocol: DeltaChatProtocol): TwillProtocol
}

/**
 * Whether Twill is on screen. [isVisible] is the real state, used to skip notifications for
 * the open chat. [isRecentlyVisible] stays true for a short grace period after leaving, so
 * quick app switches don't drop network connections.
 */
@Singleton
internal class TwillAppVisibility @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) {
    private val startedActivities = MutableStateFlow(0)
    private val _isVisible = MutableStateFlow(false)
    private val _isRecentlyVisible = MutableStateFlow(false)
    private var graceJob: Job? = null
    private var isRegistered = false

    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()
    val isRecentlyVisible: StateFlow<Boolean> = _isRecentlyVisible.asStateFlow()

    /** The chat on screen, as "protocol:chatId", so its messages don't notify. */
    @Volatile
    var activeChat: String? = null
        private set

    fun setActiveChat(protocol: TwillProtocolId, chatId: String?) {
        activeChat = chatId?.let { protocol.key + ":" + it }
        if (chatId != null) TwillNotifications.cancelChat(context, protocol, chatId)
    }

    fun isViewing(protocol: TwillProtocolId, chatId: String): Boolean =
        _isVisible.value && activeChat == protocol.key + ":" + chatId

    /**
     * Starts tracking. Screens pass [markVisible] true, because the activity was already
     * started before tracking began; background components pass false.
     */
    fun install(markVisible: Boolean) {
        synchronized(this) {
            if (!isRegistered) {
                isRegistered = true
                (context.applicationContext as Application)
                    .registerActivityLifecycleCallbacks(Callbacks())
            }
            if (markVisible && startedActivities.value == 0) {
                startedActivities.value = 1
                onStartedCountChanged()
            }
        }
    }

    private fun onStartedCountChanged() {
        graceJob?.cancel()
        if (startedActivities.value > 0) {
            _isVisible.value = true
            _isRecentlyVisible.value = true
        } else {
            _isVisible.value = false
            graceJob = appScope.launch {
                delay(BACKGROUND_GRACE_MILLIS)
                _isRecentlyVisible.value = false
            }
        }
    }

    private inner class Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            synchronized(this@TwillAppVisibility) {
                startedActivities.value += 1
                onStartedCountChanged()
            }
        }

        override fun onActivityStopped(activity: Activity) {
            synchronized(this@TwillAppVisibility) {
                startedActivities.value = (startedActivities.value - 1).coerceAtLeast(0)
                onStartedCountChanged()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private companion object {
        const val BACKGROUND_GRACE_MILLIS = 30_000L
    }
}
