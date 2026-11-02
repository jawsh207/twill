package com.android.messaging.ui.blockedparticipants.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.messaging.R
import com.android.messaging.data.conversation.model.ConversationId
import com.android.messaging.data.conversation.model.ParticipantId
import com.android.messaging.ui.blockedparticipants.common.BlockedParticipantItem
import com.android.messaging.ui.blockedparticipants.common.BlockedParticipantsTopAppBar
import com.android.messaging.ui.blockedparticipants.common.ScreenContentPadding
import com.android.messaging.ui.blockedparticipants.screen.model.BlockedParticipantUiState
import com.android.messaging.ui.blockedparticipants.screen.model.BlockedParticipantsAction as Action
import com.android.messaging.ui.blockedparticipants.screen.model.BlockedParticipantsNavEvent as NavEvent
import com.android.messaging.ui.blockedparticipants.screen.model.BlockedParticipantsUiState as State
import com.android.messaging.ui.common.components.safeDrawingContentPadding
import com.android.messaging.ui.conversationlist.common.status.ConversationListStatusMessage
import com.android.messaging.ui.core.CollectEvents
import com.android.messaging.ui.core.MessagingPreviewTheme
import com.android.messaging.ui.twill.inbox.LocalTwillCardShape
import com.android.messaging.ui.twill.inbox.TwillCards
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

@Composable
internal fun BlockedParticipantsScreen(
    screenModel: BlockedParticipantsScreenModel,
    effectHandler: BlockedParticipantsEffectHandler,
    onNavigateBack: () -> Unit,
    onNavigateToConversation: (ConversationId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by screenModel.uiState.collectAsStateWithLifecycle()

    CollectEvents(
        events = screenModel.effects,
        onEvent = effectHandler::handle,
    )

    CollectEvents(events = screenModel.navigationEvents) { event ->
        when (event) {
            is NavEvent.CloseAfterLastUnblock -> onNavigateBack()
            is NavEvent.OpenParticipantChat -> onNavigateToConversation(event.conversationId)
        }
    }

    BlockedParticipantsContent(
        uiState = uiState,
        onAction = screenModel::onAction,
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockedParticipantsContent(
    uiState: State,
    onAction: (Action) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            BlockedParticipantsTopAppBar(
                onNavigateBack = onNavigateBack,
                selectedCount = uiState.selectedParticipantIds.size,
                onClearSelectionClick = { onAction(Action.ClearSelectionClicked) },
                onDeleteClick = { showDeleteConfirmation = true },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Twill: rows are cards on the page colour, like the inbox (see TwillCards).
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            when {
                uiState.isLoading -> Unit

                // Twill: the same empty-state card as the archived list.
                uiState.participants.isEmpty() -> {
                    ConversationListStatusMessage(
                        text = stringResource(R.string.blocked_contacts_empty),
                        modifier = Modifier.padding(
                            bottom = contentPadding.calculateBottomPadding(),
                        ),
                    )
                }

                else -> {
                    BlockedParticipantsList(
                        uiState = uiState,
                        onAction = onAction,
                        scaffoldContentPadding = contentPadding,
                    )
                }
            }
        }
    }

    BlockedParticipantsDialogs(
        selectedCount = uiState.selectedParticipantIds.size,
        onAction = onAction,
        showDeleteConfirmation = showDeleteConfirmation,
        onDismissDeleteConfirmation = { showDeleteConfirmation = false },
    )
}

@Composable
private fun BlockedParticipantsList(
    uiState: State,
    onAction: (Action) -> Unit,
    scaffoldContentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = safeDrawingContentPadding(
            top = ScreenContentPadding,
            bottom = ScreenContentPadding + scaffoldContentPadding.calculateBottomPadding(),
            horizontal = TwillCards.SideMargin,
        ),
        verticalArrangement = Arrangement.spacedBy(TwillCards.RowGap),
    ) {
        itemsIndexed(
            items = uiState.participants,
            key = { _, participant -> participant.participantId.value },
        ) { index, participant ->
            CompositionLocalProvider(
                LocalTwillCardShape provides TwillCards.shape(index, uiState.participants.size),
            ) {
                val destination = participant.normalizedDestination.orEmpty()
                val hasDestination = destination.isNotEmpty()
                val isSelected = participant.participantId in uiState.selectedParticipantIds
                val inSelectionMode = uiState.selectedParticipantIds.isNotEmpty()

                BlockedParticipantItem(
                    participant = participant,
                    isSelected = isSelected,
                    inSelectionMode = inSelectionMode,
                    onClick = {
                        onAction(Action.ParticipantClicked(participant.participantId))
                    },
                    onLongClick = {
                        onAction(Action.ParticipantLongClicked(participant.participantId))
                    },
                    onUnblockClick = {
                        if (hasDestination) {
                            onAction(Action.UnblockClicked(destination))
                        }
                    },
                    onMessageClick = {
                        onAction(Action.ParticipantMessageClicked(participant.conversationId))
                    },
                    onCallClick = {
                        onAction(Action.ParticipantCallClicked(destination))
                    }.takeIf { participant.canCall },
                    onContactClick = {
                        onAction(Action.ParticipantContactInfoClicked(participant))
                    }.takeIf { participant.canShowContact },
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun BlockedParticipantsContentPreview() {
    MessagingPreviewTheme {
        BlockedParticipantsContent(
            uiState = State(
                isLoading = false,
                participants = persistentListOf(
                    BlockedParticipantUiState(
                        participantId = ParticipantId("1"),
                        conversationId = ConversationId("c1"),
                        avatarUri = null,
                        displayName = "Spam Caller",
                        details = "+31 6 1234 5678",
                        contactId = 1L,
                        lookupKey = null,
                        normalizedDestination = "+31612345678",
                        canCall = true,
                        canShowContact = true,
                        isContactSaved = true,
                    ),
                    BlockedParticipantUiState(
                        participantId = ParticipantId("2"),
                        conversationId = ConversationId("c2"),
                        avatarUri = null,
                        displayName = "+31 6 0000 1111",
                        details = null,
                        contactId = -1L,
                        lookupKey = null,
                        normalizedDestination = "+31600001111",
                        canCall = true,
                        canShowContact = true,
                        isContactSaved = false,
                    ),
                ),
                selectedParticipantIds = persistentSetOf(ParticipantId("2")),
            ),
            onAction = {},
            onNavigateBack = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun BlockedParticipantsEmptyPreview() {
    MessagingPreviewTheme {
        BlockedParticipantsContent(
            uiState = State(isLoading = false),
            onAction = {},
            onNavigateBack = {},
        )
    }
}
