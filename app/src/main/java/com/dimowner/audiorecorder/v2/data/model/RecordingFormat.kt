/*
 * Copyright 2024 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimowner.audiorecorder.v2.data.model

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
enum class RecordingFormat(val value: String, val index: Int) : Parcelable {
    M4a("m4a", 0), Wav("wav", 1), ThreeGp("3gp", 2), Opus("opus", 3);

    /** Recording capabilities (supported sample rates, bitrates and channel counts) of this format. */
    val config: FormatConfig
        get() = when (this) {
            M4a -> FormatConfig(
                format = this,
                supportedSampleRates = listOf(
                    SampleRate.SR8000,
                    SampleRate.SR16000,
                    SampleRate.SR22050,
                    SampleRate.SR32000,
                    SampleRate.SR44100,
                    SampleRate.SR48000
                ),
                supportedBitRates = listOf(
                    BitRate.BR48,
                    BitRate.BR96,
                    BitRate.BR128,
                    BitRate.BR192,
                    BitRate.BR256,
                    BitRate.BR288,
                ),
                supportedChannelCounts = listOf(ChannelCount.Mono, ChannelCount.Stereo)
            )
            Wav -> FormatConfig(
                format = this,
                supportedSampleRates = listOf(
                    SampleRate.SR8000,
                    SampleRate.SR16000,
                    SampleRate.SR22050,
                    SampleRate.SR32000,
                    SampleRate.SR44100,
                    SampleRate.SR48000
                ),
                supportedBitRates = emptyList(),
                supportedChannelCounts = listOf(ChannelCount.Mono, ChannelCount.Stereo)
            )
            ThreeGp -> FormatConfig(
                format = this,
                supportedSampleRates = listOf(SampleRate.SR8000, SampleRate.SR16000),
                supportedBitRates = emptyList(),
                supportedChannelCounts = listOf(ChannelCount.Mono),
            )
            Opus -> FormatConfig(
                format = this,
                supportedSampleRates = listOf(
                    SampleRate.SR16000,
                    SampleRate.SR48000,
                ),
                supportedBitRates = listOf(
                    BitRate.BR16,
                    BitRate.BR24,
                    BitRate.BR32,
                    BitRate.BR48,
                    BitRate.BR64,
                    BitRate.BR96,
                ),
                supportedChannelCounts = listOf(ChannelCount.Mono, ChannelCount.Stereo),
            )
        }

    /** Whether this format is recorded with a user-configurable bitrate. */
    val hasBitrate: Boolean get() = config.hasBitrate

    companion object {
        fun availableFormats(): List<RecordingFormat> {
            // Opus is always offered: MediaRecorder when the system encoder exists, otherwise
            // the bundled libopusenc software path.
            return entries.filter { it != Opus || isOpusRecordingSupported() }
        }
    }
}

/**
 * Whether Opus recording is available on this build.
 *
 * Always true: a software Opus encoder (libopusenc) is bundled. Use
 * [isSystemOpusEncoderAvailable] to choose MediaRecorder vs software.
 */
fun isOpusRecordingSupported(): Boolean = true

/**
 * Whether the platform registers a MediaCodec Opus encoder.
 *
 * Some OEM builds (notably certain Onyx Boox firmwares) ship API 29+ but leave
 * `c2.android.opus.encoder` commented out in `media_codecs*.xml`. Cached for the process
 * lifetime: codec lists do not change at runtime.
 */
fun isSystemOpusEncoderAvailable(): Boolean {
    synchronized(systemOpusSupportLock) {
        systemOpusSupportCached?.let { return it }
        val supported = probeSystemOpusEncoderAvailable()
        systemOpusSupportCached = supported
        return supported
    }
}

/** Test seam: clears the cached system-encoder probe result. */
internal fun resetSystemOpusSupportCacheForTests() {
    synchronized(systemOpusSupportLock) {
        systemOpusSupportCached = null
    }
}

/** @deprecated Use [resetSystemOpusSupportCacheForTests]. */
internal fun resetOpusSupportCacheForTests() = resetSystemOpusSupportCacheForTests()

private val systemOpusSupportLock = Any()

@Volatile
private var systemOpusSupportCached: Boolean? = null

private fun probeSystemOpusEncoderAvailable(): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
    return try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
            }
        }
    } catch (_: Throwable) {
        // MediaCodecList can throw on incomplete Robolectric shadows / broken OEM stores.
        false
    }
}

fun String.convertToRecordingFormat(): RecordingFormat? {
    return RecordingFormat.entries.firstOrNull { it.value.equals(this, ignoreCase = true) }
}
