package com.android.messaging.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import com.android.messaging.R
import com.android.messaging.twill.TwillSms
import com.android.messaging.twill.twillEntryPoint
import com.android.messaging.ui.MainActivity
import com.android.messaging.ui.UIIntents
import com.android.messaging.util.Dates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Twill's home screen widgets, drawn with RemoteViews in the app's Settings-style system
 * colours (`res/values/twill_widget_colors.xml` and its night copy):
 *
 * - "Conversations" ([BugleWidgetProvider]): every protocol's conversations, newest first.
 * - "Conversation" ([WidgetConversationProvider]): one conversation's latest messages, picked
 *   in WidgetPickConversationActivity (key stored by [WidgetConversationPrefs]).
 *
 * Both are rebuilt together: when SMS data changes (upstream's content provider calls the
 * providers' notify methods), when another protocol's chat list changes ([watch]) and when the
 * launcher asks. Updates are debounced and never connect to a network.
 */
internal object TwillWidgets {
    private const val DEBOUNCE_MILLIS = 1_000L
    private const val UPDATE_TIMEOUT_MILLIS = 9_000L
    private const val REQUEST_OPEN_APP = -1
    private const val REQUEST_CHAT_TEMPLATE = -2
    private const val REQUEST_NEW_CHAT = -3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null
    private var watchJob: Job? = null

    /** Rebuilds every widget shortly, coalescing bursts of changes. */
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(DEBOUNCE_MILLIS)
                updateAll(app)
            }
        }
    }

    /** Rebuilds every widget now; [result] (from `goAsync`) is finished afterwards. */
    fun update(context: Context, result: BroadcastReceiver.PendingResult? = null) {
        val app = context.applicationContext
        scope.launch {
            try {
                withTimeoutOrNull(UPDATE_TIMEOUT_MILLIS) { updateAll(app) }
            } finally {
                result?.finish()
            }
        }
        watch(app)
    }

    /**
     * While widgets are placed, follows every protocol's chat list so they update when a
     * Nostr or Delta Chat message arrives. Called when Twill starts and on widget updates.
     */
    fun watch(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (watchJob != null || !hasWidgets(app)) return
            val protocols = app.twillEntryPoint().twillProtocols().all
            watchJob = scope.launch {
                if (protocols.isEmpty()) return@launch
                combine(
                    protocols.map {
                        it.chats
                    }
                ) { lists -> lists.map { it.size to it.hashCode() } }
                    .drop(1)
                    .collect { requestUpdate(app) }
            }
        }
        requestUpdate(app)
    }

    private fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return manager.ids<BugleWidgetProvider>(context).isNotEmpty() ||
            manager.ids<WidgetConversationProvider>(context).isNotEmpty()
    }

    private inline fun <reified T> AppWidgetManager.ids(context: Context): IntArray =
        getAppWidgetIds(ComponentName(context, T::class.java)) ?: IntArray(0)

    private suspend fun updateAll(context: Context) = mutex.withLock {
        val manager = AppWidgetManager.getInstance(context) ?: return@withLock
        val listIds = manager.ids<BugleWidgetProvider>(context)
        val conversationIds = manager.ids<WidgetConversationProvider>(context)
        if (listIds.isEmpty() && conversationIds.isEmpty()) return@withLock

        val data = TwillWidgetData(context, context.twillEntryPoint().twillProtocols())
        if (listIds.isNotEmpty()) {
            manager.updateAppWidget(listIds, listViews(context, data))
        }
        conversationIds.forEach { appWidgetId ->
            manager.updateAppWidget(appWidgetId, conversationViews(context, data, appWidgetId))
        }
    }

    // "Conversations" widget.

    private suspend fun listViews(context: Context, data: TwillWidgetData): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.twill_widget_list)
        val openApp = openAppIntent(context)
        views.setOnClickPendingIntent(R.id.twill_widget_title, openApp)
        views.setOnClickPendingIntent(R.id.twill_widget_new_chat, newChatIntent(context))

        val chats = data.chats()
        val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
        chats.forEachIndexed { index, chat ->
            items.addItem(index.toLong(), chatRow(context, chat, rowBackground(index, chats.size)))
        }
        views.setRemoteAdapter(R.id.twill_widget_list, items.build())
        views.setPendingIntentTemplate(R.id.twill_widget_list, chatTemplate(context))
        views.setEmptyView(R.id.twill_widget_list, R.id.twill_widget_empty)
        views.setTextViewText(
            R.id.twill_widget_empty,
            context.getString(
                if (data.smsNeedsSetup) {
                    R.string.twill_widget_needs_setup
                } else {
                    R.string.twill_widget_empty
                },
            ),
        )
        views.setOnClickPendingIntent(R.id.twill_widget_empty, openApp)
        return views
    }

    private suspend fun chatRow(context: Context, chat: WidgetChat, background: Int): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.twill_widget_list_item)
        row.setInt(R.id.twill_widget_row, "setBackgroundResource", background)
        row.setTextViewText(R.id.twill_widget_row_title, chat.title.bold(chat.isUnread))
        row.setTextViewText(R.id.twill_widget_row_snippet, chat.snippet.bold(chat.isUnread))
        if (chat.isUnread) {
            row.setColorStateList(
                R.id.twill_widget_row_snippet,
                "setTextColor",
                R.color.twill_widget_on_surface
            )
        }
        row.setTextViewText(
            R.id.twill_widget_row_time,
            if (chat.timeMillis > 0) Dates.getWidgetTimeString(chat.timeMillis, true) else "",
        )
        row.setImageViewResource(R.id.twill_widget_row_protocol, chat.iconRes)
        row.setViewVisibility(
            R.id.twill_widget_row_unread,
            if (chat.isUnread) View.VISIBLE else View.GONE
        )
        row.setAvatar(chat.title, chat.isGroup, chat.loadPhoto())
        row.setOnClickFillInIntent(R.id.twill_widget_row, chat.key.fillInIntent())
        return row
    }

    /** Cards grouped like the app's lists: rounded at the top and bottom of the group. */
    private fun rowBackground(index: Int, count: Int): Int = when {
        count == 1 -> R.drawable.twill_widget_row_single
        index == 0 -> R.drawable.twill_widget_row_top
        index == count - 1 -> R.drawable.twill_widget_row_bottom
        else -> R.drawable.twill_widget_row_middle
    }

    // "Conversation" widget.

    private suspend fun conversationViews(
        context: Context,
        data: TwillWidgetData,
        appWidgetId: Int,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.twill_widget_conversation)
        val key = WidgetChatKey.decode(WidgetConversationPrefs.getConversationIdPref(appWidgetId))
        val chat = key?.let { data.chat(it) }
        views.setEmptyView(R.id.twill_widget_list, R.id.twill_widget_empty)

        if (key == null || chat == null) {
            // Not set up yet, or the conversation is gone: tap to pick one.
            val configure = UIIntents.get()
                .getWidgetPendingIntentForConfigurationActivity(context, appWidgetId)
            views.setViewVisibility(R.id.twill_widget_header_avatar, View.GONE)
            views.setViewVisibility(R.id.twill_widget_protocol, View.GONE)
            views.setViewVisibility(R.id.twill_widget_compose, View.GONE)
            views.setTextViewText(
                R.id.twill_widget_empty,
                context.getString(
                    if (key is WidgetChatKey.Sms && data.smsNeedsSetup) {
                        R.string.twill_widget_needs_setup
                    } else {
                        R.string.twill_widget_choose
                    },
                ),
            )
            views.setRemoteAdapter(
                R.id.twill_widget_list,
                RemoteViews.RemoteCollectionItems.Builder().build(),
            )
            views.setOnClickPendingIntent(R.id.twill_widget_header, configure)
            views.setOnClickPendingIntent(R.id.twill_widget_empty, configure)
            return views
        }

        val open = openChatIntent(context, chat.key, appWidgetId)
        views.setTextViewText(R.id.twill_widget_title, chat.title)
        views.setImageViewResource(R.id.twill_widget_protocol, chat.iconRes)
        views.setAvatar(chat.title, chat.isGroup, chat.loadPhoto())
        views.setOnClickPendingIntent(R.id.twill_widget_header, open)
        views.setOnClickPendingIntent(R.id.twill_widget_compose, open)
        views.setOnClickPendingIntent(R.id.twill_widget_empty, open)

        val fillIn = chat.key.fillInIntent()
        val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2)
        data.messages(chat.key, chat.isGroup).forEachIndexed { index, message ->
            val item = RemoteViews(
                context.packageName,
                if (message.isOutgoing) {
                    R.layout.twill_widget_message_out
                } else {
                    R.layout.twill_widget_message_in
                },
            )
            item.setTextViewText(R.id.twill_widget_message_text, message.text)
            item.setTextViewText(
                R.id.twill_widget_message_time,
                Dates.getWidgetTimeString(message.timeMillis, false),
            )
            if (message.sender != null) {
                item.setViewVisibility(R.id.twill_widget_message_sender, View.VISIBLE)
                item.setTextViewText(R.id.twill_widget_message_sender, message.sender)
            }
            item.setOnClickFillInIntent(R.id.twill_widget_message, fillIn)
            items.addItem(index.toLong(), item)
        }
        views.setRemoteAdapter(R.id.twill_widget_list, items.build())
        views.setPendingIntentTemplate(R.id.twill_widget_list, chatTemplate(context))
        views.setTextViewText(
            R.id.twill_widget_empty,
            context.getString(R.string.twill_widget_no_messages)
        )
        return views
    }

    // Shared pieces.

    private fun RemoteViews.setAvatar(name: String, isGroup: Boolean, photo: Bitmap?) {
        val letter = avatarLetter(name, isGroup)
        setViewVisibility(
            R.id.twill_widget_avatar_photo,
            if (photo !=
                null
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        )
        setViewVisibility(
            R.id.twill_widget_avatar_letter,
            if (photo == null && letter != null) View.VISIBLE else View.GONE,
        )
        setViewVisibility(
            R.id.twill_widget_avatar_glyph,
            if (photo == null && letter == null) View.VISIBLE else View.GONE,
        )
        when {
            photo != null -> setImageViewBitmap(R.id.twill_widget_avatar_photo, photo)
            letter != null -> setTextViewText(R.id.twill_widget_avatar_letter, letter)
            else -> setImageViewResource(
                R.id.twill_widget_avatar_glyph,
                if (isGroup) {
                    R.drawable.ic_twill_widget_group
                } else {
                    R.drawable.ic_twill_widget_person
                },
            )
        }
    }

    private fun CharSequence.bold(isBold: Boolean): CharSequence {
        if (!isBold || isEmpty()) return this
        return SpannableString(this).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun mainIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        mainIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** List items fill in which conversation to open ([WidgetChatKey.fillInIntent]). */
    private fun chatTemplate(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CHAT_TEMPLATE,
        mainIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun openChatIntent(
        context: Context,
        key: WidgetChatKey,
        appWidgetId: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            appWidgetId,
            mainIntent(context).putExtras(key.fillInIntent()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** A new SMS when SMS is on (like the app's button); otherwise the inbox, to pick a service. */
    private fun newChatIntent(context: Context): PendingIntent = if (TwillSms.isEnabled(context)) {
        UIIntents.get().getWidgetPendingIntentForNewConversation(context, REQUEST_NEW_CHAT)
    } else {
        openAppIntent(context)
    }
}
