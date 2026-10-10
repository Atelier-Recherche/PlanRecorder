/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data

import com.dimowner.audiorecorder.v2.data.calendar.CalendarEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the calendar snapshot captured when recording starts, so templates at stop
 * stay consistent even if the event ends during the recording.
 */
@Singleton
class RecordingSessionContext @Inject constructor() {
    @Volatile
    var capturedEvent: CalendarEvent? = null

    @Volatile
    var capturedAtMillis: Long = 0L

    fun capture(event: CalendarEvent?, timeMillis: Long = System.currentTimeMillis()) {
        capturedEvent = event
        capturedAtMillis = timeMillis
    }

    fun clear() {
        capturedEvent = null
        capturedAtMillis = 0L
    }
}
