/*
 * Copyright 2026 PlanRecorder contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.dimowner.audiorecorder.v2.data.calendar

/**
 * Active calendar event resolved at recording time (typically via DAVx⁵ sync).
 */
data class CalendarEvent(
    val title: String,
    val calendarName: String,
    val description: String?,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
)
