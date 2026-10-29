/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RealFftTest {

    @Test
    fun `vorbis window peaks near the center and is zero-ish at edges`() {
        val w = DpdfNetEnhancer.vorbisWindow(320)
        assertEquals(320, w.size)
        assertTrue(w[0] < 0.05f)
        assertTrue(w[319] < 0.05f)
        assertTrue(w[160] > 0.9f)
    }

    @Test
    fun `rfft then irfft reconstructs a sine within tolerance`() {
        val n = 320
        val input = FloatArray(n) { i ->
            (0.5 * sin(2.0 * Math.PI * 5 * i / n) + 0.25 * cos(2.0 * Math.PI * 11 * i / n)).toFloat()
        }
        val bins = n / 2 + 1
        val re = FloatArray(bins)
        val im = FloatArray(bins)
        RealFft.rfft(input, re, im)
        val out = FloatArray(n)
        RealFft.irfft(re, im, n, out)
        var maxErr = 0f
        for (i in 0 until n) {
            maxErr = maxOf(maxErr, abs(input[i] - out[i]))
        }
        assertTrue("max reconstruction error=$maxErr", maxErr < 1e-3f)
    }

    @Test
    fun `dpdf hop constants match baseline 16 kHz contract`() {
        assertEquals(16_000, DpdfNetEnhancer.SAMPLE_RATE)
        assertEquals(320, DpdfNetEnhancer.WIN_LEN)
        assertEquals(160, DpdfNetEnhancer.HOP_SIZE)
        assertEquals(161, DpdfNetEnhancer.FREQ_BINS)
    }
}
