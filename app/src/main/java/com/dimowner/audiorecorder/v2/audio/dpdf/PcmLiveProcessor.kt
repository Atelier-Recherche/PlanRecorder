/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.audio.dpdf

/**
 * Optional in-place PCM processor for live recording loops (16-bit little-endian).
 */
interface PcmLiveProcessor {
    /**
     * Process [byteCount] bytes of interleaved PCM in [pcm] in place.
     * Returns the number of bytes to write/encode (same as [byteCount] when successful).
     */
    fun processInPlace(pcm: ByteArray, byteCount: Int, channelCount: Int): Int

    fun reset()

    fun close()
}

/** No-op processor used when denoising is disabled. */
object PassthroughPcmLiveProcessor : PcmLiveProcessor {
    override fun processInPlace(pcm: ByteArray, byteCount: Int, channelCount: Int): Int = byteCount
    override fun reset() = Unit
    override fun close() = Unit
}
