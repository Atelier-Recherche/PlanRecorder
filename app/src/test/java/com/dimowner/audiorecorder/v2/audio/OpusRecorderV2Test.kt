/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import android.media.MediaRecorder
import com.dimowner.audiorecorder.v2.data.model.isSystemOpusEncoderAvailable
import com.dimowner.audiorecorder.v2.data.model.resetSystemOpusSupportCacheForTests
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpusRecorderV2Test {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var mediaRecorder: MediaOpusRecorderV2
    private lateinit var softwareRecorder: SoftwareOpusRecorderV2
    private lateinit var outputFile: File

    private val micInput = AudioInput.Mic(MediaRecorder.AudioSource.DEFAULT)

    @Before
    fun setup() {
        mediaRecorder = mockk(relaxed = true)
        softwareRecorder = mockk(relaxed = true)
        every { mediaRecorder.subscribeRecorderEvents() } returns emptyFlow()
        every { softwareRecorder.subscribeRecorderEvents() } returns emptyFlow()
        every { mediaRecorder.startRecording(any(), any(), any(), any(), any(), any()) } returns true
        every { softwareRecorder.startRecording(any(), any(), any(), any(), any(), any()) } returns true
        outputFile = folder.newFile("record.opus")
        mockkStatic("com.dimowner.audiorecorder.v2.data.model.RecordingFormatKt")
        resetSystemOpusSupportCacheForTests()
    }

    @After
    fun tearDown() {
        unmockkStatic("com.dimowner.audiorecorder.v2.data.model.RecordingFormatKt")
        resetSystemOpusSupportCacheForTests()
    }

    private fun CoroutineScope.createRecorder() =
        OpusRecorderV2(mediaRecorder, softwareRecorder, this)

    @Test
    fun `uses MediaRecorder when system Opus encoder is available`() = runTest {
        every { isSystemOpusEncoderAvailable() } returns true
        val recorder = createRecorder()

        assertTrue(
            recorder.startRecording(outputFile, 1, 16_000, 32_000, 0, micInput)
        )

        verify(exactly = 1) {
            mediaRecorder.startRecording(outputFile, 1, 16_000, 32_000, 0, micInput)
        }
        verify(exactly = 0) {
            softwareRecorder.startRecording(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `uses software encoder when system Opus encoder is missing`() = runTest {
        every { isSystemOpusEncoderAvailable() } returns false
        val recorder = createRecorder()

        assertTrue(
            recorder.startRecording(outputFile, 1, 16_000, 32_000, 0, micInput)
        )

        verify(exactly = 1) {
            softwareRecorder.startRecording(outputFile, 1, 16_000, 32_000, 0, micInput)
        }
        verify(exactly = 0) {
            mediaRecorder.startRecording(any(), any(), any(), any(), any(), any())
        }
    }
}
