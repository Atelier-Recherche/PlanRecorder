/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decodes audio, softens short transients (e.g. keyboard clicks), applies robust
 * loudness normalization (~90% of Int16 via percentile peak + soft limiter),
 * and re-encodes preferring the original container/format (Opus / M4A / WAV).
 */
object AudioPeakNormalizer {

    internal const val TARGET_PEAK = 29490 // ~90% of 32767

    /** Percentile of |samples| used as robust peak (ignores sparse click spikes). */
    private const val ROBUST_PERCENTILE = 0.997

    /** Transient window length in milliseconds. */
    private const val TRANSIENT_WINDOW_MS = 10

    /**
     * Attenuate a frame when its RMS or peak exceeds the rolling baseline by this
     * factor and the peak is brief relative to the window.
     */
    private const val TRANSIENT_RMS_RATIO = 4.5f
    private const val TRANSIENT_PEAK_RATIO = 4f

    /** Gain applied to detected click frames (keep a faint residual). */
    private const val TRANSIENT_ATTENUATION = 0.12f

    /** EMA coefficient for rolling RMS/peak baseline (higher = slower adapt). */
    private const val BASELINE_EMA = 0.92f

    data class NormalizeResult(
        val file: File,
        val extension: String,
    )

    /**
     * Normalize [input] while keeping [preferredExtension] when possible.
     * Returns null if normalization fails (caller should export the original).
     */
    fun normalizeKeepingFormat(
        input: File,
        outputDir: File,
        preferredExtension: String,
    ): NormalizeResult? {
        if (!input.exists()) return null
        return try {
            val decoded = decodeToPcm(input) ?: return null
            if (decoded.samples.isEmpty()) return null
            val processed = processForNormalize(
                decoded.samples,
                decoded.sampleRate,
                decoded.channelCount,
            )
            val ext = preferredExtension.lowercase()
            when (ext) {
                "wav" -> {
                    val out = File(outputDir, "${input.nameWithoutExtension}_norm.wav")
                    writeWav(out, processed, decoded.sampleRate, decoded.channelCount, 1f)
                    NormalizeResult(out, "wav")
                }
                "m4a", "mp4", "aac" -> {
                    val out = File(outputDir, "${input.nameWithoutExtension}_norm.m4a")
                    if (encodeAac(processed, decoded.sampleRate, decoded.channelCount, out)) {
                        NormalizeResult(out, "m4a")
                    } else null
                }
                "opus", "ogg", "oga" -> {
                    val out = File(outputDir, "${input.nameWithoutExtension}_norm.opus")
                    if (encodeOpus(processed, decoded.sampleRate, decoded.channelCount, out)) {
                        NormalizeResult(out, "opus")
                    } else null
                }
                else -> {
                    val out = File(outputDir, "${input.nameWithoutExtension}_norm.wav")
                    writeWav(out, processed, decoded.sampleRate, decoded.channelCount, 1f)
                    NormalizeResult(out, "wav")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Peak normalize failed")
            null
        }
    }

    /** @deprecated Prefer [normalizeKeepingFormat]. */
    fun normalizeToWav(input: File, outputDir: File): File? =
        normalizeKeepingFormat(input, outputDir, "wav")?.file

    /**
     * Softens short transients, then applies percentile-based gain + soft limiter.
     * Exposed for unit tests (no Android decode/encode).
     */
    internal fun processForNormalize(
        samples: ShortArray,
        sampleRate: Int,
        channelCount: Int,
    ): ShortArray {
        if (samples.isEmpty()) return samples
        val channels = channelCount.coerceAtLeast(1)
        val softened = softenTransients(samples, sampleRate, channels)
        val robustPeak = robustPeakAbs(softened)
        val gain = if (robustPeak >= TARGET_PEAK) {
            1f
        } else {
            TARGET_PEAK.toFloat() / robustPeak.toFloat()
        }
        return applyGainWithSoftLimiter(softened, gain)
    }

    /**
     * Attenuates brief high-energy frames (keyboard-like clicks) relative to a
     * rolling RMS baseline, without touching sustained speech.
     */
    internal fun softenTransients(
        samples: ShortArray,
        sampleRate: Int,
        channelCount: Int,
    ): ShortArray {
        val channels = channelCount.coerceAtLeast(1)
        val frameSamples = ((sampleRate * TRANSIENT_WINDOW_MS) / 1000).coerceAtLeast(1) * channels
        if (samples.size < frameSamples * 3) return samples.copyOf()

        val out = samples.copyOf()
        var baselineRms = 0f
        var baselinePeak = 0f
        var baselineInitialized = false
        var i = 0
        while (i + frameSamples <= out.size) {
            var sumSq = 0.0
            var peak = 0
            var aboveHalfPeak = 0
            for (j in 0 until frameSamples) {
                val a = abs(out[i + j].toInt())
                sumSq += a.toDouble() * a
                if (a > peak) peak = a
            }
            val halfPeak = peak / 2
            for (j in 0 until frameSamples) {
                if (abs(out[i + j].toInt()) >= halfPeak) aboveHalfPeak++
            }
            val rms = sqrt(sumSq / frameSamples).toFloat()
            // Fraction of samples near the peak — clicks are sparse within the window.
            val peakDensity = aboveHalfPeak.toFloat() / frameSamples

            // Sparse impulses (keyboard) have a high peak but modest RMS; use either cue.
            val loudVsBaseline = baselineInitialized && (
                (baselineRms > 1f && rms > baselineRms * TRANSIENT_RMS_RATIO) ||
                    (baselinePeak > 1f && peak > baselinePeak * TRANSIENT_PEAK_RATIO)
                )
            val isClick = loudVsBaseline &&
                peakDensity < 0.35f &&
                peak > TARGET_PEAK / 4

            if (isClick) {
                for (j in 0 until frameSamples) {
                    out[i + j] = (out[i + j] * TRANSIENT_ATTENUATION)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            } else {
                if (!baselineInitialized) {
                    baselineRms = rms.coerceAtLeast(1f)
                    baselinePeak = peak.toFloat().coerceAtLeast(1f)
                    baselineInitialized = true
                } else {
                    baselineRms = BASELINE_EMA * baselineRms + (1f - BASELINE_EMA) * rms
                    baselinePeak =
                        BASELINE_EMA * baselinePeak + (1f - BASELINE_EMA) * peak.toFloat()
                }
            }
            i += frameSamples
        }
        return out
    }

    /** Absolute amplitude at [ROBUST_PERCENTILE] via Int16 histogram (O(n), fixed memory). */
    internal fun robustPeakAbs(samples: ShortArray): Int {
        if (samples.isEmpty()) return 1
        val hist = IntArray(Short.MAX_VALUE + 1)
        var total = 0
        for (s in samples) {
            hist[abs(s.toInt())]++
            total++
        }
        if (total == 0) return 1
        val targetCount = (total * ROBUST_PERCENTILE).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (amp in hist.indices) {
            cumulative += hist[amp]
            if (cumulative >= targetCount) {
                return amp.coerceAtLeast(1)
            }
        }
        return (hist.size - 1).coerceAtLeast(1)
    }

    internal fun applyGainWithSoftLimiter(samples: ShortArray, gain: Float): ShortArray {
        if (samples.isEmpty()) return samples
        val threshold = TARGET_PEAK.toFloat()
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val scaled = samples[i] * gain
            val limited = softLimit(scaled, threshold)
            out[i] = limited
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    /** Soft knee above [threshold]: compresses remaining click peaks instead of hard clip. */
    private fun softLimit(x: Float, threshold: Float): Float {
        val a = abs(x)
        if (a <= threshold) return x
        val over = a - threshold
        val compressed = threshold + over / (1f + over / threshold)
        return if (x < 0f) -compressed else compressed
    }

    private data class PcmData(
        val samples: ShortArray,
        val sampleRate: Int,
        val channelCount: Int,
    )

    private fun decodeToPcm(input: File): PcmData? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val outSamples = ArrayList<Short>(sampleRate * channelCount * 60)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            val shortBuf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            while (shortBuf.hasRemaining()) {
                                outSamples.add(shortBuf.get())
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
            codec.stop()
            codec.release()
            return PcmData(outSamples.toShortArray(), sampleRate, channelCount)
        } catch (e: Exception) {
            Timber.e(e, "PCM decode failed for normalize")
            return null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun encodeOpus(
        samples: ShortArray,
        sampleRate: Int,
        channelCount: Int,
        output: File,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val systemOk = encodeCompressed(
                samples = samples,
                sampleRate = sampleRate,
                channelCount = channelCount,
                output = output,
                mime = MediaFormat.MIMETYPE_AUDIO_OPUS,
                bitRate = 32_000,
                muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
            )
            if (systemOk) return true
            Timber.w("MediaCodec Opus encode failed; falling back to software Opus")
        }
        return encodePcmToOpusFile(
            samples = samples,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitrate = 32_000,
            output = output,
        )
    }

    private fun encodeAac(
        samples: ShortArray,
        sampleRate: Int,
        channelCount: Int,
        output: File,
    ): Boolean {
        return encodeCompressed(
            samples = samples,
            sampleRate = sampleRate,
            channelCount = channelCount,
            output = output,
            mime = MediaFormat.MIMETYPE_AUDIO_AAC,
            bitRate = 128_000,
            muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
    }

    private fun encodeCompressed(
        samples: ShortArray,
        sampleRate: Int,
        channelCount: Int,
        output: File,
        mime: String,
        bitRate: Int,
        muxerFormat: Int,
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sampleRate * channelCount * 2)
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
            }
            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            if (output.exists()) output.delete()
            muxer = MediaMuxer(output.absolutePath, muxerFormat)
            var trackIndex = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            var sampleOffset = 0
            val frameSamples = (sampleRate / 50) * channelCount // ~20ms
            var inputDone = false
            var outputDone = false
            var presentationUs = 0L
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        buffer.clear()
                        if (sampleOffset >= samples.size) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val remain = samples.size - sampleOffset
                            val count = min(frameSamples, remain)
                            val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until count) {
                                bytes.putShort(samples[sampleOffset + i])
                            }
                            bytes.flip()
                            buffer.put(bytes)
                            val pts = presentationUs
                            presentationUs += (count / channelCount) * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inIndex, 0, count * 2, pts, 0)
                            sampleOffset += count
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IllegalStateException("format changed twice")
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0 && muxerStarted &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, encoded, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to re-encode normalized audio as %s", mime)
            output.delete()
            false
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeWav(
        file: File,
        samples: ShortArray,
        sampleRate: Int,
        channelCount: Int,
        gain: Float,
    ) {
        val dataSize = samples.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.writeBytes("RIFF")
            raf.writeIntLE(36 + dataSize)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1)
            raf.writeShortLE(channelCount)
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(sampleRate * channelCount * 2)
            raf.writeShortLE(channelCount * 2)
            raf.writeShortLE(16)
            raf.writeBytes("data")
            raf.writeIntLE(dataSize)
            val buf = ByteBuffer.allocate(min(8192, samples.size * 2)).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < samples.size) {
                buf.clear()
                while (i < samples.size && buf.remaining() >= 2) {
                    val v = (samples[i] * gain).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    buf.putShort(v.toShort())
                    i++
                }
                raf.write(buf.array(), 0, buf.position())
            }
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xff)
        write(value shr 8 and 0xff)
        write(value shr 16 and 0xff)
        write(value shr 24 and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xff)
        write(value shr 8 and 0xff)
    }
}
