package com.android.messaging.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle

/**
 * Twill's "Conversation" widget: one conversation of any protocol (see [TwillWidgets]); the
 * conversation is picked in WidgetPickConversationActivity and stored by
 * [WidgetConversationPrefs]. Keeps upstream's class name and notify methods.
 */
class WidgetConversationProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        TwillWidgets.update(context, goAsync())
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        TwillWidgets.update(context, goAsync())
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach(WidgetConversationPrefs::deleteConversationIdPref)
    }

    companion object {
        /** Messages of an SMS conversation changed (null: possibly all of them). */
        @JvmStatic
        fun notifyMessagesChanged(context: Context, conversationId: String?) {
            TwillWidgets.requestUpdate(context)
        }

        /** An SMS conversation was deleted: widgets showing it go back to "choose". */
        @JvmStatic
        fun notifyConversationDeleted(context: Context, conversationId: String) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            manager.getAppWidgetIds(ComponentName(context, WidgetConversationProvider::class.java))
                ?.filter { WidgetConversationPrefs.getConversationIdPref(it) == conversationId }
                ?.forEach(WidgetConversationPrefs::deleteConversationIdPref)
            TwillWidgets.requestUpdate(context)
        }

        /** An SMS conversation was renamed. */
        @JvmStatic
        fun notifyConversationRenamed(context: Context, conversationId: String) {
            TwillWidgets.requestUpdate(context)
        }

        /** Redraws the widgets now, e.g. after a conversation was picked. */
        @JvmStatic
        fun rebuildWidget(context: Context, appWidgetId: Int) {
            TwillWidgets.update(context)
        }
    }
}
