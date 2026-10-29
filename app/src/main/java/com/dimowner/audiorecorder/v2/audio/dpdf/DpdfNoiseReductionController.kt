/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import android.content.Context
import com.dimowner.audiorecorder.v2.data.PrefsV2
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazily loads the DPDFNet TFLite model and vends per-session [PcmLiveProcessor] instances
 * when [PrefsV2.dpdfNoiseReductionEnabled] is true.
 */
@Singleton
class DpdfNoiseReductionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PrefsV2,
) {
    private val sharedEnhancer = AtomicReference<DpdfNetEnhancer?>(null)
    private val sessionProcessor = AtomicReference<PcmLiveProcessor?>(null)

    val isEnabled: Boolean
        get() = prefs.dpdfNoiseReductionEnabled

    /**
     * Returns a processor for a new recording session, or null when denoising is off / failed to load.
     * Call [endSession] when the recording stops.
     */
    @Synchronized
    fun beginSession(): PcmLiveProcessor? {
        if (!prefs.dpdfNoiseReductionEnabled) {
            sessionProcessor.set(null)
            return null
        }
        val enhancer = obtainEnhancer() ?: return null
        enhancer.reset()
        val processor = DpdfPcmLiveProcessor(enhancer)
        sessionProcessor.set(processor)
        return processor
    }

    /** Active session processor, if any (recorders may also hold the reference from [beginSession]). */
    fun currentProcessor(): PcmLiveProcessor? = sessionProcessor.get()

    @Synchronized
    fun endSession() {
        val proc = sessionProcessor.getAndSet(null)
        // Do not close the shared enhancer — only reset STFT/RNN state for the next take.
        try {
            proc?.reset()
        } catch (e: Exception) {
            Timber.w(e, "DPDFNet session reset failed")
        }
    }

    @Synchronized
    fun release() {
        endSession()
        sharedEnhancer.getAndSet(null)?.close()
    }

    private fun obtainEnhancer(): DpdfNetEnhancer? {
        sharedEnhancer.get()?.let { return it }
        return try {
            val created = DpdfNetEnhancer.create(context)
            if (sharedEnhancer.compareAndSet(null, created)) {
                created
            } else {
                created.close()
                sharedEnhancer.get()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load DPDFNet baseline model")
            null
        }
    }
}
