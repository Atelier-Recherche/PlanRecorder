/*
 * Copyright 2026 PlanRecorder contributors
 *
 * Streaming speech enhancement using Ceva DPDFNet baseline TFLite (Apache-2.0).
 * STFT / iSTFT hop processing mirrors Ceva's real_time_demo.py; the TFLite graph
 * keeps RNN state in interpreter variables between invokes.
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import android.content.Context
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.sin

/**
 * Causal DPDFNet baseline enhancer at 16 kHz mono.
 *
 * Call [processHop] with exactly [HOP_SIZE] float samples in [-1, 1]; returns [HOP_SIZE]
 * enhanced samples (early hops may be near-silent while the OLA buffer fills).
 */
class DpdfNetEnhancer private constructor(
    private val interpreter: Interpreter,
    private val inputBuffer: ByteBuffer,
    private val outputBuffer: ByteBuffer,
) : Closeable {

    private val fft = RealFft(WIN_LEN)
    private val window = vorbisWindow(WIN_LEN)
    private val analysisBuf = FloatArray(WIN_LEN)
    private val olaBuf = FloatArray(WIN_LEN)
    private val olaShift = FloatArray(WIN_LEN)
    private val frameTime = FloatArray(WIN_LEN)
    private val specReal = FloatArray(FREQ_BINS)
    private val specImag = FloatArray(FREQ_BINS)
    private val noisyReal = FloatArray(FREQ_BINS)
    private val noisyImag = FloatArray(FREQ_BINS)
    private val istftFrame = FloatArray(WIN_LEN)
    private val attnLimiter = AttnLimiter().also { it.setDb(AttnLimiter.DEFAULT_ATTN_LIMIT_DB) }

    private var hopCount = 0L
    private var hopNanosSum = 0L
    private var windowHopCount = 0L
    private var windowHopNanosSum = 0L

    /** 0 = full wet denoise; 1 = max dry blend + presence (see [applyClarity]). */
    @Volatile
    var clarity: Float = 0.4f

    private val dryDelay = FloatArray(HOP_SIZE)
    private var presenceLp = 0f

    @Volatile
    private var closed = false

    fun setAttnLimitDb(db: Float) {
        attnLimiter.setDb(db)
    }

    fun reset() {
        analysisBuf.fill(0f)
        olaBuf.fill(0f)
        dryDelay.fill(0f)
        presenceLp = 0f
        attnLimiter.reset()
        hopCount = 0
        hopNanosSum = 0
        windowHopCount = 0
        windowHopNanosSum = 0
        try {
            interpreter.resetVariableTensors()
        } catch (e: Exception) {
            Timber.w(e, "DPDFNet: resetVariableTensors failed; continuing with cleared STFT state")
        }
    }

    /**
     * Enhance one hop of mono float PCM. [hop] must have length [HOP_SIZE].
     * Writes into [out] (length >= [HOP_SIZE]) and returns [HOP_SIZE].
     */
    fun processHop(hop: FloatArray, hopOffset: Int, out: FloatArray, outOffset: Int): Int {
        check(!closed) { "DpdfNetEnhancer closed" }
        require(hop.size - hopOffset >= HOP_SIZE)
        require(out.size - outOffset >= HOP_SIZE)

        val t0 = System.nanoTime()

        // Shift analysis buffer and append new hop.
        System.arraycopy(analysisBuf, HOP_SIZE, analysisBuf, 0, WIN_LEN - HOP_SIZE)
        System.arraycopy(hop, hopOffset, analysisBuf, WIN_LEN - HOP_SIZE, HOP_SIZE)

        for (i in 0 until WIN_LEN) {
            frameTime[i] = analysisBuf[i] * window[i]
        }
        fft.rfft(frameTime, specReal, specImag)
        // Ceva TFLite offline path scales the spectrum by wnorm before invoke and divides the
        // waveform after iSTFT. Without this the model sees ~320× too-hot bins → dull/muffled.
        for (f in 0 until FREQ_BINS) {
            specReal[f] *= WNORM
            specImag[f] *= WNORM
        }
        System.arraycopy(specReal, 0, noisyReal, 0, FREQ_BINS)
        System.arraycopy(specImag, 0, noisyImag, 0, FREQ_BINS)

        // Model input: [1, 1, F, 2] float32
        inputBuffer.rewind()
        for (f in 0 until FREQ_BINS) {
            inputBuffer.putFloat(specReal[f])
            inputBuffer.putFloat(specImag[f])
        }
        inputBuffer.rewind()
        outputBuffer.rewind()

        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        for (f in 0 until FREQ_BINS) {
            specReal[f] = outputBuffer.float
            specImag[f] = outputBuffer.float
        }
        attnLimiter.apply(noisyReal, noisyImag, specReal, specImag)

        fft.irfft(specReal, specImag, istftFrame)
        for (i in 0 until WIN_LEN) {
            istftFrame[i] = istftFrame[i] * window[i] * INV_WNORM
        }

        // Overlap-add (reuse olaShift — never allocate per hop).
        System.arraycopy(olaBuf, HOP_SIZE, olaShift, 0, WIN_LEN - HOP_SIZE)
        olaShift.fill(0f, WIN_LEN - HOP_SIZE, WIN_LEN)
        for (i in 0 until WIN_LEN) {
            olaShift[i] += istftFrame[i]
        }
        System.arraycopy(olaShift, 0, out, outOffset, HOP_SIZE)
        System.arraycopy(olaShift, 0, olaBuf, 0, WIN_LEN)
        applyClarity(hop, hopOffset, out, outOffset)

        val dt = System.nanoTime() - t0
        hopCount++
        hopNanosSum += dt
        windowHopCount++
        windowHopNanosSum += dt
        // Rolling window so multi-hour sessions never rely on a forever-growing average.
        if (windowHopCount >= 500L) {
            val avgMs = windowHopNanosSum / windowHopCount / 1_000_000.0
            Timber.i(
                "DPDFNet hop avg=%.2f ms (budget=10 ms) totalHops=%d last=%.2f ms clarity=%.2f",
                avgMs,
                hopCount,
                dt / 1e6,
                clarity,
            )
            if (avgMs > 12.0) {
                Timber.w("DPDFNet over budget on this device — expect recording lag")
            }
            windowHopCount = 0
            windowHopNanosSum = 0
        }

        return HOP_SIZE
    }

    /**
     * Restores presence after denoise: delayed dry mix + light high-shelf.
     * Dry is delayed by one hop to roughly match STFT group delay.
     */
    private fun applyClarity(hop: FloatArray, hopOffset: Int, out: FloatArray, outOffset: Int) {
        val c = clarity.coerceIn(0f, 1f)
        if (c <= 0.001f) {
            System.arraycopy(hop, hopOffset, dryDelay, 0, HOP_SIZE)
            return
        }
        val dryAmt = c * 0.40f // up to 40% original
        val wetAmt = 1f - dryAmt
        val presence = c * 1.2f // high-shelf amount
        val lpAlpha = 0.88f
        for (i in 0 until HOP_SIZE) {
            val enhanced = out[outOffset + i]
            val dry = dryDelay[i]
            var mixed = wetAmt * enhanced + dryAmt * dry
            presenceLp = lpAlpha * presenceLp + (1f - lpAlpha) * mixed
            val high = mixed - presenceLp
            mixed += presence * high
            out[outOffset + i] = mixed.coerceIn(-1f, 1f)
        }
        System.arraycopy(hop, hopOffset, dryDelay, 0, HOP_SIZE)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            interpreter.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WIN_LEN = 320
        const val HOP_SIZE = 160
        const val FREQ_BINS = WIN_LEN / 2 + 1 // 161
        /** `1 / (win_len^2 / (2 * hop))` — see Ceva `infer_dpdfnet_tflite.get_wnorm`. */
        private const val WNORM = (2f * HOP_SIZE) / (WIN_LEN * WIN_LEN) // 1/320
        private const val INV_WNORM = 1f / WNORM
        private const val ASSET_PATH = "dpdfnet/baseline.tflite"

        fun create(context: Context): DpdfNetEnhancer {
            val model = loadModelFile(context)
            // Single thread: avoids fighting the UI / e-ink compositor on weak SoCs (Boox).
            val options = Interpreter.Options().apply {
                setNumThreads(1)
            }
            val interpreter = Interpreter(model, options)
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            Timber.d(
                "DPDFNet loaded: in=%s out=%s threads=1",
                input.shape().contentToString(),
                output.shape().contentToString(),
            )
            val expectedElems = 1 * 1 * FREQ_BINS * 2
            val inBytes = expectedElems * 4
            val outBytes = expectedElems * 4
            val inputBuffer = ByteBuffer.allocateDirect(inBytes).order(ByteOrder.nativeOrder())
            val outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
            return DpdfNetEnhancer(
                interpreter = interpreter,
                inputBuffer = inputBuffer,
                outputBuffer = outputBuffer,
            ).also { it.reset() }
        }

        private fun loadModelFile(context: Context): MappedByteBuffer {
            context.assets.openFd(ASSET_PATH).use { fd ->
                return fd.createInputStream().channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
            }
        }

        /** Vorbis window used by Ceva DPDFNet streaming demos. */
        fun vorbisWindow(windowLen: Int): FloatArray {
            val half = windowLen / 2.0
            val out = FloatArray(windowLen)
            for (i in 0 until windowLen) {
                val sin1 = sin(0.5 * PI * (i + 0.5) / half)
                out[i] = sin(0.5 * PI * sin1 * sin1).toFloat()
            }
            return out
        }
    }
}
