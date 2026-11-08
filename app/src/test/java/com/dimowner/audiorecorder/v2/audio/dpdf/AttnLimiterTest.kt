/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttnLimiterTest {

    @Test
    fun `zero db returns delayed noisy after priming`() {
        val n = DpdfNetEnhancer.FREQ_BINS
        val limiter = AttnLimiter()
        limiter.setDb(0f)
        val noisyRe = FloatArray(n) { it.toFloat() }
        val noisyIm = FloatArray(n) { (it * 0.5f) }
        repeat(AttnLimiter.NOISY_FRAME_OFFSET) {
            val enhRe = FloatArray(n)
            val enhIm = FloatArray(n)
            limiter.apply(noisyRe, noisyIm, enhRe, enhIm)
        }
        val enhRe = FloatArray(n) { 7f }
        val enhIm = FloatArray(n) { 7f }
        limiter.apply(noisyRe, noisyIm, enhRe, enhIm)
        assertEquals(noisyRe[10], enhRe[10], 1e-4f)
        assertEquals(noisyIm[10], enhIm[10], 1e-4f)
    }

    @Test
    fun `high db leaves enhanced nearly unchanged after priming`() {
        val n = DpdfNetEnhancer.FREQ_BINS
        val limiter = AttnLimiter()
        limiter.setDb(AttnLimiter.DEFAULT_ATTN_LIMIT_DB) // unlimited / blend disabled
        val noisyRe = FloatArray(n) { 1f }
        val noisyIm = FloatArray(n) { 0f }
        repeat(AttnLimiter.NOISY_FRAME_OFFSET + 1) {
            val enhRe = FloatArray(n) { 9f }
            val enhIm = FloatArray(n) { 3f }
            limiter.apply(noisyRe, noisyIm, enhRe, enhIm)
            assertTrue(enhRe[0] > 8.5f)
        }
    }
}
