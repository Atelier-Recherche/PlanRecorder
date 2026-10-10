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

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Bitrate in Kbps (thousands bits per second)
 */
@SuppressWarnings("MagicNumber")
@Parcelize
enum class BitRate(val value: Int, val index: Int): Parcelable {
    BR48(48000, 0),
    BR96(96000, 1),
    BR128(128000, 2),
    BR192(192000, 3),
    BR256(256000, 4),
    BR288(288000, 5),
    /** Opus / speech-oriented bitrates (indices follow bit_rates2 array). */
    BR16(16000, 6),
    BR24(24000, 7),
    BR32(32000, 8),
    BR64(64000, 9),
}

fun Int.convertToBitRate(): BitRate? {
    return BitRate.entries.firstOrNull { it.value == this }
}
