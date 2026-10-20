package com.android.messaging.ui.twill.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.twill.TwillSearchHit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Search in one open conversation, shared by the SMS/MMS screen and the Nostr / Delta Chat chat
 * screen. Matches come newest first; "older" and "newer" step through them. Each screen provides
 * the search itself, the search bar ([TwillSearchTopBar]), [LocalTwillSearch] (for highlighting)
 * and [TwillSearchScrollEffect] (to bring the current match into view).
 */
@Stable
internal class TwillConversationSearch(
    private val search: suspend (query: String) -> List<TwillSearchHit>,
) {
    var isActive by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var hits by mutableStateOf<List<TwillSearchHit>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var isSearching by mutableStateOf(false)
        private set

    /** Bumped on every new result or step, so the screen scrolls even to the same match again. */
    var jumpCount by mutableIntStateOf(0)
        private set

    val current: TwillSearchHit? get() = hits.getOrNull(index)

    /** The text to highlight in messages, or null when not searching. */
    val highlight: String? get() = query.trim().takeIf { isActive && it.isNotEmpty() }

    fun open() {
        isActive = true
    }

    fun close() {
        isActive = false
        query = ""
        hits = emptyList()
        index = 0
    }

    fun onQueryChange(value: String) {
        query = value
    }

    fun older() {
        if (index < hits.lastIndex) {
            index++
            jumpCount++
        }
    }

    fun newer() {
        if (index > 0) {
            index--
            jumpCount++
        }
    }

    /** Runs searches as the query changes, after a short pause in typing. */
    suspend fun run() {
        snapshotFlow { query.trim() }
            .distinctUntilChanged()
            .collectLatest { text ->
                if (text.isEmpty()) {
                    hits = emptyList()
                    index = 0
                    isSearching = false
                    return@collectLatest
                }
                delay(SEARCH_DELAY_MILLIS)
                isSearching = true
                val result = try {
                    search(text)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    emptyList()
                }
                hits = result
                index = 0
                jumpCount++
                isSearching = false
            }
    }

    private companion object {
        const val SEARCH_DELAY_MILLIS = 250L
    }
}

/** A search for the conversation identified by [key]; a new conversation starts a new search. */
@Composable
internal fun rememberTwillConversationSearch(
    key: Any?,
    search: suspend (query: String) -> List<TwillSearchHit>,
): TwillConversationSearch {
    val currentSearch by rememberUpdatedState(newValue = search)
    val state = remember(key) { TwillConversationSearch { query -> currentSearch(query) } }
    LaunchedEffect(state) { state.run() }
    return state
}

/** The active search, provided by the conversation screens while the search bar is open. */
internal val LocalTwillSearch = compositionLocalOf<TwillConversationSearch?> { null }

/** The top-bar button that opens the search bar. */
@Composable
internal fun TwillSearchAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = stringResource(R.string.twill_search),
        )
    }
}

/** Replaces a conversation's top bar while searching: query, match count, older / newer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TwillSearchTopBar(search: TwillConversationSearch) {
    BackHandler(onBack = search::close)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = search::close) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.twill_search_close),
                )
            }
        },
        title = {
            TextField(
                value = search.query,
                onValueChange = search::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(text = stringResource(R.string.twill_search_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        actions = {
            when {
                search.isSearching -> CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )

                search.query.isNotBlank() -> Text(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    text = when {
                        search.hits.isEmpty() -> stringResource(R.string.twill_search_no_results)
                        else -> stringResource(
                            R.string.twill_search_count,
                            search.index + 1,
                            search.hits.size,
                        )
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = search::older, enabled = search.index < search.hits.lastIndex) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.twill_search_older),
                )
            }
            IconButton(onClick = search::newer, enabled = search.index > 0) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.twill_search_newer),
                )
            }
        },
    )
}

/**
 * Brings the current match into view. [messageIds] are the loaded messages, oldest first, shown
 * in a reversed list (newest at index [indexOffset]). While the match isn't loaded yet,
 * [onLoadOlder] is asked for more (with how many of the newest messages are needed); the effect
 * runs again as messages arrive.
 */
@Composable
internal fun TwillSearchScrollEffect(
    messageIds: List<String>,
    listState: LazyListState,
    onLoadOlder: (rank: Int) -> Unit,
    indexOffset: Int = 0,
) {
    val search = LocalTwillSearch.current ?: return
    val hit = search.current ?: return
    val jump = search.jumpCount
    val currentOnLoadOlder by rememberUpdatedState(newValue = onLoadOlder)
    var scrolledJump by remember(search) { mutableIntStateOf(-1) }

    LaunchedEffect(hit, jump, messageIds.size) {
        if (scrolledJump == jump) return@LaunchedEffect
        val position = messageIds.indexOf(hit.messageId)
        if (position < 0) {
            if (messageIds.size < hit.rank) currentOnLoadOlder(hit.rank)
            return@LaunchedEffect
        }
        scrolledJump = jump
        val target = indexOffset + messageIds.lastIndex - position
        // Far away: jump most of the way, then animate the rest.
        val distance = target - listState.firstVisibleItemIndex
        if (distance > JUMP_THRESHOLD) {
            listState.scrollToItem(target - JUMP_THRESHOLD)
        } else if (distance < -JUMP_THRESHOLD) {
            listState.scrollToItem(target + JUMP_THRESHOLD)
        }
        listState.animateScrollToItem(target)
    }
}

private const val JUMP_THRESHOLD = 8

/** This text with every occurrence of the current search highlighted (ignoring case). */
@Composable
internal fun AnnotatedString.withTwillSearchHighlight(): AnnotatedString {
    val query = LocalTwillSearch.current?.highlight ?: return this
    val style = SpanStyle(
        background = MaterialTheme.colorScheme.tertiaryContainer,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    return remember(this, query, style) { highlighted(query, style) }
}

/** Plain-text version of [withTwillSearchHighlight]. */
@Composable
internal fun String.withTwillSearchHighlight(): AnnotatedString =
    AnnotatedString(this).withTwillSearchHighlight()

private fun AnnotatedString.highlighted(query: String, style: SpanStyle): AnnotatedString {
    val starts = buildList {
        var from = text.indexOf(query, ignoreCase = true)
        while (from >= 0) {
            add(from)
            from = text.indexOf(query, startIndex = from + query.length, ignoreCase = true)
        }
    }
    if (starts.isEmpty()) return this
    val source = this
    return buildAnnotatedString {
        append(source)
        starts.forEach { start -> addStyle(style, start, start + query.length) }
    }
}
