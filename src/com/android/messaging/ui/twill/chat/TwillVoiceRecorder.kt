package com.android.messaging.ui.twill.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import com.android.messaging.ui.conversation.audio.model.ConversationAudioRecordingPhase
import com.android.messaging.ui.conversation.audio.model.ConversationAudioRecordingUiState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records voice messages for chats whose protocol has them (Delta Chat), driving the same
 * recording UI as SMS: hold the mic to record, slide up to lock, slide left to cancel.
 * Records exactly what Delta Chat for Android records (AAC-LC, mono, 44.1 kHz, 32 kbit/s, in
 * an MPEG-4 .m4a file), so a voice message from Twill looks like one from the official app.
 */
internal class TwillVoiceRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ConversationAudioRecordingUiState())
    val state: StateFlow<ConversationAudioRecordingUiState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var ticker: Job? = null

    /** Starts recording. False if the microphone couldn't be opened. */
    fun start(isLocked: Boolean = false): Boolean {
        if (recorder != null) return true
        val output = File(context.cacheDir, "voice/voice-${System.currentTimeMillis()}.m4a")
            .apply { parentFile?.mkdirs() }
        val started = MediaRecorder(context)
        try {
            started.setAudioSource(MediaRecorder.AudioSource.MIC)
            started.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            started.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            started.setAudioChannels(1)
            started.setAudioSamplingRate(SAMPLE_RATE)
            started.setAudioEncodingBitRate(BIT_RATE)
            started.setMaxDuration(MAX_DURATION_MILLIS)
            started.setOutputFile(output.absolutePath)
            started.prepare()
            started.start()
        } catch (exception: Exception) {
            started.release()
            output.delete()
            return false
        }
        recorder = started
        file = output
        startedAt = SystemClock.elapsedRealtime()
        _state.value = ConversationAudioRecordingUiState(
            phase = ConversationAudioRecordingPhase.Recording,
            isLocked = isLocked,
        )
        ticker = scope.launch {
            while (isActive) {
                _state.value = _state.value.copy(
                    durationMillis = SystemClock.elapsedRealtime() - startedAt,
                )
                delay(TICK_MILLIS)
            }
        }
        return true
    }

    /** Keeps recording without holding the button. */
    fun lock(): Boolean {
        if (recorder == null) return false
        _state.value = _state.value.copy(isLocked = true)
        return true
    }

    /** Stops and returns the recording, or null if it was too short to keep. */
    fun finish(): File? {
        val tooShort = SystemClock.elapsedRealtime() - startedAt < MIN_DURATION_MILLIS
        val recorded = stop(keep = !tooShort)
        return recorded
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        stop(keep = false)
    }

    private fun stop(keep: Boolean): File? {
        val active = recorder ?: return null
        recorder = null
        ticker?.cancel()
        ticker = null
        // stop() throws when nothing usable was recorded (released too quickly).
        val ok = runCatching { active.stop() }.isSuccess
        active.release()
        _state.value = ConversationAudioRecordingUiState()
        val recorded = file
        file = null
        if (!keep || !ok) {
            recorded?.delete()
            return null
        }
        return recorded
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 32_000
        const val MAX_DURATION_MILLIS = 10 * 60 * 1000
        const val MIN_DURATION_MILLIS = 700L
        const val TICK_MILLIS = 200L
    }
}
