package com.android.messaging.ui.conversationpicker.host.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.messaging.R
import com.android.messaging.twill.twillEntryPoint
import com.android.messaging.ui.BugleComponentActivity
import com.android.messaging.ui.appsettings.common.SettingsLazyColumn
import com.android.messaging.ui.appsettings.common.SettingsTopAppBar
import com.android.messaging.ui.core.AppTheme
import com.android.messaging.util.Dates
import com.android.messaging.widget.TwillWidgetData
import com.android.messaging.widget.WidgetChat
import com.android.messaging.widget.WidgetConversationPrefs
import com.android.messaging.widget.WidgetConversationProvider
import com.android.messaging.widget.avatarLetter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Twill: picks the conversation a "Conversation" widget shows, from every protocol (SMS/MMS,
 * Nostr, Delta Chat), in the app's Settings-style grouped list. Keeps upstream's class name,
 * which the manifest alias and UIIntents use.
 */
@AndroidEntryPoint
class WidgetPickConversationActivity : BugleComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isFinishing) {
            return
        }

        setResult(RESULT_CANCELED)

        appWidgetId = resolveAppWidgetId()
        if (!isValidConfigureRequest()) {
            finish()
            return
        }

        enableEdgeToEdge()

        val data = TwillWidgetData(applicationContext, twillEntryPoint().twillProtocols())
        setContent {
            AppTheme {
                WidgetPickConversationScreen(
                    loadChats = {
                        withContext(Dispatchers.IO) { data.chats(limit = MAX_PICKER_CHATS) }
                    },
                    onPick = ::bindConversationToWidget,
                    onNavigateBack = ::finish,
                )
            }
        }
    }

    private fun bindConversationToWidget(chat: WidgetChat) {
        WidgetConversationPrefs.saveConversationIdPref(appWidgetId, chat.key.encode())
        WidgetConversationProvider.rebuildWidget(this, appWidgetId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }

    private fun resolveAppWidgetId(): Int {
        return intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun isValidConfigureRequest(): Boolean {
        return appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
            intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
    }

    private companion object {
        const val MAX_PICKER_CHATS = 200
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetPickConversationScreen(
    loadChats: suspend () -> List<WidgetChat>,
    onPick: (WidgetChat) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val chats by produceState<List<WidgetChat>?>(initialValue = null) { value = loadChats() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.twill_widget_pick_title),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        val list = chats
        when {
            list == null -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            list.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.twill_widget_pick_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> SettingsLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                items(count = list.size, key = { list[it].key.encode() }) { index ->
                    PickerRow(chat = list[index], onClick = { onPick(list[index]) })
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    chat: WidgetChat,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PickerAvatar(chat)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (chat.isUnread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(chat.iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(16.dp),
                )
                if (chat.timeMillis > 0) {
                    Text(
                        modifier = Modifier.padding(start = 4.dp),
                        text = Dates.getWidgetTimeString(chat.timeMillis, true).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (chat.snippet.isNotEmpty()) {
                Text(
                    text = chat.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The same avatar as the app's lists: a picture, or a letter or glyph on tertiary container. */
@Composable
private fun PickerAvatar(chat: WidgetChat) {
    val photo by produceState<ImageBitmap?>(initialValue = null, chat.key) {
        value = withContext(Dispatchers.IO) { chat.loadPhoto()?.asImageBitmap() }
    }
    val letter = avatarLetter(chat.title, chat.isGroup)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = photo
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp),
            )

            letter != null -> Text(
                text = letter,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            else -> Icon(
                imageVector = if (chat.isGroup) Icons.Rounded.Group else Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
