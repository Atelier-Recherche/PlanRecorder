/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dimowner.audiorecorder.AppConstants
import com.dimowner.audiorecorder.v2.data.PrefsV2
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarRepository: CalendarRepository,
    private val prefs: PrefsV2,
) {

    fun reschedule() {
        cancel()
        if (!prefs.calendarReminderEnabled) return
        val minutesBefore = prefs.calendarReminderMinutes.coerceIn(1, 180)
        val calendarId = prefs.selectedCalendarId.takeIf { it != PrefsV2.NO_CALENDAR_FILTER }
        val now = System.currentTimeMillis()
        val windowEnd = now + 48L * 60L * 60L * 1000L
        val upcoming = calendarRepository.getUpcomingEvents(now, windowEnd, calendarId)
        val next = upcoming.firstOrNull() ?: return
        val triggerAt = next.startTimeMillis - minutesBefore * 60_000L
        if (triggerAt <= now) {
            // Event starts sooner than the lead time — skip to the following one if any.
            val later = upcoming.drop(1).firstOrNull() ?: return
            scheduleAlarm(
                later.startTimeMillis - minutesBefore * 60_000L,
                later.title,
                later.startTimeMillis,
            )
            return
        }
        scheduleAlarm(triggerAt, next.title, next.startTimeMillis)
    }

    fun cancel() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(0L, "", 0L))
    }

    private fun scheduleAlarm(triggerAt: Long, title: String, eventStart: Long) {
        if (triggerAt <= System.currentTimeMillis()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(triggerAt, title, eventStart)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Timber.d("Calendar reminder scheduled at %d for %s", triggerAt, title)
        } catch (e: SecurityException) {
            Timber.w(e, "Exact alarm not permitted; reminder skipped")
        }
    }

    private fun pendingIntent(triggerAt: Long, title: String, eventStart: Long): PendingIntent {
        val intent = Intent(context, CalendarReminderReceiver::class.java).apply {
            action = CalendarReminderReceiver.ACTION_REMIND
            putExtra(CalendarReminderReceiver.EXTRA_TITLE, title)
            putExtra(CalendarReminderReceiver.EXTRA_EVENT_START, eventStart)
            putExtra(CalendarReminderReceiver.EXTRA_TRIGGER_AT, triggerAt)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            AppConstants.PENDING_INTENT_FLAGS or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val REQUEST_CODE = 7101
    }
}
