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
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.view.View
import android.widget.RemoteViews
import com.dimowner.audiorecorder.AppConstants
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.app.TransparentRecordingActivity
import com.dimowner.audiorecorder.util.TimeUtils
import com.dimowner.audiorecorder.v2.data.PrefsV2
import com.dimowner.audiorecorder.v2.data.calendar.CalendarRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Home-screen widget: current/next course, live timer, start / stop (opens app) / refresh.
 */
@AndroidEntryPoint
class CourseRecordingWidget : AppWidgetProvider() {

    @Inject
    lateinit var calendarRepository: CalendarRepository

    @Inject
    lateinit var prefs: PrefsV2

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_REFRESH, ACTION_RECORDING_TICK -> {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, CourseRecordingWidget::class.java))
                if (ids.isNotEmpty()) onUpdate(context, manager, ids)
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val calendarId = prefs.selectedCalendarId.takeIf { it != PrefsV2.NO_CALENDAR_FILTER }
        val now = System.currentTimeMillis()
        val grace = prefs.calendarEventWindowMinutes.coerceAtLeast(0) * 60_000L
        val active = calendarRepository.getActiveEvent(now, calendarId, grace)
        val label = active?.title
            ?: calendarRepository.getUpcomingEvents(now, now + 6L * 60L * 60L * 1000L, calendarId)
                .firstOrNull()?.title
            ?: context.getString(R.string.course_widget_no_event)

        val recording = isRecordingActive()
        val duration = recordingDurationMills()
        for (id in appWidgetIds) {
            updateCourseWidget(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetId = id,
                courseLabel = label,
                isRecording = recording,
                durationMills = duration,
            )
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.planrecorder.widget.REFRESH"
        const val ACTION_RECORDING_TICK = "com.planrecorder.widget.RECORDING_TICK"
        const val EXTRA_STOP_RECORDING = "extra_stop_recording"

        @Volatile
        private var recordingActive: Boolean = false

        @Volatile
        private var recordingDuration: Long = 0L

        fun setRecordingState(isRecording: Boolean, durationMills: Long) {
            recordingActive = isRecording
            recordingDuration = durationMills.coerceAtLeast(0L)
        }

        fun isRecordingActive(): Boolean = recordingActive

        fun recordingDurationMills(): Long = recordingDuration

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

        /** Called from [com.dimowner.audiorecorder.v2.audio.AudioRecordingService] about once/sec. */
        fun onRecordingProgress(context: Context, isRecording: Boolean, durationMills: Long) {
            setRecordingState(isRecording, durationMills)
            requestUpdate(context)
        }
    }
}

internal fun updateCourseWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    courseLabel: String,
    isRecording: Boolean,
    durationMills: Long,
) {
    val views = RemoteViews(context.packageName, R.layout.course_recording_widget)
    views.setTextViewText(R.id.txt_course_title, courseLabel)
    if (isRecording) {
        views.setTextViewText(
            R.id.txt_recording_timer,
            context.getString(
                R.string.course_widget_recording,
                TimeUtils.formatTimeIntervalHourMinSec2(durationMills),
            ),
        )
        views.setViewVisibility(R.id.btn_course_stop, View.VISIBLE)
        views.setViewVisibility(R.id.btn_course_record, View.GONE)
    } else {
        views.setTextViewText(R.id.txt_recording_timer, context.getString(R.string.course_widget_idle))
        views.setViewVisibility(R.id.btn_course_stop, View.GONE)
        views.setViewVisibility(R.id.btn_course_record, View.VISIBLE)
    }
    views.setOnClickPendingIntent(R.id.btn_course_record, courseRecordingPendingIntent(context))
    views.setOnClickPendingIntent(R.id.btn_course_stop, courseStopPendingIntent(context))
    views.setOnClickPendingIntent(R.id.btn_course_refresh, courseRefreshPendingIntent(context))
    appWidgetManager.updateAppWidget(appWidgetId, views)
}

@SuppressLint("WrongConstant")
private fun courseRecordingPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, TransparentRecordingActivity::class.java)
    intent.flags = FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    return PendingIntent.getActivity(
        context,
        12,
        intent,
        AppConstants.PENDING_INTENT_FLAGS or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

@SuppressLint("WrongConstant")
private fun courseStopPendingIntent(context: Context): PendingIntent {
    // Bring Home to foreground so the rename/export dialog can be confirmed.
    val intent = Intent(context, HomeActivity::class.java).apply {
        flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
        putExtra(CourseRecordingWidget.EXTRA_STOP_RECORDING, true)
        action = Intent.ACTION_MAIN
    }
    return PendingIntent.getActivity(
        context,
        13,
        intent,
        AppConstants.PENDING_INTENT_FLAGS or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

@SuppressLint("WrongConstant")
private fun courseRefreshPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, CourseRecordingWidget::class.java).apply {
        action = CourseRecordingWidget.ACTION_REFRESH
    }
    return PendingIntent.getBroadcast(
        context,
        14,
        intent,
        AppConstants.PENDING_INTENT_FLAGS or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
