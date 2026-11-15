package com.android.messaging.ui.twill.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.domain.conversation.usecase.draft.model.ConversationDraftSendProtocol
import com.android.messaging.ui.conversation.composer.model.ComposerAttachmentUiModel
import com.android.messaging.ui.conversation.composer.ui.ConversationAttachmentPreview
import com.android.messaging.ui.conversation.composer.ui.ConversationComposeAttachmentItems
import com.android.messaging.ui.conversation.composer.ui.ConversationComposeBar
import java.io.File
import kotlinx.collections.immutable.persistentListOf

/**
 * The SMS compose bar, for Nostr and Delta Chat chats: the same field, ⊕ attachment menu and
 * round button. Where the protocol has voice messages (Delta Chat), the button is a mic while
 * the field is empty, used as in SMS (hold to record, slide up to lock, slide away to cancel);
 * a finished recording is attached above the field, as in SMS, and goes out with Send.
 * Elsewhere (Nostr) it is always the send button.
 */
@Composable
internal fun TwillComposeBar(
    text: String,
    textRevision: Int,
    isEnabled: Boolean,
    canSendVoice: Boolean,
    onTextChange: (text: String, revision: Int) -> Unit,
    /** Sends the text, with the recorded voice message when there is one. */
    onSend: (voice: File?) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember(context) { TwillVoiceRecorder(context.applicationContext, scope) }
    val recording by recorder.state.collectAsStateWithLifecycle()
    // As in SMS, a finished recording is attached, not sent: Send sends it.
    var voice by remember { mutableStateOf<Pair<File, Long>?>(null) }
    DisposableEffect(recorder) {
        onDispose {
            recorder.cancel()
            voice?.first?.delete()
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, R.string.twill_voice_needs_mic, Toast.LENGTH_SHORT).show()
        }
    }
    val hasMic = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val startRecording: (Boolean) -> Unit = { locked ->
        when {
            !hasMic() -> permission.launch(Manifest.permission.RECORD_AUDIO)
            !recorder.start(isLocked = locked) ->
                Toast.makeText(context, R.string.twill_voice_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val finishRecording = {
        val duration = recording.durationMillis
        recorder.finish()?.let { file ->
            voice?.first?.delete()
            voice = file to duration
        }
    }

    Column {
        voice?.let { (file, duration) ->
            ConversationAttachmentPreview(
                attachments = persistentListOf(
                    ComposerAttachmentUiModel.Resolved.Audio(
                        key = file.path,
                        contentType = "audio/mp4",
                        contentUri = Uri.fromFile(file).toString(),
                        durationMillis = duration,
                    ),
                ),
                onPendingAttachmentRemove = {},
                onResolvedAttachmentClick = {},
                onResolvedAttachmentRemove = {
                    file.delete()
                    voice = null
                },
            )
        }
        ConversationComposeBar(
            audioRecording = recording,
            messageText = text,
            messageTextRevision = textRevision,
            subjectText = "",
            sendProtocol = ConversationDraftSendProtocol.SMS,
            segmentCounter = null,
            isMessageFieldEnabled = isEnabled,
            isAttachmentActionEnabled = isEnabled,
            isRecordActionEnabled = isEnabled && canSendVoice,
            isSendActionEnabled = isEnabled && (text.isNotBlank() || voice != null),
            shouldShowRecordAction = canSendVoice && isEnabled && text.isBlank() && voice == null,
            onContactAttachClick = {},
            onMediaPickerClick = onPickPhoto,
            onLockedAudioRecordingStartRequest = { startRecording(true) },
            onMessageTextChange = onTextChange,
            onAudioRecordingStartRequest = { startRecording(false) },
            onAudioRecordingFinish = { finishRecording() },
            onAudioRecordingLock = recorder::lock,
            onAudioRecordingCancel = recorder::cancel,
            onSendClick = {
                val attached = voice?.first
                voice = null
                onSend(attached)
            },
            onSendActionLongClick = {},
            onSubjectChipClick = {},
            onSubjectChipClear = {},
            attachmentItems = ConversationComposeAttachmentItems(
                isAudioVisible = canSendVoice,
                isContactVisible = false,
                isFileVisible = true,
            ),
            onFileAttachClick = onPickFile,
        )
    }
}
