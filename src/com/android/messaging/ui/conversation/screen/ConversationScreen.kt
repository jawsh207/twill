package com.android.messaging.ui.conversation.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.data.conversation.model.ConversationId
import com.android.messaging.data.conversation.model.MessageId
import com.android.messaging.data.conversation.model.ParticipantId
import com.android.messaging.data.conversation.model.draft.ConversationDraft
import com.android.messaging.twill.SmsMessageSearch
import com.android.messaging.ui.common.components.snackbar.MessagingSnackbarHost
import com.android.messaging.ui.contact.model.AddContactRequest
import com.android.messaging.ui.conversation.composer.ui.ConversationComposerSection
import com.android.messaging.ui.conversation.composer.ui.ConversationSimSelectorSheet
import com.android.messaging.ui.conversation.composer.ui.DelayedSendUi
import com.android.messaging.ui.conversation.composer.ui.LocalDelayedSendUi
import com.android.messaging.ui.conversation.composer.ui.LocalSendLongPressActions
import com.android.messaging.ui.conversation.composer.ui.SendLongPressAction
import com.android.messaging.ui.conversation.mediapicker.rememberConversationMediaPickerPermissionState
import com.android.messaging.ui.conversation.mediapicker.rememberConversationMediaPickerState
import com.android.messaging.ui.conversation.metadata.ui.ConversationTopAppBar
import com.android.messaging.ui.conversation.scheduled.LocalConversationTopBarExtraActions
import com.android.messaging.ui.conversation.scheduled.LocalScheduledMessagesController
import com.android.messaging.ui.conversation.scheduled.ScheduleMessageDialog
import com.android.messaging.ui.conversation.scheduled.ScheduledMessagesListDialog
import com.android.messaging.ui.conversation.scheduled.ScheduledMessagesTopBarAction
import com.android.messaging.ui.conversation.scheduled.rememberScheduledMessagesFor
import com.android.messaging.ui.conversation.screen.model.ConversationPendingLaunchPayload
import com.android.messaging.ui.conversation.screen.model.ConversationScreenScaffoldUiState
import com.android.messaging.ui.photoviewer.model.PhotoViewerLaunchRequest
import com.android.messaging.ui.twill.search.LocalTwillSearch
import com.android.messaging.ui.twill.search.TwillSearchAction
import com.android.messaging.ui.twill.search.TwillSearchTopBar
import com.android.messaging.ui.twill.search.rememberTwillConversationSearch
import com.android.messaging.util.UiUtils

