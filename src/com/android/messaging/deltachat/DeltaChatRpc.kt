package com.android.messaging.deltachat

import android.content.Context
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.util.LogUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** An error returned by the Delta Chat core, with its message (often user-readable). */
internal class DeltaChatRpcException(
    message: String,
) : IOException(message)

/**
 * One core event: [id] is a DC_EVENT_* constant (see [DeltaChatEvents]); [data1] and [data2]
 * are its integer fields (e.g. chat id and message id).
 */
internal class DeltaChatEvent(
    val id: Int,
    val accountId: Int,
    val data1: Int,
    val data2: Int,
)

/** The native bridge (native/deltachat/twill_deltachat_jni.c). */
internal object DeltaChatNative {
    /** False when this build doesn't include the core. */
    val isLoaded: Boolean = try {
        System.loadLibrary("twill_deltachat")
        true
    } catch (error: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic external fun accountsNew(dir: ByteArray): Long

    @JvmStatic external fun accountsUnref(accounts: Long)

    @JvmStatic external fun jsonrpcInit(accounts: Long): Long

    @JvmStatic external fun jsonrpcUnref(rpc: Long)

    @JvmStatic external fun jsonrpcCall(rpc: Long, request: ByteArray): ByteArray?

    @JvmStatic external fun eventEmitterNew(accounts: Long): Long

    @JvmStatic external fun eventEmitterUnref(emitter: Long)

    @JvmStatic external fun nextEvent(emitter: Long): IntArray?
}

/**
 * The Delta Chat core (chatmail core), running inside Twill's process. Calls go through the
 * core's JSON-RPC API (`dc_jsonrpc_blocking_call`), each on an IO thread; events come from
 * the core's event emitter on a dedicated thread.
 *
 * The core starts on the first call. When nothing needs it ([keepRunning] false), it is
 * shut down after a minute without calls, freeing its memory; the next call starts it again.
 * Shutdown waits for calls in progress, so no call ever uses a freed core.
 */
@Singleton
internal class DeltaChatRpc @Inject constructor(
    @param:ApplicationContext context: Context,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) {
    val isAvailable: Boolean
        get() = DeltaChatNative.isLoaded

    private val accountsDir: File = File(context.filesDir, "deltachat-accounts")

    // Calls hold the read lock; starting and stopping hold the write lock.
    private val lock = ReentrantReadWriteLock()
    private var accounts = 0L
    private var rpc = 0L
    private val nextId = AtomicInteger(1)
    private val callsInFlight = AtomicInteger(0)
    private var idleJob: Job? = null

    private val _events = MutableSharedFlow<DeltaChatEvent>(extraBufferCapacity = 1024)

    /** Every core event, for all accounts. */
    val events: SharedFlow<DeltaChatEvent> = _events.asSharedFlow()

    private val _starts = MutableStateFlow(0)

    /** Counts core starts, so callers can restore state such as running IO. */
    val starts: StateFlow<Int> = _starts.asStateFlow()

    /**
     * Whether the core must stay loaded (network running, or about to). When false, it is
     * released after [IDLE_STOP_MILLIS] without calls.
     */
    @Volatile
    var keepRunning: Boolean = false
        set(value) {
            field = value
            scheduleIdleStop()
        }

    /**
     * Calls [method] with positional [params] and returns the JSON result (JSONObject,
     * JSONArray, String, Number, Boolean or null). Throws [DeltaChatRpcException] on error.
     */
    suspend fun call(method: String, vararg params: Any?): Any? = withContext(Dispatchers.IO) {
        if (!isAvailable) throw DeltaChatRpcException("Delta Chat isn't included in this build")
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId.getAndIncrement())
            .put("method", method)
            .put("params", JSONArray().apply { params.forEach { put(toJson(it)) } })
            .toString()
            .toByteArray(Charsets.UTF_8)

        callsInFlight.incrementAndGet()
        val raw = try {
            withCore { handle -> DeltaChatNative.jsonrpcCall(handle, request) }
        } finally {
            callsInFlight.decrementAndGet()
            scheduleIdleStop()
        } ?: throw DeltaChatRpcException("$method: no response")

        val response = JSONObject(String(raw, Charsets.UTF_8))
        response.optJSONObject("error")?.let { error ->
            throw DeltaChatRpcException(error.optString("message", "Unknown error"))
        }
        response.opt("result").takeUnless { it == JSONObject.NULL }
    }

