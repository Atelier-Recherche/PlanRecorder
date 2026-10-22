/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import android.media.audiofx.NoiseSuppressor
import timber.log.Timber

object NoiseSuppressorHelper {

    /**
     * Enables Android [NoiseSuppressor] on [audioSessionId] when the device supports it.
     * Returns the effect instance to keep alive for the recording duration, or null.
     */
    fun enable(audioSessionId: Int): NoiseSuppressor? {
        if (audioSessionId <= 0) return null
        if (!NoiseSuppressor.isAvailable()) {
            Timber.d("NoiseSuppressor not available on this device")
            return null
        }
        return try {
            NoiseSuppressor.create(audioSessionId)?.also { ns ->
                ns.enabled = true
                Timber.d("NoiseSuppressor enabled on session %d", audioSessionId)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable NoiseSuppressor")
            null
        }
    }

    fun release(ns: NoiseSuppressor?) {
        try {
            ns?.release()
        } catch (_: Exception) {
            // ignore
        }
    }
}
