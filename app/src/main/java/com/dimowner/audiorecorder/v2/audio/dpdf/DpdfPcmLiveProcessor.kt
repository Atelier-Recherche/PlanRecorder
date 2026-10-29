/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Buffers arbitrary PCM chunks into DPDFNet hops (10 ms @ 16 kHz), enhances, and writes
 * the same number of samples back so encoders keep a continuous timeline.
 *
 * Startup latency is up to one model window (~20 ms): early samples may be near silence
 * until the overlap-add buffer fills. Incomplete hops stay buffered until the next chunk
 * (or [flush]) — they are not zero-padded mid-stream.
 */
class DpdfPcmLiveProcessor(
    private val enhancer: DpdfNetEnhancer,
) : PcmLiveProcessor {

    private val pending = FloatArray(DpdfNetEnhancer.HOP_SIZE)
    private var pendingCount = 0
    private val hopIn = FloatArray(DpdfNetEnhancer.HOP_SIZE)
    private val hopOut = FloatArray(DpdfNetEnhancer.HOP_SIZE)
    private val outQueue = FloatArray(DpdfNetEnhancer.HOP_SIZE * 16)
    private var outHead = 0
    private var outCount = 0

    override fun processInPlace(pcm: ByteArray, byteCount: Int, channelCount: Int): Int {
        if (byteCount <= 0) return byteCount
        val channels = max(1, channelCount)
        val frameBytes = channels * 2
        val frames = byteCount / frameBytes
        if (frames <= 0) return byteCount

        for (i in 0 until frames) {
            feedSample(monoFloatAt(pcm, i, channels))
        }

        val bb = ByteBuffer.wrap(pcm, 0, frames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        val asShort = bb.asShortBuffer()
        for (i in 0 until frames) {
            val sample = pollOutputOrZero()
            val pcm16 = floatToPcm16(sample)
            if (channels == 1) {
                asShort.put(i, pcm16)
            } else {
                val base = i * channels
                for (c in 0 until channels) {
                    asShort.put(base + c, pcm16)
                }
            }
        }
        return frames * frameBytes
    }

    /** Drain a final partial hop with zeros (end of recording). */
    fun flush() {
        if (pendingCount == 0) return
        while (pendingCount < DpdfNetEnhancer.HOP_SIZE) {
            pending[pendingCount++] = 0f
        }
        runHop()
        pendingCount = 0
    }

    private fun feedSample(sample: Float) {
        pending[pendingCount++] = sample
        if (pendingCount >= DpdfNetEnhancer.HOP_SIZE) {
            runHop()
            pendingCount = 0
        }
    }

    private fun runHop() {
        System.arraycopy(pending, 0, hopIn, 0, DpdfNetEnhancer.HOP_SIZE)
        try {
            enhancer.processHop(hopIn, 0, hopOut, 0)
        } catch (e: Exception) {
            Timber.e(e, "DPDFNet processHop failed; emitting silence for hop")
            hopOut.fill(0f)
        }
        for (i in 0 until DpdfNetEnhancer.HOP_SIZE) {
            pushOutput(hopOut[i])
        }
    }

    private fun pushOutput(sample: Float) {
        if (outCount >= outQueue.size) {
            // Drop oldest hop to keep realtime; should not happen with normal chunk sizes.
            outHead = (outHead + DpdfNetEnhancer.HOP_SIZE) % outQueue.size
            outCount = max(0, outCount - DpdfNetEnhancer.HOP_SIZE)
            Timber.w("DPDFNet output queue overflow; dropped one hop")
        }
        val idx = (outHead + outCount) % outQueue.size
        outQueue[idx] = sample
        outCount++
    }

    private fun pollOutputOrZero(): Float {
        if (outCount <= 0) return 0f
        val sample = outQueue[outHead]
        outHead = (outHead + 1) % outQueue.size
        outCount--
        return sample
    }

    override fun reset() {
        pendingCount = 0
        outHead = 0
        outCount = 0
        pending.fill(0f)
        outQueue.fill(0f)
        enhancer.reset()
    }

    override fun close() {
        // Enhancer is owned by DpdfNoiseReductionController — only clear session buffers.
        pendingCount = 0
        outHead = 0
        outCount = 0
    }

    companion object {
        private fun monoFloatAt(pcm: ByteArray, frameIndex: Int, channels: Int): Float {
            val base = frameIndex * channels * 2
            if (channels == 1) {
                return pcm16ToFloat(pcm, base)
            }
            var sum = 0f
            for (c in 0 until channels) {
                sum += pcm16ToFloat(pcm, base + c * 2)
            }
            return sum / channels
        }

        private fun pcm16ToFloat(pcm: ByteArray, offset: Int): Float {
            if (offset + 1 >= pcm.size) return 0f
            val lo = pcm[offset].toInt() and 0xff
            val hi = pcm[offset + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            return sample / 32768f
        }

        private fun floatToPcm16(value: Float): Short {
            val clipped = value.coerceIn(-1f, 1f)
            return (clipped * 32767f).toInt().toShort()
        }
    }
}
