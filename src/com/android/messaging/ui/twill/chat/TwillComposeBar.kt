package com.android.messaging.ui.twill.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.domain.conversation.usecase.draft.model.ConversationDraftSendProtocol
import com.android.messaging.ui.conversation.composer.ui.ConversationComposeAttachmentItems
import com.android.messaging.ui.conversation.composer.ui.ConversationComposeBar
import java.io.File

/**
 * The SMS compose bar, for Nostr and Delta Chat chats: the same field, ⊕ attachment menu and
 * round button. Where the protocol has voice messages (Delta Chat), the button is a mic while
 * the field is empty, used as in SMS (hold to record, slide up to lock, slide away to cancel),
 * and a recording is sent when released. Elsewhere (Nostr) it is always the send button.
 */
@Composable
internal fun TwillComposeBar(
    text: String,
    textRevision: Int,
    isEnabled: Boolean,
    canSendVoice: Boolean,
    onTextChange: (text: String, revision: Int) -> Unit,
    onSend: () -> Unit,
    onSendVoice: (File) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember(context) { TwillVoiceRecorder(context.applicationContext, scope) }
    val recording by recorder.state.collectAsStateWithLifecycle()
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }

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
    val finishRecording = { recorder.finish()?.let(onSendVoice) }

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
        isSendActionEnabled = isEnabled && text.isNotBlank(),
        shouldShowRecordAction = canSendVoice && isEnabled && text.isBlank(),
        onContactAttachClick = {},
        onMediaPickerClick = onPickPhoto,
        onLockedAudioRecordingStartRequest = { startRecording(true) },
        onMessageTextChange = onTextChange,
        onAudioRecordingStartRequest = { startRecording(false) },
        onAudioRecordingFinish = { finishRecording() },
        onAudioRecordingLock = recorder::lock,
        onAudioRecordingCancel = recorder::cancel,
        onSendClick = onSend,
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
