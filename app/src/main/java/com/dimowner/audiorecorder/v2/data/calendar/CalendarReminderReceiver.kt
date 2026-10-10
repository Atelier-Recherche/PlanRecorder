/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data.calendar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dimowner.audiorecorder.AppConstants
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.app.TransparentRecordingActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CalendarReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderScheduler: CalendarReminderScheduler

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                reminderScheduler.reschedule()
            }
            ACTION_REMIND -> {
                showNotification(
                    context,
                    intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                )
                reminderScheduler.reschedule()
            }
        }
    }

    private fun showNotification(context: Context, title: String) {
        ensureChannel(context)
        val recordIntent = Intent(context, TransparentRecordingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val contentPi = PendingIntent.getActivity(
            context,
            7102,
            recordIntent,
            AppConstants.PENDING_INTENT_FLAGS or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = title.ifBlank { context.getString(R.string.calendar_reminder_default_title) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record_rec)
            .setContentTitle(context.getString(R.string.calendar_reminder_notif_title))
            .setContentText(context.getString(R.string.calendar_reminder_notif_text, label))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .addAction(
                R.drawable.ic_record_rec,
                context.getString(R.string.calendar_reminder_action_record),
                contentPi,
            )
            .build()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.calendar_reminder_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_REMIND = "com.planrecorder.action.CALENDAR_REMIND"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_EVENT_START = "extra_event_start"
        const val EXTRA_TRIGGER_AT = "extra_trigger_at"
        private const val CHANNEL_ID = "planrecorder_calendar_reminder"
        private const val NOTIF_ID = 7103
    }
}
