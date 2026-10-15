/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import io.element.android.opusencoder.OggOpusEncoder
import io.element.android.opusencoder.configuration.SampleRate as ElementSampleRate
import timber.log.Timber
import java.io.File

/** libopusenc success code used by Element's JNI wrapper. */
internal const val OPE_OK = 0

/**
 * Maps an app sample rate (Hz) to Element's sealed [ElementSampleRate].
 * Returns null when the rate is not one of the Opus-legal values Element accepts.
 */
internal fun toElementSampleRate(sampleRateHz: Int): ElementSampleRate? = when (sampleRateHz) {
    8_000 -> ElementSampleRate.Rate8khz
    12_000 -> ElementSampleRate.Rate12kHz
    16_000 -> ElementSampleRate.Rate16kHz
    24_000 -> ElementSampleRate.Rate24KHz
    48_000 -> ElementSampleRate.Rate48kHz
    else -> null
}

/**
 * Downmixes interleaved little-endian PCM-16 stereo (or copies mono) into a mono [ShortArray].
 *
 * @param pcmBytes raw PCM bytes from AudioRecord
 * @param bytesRead number of valid bytes in [pcmBytes]
 * @param channelCount 1 (mono) or 2 (stereo interleaved)
 * @return mono samples; empty if nothing usable was read
 */
internal fun pcmBytesToMonoShorts(
    pcmBytes: ByteArray,
    bytesRead: Int,
    channelCount: Int,
): ShortArray {
    if (bytesRead <= 0 || channelCount < 1) return ShortArray(0)
    val bytesPerFrame = channelCount * 2
    val frames = bytesRead / bytesPerFrame
    if (frames <= 0) return ShortArray(0)
    val out = ShortArray(frames)
    var byteIndex = 0
    for (frame in 0 until frames) {
        if (channelCount == 1) {
            val lo = pcmBytes[byteIndex].toInt() and 0xFF
            val hi = pcmBytes[byteIndex + 1].toInt()
            out[frame] = ((hi shl 8) or lo).toShort()
            byteIndex += 2
        } else {
            val loL = pcmBytes[byteIndex].toInt() and 0xFF
            val hiL = pcmBytes[byteIndex + 1].toInt()
            val loR = pcmBytes[byteIndex + 2].toInt() and 0xFF
            val hiR = pcmBytes[byteIndex + 3].toInt()
            val left = (hiL shl 8) or loL
            val right = (hiR shl 8) or loR
            out[frame] = ((left + right) / 2).toShort()
            byteIndex += 4
        }
    }
    return out
}

/**
 * Downmixes an interleaved PCM-16 [ShortArray] (mono or stereo) to mono.
 */
internal fun pcmShortsToMono(samples: ShortArray, channelCount: Int): ShortArray {
    if (channelCount <= 1) return samples
    val frames = samples.size / channelCount
    if (frames <= 0) return ShortArray(0)
    val out = ShortArray(frames)
    var i = 0
    for (frame in 0 until frames) {
        var sum = 0
        for (ch in 0 until channelCount) {
            sum += samples[i++].toInt()
        }
        out[frame] = (sum / channelCount).toShort()
    }
    return out
}

/**
 * Encodes PCM-16 samples to an Ogg Opus file using Element's libopusenc wrapper.
 * Always writes mono (stereo input is downmixed).
 */
internal fun encodePcmToOpusFile(
    samples: ShortArray,
    sampleRate: Int,
    channelCount: Int,
    bitrate: Int,
    output: File,
): Boolean {
    val elementRate = toElementSampleRate(sampleRate) ?: run {
        Timber.e("Unsupported Opus sample rate for software encoder: $sampleRate")
        return false
    }
    val mono = pcmShortsToMono(samples, channelCount)
    if (mono.isEmpty()) return false
    if (output.exists() && !output.delete()) {
        Timber.w("Could not delete existing output before software Opus encode: ${output.absolutePath}")
    }
    val encoder = OggOpusEncoder.create()
    return try {
        val initResult = encoder.init(output.absolutePath, elementRate)
        if (initResult != OPE_OK) {
            Timber.e("Software Opus init failed: $initResult")
            return false
        }
        val bitrateResult = encoder.setBitrate(bitrate.coerceIn(500, 512_000))
        if (bitrateResult != OPE_OK) {
            Timber.w("Software Opus setBitrate returned $bitrateResult; continuing")
        }
        // Feed in ~20 ms chunks so libopusenc gets Opus-friendly frame sizes.
        val frameSamples = (sampleRate / 50).coerceAtLeast(1)
        var offset = 0
        while (offset < mono.size) {
            val count = minOf(frameSamples, mono.size - offset)
            val chunk = mono.copyOfRange(offset, offset + count)
            val writeResult = encoder.encode(chunk, count)
            if (writeResult < 0) {
                Timber.e("Software Opus encode failed at offset $offset: $writeResult")
                return false
            }
            offset += count
        }
        true
    } catch (e: Exception) {
        Timber.e(e, "Software Opus encode failed")
        false
    } finally {
        try {
            encoder.release()
        } catch (e: Exception) {
            Timber.w(e, "Software Opus encoder release failed")
        }
    }
}