    suspend fun callInt(method: String, vararg params: Any?): Int =
        (call(method, *params) as? Number)?.toInt()
            ?: throw DeltaChatRpcException("$method: unexpected result")

    suspend fun callObject(method: String, vararg params: Any?): JSONObject =
        call(method, *params) as? JSONObject
            ?: throw DeltaChatRpcException("$method: unexpected result")

    suspend fun callArray(method: String, vararg params: Any?): JSONArray =
        call(method, *params) as? JSONArray ?: JSONArray()

    /** Loads the core if needed, so events begin to flow. */
    fun start() {
        if (isAvailable) withCore { }
    }

    /** Runs [block] with the JSON-RPC handle, starting the core first if needed. */
    private fun <T> withCore(block: (Long) -> T): T {
        while (true) {
            lock.read { if (rpc != 0L) return block(rpc) }
            lock.write { if (rpc == 0L) startLocked() }
        }
    }

    private fun startLocked() {
        accountsDir.mkdirs()
        val newAccounts = DeltaChatNative.accountsNew(accountsDir.absolutePath.toByteArray())
        if (newAccounts == 0L) throw DeltaChatRpcException("Couldn't open Delta Chat's data")
        val newRpc = DeltaChatNative.jsonrpcInit(newAccounts)
        if (newRpc == 0L) {
            DeltaChatNative.accountsUnref(newAccounts)
            throw DeltaChatRpcException("Couldn't start Delta Chat")
        }
        val emitter = DeltaChatNative.eventEmitterNew(newAccounts)
        accounts = newAccounts
        rpc = newRpc

        // Ends by itself once the core is freed (nextEvent returns null).
        thread(name = "deltachat-events", isDaemon = true) {
            while (true) {
                val values = DeltaChatNative.nextEvent(emitter) ?: break
                _events.tryEmit(
                    DeltaChatEvent(
                        id = values[0],
                        accountId = values[1],
                        data1 = values[2],
                        data2 = values[3],
                    ),
                )
            }
            DeltaChatNative.eventEmitterUnref(emitter)
        }
        _starts.value += 1
    }

    private fun scheduleIdleStop() {
        idleJob?.cancel()
        if (keepRunning || !isAvailable) return
        idleJob = appScope.launch(Dispatchers.IO) {
            delay(IDLE_STOP_MILLIS)
            if (!keepRunning && callsInFlight.get() == 0) stop()
        }
    }

    /** Stops the network and frees the core. The next call loads it again. */
    fun stop() {
        if (lock.read { rpc == 0L }) return
        // Stop IO first, so the core shuts its connections down cleanly.
        runCatching {
            lock.read {
                if (rpc != 0L) {
                    DeltaChatNative.jsonrpcCall(
                        rpc,
                        """{"jsonrpc":"2.0","id":0,"method":"stop_io_for_all_accounts","params":[]}"""
                            .toByteArray(),
                    )
                }
            }
        }
        lock.write {
            if (rpc == 0L) return
            DeltaChatNative.jsonrpcUnref(rpc)
            DeltaChatNative.accountsUnref(accounts)
            rpc = 0L
            accounts = 0L
        }
        LogUtil.i(TAG, "Delta Chat core released")
    }

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, item) -> put(key.toString(), toJson(item)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
        else -> value.toString()
    }

    private companion object {
        const val TAG = "DeltaChatRpc"
        const val IDLE_STOP_MILLIS = 60_000L
    }
}

/** DC_EVENT_* constants from deltachat.h that Twill uses. */
internal object DeltaChatEvents {
    const val MSGS_CHANGED = 2000
    const val REACTIONS_CHANGED = 2001
    const val INCOMING_MSG = 2005
    const val INCOMING_MSG_BUNCH = 2006
    const val MSGS_NOTICED = 2008
    const val MSG_DELIVERED = 2010
    const val MSG_FAILED = 2012
    const val MSG_READ = 2015
    const val MSG_DELETED = 2016
    const val CHAT_MODIFIED = 2020
    const val CHAT_DELETED = 2023
    const val CONTACTS_CHANGED = 2030
    const val CONFIGURE_PROGRESS = 2041
    const val SELFAVATAR_CHANGED = 2110
    const val CONFIG_SYNCED = 2111
    const val CHATLIST_CHANGED = 2300
    const val CHATLIST_ITEM_CHANGED = 2301
}
