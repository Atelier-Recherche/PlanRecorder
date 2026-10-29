/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real FFT / iFFT matching NumPy `rfft` / `irfft` for arbitrary [n] (used with n=320 for DPDFNet).
 * Direct DFT is fine at hop rate (~100 Hz); the neural net dominates CPU.
 */
internal object RealFft {

    fun rfft(input: FloatArray, realOut: FloatArray, imagOut: FloatArray) {
        val n = input.size
        val bins = n / 2 + 1
        require(realOut.size >= bins && imagOut.size >= bins)
        for (k in 0 until bins) {
            var re = 0.0
            var im = 0.0
            val angleStep = -2.0 * PI * k / n
            for (t in 0 until n) {
                val angle = angleStep * t
                val sample = input[t].toDouble()
                re += sample * cos(angle)
                im += sample * sin(angle)
            }
            realOut[k] = re.toFloat()
            imagOut[k] = im.toFloat()
        }
    }

    fun irfft(realIn: FloatArray, imagIn: FloatArray, n: Int, out: FloatArray) {
        require(out.size >= n)
        val bins = n / 2 + 1
        require(realIn.size >= bins && imagIn.size >= bins)
        for (t in 0 until n) {
            var sample = realIn[0].toDouble()
            if (n % 2 == 0) {
                sample += realIn[bins - 1].toDouble() * cos(PI * t)
            }
            val last = if (n % 2 == 0) bins - 2 else bins - 1
            for (k in 1..last) {
                val angle = 2.0 * PI * k * t / n
                sample += 2.0 * (realIn[k] * cos(angle) - imagIn[k] * sin(angle))
            }
            out[t] = (sample / n).toFloat()
        }
    }
}
