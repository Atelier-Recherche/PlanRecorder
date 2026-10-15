/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import io.element.android.opusencoder.configuration.SampleRate as ElementSampleRate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoftwareOpusSupportTest {

    @Test
    fun `toElementSampleRate maps Opus-legal rates`() {
        assertEquals(ElementSampleRate.Rate16kHz, toElementSampleRate(16_000))
        assertEquals(ElementSampleRate.Rate48kHz, toElementSampleRate(48_000))
        assertEquals(ElementSampleRate.Rate8khz, toElementSampleRate(8_000))
        assertNull(toElementSampleRate(44_100))
        assertNull(toElementSampleRate(22_050))
    }

    @Test
    fun `pcmBytesToMonoShorts copies mono little-endian PCM`() {
        // samples: 1, -2
        val bytes = byteArrayOf(
            0x01, 0x00,
            0xFE.toByte(), 0xFF.toByte(),
        )
        val mono = pcmBytesToMonoShorts(bytes, bytes.size, channelCount = 1)
        assertArrayEquals(shortArrayOf(1, -2), mono)
    }

    @Test
    fun `pcmBytesToMonoShorts downmixes stereo to average`() {
        // L=100, R=200 → 150; L=-10, R=10 → 0
        val bytes = byteArrayOf(
            100, 0, // L = 100
            200.toByte(), 0, // R = 200 (200 fits in signed byte? 200 > 127)
            // Use proper little-endian for 100 and 200
        )
        // Rebuild properly: 100 = 0x0064, 200 = 0x00C8, -10 = 0xFFF6, 10 = 0x000A
        val stereo = byteArrayOf(
            0x64, 0x00, // L 100
            0xC8.toByte(), 0x00, // R 200
            0xF6.toByte(), 0xFF.toByte(), // L -10
            0x0A, 0x00, // R 10
        )
        val mono = pcmBytesToMonoShorts(stereo, stereo.size, channelCount = 2)
        assertArrayEquals(shortArrayOf(150, 0), mono)
    }

    @Test
    fun `pcmShortsToMono averages interleaved stereo`() {
        val stereo = shortArrayOf(100, 200, -10, 10)
        val mono = pcmShortsToMono(stereo, channelCount = 2)
        assertArrayEquals(shortArrayOf(150, 0), mono)
    }

    @Test
    fun `pcmShortsToMono returns mono input unchanged`() {
        val mono = shortArrayOf(1, 2, 3)
        assertArrayEquals(mono, pcmShortsToMono(mono, channelCount = 1))
    }
}
