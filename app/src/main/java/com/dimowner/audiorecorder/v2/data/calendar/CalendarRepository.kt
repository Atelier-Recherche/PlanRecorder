/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data.calendar

/**
 * Looks up calendar events / device calendars (typically synced via DAVx⁵).
 */
interface CalendarRepository {

    fun listCalendars(): List<DeviceCalendar>

    /**
     * Returns the event whose interval contains [timeMillis], or null if none / no permission.
     * When [calendarId] is non-null, only that calendar is searched.
     * When several overlap, the most recently started one wins.
     */
    fun getActiveEvent(
        timeMillis: Long = System.currentTimeMillis(),
        calendarId: Long? = null,
    ): CalendarEvent?

    /**
     * All events overlapping [timeMillis] (optionally filtered by calendar).
     * Ordered by begin time descending (most recently started first).
     */
    fun getActiveEvents(
        timeMillis: Long = System.currentTimeMillis(),
        calendarId: Long? = null,
    ): List<CalendarEvent>

    /**
     * Events that start in `[fromMillis, toMillis]` (optionally filtered by calendar),
     * ordered by begin time ascending.
     */
    fun getUpcomingEvents(
        fromMillis: Long,
        toMillis: Long,
        calendarId: Long? = null,
    ): List<CalendarEvent>
}
