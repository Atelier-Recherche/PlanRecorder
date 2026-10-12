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
import android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import com.dimowner.audiorecorder.AppConstants
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.app.TransparentRecordingActivity
import com.dimowner.audiorecorder.util.TimeUtils
import com.dimowner.audiorecorder.v2.data.PrefsV2
import com.dimowner.audiorecorder.v2.di.WidgetEntryPoint
import dagger.hilt.android.EntryPointAccessors
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * logo [open app] | title+timer [open app] | [Stop/REC] [Refresh]
 */
class CourseRecordingWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_REFRESH, ACTION_RECORDING_TICK -> {
                val pendingResult = goAsync()
                widgetExecutor.execute {
                    try {
                        refreshWidgets(context.applicationContext)
                    } catch (t: Throwable) {
                        Timber.e(t, "CourseRecordingWidget refresh failed")
                    } finally {
                        try {
                            pendingResult.finish()
                        } catch (t: Throwable) {
                            Timber.w(t, "goAsync finish failed")
                        }
                    }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Keep the system callback non-blocking: schedule the same path as Refresh.
        requestUpdate(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.planrecorder.widget.REFRESH"
        const val ACTION_RECORDING_TICK = "com.planrecorder.widget.RECORDING_TICK"
        const val ACTION_STOP_FROM_WIDGET = "com.planrecorder.widget.STOP"
        const val EXTRA_STOP_RECORDING = "extra_stop_recording"

        private val widgetExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())

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
            val appContext = context.applicationContext
            widgetExecutor.execute {
                try {
                    refreshWidgets(appContext)
                } catch (t: Throwable) {
                    Timber.e(t, "CourseRecordingWidget.requestUpdate failed")
                }
            }
        }

        fun onRecordingProgress(context: Context, isRecording: Boolean, durationMills: Long) {
            setRecordingState(isRecording, durationMills)
            requestUpdate(context)
        }

        private fun refreshWidgets(appContext: Context) {
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(appContext, CourseRecordingWidget::class.java),
            )
            if (ids.isEmpty()) return
            val (label, isRecording, duration) = resolveWidgetContent(appContext)
            mainHandler.post {
                for (id in ids) {
                    try {
                        updateCourseWidget(
                            context = appContext,
                            appWidgetManager = manager,
                            appWidgetId = id,
                            courseLabel = label,
                            isRecording = isRecording,
                            durationMills = duration,
                        )
                    } catch (t: Throwable) {
                        Timber.e(t, "updateAppWidget($id) failed")
                    }
                }
            }
        }

        private fun resolveWidgetContent(appContext: Context): Triple<String, Boolean, Long> {
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext,
                WidgetEntryPoint::class.java,
            )
            val prefs = entryPoint.prefs()
            val calendarRepository = entryPoint.calendarRepository()
            val calendarId = prefs.selectedCalendarId.takeIf { it != PrefsV2.NO_CALENDAR_FILTER }
            val now = System.currentTimeMillis()
            val grace = prefs.calendarEventWindowMinutes.coerceAtLeast(0) * 60_000L
            val active = calendarRepository.getActiveEvent(now, calendarId, grace)
            val label = active?.title
                ?: calendarRepository.getUpcomingEvents(now, now + 6L * 60L * 60L * 1000L, calendarId)
                    .firstOrNull()?.title
                ?: appContext.getString(R.string.course_widget_no_event)
            return Triple(label, isRecordingActive(), recordingDurationMills())
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
        views.setImageViewResource(R.id.btn_course_rec_stop, R.drawable.ic_widget_square)
        views.setContentDescription(
            R.id.btn_course_rec_stop,
            context.getString(R.string.course_widget_stop),
        )
        views.setOnClickPendingIntent(R.id.btn_course_rec_stop, courseStopPendingIntent(context))
    } else {
        views.setTextViewText(R.id.txt_recording_timer, context.getString(R.string.course_widget_idle))
        views.setImageViewResource(R.id.btn_course_rec_stop, R.drawable.ic_widget_mic)
        views.setContentDescription(
            R.id.btn_course_rec_stop,
            context.getString(R.string.course_widget_record),
        )
        views.setOnClickPendingIntent(R.id.btn_course_rec_stop, courseRecordingPendingIntent(context))
    }
    val openApp = courseOpenAppPendingIntent(context)
    views.setOnClickPendingIntent(R.id.btn_course_logo, openApp)
    views.setOnClickPendingIntent(R.id.area_course_info, openApp)
    views.setOnClickPendingIntent(R.id.btn_course_refresh, courseRefreshPendingIntent(context))
    appWidgetManager.updateAppWidget(appWidgetId, views)
}

@SuppressLint("WrongConstant")
private fun courseOpenAppPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, HomeActivity::class.java).apply {
        flags = FLAG_ACTIVITY_NEW_TASK or
            FLAG_ACTIVITY_CLEAR_TOP or
            FLAG_ACTIVITY_SINGLE_TOP or
            FLAG_ACTIVITY_REORDER_TO_FRONT
        action = Intent.ACTION_MAIN
    }
    return PendingIntent.getActivity(
        context,
        15,
        intent,
        AppConstants.PENDING_INTENT_FLAGS or PendingIntent.FLAG_UPDATE_CURRENT,
    )
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
    // Activity PendingIntent keeps the user-gesture BAL exemption so the task reliably
    // comes to the foreground; HomeActivity then requestStop()'s the recording service.
    val intent = Intent(context, HomeActivity::class.java).apply {
        action = CourseRecordingWidget.ACTION_STOP_FROM_WIDGET
        flags = FLAG_ACTIVITY_NEW_TASK or
            FLAG_ACTIVITY_CLEAR_TOP or
            FLAG_ACTIVITY_SINGLE_TOP or
            FLAG_ACTIVITY_REORDER_TO_FRONT
        putExtra(CourseRecordingWidget.EXTRA_STOP_RECORDING, true)
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
