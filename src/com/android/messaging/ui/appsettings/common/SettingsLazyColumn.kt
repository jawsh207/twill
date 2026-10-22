package com.android.messaging.ui.appsettings.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Marks a settings list item that only separates groups (an untitled category). Its content
 * isn't drawn: the gap between the cards is the separator.
 */
internal object SettingsGroupBreak

/** Marks a settings list item drawn on the page, above a group (a category title). */
internal object SettingsGroupTitle

private val SideMargin = 16.dp
private val GroupGap = 16.dp
private val RowGap = 3.dp
private val OuterCorner = 24.dp
private val InnerCorner = 4.dp

/**
 * A settings list laid out like GrapheneOS Settings: rows grouped into rounded cards
 * (system surface bright) on the page colour, 16dp from the sides, 3dp apart, with 24dp
 * corners at the top and bottom of each group and 4dp corners between rows.
 *
 * Fill it like a [LazyColumn]. Every item is a row, except items whose `contentType` is
 * [SettingsGroupBreak] (ends the group; not drawn) or [SettingsGroupTitle] (drawn on the page
 * and starts a new group). Rows clip their own click ripples to their card shape.
 */
@Composable
internal fun SettingsLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit,
) {
    val entries = SettingsListScope().apply(content).entries
    val positions = rowPositions(entries)

    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        entries.forEachIndexed { index, entry ->
            when (entry.kind) {
                EntryKind.Break -> Unit

                EntryKind.Title -> item(key = entry.key, contentType = entry.contentType) {
                    entry.content(this)
                }

                EntryKind.Row -> item(key = entry.key, contentType = entry.contentType) {
                    SettingsCardRow(position = positions.getValue(index)) {
                        entry.content(this@item)
                    }
                }
            }
        }
    }
}

private enum class EntryKind { Row, Break, Title }

private class Entry(
    val key: Any?,
    val contentType: Any?,
    val kind: EntryKind,
    val content: @Composable LazyItemScope.() -> Unit,
)

private class RowPosition(
    val isFirst: Boolean,
    val isLast: Boolean,
    val followsTitle: Boolean,
)

/** Collects the items the screens add, so they can be grouped before being laid out. */
private class SettingsListScope : LazyListScope {
    val entries = mutableListOf<Entry>()

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        val kind = when (contentType) {
            SettingsGroupBreak -> EntryKind.Break
            SettingsGroupTitle -> EntryKind.Title
            else -> EntryKind.Row
        }
        entries += Entry(key, contentType, kind, content)
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        repeat(count) { index ->
            item(key = key?.invoke(index), contentType = contentType(index)) {
                itemContent(index)
            }
        }
    }
}

private fun rowPositions(entries: List<Entry>): Map<Int, RowPosition> {
    val positions = mutableMapOf<Int, RowPosition>()
    var group = mutableListOf<Int>()
    var followsTitle = false
    fun close() {
        group.forEachIndexed { i, entryIndex ->
            positions[entryIndex] = RowPosition(
                isFirst = i == 0,
                isLast = i == group.lastIndex,
                followsTitle = followsTitle,
            )
        }
        group = mutableListOf()
    }
    entries.forEachIndexed { index, entry ->
        when (entry.kind) {
            EntryKind.Row -> group += index

            EntryKind.Break -> {
                close()
                followsTitle = false
            }

            EntryKind.Title -> {
                close()
                followsTitle = true
            }
        }
    }
    close()
    return positions
}

@Composable
private fun SettingsCardRow(
    position: RowPosition,
    content: @Composable () -> Unit,
) {
    val top = if (position.isFirst) OuterCorner else InnerCorner
    val bottom = if (position.isLast) OuterCorner else InnerCorner
    val shape = RoundedCornerShape(
        topStart = top,
        topEnd = top,
        bottomStart = bottom,
        bottomEnd = bottom,
    )
    val topGap = when {
        !position.isFirst -> RowGap
        position.followsTitle -> 0.dp
        else -> GroupGap
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SideMargin, end = SideMargin, top = topGap)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceBright),
    ) {
        // Inside a card, "surface" is the card colour, so ListItems and plain Surfaces in a row
        // (which paint `surface`, the page colour elsewhere) blend into the card.
        val colors = MaterialTheme.colorScheme
        MaterialTheme(
            colorScheme = colors.copy(surface = colors.surfaceBright),
            motionScheme = MaterialTheme.motionScheme,
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography,
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                content()
            }
        }
    }
}
