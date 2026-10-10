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

package com.dimowner.audiorecorder.v2.data

import com.dimowner.audiorecorder.v2.data.model.AudioSource
import com.dimowner.audiorecorder.v2.data.model.BitRate
import com.dimowner.audiorecorder.v2.data.model.ChannelCount
import com.dimowner.audiorecorder.v2.data.model.NameFormat
import com.dimowner.audiorecorder.v2.data.model.NameFormatToken
import com.dimowner.audiorecorder.v2.data.model.RecordingFormat
import com.dimowner.audiorecorder.v2.data.model.SampleRate
import com.dimowner.audiorecorder.v2.data.model.SortOrder
import kotlinx.coroutines.flow.StateFlow

interface PrefsV2 {
    val isFirstRun: Boolean
    fun confirmFirstRunExecuted()

    /** Whether the "records are stored locally" info dialog has already been shown on Home screen. */
    var isLocalStorageInfoShown: Boolean

    var askToRenameAfterRecordingStopped: Boolean

    /** Last chosen state of the "Also save to audio file" checkbox in the description dialog. */
    var saveDescriptionToFile: Boolean

    /** When true, Bluetooth microphone routing is enabled automatically when a device is available. */
    var alwaysUseBluetoothMic: Boolean

    var activeRecordId: Long
    //Stores the last recorded record id. It is not gets cleared after recording stops.
    // It gets overwritten after new recording starts.
    var recordedRecordId: Long
    var recordedRecordPartCounter: Int
    var recordedRecordBaseName: String?

    val recordCounter: Long
    fun incrementRecordCounter()

    var isKeepScreenOn: Boolean

    var recordsSortOrder: SortOrder

    var isDynamicTheme: Boolean
    val isDynamicThemeFlow: StateFlow<Boolean>
    var isDarkTheme: Boolean
    val isDarkThemeFlow: StateFlow<Boolean>
    var isAppV2: Boolean

    /**
     * Flag indicates that the user previously used the legacy V1 app and intentionally switched to V2.
     * Read-only in V2 — set by V1's SettingsPresenter.confirmSwitchAppV2().
     */
    var isLegacyAppUser: Boolean

    var settingNamingFormat: NameFormat

    /**
     * Tokens of the format built in the name format constructor. Only used for record naming while
     * [settingNamingFormat] is [NameFormat.Custom], but kept stored regardless so that switching to
     * a preset and back does not lose the user's work.
     */
    var customNameFormat: List<NameFormatToken>
    var settingRecordingFormat: RecordingFormat
    var settingSampleRate: SampleRate
    var settingBitrate: BitRate
    var settingChannelCount: ChannelCount
    var settingAudioSource: AudioSource

    var maxRecordingDurationMills: Int

    var recordAuthorName: String

    /** SAF tree URI for PlanRecorder export root, or null if not configured. */
    var safRootTreeUri: String?

    /** Relative folder pattern, e.g. `[calendar]/[ue] [title_rest]`. */
    var folderPattern: String

    /** File name pattern without extension, e.g. `[NN]-T[HH][MM][SS]-[dd][mm][yy]-[ue]`. */
    var filePattern: String

    /**
     * When true, recording uses Opus + speech bitrate + [AudioSource.VOICE_RECOGNITION].
     * Previous format settings are restored when disabled.
     */
    var voiceFilterEnabled: Boolean

    /**
     * Selected Android calendar id for event lookup, or [NO_CALENDAR_FILTER] for all calendars.
     */
    var selectedCalendarId: Long

    /**
     * Identifier rules text (`PREFIX:N` / `PREFIX:N+`, one per line). See [com.dimowner.audiorecorder.v2.data.template.IdentifierRules].
     */
    var identifierRulesText: String

    /** Course / UE codes already seen (for pickers). */
    var seenIdentifiers: Set<String>

    /** Enable Android AutomaticGainControl while recording when available. */
    var agcLiveEnabled: Boolean

    /** Peak-normalize and export as WAV on SAF export. */
    var normalizeOnExport: Boolean

    /** Notify before the next calendar event. */
    var calendarReminderEnabled: Boolean

    /** Minutes before event start for [calendarReminderEnabled]. */
    var calendarReminderMinutes: Int

    /**
     * Grace window in minutes before/after an event during which it still counts as "active"
     * for naming / widget (e.g. record 15 min early).
     */
    var calendarEventWindowMinutes: Int

    companion object {
        const val NO_CALENDAR_FILTER: Long = -1L
    }

    fun resetRecordingSettings()

    fun fullPreferenceReset()
}
