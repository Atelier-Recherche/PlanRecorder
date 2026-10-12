/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.di

import com.dimowner.audiorecorder.v2.data.PrefsV2
import com.dimowner.audiorecorder.v2.data.calendar.CalendarRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Safe access to calendar/prefs from [android.appwidget.AppWidgetProvider] code paths
 * (especially custom refresh broadcasts) without relying on field injection timing.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun prefs(): PrefsV2
    fun calendarRepository(): CalendarRepository
}
