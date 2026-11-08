/*
 * Copyright 2026 PlanRecorder contributors
 *
 * Offline (post-recording) DPDFNet enhancement: decode → enhance at 16 kHz → re-encode.
 * Streams through a temp WAV so multi-hour sessions do not keep the full PCM in RAM
 * during the enhance pass; Opus/AAC re-encode also streams from that WAV.
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.dimowner.audiorecorder.v2.audio.OPE_OK
import com.dimowner.audiorecorder.v2.audio.toElementSampleRate
import io.element.android.opusencoder.OggOpusEncoder
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.min

object DpdfFileEnhancer {

    /**
     * Enhance [input] in place with [enhancer]. Returns false on failure (input unchanged).
     */
    fun enhanceInPlace(input: File, enhancer: DpdfNetEnhancer): Boolean {
        if (!input.exists() || !input.isFile) return false
        val parent = input.parentFile ?: return false
        val ext = input.extension.lowercase()
        val tempWav = File(parent, "${input.nameWithoutExtension}.dpdf.tmp.wav")
        val tempOut = File(parent, "${input.nameWithoutExtension}.dpdf.out.$ext")
        try {
            tempWav.delete()
            tempOut.delete()
            val meta = decodeEnhanceToWav(input, tempWav, enhancer) ?: return false
            val encoded = when (ext) {
                "wav" -> {
                    if (tempWav.renameTo(tempOut)) {
                        true
                    } else {
                        tempWav.copyTo(tempOut, overwrite = true)
                        tempWav.delete()
                        tempOut.exists()
                    }
                }
                "opus", "ogg", "oga" -> encodeWavToOpus(tempWav, tempOut, meta.sampleRate, 32_000)
                "m4a", "mp4", "aac" -> encodeWavToAac(tempWav, tempOut, meta.sampleRate)
                else -> {
                    tempWav.copyTo(tempOut, overwrite = true)
                    tempOut.exists()
                }
            }
            if (!encoded || !tempOut.exists() || tempOut.length() < 64L) {
                Timber.e("DPDF post-process encode failed for %s", input.name)
                return false
            }
            val backup = File(parent, "${input.name}.dpdf.bak")
            backup.delete()
            if (!input.renameTo(backup)) {
                Timber.e("DPDF post-process could not backup original")
                return false
            }
            if (!tempOut.renameTo(input)) {
                tempOut.copyTo(input, overwrite = true)
                tempOut.delete()
            }
            backup.delete()
            Timber.i("DPDF post-process OK: %s (%d bytes)", input.name, input.length())
            return true
        } catch (e: Exception) {
            Timber.e(e, "DPDF post-process failed")
            return false
        } finally {
            tempWav.delete()
            tempOut.delete()
            File(parent, "${input.name}.dpdf.bak").delete()
        }
    }

    private data class WavMeta(val sampleRate: Int, val channelCount: Int)

    private fun decodeEnhanceToWav(
        input: File,
        wavOut: File,
        enhancer: DpdfNetEnhancer,
    ): WavMeta? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: GrowingMonoWavWriter? = null
        try {
            extractor.setDataSource(input.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val inCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            enhancer.reset()
            writer = GrowingMonoWavWriter(wavOut, inRate)
            val pending16k = ArrayDeque<Float>(DpdfNetEnhancer.HOP_SIZE * 4)
            val hopIn = FloatArray(DpdfNetEnhancer.HOP_SIZE)
            val hopOut = FloatArray(DpdfNetEnhancer.HOP_SIZE)
            val resamplerIn = LinearResampler(inRate, DpdfNetEnhancer.SAMPLE_RATE)
            val resamplerOut = LinearResampler(DpdfNetEnhancer.SAMPLE_RATE, inRate)
            val tmpIn = ArrayList<Float>(4096)
            val tmp16k = ArrayList<Float>(4096)
            val tmpOut = ArrayList<Float>(4096)

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
                            val shorts = ByteBuffer.wrap(bytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                            tmpIn.clear()
                            while (shorts.remaining() >= inCh) {
                                var sum = 0f
                                for (c in 0 until inCh) {
                                    sum += shorts.get() / 32768f
                                }
                                tmpIn.add(sum / inCh)
                            }
                            tmp16k.clear()
                            resamplerIn.process(tmpIn, tmp16k)
                            for (s in tmp16k) {
                                pending16k.addLast(s)
                                while (pending16k.size >= DpdfNetEnhancer.HOP_SIZE) {
                                    for (i in 0 until DpdfNetEnhancer.HOP_SIZE) {
                                        hopIn[i] = pending16k.removeFirst()
                                    }
                                    enhancer.processHop(hopIn, 0, hopOut, 0)
                                    tmpOut.clear()
                                    resamplerOut.process(hopOut, tmpOut)
                                    writer.writeFloats(tmpOut)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            if (pending16k.isNotEmpty()) {
                while (pending16k.size < DpdfNetEnhancer.HOP_SIZE) pending16k.addLast(0f)
                for (i in 0 until DpdfNetEnhancer.HOP_SIZE) hopIn[i] = pending16k.removeFirst()
                enhancer.processHop(hopIn, 0, hopOut, 0)
                tmpOut.clear()
                resamplerOut.process(hopOut, tmpOut)
                writer.writeFloats(tmpOut)
            }
            writer.close()
            writer = null
            return WavMeta(sampleRate = inRate, channelCount = 1)
        } catch (e: Exception) {
            Timber.e(e, "DPDF decode/enhance failed")
            return null
        } finally {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun encodeWavToOpus(
        wav: File,
        output: File,
        sampleRate: Int,
        bitrate: Int,
    ): Boolean {
        val elementRate = toElementSampleRate(sampleRate) ?: run {
            Timber.e("Unsupported Opus sample rate for DPDF post: %d", sampleRate)
            return false
        }
        if (output.exists()) output.delete()
        val encoder = OggOpusEncoder.create()
        return try {
            openMonoWavData(wav)?.use { stream ->
                val initResult = encoder.init(output.absolutePath, elementRate)
                if (initResult != OPE_OK) {
                    Timber.e("Software Opus init failed: $initResult")
                    return false
                }
                val bitrateResult = encoder.setBitrate(bitrate.coerceIn(500, 512_000))
                if (bitrateResult != OPE_OK) {
                    Timber.w("Software Opus setBitrate returned $bitrateResult; continuing")
                }
                val frameSamples = (sampleRate / 50).coerceAtLeast(1)
                val chunk = ShortArray(frameSamples)
                val bytes = ByteArray(frameSamples * 2)
                while (true) {
                    val n = stream.readFullyOrEof(bytes)
                    if (n <= 0) break
                    val sampleCount = n / 2
                    ByteBuffer.wrap(bytes, 0, n)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(chunk, 0, sampleCount)
                    val writeResult = encoder.encode(chunk, sampleCount)
                    if (writeResult < 0) {
                        Timber.e("Software Opus encode failed: $writeResult")
                        return false
                    }
                }
                true
            } ?: false
        } catch (e: Exception) {
            Timber.e(e, "Opus encode failed for DPDF post")
            output.delete()
            false
        } finally {
            try {
                encoder.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun encodeWavToAac(
        wav: File,
        output: File,
        sampleRate: Int,
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sampleRate * 2)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            if (output.exists()) output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            val frameSamples = sampleRate / 50
            val pcmChunk = ByteArray(frameSamples * 2)
            var presentationUs = 0L
            var inputDone = false
            var outputDone = false
            openMonoWavData(wav)?.use { stream ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            buffer.clear()
                            val n = stream.readFullyOrEof(pcmChunk)
                            if (n <= 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                buffer.put(pcmChunk, 0, n)
                                val count = n / 2
                                codec.queueInputBuffer(inIndex, 0, n, presentationUs, 0)
                                presentationUs += count * 1_000_000L / sampleRate
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
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
            } ?: return false
            true
        } catch (e: Exception) {
            Timber.w(e, "AAC encode failed for DPDF post")
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

    private data class WavDataStream(
        private val raf: RandomAccessFile,
        private val dataEnd: Long,
    ) : AutoCloseable {
        fun readFullyOrEof(dest: ByteArray): Int {
            val remain = (dataEnd - raf.filePointer).toInt().coerceAtLeast(0)
            if (remain <= 0) return -1
            val toRead = min(dest.size, remain)
            raf.readFully(dest, 0, toRead)
            return toRead
        }

        override fun close() = raf.close()
    }

    private fun openMonoWavData(wav: File): WavDataStream? {
        return try {
            val raf = RandomAccessFile(wav, "r")
            val hdr = ByteArray(12)
            raf.readFully(hdr)
            if (String(hdr, 0, 4) != "RIFF" || String(hdr, 8, 4) != "WAVE") {
                raf.close()
                return null
            }
            var dataSize = -1
            while (raf.filePointer < raf.length()) {
                val id = ByteArray(4)
                raf.readFully(id)
                val size = readIntLE(raf)
                if (String(id) == "data") {
                    dataSize = size
                    break
                }
                raf.skipBytes(size)
            }
            if (dataSize <= 0) {
                raf.close()
                return null
            }
            WavDataStream(raf, raf.filePointer + dataSize)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open temp WAV")
            null
        }
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}

/** WAV writer that patches RIFF sizes on [close]. */
internal class GrowingMonoWavWriter(
    private val file: File,
    private val sampleRate: Int,
) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0
    private val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)

    init {
        raf.setLength(0)
        raf.writeBytes("RIFF")
        writeInt(0) // placeholder
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        writeInt(16)
        writeShort(1)
        writeShort(1)
        writeInt(sampleRate)
        writeInt(sampleRate * 2)
        writeShort(2)
        writeShort(16)
        raf.writeBytes("data")
        writeInt(0) // placeholder
    }

    fun writeFloats(samples: List<Float>) {
        var i = 0
        while (i < samples.size) {
            buf.clear()
            while (i < samples.size && buf.remaining() >= 2) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                buf.putShort(v.toShort())
                i++
            }
            raf.write(buf.array(), 0, buf.position())
            dataBytes += buf.position()
        }
    }

    fun close() {
        raf.seek(4)
        writeInt(36 + dataBytes)
        raf.seek(40)
        writeInt(dataBytes)
        raf.close()
    }

    private fun writeInt(value: Int) {
        raf.write(value and 0xff)
        raf.write(value shr 8 and 0xff)
        raf.write(value shr 16 and 0xff)
        raf.write(value shr 24 and 0xff)
    }

    private fun writeShort(value: Int) {
        raf.write(value and 0xff)
        raf.write(value shr 8 and 0xff)
    }
}

/** Streaming linear resampler with fractional phase. */
internal class LinearResampler(
    private val inRate: Int,
    private val outRate: Int,
) {
    private var last = 0f
    private var hasLast = false
    private var pos = 0.0
    private val step = if (outRate <= 0) 1.0 else inRate.toDouble() / outRate.toDouble()

    fun process(input: FloatArray, output: MutableList<Float>) {
        if (inRate == outRate) {
            for (s in input) output.add(s)
            if (input.isNotEmpty()) {
                last = input.last()
                hasLast = true
            }
            return
        }
        if (input.isEmpty()) return
        var index = 0
        var s0 = if (hasLast) last else input[0]
        while (index < input.size) {
            val s1 = input[index]
            while (pos < 1.0 - 1e-12) {
                val t = pos.toFloat()
                output.add(s0 * (1f - t) + s1 * t)
                pos += step
            }
            pos -= 1.0
            s0 = s1
            index++
        }
        last = s0
        hasLast = true
    }

    fun process(input: List<Float>, output: MutableList<Float>) {
        if (input.isEmpty()) return
        val arr = FloatArray(input.size)
        for (i in input.indices) arr[i] = input[i]
        process(arr, output)
    }
}
