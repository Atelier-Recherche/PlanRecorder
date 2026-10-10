/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data.markers

import javax.inject.Inject
import javax.inject.Singleton

data class RecordingMarker(
    val timeMillis: Long,
    val note: String,
)

@Singleton
class RecordingMarkersStore @Inject constructor() {
    private val lock = Any()
    private val markers = mutableListOf<RecordingMarker>()

    fun clear() {
        synchronized(lock) { markers.clear() }
    }

    fun add(timeMillis: Long, note: String) {
        val cleaned = note.trim().ifBlank { "marqueur" }
        synchronized(lock) {
            markers.add(RecordingMarker(timeMillis = timeMillis.coerceAtLeast(0L), note = cleaned))
        }
    }

    fun snapshot(): List<RecordingMarker> = synchronized(lock) { markers.toList() }

    fun isEmpty(): Boolean = synchronized(lock) { markers.isEmpty() }
}
