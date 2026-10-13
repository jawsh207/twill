package com.android.messaging.deltachat

import android.content.Context
import com.android.messaging.di.core.ApplicationCoroutineScope
import com.android.messaging.util.LogUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** An error returned by the Delta Chat core, with its message (often user-readable). */
internal class DeltaChatRpcException(
    message: String,
) : IOException(message)

/** One core event: [kind] plus its fields, for account [accountId]. */
internal class DeltaChatEvent(
    val accountId: Int,
    val kind: String,
    val fields: JSONObject,
) {
    fun int(name: String): Int? = if (fields.has(name) &&
        !fields.isNull(name)
    ) {
        fields.optInt(name)
    } else {
        null
    }
}

/**
 * Runs the Delta Chat core (chatmail core's `deltachat-rpc-server`, packaged as
 * libdeltachat_rpc_server.so) as a child process and talks JSON-RPC 2.0 to it over
 * stdin/stdout, one JSON object per line. The process starts on the first call and is
 * restarted if it dies. Data lives in the app's private files directory.
 */
@Singleton
internal class DeltaChatRpc @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationCoroutineScope private val appScope: CoroutineScope,
) {
    private val executable = File(context.applicationInfo.nativeLibraryDir, LIBRARY_NAME)

    /** False when this build doesn't include the core (e.g. built without fetching it). */
    val isAvailable: Boolean
        get() = executable.canExecute()

    val accountsDir: File = File(context.filesDir, "deltachat-accounts")

    private val lock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var eventJob: Job? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Any?>>()

    private val _events = MutableSharedFlow<DeltaChatEvent>(extraBufferCapacity = 512)

    /** Every core event, for all accounts. */
    val events: SharedFlow<DeltaChatEvent> = _events.asSharedFlow()

    private val _starts = MutableStateFlow(0)

    /** Counts core (re)starts, so callers can restore state such as running IO. */
    val starts: StateFlow<Int> = _starts.asStateFlow()

    /**
     * Calls [method] with positional [params] and returns the JSON result (JSONObject,
     * JSONArray, String, Number, Boolean or null). Throws [DeltaChatRpcException] on error.
     */
    suspend fun call(method: String, vararg params: Any?): Any? = withContext(Dispatchers.IO) {
        val id = nextId.getAndIncrement()
        val response = CompletableDeferred<Any?>()
        pending[id] = response
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", JSONArray().apply { params.forEach { put(toJson(it)) } })
        try {
            synchronized(lock) {
                val out = ensureStartedLocked()
                out.write(request.toString())
                out.write("\n")
                out.flush()
            }
            response.await()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: DeltaChatRpcException) {
            throw exception
        } catch (exception: Exception) {
            throw DeltaChatRpcException(exception.message ?: exception.javaClass.simpleName)
        } finally {
            pending.remove(id)
        }
    }

    suspend fun callInt(method: String, vararg params: Any?): Int =
        (call(method, *params) as? Number)?.toInt()
            ?: throw DeltaChatRpcException("$method: unexpected result")

    suspend fun callObject(method: String, vararg params: Any?): JSONObject =
        call(method, *params) as? JSONObject
            ?: throw DeltaChatRpcException("$method: unexpected result")

    suspend fun callArray(method: String, vararg params: Any?): JSONArray =
        call(method, *params) as? JSONArray ?: JSONArray()

    /** Starts the core if needed, so events begin to flow. */
    fun start() {
        synchronized(lock) { ensureStartedLocked() }
    }

    private fun ensureStartedLocked(): BufferedWriter {
        writer?.let { if (process?.isAlive == true) return it }
        if (!isAvailable) throw DeltaChatRpcException("Delta Chat isn't included in this build")

        accountsDir.parentFile?.mkdirs()
        val started = ProcessBuilder(executable.absolutePath)
            .directory(context.filesDir)
            .apply {
                environment()["DC_ACCOUNTS_PATH"] = accountsDir.absolutePath
                environment()["RUST_LOG"] = "warn"
            }
            .start()
        process = started
        val out = started.outputStream.bufferedWriter()
        writer = out

        thread(name = "deltachat-rpc-reader", isDaemon = true) { readResponses(started) }
        // stderr must be drained or the core blocks once the pipe buffer is full. Its log
        // can contain addresses, so it is discarded rather than sent to logcat.
        thread(name = "deltachat-rpc-log", isDaemon = true) {
            runCatching {
                started.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
            }
        }
        startEventLoopLocked()
        _starts.value += 1
        return out
    }

    private fun readResponses(source: Process) {
        try {
            source.inputStream.bufferedReader().forEachLine { line ->
                val message = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val id = message.optInt("id", -1)
                val deferred = pending[id] ?: return@forEachLine
                val error = message.optJSONObject("error")
                if (error != null) {
                    deferred.completeExceptionally(
                        DeltaChatRpcException(error.optString("message", "Unknown error")),
                    )
                } else {
                    deferred.complete(message.opt("result").takeUnless { it == JSONObject.NULL })
                }
            }
        } catch (exception: IOException) {
            // The process ended.
        }
        synchronized(lock) {
            if (process === source) {
                process = null
                writer = null
            }
        }
        pending.values.forEach {
            it.completeExceptionally(DeltaChatRpcException("Delta Chat core stopped"))
        }
        pending.clear()
        LogUtil.w(
            TAG,
            "Delta Chat core exited (code ${runCatching {
                source.exitValue()
            }.getOrNull()})"
        )
    }

    /** Long-polls get_next_event and publishes the results. Restarts the core if it died. */
    private fun startEventLoopLocked() {
        if (eventJob?.isActive == true) return
        eventJob = appScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val event = call("get_next_event") as? JSONObject ?: continue
                    val inner = event.optJSONObject("event") ?: continue
                    _events.emit(
                        DeltaChatEvent(
                            accountId = event.optInt("contextId"),
                            kind = inner.optString("kind"),
                            fields = inner,
                        ),
                    )
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    delay(RESTART_DELAY_MILLIS)
                }
            }
        }
    }

    /** Stops the core (closing stdin makes it exit cleanly). */
    fun stop() {
        synchronized(lock) {
            eventJob?.cancel()
            eventJob = null
            runCatching { writer?.close() }
            writer = null
            process?.let { running ->
                thread(isDaemon = true) {
                    if (!running.waitFor(
                            STOP_TIMEOUT_SECONDS,
                            java.util.concurrent.TimeUnit.SECONDS
                        )
                    ) {
                        running.destroy()
                    }
                }
            }
            process = null
        }
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
        const val LIBRARY_NAME = "libdeltachat_rpc_server.so"
        const val RESTART_DELAY_MILLIS = 2_000L
        const val STOP_TIMEOUT_SECONDS = 5L
    }
}
