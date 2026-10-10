/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CalendarRepository {

    override fun listCalendars(): List<DeviceCalendar> {
        if (!hasCalendarPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            DeviceCalendar(
                                id = cursor.getLong(0),
                                displayName = cursor.getString(1).orEmpty().ifBlank { "Calendar ${cursor.getLong(0)}" },
                                accountName = cursor.getString(2),
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (e: SecurityException) {
            Timber.w(e, "READ_CALENDAR denied while listing calendars")
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to list calendars")
            emptyList()
        }
    }

    override fun getActiveEvent(
        timeMillis: Long,
        calendarId: Long?,
        graceMillis: Long,
    ): CalendarEvent? = getActiveEvents(timeMillis, calendarId, graceMillis).firstOrNull()

    override fun getActiveEvents(
        timeMillis: Long,
        calendarId: Long?,
        graceMillis: Long,
    ): List<CalendarEvent> {
        val grace = graceMillis.coerceAtLeast(0L)
        val events = queryInstances(
            begin = timeMillis - grace,
            end = timeMillis + grace,
            calendarId = calendarId,
            sortOrder = "${CalendarContract.Instances.BEGIN} DESC",
        )
        if (grace == 0L) return events
        // Keep events whose expanded interval contains [timeMillis].
        return events.filter { event ->
            timeMillis >= event.startTimeMillis - grace &&
                timeMillis <= event.endTimeMillis + grace
        }
    }

    override fun getUpcomingEvents(
        fromMillis: Long,
        toMillis: Long,
        calendarId: Long?,
    ): List<CalendarEvent> {
        return queryInstances(
            begin = fromMillis,
            end = toMillis,
            calendarId = calendarId,
            sortOrder = "${CalendarContract.Instances.BEGIN} ASC",
        ).filter { it.startTimeMillis >= fromMillis }
    }

    private fun queryInstances(
        begin: Long,
        end: Long,
        calendarId: Long?,
        sortOrder: String,
    ): List<CalendarEvent> {
        if (!hasCalendarPermission()) return emptyList()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            appendPath(begin.toString())
            appendPath(end.toString())
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_ID,
        )

        val selection: String?
        val selectionArgs: Array<String>?
        if (calendarId != null) {
            selection = "${CalendarContract.Instances.CALENDAR_ID} = ?"
            selectionArgs = arrayOf(calendarId.toString())
        } else {
            selection = null
            selectionArgs = null
        }

        return try {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(0).orEmpty()
                        val calendarName = cursor.getString(1).orEmpty()
                        if (title.isBlank() && calendarName.isBlank()) continue
                        add(
                            CalendarEvent(
                                title = title,
                                calendarName = calendarName,
                                description = cursor.getString(2),
                                startTimeMillis = cursor.getLong(3),
                                endTimeMillis = cursor.getLong(4),
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (e: SecurityException) {
            Timber.w(e, "READ_CALENDAR denied while querying instances")
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to query CalendarContract.Instances")
            emptyList()
        }
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
}
