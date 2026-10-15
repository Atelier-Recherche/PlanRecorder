/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import com.dimowner.audiorecorder.v2.data.model.isSystemOpusEncoderAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opus-in-Ogg recorder: platform [MediaOpusRecorderV2] when a MediaCodec Opus encoder exists,
 * otherwise [SoftwareOpusRecorderV2] (libopusenc via Element).
 *
 * Same facade pattern as [M4aRecorderV2]: the service binds one recorder instance and this class
 * owns switching the backend without the service noticing.
 */
@Singleton
class OpusRecorderV2 @Inject constructor(
    private val mediaRecorder: MediaOpusRecorderV2,
    private val softwareRecorder: SoftwareOpusRecorderV2,
    private val coroutineScope: CoroutineScope,
) : RecorderV2 {

    @Volatile private var active: RecorderV2? = null

    private val _event = MutableSharedFlow<RecorderEvent>()
    override fun subscribeRecorderEvents(): Flow<RecorderEvent> = _event

    override fun audioSessionId(): Int = active?.audioSessionId() ?: -1

    init {
        relay(mediaRecorder)
        relay(softwareRecorder)
    }

    private fun relay(recorder: RecorderV2) {
        coroutineScope.launch {
            recorder.subscribeRecorderEvents().collect { event ->
                if (active === recorder) _event.emit(event)
            }
        }
    }

    override fun startRecording(
        outputFile: File,
        channelCount: Int,
        sampleRate: Int,
        bitrate: Int,
        maxRecordingDurationMills: Int,
        audioInput: AudioInput,
    ): Boolean {
        val useSystem = isSystemOpusEncoderAvailable()
        active = if (useSystem) mediaRecorder else softwareRecorder
        Timber.d(
            "OpusRecorderV2 starting with %s backend",
            if (useSystem) "MediaRecorder" else "software",
        )
        return active!!.startRecording(
            outputFile,
            channelCount,
            sampleRate,
            bitrate,
            maxRecordingDurationMills,
            audioInput,
        )
    }

    override fun resumeRecording(): Boolean = active?.resumeRecording() ?: false

    override fun pauseRecording(): Boolean = active?.pauseRecording() ?: false

    override fun stopRecording(): Boolean = active?.stopRecording() ?: false

    override val isRecording: Boolean
        get() = active?.isRecording ?: false

    override val isPaused: Boolean
        get() = active?.isPaused ?: false
}
