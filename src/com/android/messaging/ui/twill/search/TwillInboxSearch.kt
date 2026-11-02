package com.android.messaging.ui.twill.search

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.ui.twill.inbox.TwillCards
import com.android.messaging.ui.twill.inbox.TwillInboxSearchItem
import com.android.messaging.ui.twill.inbox.TwillInboxSearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The inbox search ("Search messages"): searches every protocol's conversation names and
 * messages while the user types. Shown in place of the conversation list while [isActive].
 */
@Stable
internal class TwillInboxSearch(
    private val search: suspend (query: String) -> TwillInboxSearchResults,
) {
    var isActive by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf(TwillInboxSearchResults())
        private set
    var isSearching by mutableStateOf(false)
        private set

    fun open() {
        isActive = true
    }

    fun close() {
        isActive = false
        query = ""
        results = TwillInboxSearchResults()
    }

    fun onQueryChange(value: String) {
        query = value
    }

    /** Searches as the query changes, after a short pause in typing. */
    suspend fun run() {
        snapshotFlow { query.trim() }
            .distinctUntilChanged()
            .collectLatest { text ->
                if (text.isEmpty()) {
                    results = TwillInboxSearchResults()
                    isSearching = false
                    return@collectLatest
                }
                delay(SEARCH_DELAY_MILLIS)
                isSearching = true
                results = try {
                    search(text)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    TwillInboxSearchResults()
                }
                isSearching = false
            }
    }

    private companion object {
        const val SEARCH_DELAY_MILLIS = 300L
    }
}

private val SearchBarHeight = 56.dp
private val SearchBarShape = RoundedCornerShape(percent = 50)

/**
 * The inbox's top bar: a "Search messages" field (surface container highest, the search bar
 * role shared with Dialtone and Contacts), with Settings on the right. Tapping it starts [search]; while searching it edits the query.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TwillInboxSearchBar(
    search: TwillInboxSearch,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hint = stringResource(R.string.twill_inbox_search_hint)

    LaunchedEffect(search.isActive) {
        if (search.isActive) focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // A solid page colour behind the bar and the status bar, so the list doesn't show
            // through while it scrolls under them.
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .padding(horizontal = TwillCards.SideMargin, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SearchBarHeight)
                .clip(SearchBarShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .then(
                    if (search.isActive) {
                        Modifier
                    } else {
                        Modifier
                            .clickable(onClickLabel = hint, onClick = search::open)
                            .semantics { contentDescription = hint }
                    },
                )
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (search.isActive) {
                IconButton(onClick = search::close) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Icon(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp),
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (search.isActive) {
                    BasicTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        value = search.query,
                        onValueChange = search::onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    )
                }
                if (search.query.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            when {
                search.isActive && search.query.isNotEmpty() -> IconButton(
                    onClick = { search.onQueryChange("") },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.twill_inbox_search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                !search.isActive -> IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** What the inbox shows while searching: matching conversations, then matching messages. */
@Composable
internal fun TwillInboxSearchResults(
    search: TwillInboxSearch,
    onOpen: (TwillInboxSearchItem) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = search.isActive, onBack = search::close)
    val results = search.results
    val query = search.query.trim()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            query.isEmpty() -> Unit

            results.isEmpty && search.isSearching -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp),
            )

            results.isEmpty -> Text(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 32.dp, end = 32.dp),
                text = stringResource(R.string.twill_inbox_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(TwillCards.RowGap),
            ) {
                resultGroup(
                    key = "conversations",
                    title = R.string.twill_inbox_search_conversations,
                    items = results.conversations,
                    query = query,
                    onOpen = onOpen,
                )
                resultGroup(
                    key = "messages",
                    title = R.string.twill_inbox_search_messages,
                    items = results.messages,
                    query = query,
                    onOpen = onOpen,
                )
            }
        }
    }
}

private fun LazyListScope.resultGroup(
    key: String,
    title: Int,
    items: List<TwillInboxSearchItem>,
    query: String,
    onOpen: (TwillInboxSearchItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "${key}_title", contentType = "title") {
        Text(
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 6.dp),
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    itemsIndexed(
        items = items,
        key = { index, item -> "${key}_$index:${item.target}:${item.messageId}" },
        contentType = { _, _ -> "result" },
    ) { index, item ->
        ResultRow(
            item = item,
            query = query,
            onClick = { onOpen(item) },
            modifier = Modifier
                .padding(horizontal = TwillCards.SideMargin)
                .clip(TwillCards.shape(index, items.size))
                .background(MaterialTheme.colorScheme.surfaceBright),
        )
    }
}

@Composable
private fun ResultRow(
    item: TwillInboxSearchItem,
    query: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    val youPrefix = stringResource(R.string.inbox_you_prefix)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                modifier = Modifier.size(20.dp),
                painter = painterResource(item.iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = if (item.messageId == null) {
                        item.title.highlighted(query, highlight)
                    } else {
                        AnnotatedString(item.title)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.timeMillis > 0) {
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = DateUtils.getRelativeTimeSpanString(
                            item.timeMillis,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_ALL,
                        ).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.messageId != null) {
                Text(
                    text = buildAnnotatedString {
                        if (item.isOutgoing) append(youPrefix)
                        append(snippet(item.text, query).highlighted(query, highlight))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The part of [text] around the first match, so the match is visible in two lines. */
private fun snippet(text: String, query: String): String {
    val flat = text.replace('\n', ' ')
    val at = flat.indexOf(query, ignoreCase = true)
    return if (at > SNIPPET_LEAD) "…" + flat.substring(at - SNIPPET_LEAD / 2) else flat
}

private const val SNIPPET_LEAD = 40

private fun String.highlighted(query: String, style: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(this@highlighted)
        if (query.isEmpty()) return@buildAnnotatedString
        var start = this@highlighted.indexOf(query, ignoreCase = true)
        while (start >= 0) {
            addStyle(style, start, start + query.length)
            start = this@highlighted.indexOf(query, start + query.length, ignoreCase = true)
        }
    }
