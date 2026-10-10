/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Decodes an audio file to PCM, applies peak normalization (~90% of Int16 range),
 * and writes a 16-bit PCM WAV. Used for optional normalize-on-export.
 */
object AudioPeakNormalizer {

    private const val TARGET_PEAK = 29490 // ~90% of 32767

    /**
     * @return temp WAV file, or null on failure
     */
    fun normalizeToWav(input: File, outputDir: File): File? {
        if (!input.exists()) return null
        return try {
            val decoded = decodeToPcm(input) ?: return null
            if (decoded.samples.isEmpty()) return null
            var peak = 1
            for (s in decoded.samples) {
                val a = abs(s.toInt())
                if (a > peak) peak = a
            }
            val gain = if (peak >= TARGET_PEAK) 1f else TARGET_PEAK.toFloat() / peak.toFloat()
            val out = File(outputDir, "${input.nameWithoutExtension}_norm.wav")
            writeWav(out, decoded.samples, decoded.sampleRate, decoded.channelCount, gain)
            out
        } catch (e: Exception) {
            Timber.e(e, "Peak normalize failed")
            null
        }
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
            raf.writeShortLE(1) // PCM
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
                    val v = (samples[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
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
