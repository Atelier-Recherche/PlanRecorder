/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.widget.RemoteViews
import com.dimowner.audiorecorder.AppConstants
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.app.TransparentRecordingActivity
import com.dimowner.audiorecorder.v2.data.PrefsV2
import com.dimowner.audiorecorder.v2.data.calendar.CalendarRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Home-screen widget: shows the current (or next) calendar course and starts recording on tap.
 */
@AndroidEntryPoint
class CourseRecordingWidget : AppWidgetProvider() {

    @Inject
    lateinit var calendarRepository: CalendarRepository

    @Inject
    lateinit var prefs: PrefsV2

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val calendarId = prefs.selectedCalendarId.takeIf { it != PrefsV2.NO_CALENDAR_FILTER }
        val now = System.currentTimeMillis()
        val active = calendarRepository.getActiveEvent(now, calendarId)
        val label = active?.title
            ?: calendarRepository.getUpcomingEvents(now, now + 6L * 60L * 60L * 1000L, calendarId)
                .firstOrNull()?.title
            ?: context.getString(R.string.course_widget_no_event)

        for (id in appWidgetIds) {
            updateCourseWidget(context, appWidgetManager, id, label)
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CourseRecordingWidget::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, CourseRecordingWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}

internal fun updateCourseWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    courseLabel: String,
) {
    val views = RemoteViews(context.packageName, R.layout.course_recording_widget)
    views.setTextViewText(R.id.txt_course_title, courseLabel)
    views.setOnClickPendingIntent(R.id.btn_course_record, courseRecordingPendingIntent(context))
    appWidgetManager.updateAppWidget(appWidgetId, views)
}

@SuppressLint("WrongConstant")
private fun courseRecordingPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, TransparentRecordingActivity::class.java)
    intent.flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK
    return PendingIntent.getActivity(
        context,
        12,
        intent,
        AppConstants.PENDING_INTENT_FLAGS or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
