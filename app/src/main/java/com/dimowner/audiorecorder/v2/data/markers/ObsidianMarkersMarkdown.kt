/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data.markers

/**
 * Builds an Obsidian-friendly markdown note with audio timestamp links:
 * `[[file.opus#t=508|08:28]] debut de session`
 */
object ObsidianMarkersMarkdown {

    fun build(audioFileName: String, markers: List<RecordingMarker>): String {
        val base = audioFileName.substringBeforeLast('.').ifBlank { audioFileName }
        val sb = StringBuilder()
        sb.append("# ").append(base).append('\n').append('\n')
        for (marker in markers.sortedBy { it.timeMillis }) {
            val seconds = marker.timeMillis / 1000L
            val label = formatTimecode(marker.timeMillis)
            sb.append("[[")
                .append(audioFileName)
                .append("#t=")
                .append(seconds)
                .append('|')
                .append(label)
                .append("]] ")
                .append(marker.note)
                .append('\n')
        }
        return sb.toString()
    }

    fun formatTimecode(timeMillis: Long): String {
        val totalSec = (timeMillis / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }
}
