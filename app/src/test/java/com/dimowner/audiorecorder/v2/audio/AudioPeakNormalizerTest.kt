package com.dimowner.audiorecorder.v2.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioPeakNormalizerTest {

    @Test
    fun `robustPeakAbs ignores sparse absolute max spike`() {
        val n = 10_000
        val samples = ShortArray(n) { 1_000 }
        // One full-scale click should not dominate the 99.7th percentile.
        samples[5_000] = 32_000
        val robust = AudioPeakNormalizer.robustPeakAbs(samples)
        assertTrue("robust peak should stay near speech level, got $robust", robust in 900..1_200)
    }

    @Test
    fun `robustPeakAbs on silence returns at least 1`() {
        val samples = ShortArray(100) { 0 }
        assertEquals(1, AudioPeakNormalizer.robustPeakAbs(samples))
    }

    @Test
    fun `processForNormalize boosts quiet speech despite loud click`() {
        val sampleRate = 16_000
        val channels = 1
        // ~1 s of quiet speech (~ -26 dBFS ≈ 1640), plus a few click spikes.
        val samples = ShortArray(sampleRate) { 1_640 }
        samples[8_000] = 30_000
        samples[8_001] = -29_000
        samples[12_000] = 31_000

        val out = AudioPeakNormalizer.processForNormalize(samples, sampleRate, channels)

        // Speech samples away from click zones should be near TARGET_PEAK.
        val speechPeak = (0 until 7_000).maxOf { abs(out[it].toInt()) }
        assertTrue(
            "speech should be boosted near target (${AudioPeakNormalizer.TARGET_PEAK}), got $speechPeak",
            speechPeak > AudioPeakNormalizer.TARGET_PEAK * 0.7f,
        )
        // Old absolute-peak normalize would have applied gain≈1 → speech stayed ~1640.
        assertTrue("speech must be much louder than input 1640, got $speechPeak", speechPeak > 10_000)
    }

    @Test
    fun `softenTransients attenuates brief click without killing speech`() {
        val sampleRate = 16_000
        val frame = (sampleRate * 10) / 1000 // 10 ms
        // Build: speech frames, then one click frame, then speech again.
        val speechFrames = 20
        val samples = ShortArray((speechFrames + 1 + speechFrames) * frame)
        var i = 0
        repeat(speechFrames) {
            for (j in 0 until frame) samples[i++] = 2_000
        }
        val clickStart = i
        for (j in 0 until frame) {
            // Sparse impulse: only a few samples near full scale (keyboard-like).
            samples[i++] = if (j < 3) 30_000 else 0
        }
        val afterClick = i
        repeat(speechFrames) {
            for (j in 0 until frame) samples[i++] = 2_000
        }

        val out = AudioPeakNormalizer.softenTransients(samples, sampleRate, 1)
        val clickPeak = (clickStart until afterClick).maxOf { abs(out[it].toInt()) }
        assertTrue("click should be attenuated, got peak $clickPeak", clickPeak < 8_000)
        val speechPeak = (0 until clickStart).maxOf { abs(out[it].toInt()) }
        assertEquals(2_000, speechPeak)
    }

    @Test
    fun `processForNormalize empty array is safe`() {
        val out = AudioPeakNormalizer.processForNormalize(ShortArray(0), 16_000, 1)
        assertEquals(0, out.size)
    }

    @Test
    fun `applyGainWithSoftLimiter keeps values within short range`() {
        val samples = shortArrayOf(20_000, (-20_000).toShort(), 100)
        val out = AudioPeakNormalizer.applyGainWithSoftLimiter(samples, 3f)
        for (s in out) {
            assertTrue(s >= Short.MIN_VALUE && s <= Short.MAX_VALUE)
        }
        assertTrue(abs(out[0].toInt()) <= Short.MAX_VALUE)
    }
}
