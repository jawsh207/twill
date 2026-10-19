package com.android.messaging.ui.twill.inbox

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Long press on the conversation list's "Start chat" button: choose which service to start
 * the chat in (SMS/MMS, Nostr, Delta Chat...). A normal press keeps starting an SMS/MMS
 * conversation. Null, so no long press, when there is nothing to choose from.
 */
@Composable
internal fun rememberStartChatLongClick(): (() -> Unit)? {
    val inbox = LocalTwillInbox.current ?: return null
    val state by inbox.controller.uiState.collectAsStateWithLifecycle()
    val onLongClick = remember(inbox) { { inbox.isNewChatPickerVisible.value = true } }
    return if (state.sections.isEmpty()) null else onLongClick
}

/**
 * Adds a long press to a button that already handles taps. Once the long press fires, the
 * rest of the gesture is consumed before the button sees it, so the tap doesn't fire too.
 */
@Composable
internal fun Modifier.twillLongPress(
    label: String,
    onLongClick: (() -> Unit)?,
): Modifier {
    if (onLongClick == null) return this
    val currentOnLongClick by rememberUpdatedState(newValue = onLongClick)
    val haptics = LocalHapticFeedback.current
    return this
        .semantics(mergeDescendants = true) {
            onLongClick(label = label) {
                currentOnLongClick()
                true
            }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                awaitLongPressOrCancellation(pointerId = down.id) ?: return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnLongClick()
                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
}
