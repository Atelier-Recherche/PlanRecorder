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

    private val window = vorbisWindow(WIN_LEN)
    private val analysisBuf = FloatArray(WIN_LEN)
    private val olaBuf = FloatArray(WIN_LEN)
    private val frameTime = FloatArray(WIN_LEN)
    private val specReal = FloatArray(FREQ_BINS)
    private val specImag = FloatArray(FREQ_BINS)
    private val istftFrame = FloatArray(WIN_LEN)

    @Volatile
    private var closed = false

    fun reset() {
        analysisBuf.fill(0f)
        olaBuf.fill(0f)
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

        // Shift analysis buffer and append new hop.
        System.arraycopy(analysisBuf, HOP_SIZE, analysisBuf, 0, WIN_LEN - HOP_SIZE)
        System.arraycopy(hop, hopOffset, analysisBuf, WIN_LEN - HOP_SIZE, HOP_SIZE)

        for (i in 0 until WIN_LEN) {
            frameTime[i] = analysisBuf[i] * window[i]
        }
        RealFft.rfft(frameTime, specReal, specImag)

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

        RealFft.irfft(specReal, specImag, WIN_LEN, istftFrame)
        for (i in 0 until WIN_LEN) {
            istftFrame[i] *= window[i]
        }

        // Overlap-add
        val shifted = FloatArray(WIN_LEN)
        System.arraycopy(olaBuf, HOP_SIZE, shifted, 0, WIN_LEN - HOP_SIZE)
        for (i in 0 until WIN_LEN) {
            shifted[i] += istftFrame[i]
        }
        System.arraycopy(shifted, 0, out, outOffset, HOP_SIZE)
        System.arraycopy(shifted, 0, olaBuf, 0, WIN_LEN)

        return HOP_SIZE
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
        private const val ASSET_PATH = "dpdfnet/baseline.tflite"

        fun create(context: Context): DpdfNetEnhancer {
            val model = loadModelFile(context)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            val interpreter = Interpreter(model, options)
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            Timber.d(
                "DPDFNet loaded: in=%s out=%s",
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
