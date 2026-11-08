/*
 * Copyright 2026 PlanRecorder contributors
 *
 * Port of HushMic's streaming attenuation limiter (crates/hushmic-denoiser/src/attn.rs).
 * Blends a delayed noisy spectrum into the enhanced one so the model cannot erase speech.
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import kotlin.math.pow

/**
 * Caps how hard DPDFNet may suppress: `out = α·noisy_delayed + (1-α)·enhanced`,
 * with `α = 10^(-db/20)`.
 *
 * - Higher [db] → more aggressive (closer to pure enhanced).
 * - Lower [db] → gentler (keeps more of the original).
 * - [db] <= 0 or non-finite → treat as pure noisy after delay (no enhancement).
 * - Very large [db] (>= 80) → unlimited (skip blend).
 */
class AttnLimiter(
    private val freqBins: Int = DpdfNetEnhancer.FREQ_BINS,
    private val noisyFrameOffset: Int = NOISY_FRAME_OFFSET,
) {
    private var alpha = 0f
    private var enabled = false
    private val ring = ArrayDeque<Pair<FloatArray, FloatArray>>(noisyFrameOffset + 1)

    fun setDb(db: Float) {
        if (!db.isFinite() || db >= 80f) {
            enabled = false
            alpha = 0f
            return
        }
        if (db <= 0f) {
            alpha = 1f
            enabled = true
            return
        }
        alpha = 10f.pow(-db / 20f).coerceAtMost(1f)
        enabled = alpha > 1e-6f
    }

    fun reset() {
        ring.clear()
    }

    /**
     * In-place blend of [enhReal]/[enhImag] with a delayed copy of [noisyReal]/[noisyImag].
     */
    fun apply(
        noisyReal: FloatArray,
        noisyImag: FloatArray,
        enhReal: FloatArray,
        enhImag: FloatArray,
    ) {
        val noisyCopy = FloatArray(freqBins) { noisyReal[it] } to FloatArray(freqBins) { noisyImag[it] }
        ring.addLast(noisyCopy)
        val delayed = if (ring.size > noisyFrameOffset) ring.removeFirst() else null
        if (delayed == null || !enabled) return
        val (dRe, dIm) = delayed
        val a = alpha
        val oneMinus = 1f - a
        for (i in 0 until freqBins) {
            enhReal[i] = a * dRe[i] + oneMinus * enhReal[i]
            enhImag[i] = a * dIm[i] + oneMinus * enhImag[i]
        }
    }

    companion object {
        /** Matches HushMic / Ceva offline alignment of enhanced vs noisy frames. */
        const val NOISY_FRAME_OFFSET = 4

        /**
         * Match the desktop HushMic Gradio default (attn slider 0 = no blend / full model).
         * Values >= 80 disable the noisy-floor blend.
         */
        const val DEFAULT_ATTN_LIMIT_DB = 100f
    }
}