@Composable
@Suppress("LongMethod") // Long arguments list, not too much logic, so it's ok to suppress
internal fun ConversationScreen(
    screenModel: ConversationScreenModel,
    modifier: Modifier = Modifier,
    conversationId: ConversationId? = null,
    cancelIncomingNotification: Boolean = true,
    offersArchiveUndo: Boolean = false,
    onAddPeopleClick: () -> Unit,
    onConversationDetailsClick: () -> Unit,
    onNavigateToMessageDetails: (messageId: MessageId) -> Unit,
    onNavigateToVCardDetail: (uri: String) -> Unit,
    onNavigateToPhotoViewer: (PhotoViewerLaunchRequest) -> Unit,
    onNavigateToAddContact: (AddContactRequest) -> Unit,
    onNavigateToForward: (messageId: MessageId) -> Unit,
    onNavigateBack: () -> Unit,
    onCloseConversation: () -> Unit,
    pendingLaunchPayload: ConversationPendingLaunchPayload,
    onPendingDraftConsumed: () -> Unit,
    onPendingScrollMessageIdConsumed: () -> Unit,
    onPendingSelfParticipantIdConsumed: () -> Unit,
    onPendingStartupAttachmentConsumed: () -> Unit,
) {
    val messageFieldFocusRequester = remember { FocusRequester() }
    val mediaPickerState = rememberConversationMediaPickerState()
    val scaffoldUiState by screenModel.scaffoldUiState.collectAsStateWithLifecycle()
    val mediaPickerOverlayUiState by screenModel
        .mediaPickerOverlayUiState
        .collectAsStateWithLifecycle()

    val permissionState = rememberConversationMediaPickerPermissionState()

    val hostBoundsState = remember { mutableStateOf<ComposeRect?>(value = null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val onOpenContactPicker = rememberOpenContactPickerCallback(screenModel = screenModel)
    val requestAudioRecordingStart = rememberAudioRecordingStartRequest(
        screenModel = screenModel,
        permissionState = permissionState,
    )

    ConversationScreenRouteEffects(
        conversationId = conversationId,
        cancelIncomingNotification = cancelIncomingNotification,
        offersArchiveUndo = offersArchiveUndo,
        pendingLaunchPayload = pendingLaunchPayload,
        scaffoldUiState = scaffoldUiState,
        snackbarHostState = snackbarHostState,
        hostBoundsState = hostBoundsState,
        permissionState = permissionState,
        screenModel = screenModel,
        onNavigateToMessageDetails = onNavigateToMessageDetails,
        onNavigateToVCardDetail = onNavigateToVCardDetail,
        onNavigateToPhotoViewer = onNavigateToPhotoViewer,
        onNavigateToAddContact = onNavigateToAddContact,
        onNavigateToForward = onNavigateToForward,
        onCloseConversation = onCloseConversation,
        onPendingDraftConsumed = onPendingDraftConsumed,
        onPendingSelfParticipantIdConsumed = onPendingSelfParticipantIdConsumed,
        onPendingStartupAttachmentConsumed = onPendingStartupAttachmentConsumed,
    )

    ConversationScreenSurface(
        modifier = modifier,
        conversationId = conversationId,
        scaffoldUiState = scaffoldUiState,
        mediaPickerOverlayUiState = mediaPickerOverlayUiState,
        mediaPickerState = mediaPickerState,
        snackbarHostState = snackbarHostState,
        messageFieldFocusRequester = messageFieldFocusRequester,
        pendingScrollMessageId = pendingLaunchPayload.scrollMessageId,
        onPendingScrollMessageIdConsumed = onPendingScrollMessageIdConsumed,
        onAddPeopleClick = onAddPeopleClick,
        onConversationDetailsClick = onConversationDetailsClick,
        onNavigateBack = onNavigateBack,
        onHostBoundsChanged = { hostBounds ->
            hostBoundsState.value = hostBounds
        },
        onOpenContactPicker = onOpenContactPicker,
        onAudioRecordingStartRequest = {
            requestAudioRecordingStart(AudioRecordingStartMode.Unlocked)
        },
        onLockedAudioRecordingStartRequest = {
            requestAudioRecordingStart(AudioRecordingStartMode.Locked)
        },
        screenModel = screenModel,
    )
}

@Composable
@Suppress("LongMethod") // Long arguments list, not too much logic, so it's ok to suppress
internal fun ConversationScreenScaffold(
    modifier: Modifier = Modifier,
    conversationId: ConversationId?,
    uiState: ConversationScreenScaffoldUiState,
    snackbarHostState: SnackbarHostState,
    isMediaPickerOpen: Boolean,
    messageFieldFocusRequester: FocusRequester,
    pendingScrollMessageId: MessageId?,
    onPendingScrollMessageIdConsumed: () -> Unit,
    onAddPeopleClick: () -> Unit,
    onConversationDetailsClick: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenContactPicker: () -> Unit,
    onOpenMediaPicker: () -> Unit,
    onAudioRecordingStartRequest: () -> Unit,
    onLockedAudioRecordingStartRequest: () -> Unit,
    screenModel: ConversationScreenModel,
) {
    val isSimSelectorAvailable = uiState.composer.simSelector.isAvailable
    val simSheetState = rememberConversationSimSheetState(isAvailable = isSimSelectorAvailable)
    val showSimSelectorSheet = rememberShowSimSelectorSheetCallback(
        simSheetState = simSheetState,
        isAvailable = isSimSelectorAvailable,
    )
    val scheduledMessagesController = LocalScheduledMessagesController.current
    var isScheduleDialogVisible by rememberSaveable { mutableStateOf(false) }
    val canScheduleMessages = scheduledMessagesController != null &&
        conversationId != null &&
        uiState.composer.isMessageFieldEnabled

    val scheduleLabel = stringResource(R.string.schedule_message_menu)
    val switchSimLabel = stringResource(R.string.conversation_switch_sims)
    val twillLongPressActions = remember(
        canScheduleMessages,
        isSimSelectorAvailable,
        showSimSelectorSheet,
        scheduleLabel,
        switchSimLabel,
    ) {
        buildList {
            if (canScheduleMessages) {
                add(
                    SendLongPressAction(
                        label = scheduleLabel,
                        icon = Icons.Rounded.Schedule,
                        onClick = { isScheduleDialogVisible = true },
                    ),
                )
            }
            if (isSimSelectorAvailable) {
                add(
                    SendLongPressAction(
                        label = switchSimLabel,
                        icon = Icons.Rounded.SimCard,
                        onClick = showSimSelectorSheet,
                    ),
                )
            }
        }
    }
    // Only the real app route provides the scheduler; previews and tests keep upstream behaviour.
    val sendLongPressActions = twillLongPressActions.takeIf { scheduledMessagesController != null }

    val delayedSendState by screenModel.delayedSend.collectAsStateWithLifecycle()
    val delayedSendUi = delayedSendState
        ?.takeIf { state -> state.conversationId == conversationId }
        ?.let { state ->
            DelayedSendUi(
                startedAtElapsedMillis = state.startedAtElapsedMillis,
                durationMillis = state.durationMillis,
                onCancel = screenModel::onUndoDelayedSendClick,
            )
        }

    var isScheduledListVisible by rememberSaveable { mutableStateOf(false) }
    val scheduledHere = when {
        scheduledMessagesController != null && conversationId != null -> {
            rememberScheduledMessagesFor(
                controller = scheduledMessagesController,
                conversationId = conversationId,
            )
        }

        else -> emptyList()
    }
    val scheduledCount = scheduledHere.size
    // Twill: search this conversation (real app route only, like scheduling).
    val search = rememberTwillConversationSearch(key = conversationId) { query ->
        conversationId?.let { SmsMessageSearch.search(it.value, query) }.orEmpty()
    }
    val topBarExtraActions: (@Composable () -> Unit)? =
        if (scheduledMessagesController != null) {
            {
                TwillSearchAction(onClick = search::open)
                ScheduledMessagesTopBarAction(
                    count = scheduledCount,
                    onClick = { isScheduledListVisible = true },
                )
            }
        } else {
            null
        }

    CompositionLocalProvider(
        LocalSendLongPressActions provides sendLongPressActions,
        LocalDelayedSendUi provides delayedSendUi,
        LocalConversationTopBarExtraActions provides topBarExtraActions,
        LocalTwillSearch provides search.takeIf { it.isActive },
    ) {
        Scaffold(
            modifier = modifier,
            snackbarHost = { MessagingSnackbarHost(hostState = snackbarHostState) },
            topBar = {
                if (search.isActive && !uiState.selection.isSelectionMode) {
                    TwillSearchTopBar(search = search)
                } else {
                    ConversationScreenTopBar(
                        uiState = uiState,
                        onAddPeopleClick = onAddPeopleClick,
                        onConversationDetailsClick = onConversationDetailsClick,
                        onNavigateBack = onNavigateBack,
                        onSimSelectorClick = showSimSelectorSheet,
                        screenModel = screenModel,
                    )
                }
            },
            bottomBar = {
                ConversationScreenBottomBar(
                    uiState = uiState,
                    isMediaPickerOpen = isMediaPickerOpen,
                    messageFieldFocusRequester = messageFieldFocusRequester,
                    onOpenContactPicker = onOpenContactPicker,
                    onOpenMediaPicker = onOpenMediaPicker,
                    onAudioRecordingStartRequest = onAudioRecordingStartRequest,
                    onLockedAudioRecordingStartRequest = onLockedAudioRecordingStartRequest,
                    onSendActionLongClick = showSimSelectorSheet,
                    screenModel = screenModel,
                )
            },
        ) { contentPadding ->
            ConversationScreenContent(
                modifier = Modifier.fillMaxSize(),
                conversationId = conversationId,
                uiState = uiState,
                snackbarHostState = snackbarHostState,
                contentPadding = contentPadding,
                pendingScrollMessageId = pendingScrollMessageId,
                onPendingScrollMessageIdConsumed = onPendingScrollMessageIdConsumed,
                onAttachmentClick = screenModel::onMessageAttachmentClicked,
                onExternalUriClick = screenModel::onExternalUriClicked,
                onLoadOlderMessages = screenModel::onLoadOlderMessages,
                onMessageClick = screenModel::onMessageClick,
                onMessageAvatarClick = screenModel::onMessageAvatarClick,
                onMessageDownloadClick = screenModel::onMessageDownloadClick,
                onMessageActionClick = screenModel::onMessageActionClick,
                onMessageLongClick = screenModel::onMessageLongClick,
                onMessageResendClick = screenModel::onMessageResendClick,
                onSimSelectorClick = showSimSelectorSheet,
                onUnblockClick = screenModel::onUnblockClick,
            )
        }
    }

    ConversationScreenOverlays(
        simSheetState = simSheetState,
        uiState = uiState,
        screenModel = screenModel,
    )

    // Close the list once the last scheduled message is cancelled or sent.
    LaunchedEffect(scheduledCount) {
        if (scheduledCount == 0) {
            isScheduledListVisible = false
        }
    }

    if (isScheduledListVisible &&
        scheduledMessagesController != null &&
        scheduledHere.isNotEmpty()
    ) {
        ScheduledMessagesListDialog(
            messages = scheduledHere,
            onCancel = scheduledMessagesController::cancel,
            onDismiss = { isScheduledListVisible = false },
        )
    }

    if (isScheduleDialogVisible && scheduledMessagesController != null && conversationId != null) {
        val composer = uiState.composer

        ScheduleMessageDialog(
            conversationId = conversationId,
            controller = scheduledMessagesController,
            messageText = composer.messageText,
            subjectText = composer.subjectText,
            selfParticipantId = composer.selfParticipantId,
            hasAttachments = composer.attachments.isNotEmpty() ||
                composer.pendingAttachmentCount > 0,
            onScheduled = {
                isScheduleDialogVisible = false
                // The message now lives in the schedule, so empty the composer.
                screenModel.onSeedDraft(
                    conversationId = conversationId,
                    draft = ConversationDraft(selfParticipantId = composer.selfParticipantId),
                )
                UiUtils.showToastAtBottom(R.string.schedule_message_scheduled_toast)
            },
            onDismiss = { isScheduleDialogVisible = false },
        )
    }
}

@Composable
private fun ConversationScreenOverlays(
    simSheetState: ConversationSimSheetState,
    uiState: ConversationScreenScaffoldUiState,
    screenModel: ConversationScreenModel,
) {
    ConversationScreenDialogs(uiState = uiState, screenModel = screenModel)

    ConversationScreenSimSelectorSheet(
        simSheetState = simSheetState,
        uiState = uiState,
        onSimSelected = screenModel::onSimSelected,
    )
}

@Composable
private fun rememberShowSimSelectorSheetCallback(
    simSheetState: ConversationSimSheetState,
    isAvailable: Boolean,
): () -> Unit {
    return remember(simSheetState, isAvailable) {
        {
            if (isAvailable) {
                simSheetState.show()
            }
        }
    }
}

@Composable
private fun ConversationScreenTopBar(
    uiState: ConversationScreenScaffoldUiState,
    onAddPeopleClick: () -> Unit,
    onConversationDetailsClick: () -> Unit,
    onNavigateBack: () -> Unit,
    onSimSelectorClick: () -> Unit,
    screenModel: ConversationScreenModel,
) {
    when {
        uiState.selection.isSelectionMode -> {
            ConversationSelectionTopAppBar(
                selection = uiState.selection,
                onActionClick = screenModel::onMessageSelectionActionClick,
                onDismissSelection = screenModel::dismissMessageSelection,
            )
        }

        else -> {
            ConversationTopAppBar(
                metadata = uiState.metadata,
                isAddPeopleVisible = uiState.canAddPeople,
                isCallVisible = uiState.canCall,
                isArchiveVisible = uiState.canArchive,
                isUnarchiveVisible = uiState.canUnarchive,
                isAddContactVisible = uiState.canAddContact,
                isDeleteConversationVisible = uiState.canDeleteConversation,
                isShowSubjectFieldVisible = uiState.canEditSubject,
                simSelector = uiState.composer.simSelector,
                onAddPeopleClick = onAddPeopleClick,
                onCallClick = screenModel::onCallClick,
                onArchiveClick = screenModel::onArchiveConversationClick,
                onUnarchiveClick = screenModel::onUnarchiveConversationClick,
                onAddContactClick = screenModel::onAddContactClick,
                onDeleteConversationClick = screenModel::onDeleteConversationClick,
                onShowSubjectFieldClick = screenModel::onShowSubjectFieldClick,
                onSimSelectorClick = onSimSelectorClick,
                onTitleClick = onConversationDetailsClick,
                onNavigateBack = onNavigateBack,
            )
        }
    }
}

@Composable
private fun ConversationScreenBottomBar(
    uiState: ConversationScreenScaffoldUiState,
    isMediaPickerOpen: Boolean,
    messageFieldFocusRequester: FocusRequester,
    onOpenContactPicker: () -> Unit,
    onOpenMediaPicker: () -> Unit,
    onAudioRecordingStartRequest: () -> Unit,
    onLockedAudioRecordingStartRequest: () -> Unit,
    onSendActionLongClick: () -> Unit,
    screenModel: ConversationScreenModel,
) {
    if (isMediaPickerOpen) {
        return
    }

    ConversationComposerSection(
        audioRecording = uiState.composer.audioRecording,
        attachments = uiState.composer.attachments,
        messageText = uiState.composer.messageText,
        messageTextRevision = uiState.composer.messageTextRevision,
        subjectText = uiState.composer.subjectText,
        sendProtocol = uiState.composer.sendProtocol,
        segmentCounter = uiState.composer.segmentCounter,
        isMessageFieldEnabled = uiState.composer.isMessageFieldEnabled,
        isAttachmentActionEnabled = uiState.composer.isAttachmentActionEnabled,
        isRecordActionEnabled = uiState.composer.isRecordActionEnabled,
        isSendActionEnabled = uiState.composer.isSendEnabled,
        shouldShowRecordAction = uiState.composer.shouldShowRecordAction,
        messageFieldFocusRequester = messageFieldFocusRequester,
        onContactAttachClick = onOpenContactPicker,
        onMediaPickerClick = onOpenMediaPicker,
        onMessageTextChange = screenModel::onMessageTextChanged,
        onPendingAttachmentRemove = screenModel::onRemovePendingAttachment,
        onResolvedAttachmentClick = screenModel::onAttachmentClicked,
        onResolvedAttachmentRemove = screenModel::onRemoveResolvedAttachment,
        onAudioRecordingStartRequest = onAudioRecordingStartRequest,
        onLockedAudioRecordingStartRequest = onLockedAudioRecordingStartRequest,
        onAudioRecordingFinish = screenModel::onAudioRecordingFinish,
        onAudioRecordingLock = screenModel::onAudioRecordingLock,
        onAudioRecordingCancel = screenModel::onAudioRecordingCancel,
        onSendClick = screenModel::onSendClick,
        onSendActionLongClick = onSendActionLongClick,
        onSubjectChipClick = screenModel::onShowSubjectFieldClick,
        onSubjectChipClear = screenModel::onSubjectChipClear,
    )
}

@Composable
private fun ConversationScreenSimSelectorSheet(
    simSheetState: ConversationSimSheetState,
    uiState: ConversationScreenScaffoldUiState,
    onSimSelected: (ParticipantId) -> Unit,
) {
    if (!simSheetState.isVisible || !uiState.composer.simSelector.isAvailable) {
        return
    }

    ConversationSimSelectorSheet(
        uiState = uiState.composer.simSelector,
        onSimSelected = { selfParticipantId ->
            onSimSelected(selfParticipantId)
            simSheetState.dismiss()
        },
        onDismissRequest = simSheetState::dismiss,
    )
}
