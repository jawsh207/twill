package com.android.messaging.ui.conversation.composer.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/** One option in the menu shown when the send button is long-pressed. */
@Immutable
internal data class SendLongPressAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * Twill's long-press options for the send button (schedule message, switch SIM). When null, the
 * send button keeps upstream behaviour (long press opens the SIM selector). With one option the
 * long press runs it directly; with several, a small menu is shown.
 */
internal val LocalSendLongPressActions =
    staticCompositionLocalOf<List<SendLongPressAction>?> { null }

/**
 * A message held by "Delay sending". While set, the send button shows a countdown ring and an
 * X; tapping it calls [onCancel].
 */
@Immutable
internal data class DelayedSendUi(
    val startedAtElapsedMillis: Long,
    val durationMillis: Long,
    val onCancel: () -> Unit,
)

internal val LocalDelayedSendUi = compositionLocalOf<DelayedSendUi?> { null }
