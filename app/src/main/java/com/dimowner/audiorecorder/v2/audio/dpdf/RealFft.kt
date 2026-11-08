/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

import org.jtransforms.fft.FloatFFT_1D

/**
 * Real FFT / iFFT matching NumPy `rfft` / `irfft` for n=320 (DPDFNet), via JTransforms
 * packed `realForward` / `realInverse` (same layout both ways — avoids Hermitian packing bugs
 * that muffled the reconstructed voice).
 */
internal class RealFft(private val n: Int = DpdfNetEnhancer.WIN_LEN) {
    private val fft = FloatFFT_1D(n.toLong())
    /** Packed real-FFT workspace: size n (JTransforms realForward layout). */
    private val packed = FloatArray(n)

    fun rfft(input: FloatArray, realOut: FloatArray, imagOut: FloatArray) {
        require(input.size >= n)
        val bins = n / 2 + 1
        require(realOut.size >= bins && imagOut.size >= bins)
        System.arraycopy(input, 0, packed, 0, n)
        fft.realForward(packed)
        // Even-n packing: a[0]=Re(0), a[1]=Re(n/2), a[2k]=Re(k), a[2k+1]=Im(k)
        realOut[0] = packed[0]
        imagOut[0] = 0f
        realOut[n / 2] = packed[1]
        imagOut[n / 2] = 0f
        var k = 1
        while (k < n / 2) {
            realOut[k] = packed[2 * k]
            imagOut[k] = packed[2 * k + 1]
            k++
        }
    }

    fun irfft(realIn: FloatArray, imagIn: FloatArray, out: FloatArray) {
        val bins = n / 2 + 1
        require(realIn.size >= bins && imagIn.size >= bins && out.size >= n)
        packed[0] = realIn[0]
        packed[1] = realIn[n / 2]
        var k = 1
        while (k < n / 2) {
            packed[2 * k] = realIn[k]
            packed[2 * k + 1] = imagIn[k]
            k++
        }
        fft.realInverse(packed, true)
        System.arraycopy(packed, 0, out, 0, n)
    }
}
