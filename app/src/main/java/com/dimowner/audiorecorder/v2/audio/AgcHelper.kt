/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import android.media.audiofx.AutomaticGainControl
import timber.log.Timber

object AgcHelper {

    /**
     * Enables Android [AutomaticGainControl] on [audioSessionId] when the device supports it.
     * Returns the effect instance to keep alive for the recording duration, or null.
     */
    fun enable(audioSessionId: Int): AutomaticGainControl? {
        if (audioSessionId <= 0) return null
        if (!AutomaticGainControl.isAvailable()) {
            Timber.d("AGC not available on this device")
            return null
        }
        return try {
            AutomaticGainControl.create(audioSessionId)?.also { agc ->
                agc.enabled = true
                Timber.d("AGC enabled on session %d", audioSessionId)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable AGC")
            null
        }
    }

    fun release(agc: AutomaticGainControl?) {
        try {
            agc?.release()
        } catch (_: Exception) {
            // ignore
        }
    }
}
