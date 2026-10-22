package com.android.messaging.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * Twill's "Conversations" widget: every protocol's conversations (see [TwillWidgets]). Keeps
 * upstream's class name, so widgets already placed keep working and upstream's data code can
 * still call [notifyConversationListChanged].
 */
class BugleWidgetProvider : AppWidgetProvider() {
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

    companion object {
        /** SMS conversations changed. */
        @JvmStatic
        fun notifyConversationListChanged(context: Context) {
            TwillWidgets.requestUpdate(context)
        }
    }
}
