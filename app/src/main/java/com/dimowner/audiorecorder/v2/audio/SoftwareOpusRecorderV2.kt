/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import android.media.AudioFormat
import android.media.AudioRecord
import com.dimowner.audiorecorder.AppConstants.RECORDING_VISUALIZATION_INTERVAL_NEW
import com.dimowner.audiorecorder.IntArrayList
import com.dimowner.audiorecorder.audio.sumOfAmplitudes
import com.dimowner.audiorecorder.exception.AlreadyRecordingException
import com.dimowner.audiorecorder.exception.InvalidOutputFile
import com.dimowner.audiorecorder.exception.RecorderInitException
import io.element.android.opusencoder.OggOpusEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Timer
import java.util.TimerTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opus-in-Ogg recorder backed by Element's libopusenc JNI wrapper.
 *
 * Used when the device has no MediaCodec Opus encoder (e.g. some Onyx firmwares). The Element
 * API is mono-only: stereo capture is downmixed before encode.
 */
@Singleton
class SoftwareOpusRecorderV2 @Inject constructor(
    private val coroutineScope: CoroutineScope,
    private val dpdfNoiseReduction: com.dimowner.audiorecorder.v2.audio.dpdf.DpdfNoiseReductionController,
) : RecorderV2 {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var opusEncoder: OggOpusEncoder? = null

    @Volatile private var _isRecording: Boolean = false
    @Volatile private var _isPaused: Boolean = false

    override val isRecording: Boolean
        get() = _isRecording
    override val isPaused: Boolean
        get() = _isPaused

    override fun audioSessionId(): Int = audioRecord?.audioSessionId ?: -1

    @Volatile private var durationMills: Long = 0
    @Volatile private var currentRun: Any? = null

    private var timerProgress: Timer? = null
    private val amplitudesBuffer: IntArrayList = IntArrayList()
    @Volatile private var lastNonZeroAmplitude: Int = 0
    @Volatile private var lastEmittedDurationMills: Long = -1L

    private val _event = MutableSharedFlow<RecorderEvent>()
    override fun subscribeRecorderEvents(): Flow<RecorderEvent> = _event

    override fun startRecording(
        outputFile: File,
        channelCount: Int,
        sampleRate: Int,
        bitrate: Int,
        maxRecordingDurationMills: Int,
        audioInput: AudioInput,
    ): Boolean {
        Timber.d(
            "SoftwareOpusRecorderV2.startRecording outputFile: ${outputFile.absolutePath}" +
                " channelCount: $channelCount sampleRate: $sampleRate bitrate: $bitrate" +
                " maxRecordingDurationMills: $maxRecordingDurationMills audioInput: $audioInput"
        )
        if (_isRecording || opusEncoder != null) {
            Timber.e("Recording is already in progress.")
            emitEvent(RecorderEvent.OnError(AlreadyRecordingException()))
            return false
        }
        amplitudesBuffer.clear()
        lastNonZeroAmplitude = 0
        lastEmittedDurationMills = -1L

        if (!outputFile.exists() || !outputFile.isFile) {
            emitEvent(RecorderEvent.OnError(InvalidOutputFile()))
            return false
        }

        val elementRate = toElementSampleRate(sampleRate)
        if (elementRate == null) {
            Timber.e("Unsupported sample rate for software Opus: $sampleRate")
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }

        val captureChannels = channelCount.coerceIn(1, 2)
        val channelConfig = if (captureChannels == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val bitsPerSample = 16
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            Timber.e("Invalid buffer size: $bufferSize")
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }

        val frameSize = captureChannels * (bitsPerSample / 8)
        val readChunkSize = ((sampleRate * RECORDING_VISUALIZATION_INTERVAL_NEW / 1000) * frameSize)
            .coerceAtLeast(frameSize)
            .coerceAtMost(bufferSize)

        val recorder = try {
            AudioRecordFactory.create(
                audioInput, sampleRate, channelConfig, audioEncoding, bufferSize
            )
        } catch (e: SecurityException) {
            Timber.e(e, "AudioRecord creation failed due to missing permission")
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "AudioRecord creation failed")
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        } catch (e: UnsupportedOperationException) {
            Timber.e(e, "AudioRecord creation failed for $audioInput")
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord initialization failed")
            recorder.release()
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }

        val encoder = OggOpusEncoder.create()
        val initResult = try {
            encoder.init(outputFile.absolutePath, elementRate)
        } catch (e: Exception) {
            Timber.e(e, "OggOpusEncoder.init failed")
            recorder.release()
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }
        if (initResult != OPE_OK) {
            Timber.e("OggOpusEncoder.init returned $initResult")
            try {
                encoder.release()
            } catch (_: Exception) {
            }
            recorder.release()
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }
        val bitrateResult = encoder.setBitrate(bitrate.coerceIn(500, 512_000))
        if (bitrateResult != OPE_OK) {
            Timber.w("OggOpusEncoder.setBitrate returned $bitrateResult; continuing")
        }

        audioRecord = recorder
        opusEncoder = encoder

        try {
            recorder.startRecording()
        } catch (e: IllegalStateException) {
            Timber.e(e, "startRecording() failed")
            releaseEncoder()
            recorder.release()
            audioRecord = null
            emitEvent(RecorderEvent.OnError(RecorderInitException()))
            return false
        }

        val run = Any()
        currentRun = run
        _isRecording = true
        _isPaused = false
        durationMills = 0
        emitEvent(RecorderEvent.OnStartRecording)
        scheduleRecordingTimeUpdateBuffered()

        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            var totalMonoSamples = 0L
            var maxDurationReached = false
            var failed = false
            var capturedAudio = false
            val pcmProcessor = dpdfNoiseReduction.beginSession()

            try {
                while (isActive && _isRecording && currentRun === run) {
                    if (_isPaused) {
                        val readResult = recorder.read(buffer, 0, readChunkSize)
                        if (readResult < 0) {
                            Timber.e("AudioRecord read error during pause: $readResult")
                            failed = true
                            break
                        }
                        continue
                    }
                    val readResult = recorder.read(buffer, 0, readChunkSize)
                    if (readResult > 0) {
                        if (currentRun !== run) break
                        val processed = pcmProcessor?.processInPlace(buffer, readResult, captureChannels)
                            ?: readResult
                        val mono = pcmBytesToMonoShorts(buffer, processed, captureChannels)
                        if (mono.isNotEmpty()) {
                            val writeResult = encoder.encode(mono, mono.size)
                            if (writeResult < 0) {
                                Timber.e("OggOpusEncoder.encode failed: $writeResult")
                                failed = true
                                break
                            }
                            capturedAudio = true
                            totalMonoSamples += mono.size
                            durationMills = totalMonoSamples * 1000L / sampleRate
                        }
                        val amplitude = calculateAmplitude(buffer, processed)
                        synchronized(amplitudesBuffer) { amplitudesBuffer.add(amplitude) }

                        if (maxRecordingDurationMills > 0 &&
                            durationMills >= maxRecordingDurationMills
                        ) {
                            Timber.d("Max recording duration reached. Stop recording")
                            maxDurationReached = true
                            _isRecording = false
                            _isPaused = false
                            break
                        }
                    } else if (readResult < 0) {
                        Timber.e("AudioRecord read error: $readResult")
                        failed = true
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Software Opus recording loop failed")
                failed = true
            } finally {
                dpdfNoiseReduction.endSession()
                if (currentRun === run) {
                    _isRecording = false
                    _isPaused = false
                    stopRecordingTimer()
                }
                stopHardware(recorder)
                releaseEncoder()
            }

            if (currentRun === run) durationMills = 0

            recordingCompletionEvents(
                failed = failed,
                capturedAudio = capturedAudio,
                maxDurationReached = maxDurationReached,
            ).forEach(::emitEvent)
        }
        return true
    }

    override fun resumeRecording(): Boolean {
        if (!_isRecording || !_isPaused) return false
        _isPaused = false
        emitEvent(RecorderEvent.OnResumeRecording)
        scheduleRecordingTimeUpdateBuffered()
        return true
    }

    override fun pauseRecording(): Boolean {
        pauseRecordingTimer()
        if (!_isRecording) {
            Timber.e("Recording has already stopped or hasn't started")
            return false
        }
        if (_isPaused) {
            Timber.e("Recording has already paused")
            return false
        }
        _isPaused = true
        emitEvent(RecorderEvent.OnPauseRecording)
        return true
    }

    override fun stopRecording(): Boolean {
        stopRecordingTimer()
        if (!_isRecording) {
            Timber.e("Recording has already stopped or hasn't started")
            return false
        }
        _isRecording = false
        _isPaused = false
        synchronized(amplitudesBuffer) { amplitudesBuffer.clear() }
        return true
    }

    private fun releaseEncoder() {
        val encoder = opusEncoder ?: return
        opusEncoder = null
        try {
            encoder.release()
        } catch (e: Exception) {
            Timber.w(e, "OggOpusEncoder.release failed")
        }
    }

    private fun stopHardware(recorder: AudioRecord) {
        try {
            recorder.stop()
        } catch (e: IllegalStateException) {
            Timber.e(e, "stopHardware() problems")
        } finally {
            recorder.release()
            if (audioRecord === recorder) audioRecord = null
        }
    }

    private fun calculateAmplitude(buffer: ByteArray, bytesRead: Int): Int {
        if (bytesRead <= 0) return 0
        val sum = buffer.sumOfAmplitudes(bytesRead)
        return (sum / (bytesRead / 16 + 1)).toInt()
    }

    private fun emitEvent(event: RecorderEvent) {
        coroutineScope.launch {
            _event.emit(event)
        }
    }

    private fun scheduleRecordingTimeUpdateBuffered() {
        stopRecordingTimer()
        timerProgress = Timer()
        timerProgress?.schedule(object : TimerTask() {
            override fun run() {
                try {
                    readBufferedProgress()
                } catch (e: IllegalStateException) {
                    Timber.e(e)
                }
            }
        }, 0, RECORDING_VISUALIZATION_INTERVAL_NEW.toLong())
    }

    private fun stopRecordingTimer() {
        timerProgress?.cancel()
        timerProgress?.purge()
        timerProgress = null
    }

    private fun pauseRecordingTimer() {
        timerProgress?.cancel()
        timerProgress?.purge()
        timerProgress = null
    }

    private fun readBufferedProgress() {
        if (!_isRecording || _isPaused) return
        val currentDuration = durationMills
        if (currentDuration == lastEmittedDurationMills) return
        synchronized(amplitudesBuffer) {
            val bufferSize = amplitudesBuffer.size()
            if (bufferSize > 0) {
                lastEmittedDurationMills = currentDuration
                var amp = amplitudesBuffer.get(bufferSize - 1)
                if (amp == 0) amp = lastNonZeroAmplitude
                else lastNonZeroAmplitude = amp
                amplitudesBuffer.clear()
                amplitudesBuffer.add(amp)
                emitEvent(
                    RecorderEvent.OnRecordingProgress(
                        durationMills = currentDuration,
                        amplitude = amp,
                    )
                )
            }
        }
    }
}
