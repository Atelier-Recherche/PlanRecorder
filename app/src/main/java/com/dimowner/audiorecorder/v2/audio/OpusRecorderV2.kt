/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opus-in-Ogg recorder (API 29+). Files use the `.opus` extension by convention.
 */
@Singleton
class OpusRecorderV2 @Inject constructor(
    @ApplicationContext applicationContext: Context,
    coroutineScope: CoroutineScope,
) : MediaRecorderBase(applicationContext, coroutineScope) {

    override val recordingLogTag: String = "OPUS "

    override fun configureRecorder(
        recorder: MediaRecorder,
        channelCount: Int,
        sampleRate: Int,
        bitrate: Int,
    ) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Opus recording requires API 29+"
        }
        recorder.apply {
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioChannels(channelCount)
            setAudioSamplingRate(sampleRate)
            setAudioEncodingBitRate(bitrate)
        }
    }
}
