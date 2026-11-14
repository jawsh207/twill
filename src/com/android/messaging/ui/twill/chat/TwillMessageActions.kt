package com.android.messaging.ui.twill.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.messaging.R
import com.android.messaging.twill.TwillMessage
import com.android.messaging.twill.TwillReaction
import java.text.BreakIterator

// Replies and reactions, as in Zino: long-press a message for a quick reaction, any emoji,
// Reply or Copy text. Replies quote the message they answer; reactions sit under the bubble.

private val QUICK_REACTIONS = listOf("💜", "👍", "😂", "😮", "😢", "🔥")

private val EMOJI = listOf(
    "💜", "❤️", "🧡", "💛", "💚", "💙", "🖤", "🤍", "👍", "👎", "🤙", "🙌", "👏", "🫡", "🙏", "💪",
    "😂", "🤣", "😅", "😊", "😍", "🥰", "😘", "😎", "🤔", "🫠", "😮", "😢", "😭", "😡", "🤯", "🥳",
    "🔥", "⚡", "✨", "💯", "🎉", "🚀", "👀", "💎", "🌱", "☕", "🍻", "🍕", "🐸", "🦤", "🌽", "₿",
)

/** Long press on a message. Reactions and Reply only where the protocol has them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TwillMessageActions(
    canReplyAndReact: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onMoreReactions: () -> Unit,
    onReply: () -> Unit,
    onCopy: (() -> Unit)?,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canReplyAndReact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        ReactionCircle(onClick = { onReact(emoji) }) {
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                    ReactionCircle(onClick = onMoreReactions) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.twill_more_reactions),
                        )
                    }
                }
                ActionRow(
                    label = stringResource(R.string.twill_reply),
                    icon = { Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null) },
                    onClick = onReply,
                )
            }
            onCopy?.let {
                ActionRow(
                    label = stringResource(R.string.twill_copy_text),
                    icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                    onClick = it,
                )
            }
        }
    }
}

@Composable
private fun ReactionCircle(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ActionRow(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = icon,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Any emoji: a grid of common ones, or type your own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TwillEmojiSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var other by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.twill_react),
                style = MaterialTheme.typography.titleLarge,
            )
            LazyVerticalGrid(columns = GridCells.Fixed(8), modifier = Modifier.fillMaxWidth()) {
                items(EMOJI) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPick(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 24.sp) }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = other,
                    onValueChange = { other = it },
                    label = { Text(stringResource(R.string.twill_any_emoji)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onPick(firstGrapheme(other.trim())) },
                    enabled = other.isNotBlank(),
                ) { Text(stringResource(R.string.twill_react)) }
            }
        }
    }
}

/** One emoji only, even one built from several characters (flags, skin tones). */
private fun firstGrapheme(text: String): String {
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(text)
    val end = breaks.next()
    return if (end == BreakIterator.DONE) text else text.substring(0, end)
}

/**
 * The message being answered, small: who wrote it and how it began, on its own card so it
 * reads the same on any bubble. [message] is null when it isn't loaded or known here.
 */
@Composable
internal fun TwillReplyQuote(
    message: TwillMessage?,
    senderName: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** Long-pressing the quote acts on the message it sits in, like the rest of the bubble. */
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceContainer)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier
                },
            )
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(colors.primary),
        )
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                text = when {
                    message == null -> stringResource(R.string.twill_earlier_message)
                    message.isOutgoing -> stringResource(R.string.twill_you)
                    else -> senderName ?: stringResource(R.string.twill_earlier_message)
                },
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (message !=
                    null
                ) {
                    quoteText(message)
                } else {
                    stringResource(R.string.twill_not_loaded)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun quoteText(message: TwillMessage): String {
    val attachment = message.attachment
    return when {
        message.text.isNotBlank() -> message.text.replace('\n', ' ')
        attachment?.isImage == true -> stringResource(R.string.twill_photo)
        else -> stringResource(R.string.nostr_file)
    }
}

/** Each emoji once, with how many sent it; yours stands out. Tapping adds yours. */
@Composable
internal fun TwillReactionChips(
    reactions: List<TwillReaction>,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { reaction ->
            Surface(
                onClick = { onReact(reaction.emoji) },
                shape = RoundedCornerShape(12.dp),
                color = if (reaction.isMine) {
                    colors.secondaryContainer
                } else {
                    colors.surfaceContainerHigh
                },
            ) {
                Text(
                    text = if (reaction.count > 1) {
                        "${reaction.emoji} ${reaction.count}"
                    } else {
                        reaction.emoji
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Above the composer while writing a reply: what it answers, and a way to cancel. */
@Composable
internal fun TwillReplyBar(
    message: TwillMessage,
    senderName: String?,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TwillReplyQuote(
                message = message,
                senderName = senderName,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.twill_cancel_reply),
                )
            }
        }
    }
}
