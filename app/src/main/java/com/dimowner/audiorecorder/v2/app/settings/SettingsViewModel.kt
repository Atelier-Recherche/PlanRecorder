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

package com.dimowner.audiorecorder.v2.app.settings

import android.content.Context
import android.os.Parcelable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.audio.player.PlayerContractNew
import com.dimowner.audiorecorder.util.AndroidUtils
import com.dimowner.audiorecorder.v2.DefaultValues
import com.dimowner.audiorecorder.v2.app.formatBitRate
import com.dimowner.audiorecorder.v2.app.formatChannelCount
import com.dimowner.audiorecorder.v2.app.formatRecordingFormat
import com.dimowner.audiorecorder.v2.app.formatSampleRate
import com.dimowner.audiorecorder.v2.app.recordingSettingsCombinedText
import com.dimowner.audiorecorder.v2.app.removeOutdatedTrashRecords
import com.dimowner.audiorecorder.v2.audio.AudioRecorderDelegate
import com.dimowner.audiorecorder.v2.analytics.AnalyticsTracker
import com.dimowner.audiorecorder.v2.data.FileDataSource
import com.dimowner.audiorecorder.v2.data.PrefsV2
import com.dimowner.audiorecorder.v2.data.RecordsDataSource
import com.dimowner.audiorecorder.v2.data.calendar.CalendarReminderScheduler
import com.dimowner.audiorecorder.v2.data.calendar.CalendarRepository
import com.dimowner.audiorecorder.v2.data.saf.SafStorageManager
import com.dimowner.audiorecorder.v2.data.model.AudioSource
import com.dimowner.audiorecorder.v2.data.model.BitRate
import com.dimowner.audiorecorder.v2.data.model.ChannelCount
import com.dimowner.audiorecorder.v2.data.model.RecordingFormat
import com.dimowner.audiorecorder.v2.data.model.SampleRate
import com.dimowner.audiorecorder.v2.data.model.isSystemAudioCaptureSupported
import com.dimowner.audiorecorder.v2.data.template.IdentifierRules
import com.dimowner.audiorecorder.v2.data.template.TemplateEngine
import com.dimowner.audiorecorder.v2.app.CourseRecordingWidget
import com.dimowner.audiorecorder.v2.di.qualifiers.IoDispatcher
import com.dimowner.audiorecorder.v2.di.qualifiers.MainDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
internal class SettingsViewModel @Inject constructor(
    private val prefs: PrefsV2,
    private val recordsDataSource: RecordsDataSource,
    private val fileDataSource: FileDataSource,
    private val audioPlayer: PlayerContractNew.Player,
    private val audioRecorderDelegate: AudioRecorderDelegate,
    private val analyticsTracker: AnalyticsTracker,
    private val calendarRepository: CalendarRepository,
    private val safStorageManager: SafStorageManager,
    private val calendarReminderScheduler: CalendarReminderScheduler,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private var voiceFilterBackupFormat: RecordingFormat? = null
    private var voiceFilterBackupBitRate: BitRate? = null
    private var voiceFilterBackupSampleRate: SampleRate? = null
    private var voiceFilterBackupChannelCount: ChannelCount? = null
    private var voiceFilterBackupAudioSource: AudioSource? = null

    private val decimalFormat: DecimalFormat

    private val selectedFormat = prefs.settingRecordingFormat
    private val selectedSampleRate = prefs.settingSampleRate
    private val selectedBitRate = prefs.settingBitrate
    private val selectedChannelCount = prefs.settingChannelCount

    private val formatsStrings = appContext.resources.getStringArray(R.array.formats2)
    private val sampleRateStrings = appContext.resources.getStringArray(R.array.sample_rates2)
    private val bitRateStrings = appContext.resources.getStringArray(R.array.bit_rates2)
    private val channelCountsStrings = appContext.resources.getStringArray(R.array.channels)

    init {
        val formatSymbols = DecimalFormatSymbols(Locale.getDefault())
        formatSymbols.decimalSeparator = '.'
        decimalFormat = DecimalFormat("#.#", formatSymbols)
    }

    private val _state: MutableState<SettingsState> = mutableStateOf(createDefaultState())

    val state: State<SettingsState> = _state

    private fun createDefaultState(): SettingsState {
        return SettingsState(
            isDynamicColors = prefs.isDynamicTheme,
            isDarkTheme = prefs.isDarkTheme,
            isAppV2 = prefs.isAppV2,
            isKeepScreenOn = prefs.isKeepScreenOn,
            isShowRenameDialog = prefs.askToRenameAfterRecordingStopped,
            isRecordingSettingEditable = true,
            selectedNameFormat = prefs.settingNamingFormat.toNameFormatItem(prefs.customNameFormat),
            nameFormats = makeNameFormats(prefs.customNameFormat),
            recordingSettings = RecordingFormat.availableFormats().map { format ->
                RecordingSetting(
                    recordingFormat = ChipItem(
                        id = format.index,
                        value = format,
                        name = formatsStrings.getOrElse(format.index) { format.value },
                        isSelected = format == selectedFormat
                    ),
                    sampleRates = getSampleRates(
                        selectedFormat,
                        selectedSampleRate,
                        sampleRateStrings
                    ),
                    bitRates = getBitRates(
                        selectedFormat,
                        selectedBitRate,
                        bitRateStrings
                    ),
                    channelCounts = getChannelCounts(
                        selectedFormat,
                        selectedChannelCount,
                        channelCountsStrings
                    ),
                )
            },
            sizePerMin = decimalFormat.format(
                sizeMbPerMin(
                    selectedFormat,
                    selectedSampleRate,
                    selectedBitRate,
                    selectedChannelCount
                )
            ),
            recordingSettingsText = recordingSettingsCombinedText(
                selectedFormat,
                formatRecordingFormat(formatsStrings, selectedFormat),
                formatSampleRate(sampleRateStrings, selectedSampleRate),
                formatBitRate(bitRateStrings, selectedBitRate),
                formatChannelCount(channelCountsStrings, selectedChannelCount),
            ),
            totalRecordCount = 0,
            totalRecordDuration = 0,
            availableSpaceMills = 0,
            availableSpaceBytes = 0,
            appName = appContext.getString(R.string.app_name),
            appVersion = appContext.getString(R.string.version, AndroidUtils.getAppVersion(appContext)),
            maxRecordingDurationMinutes = prefs.maxRecordingDurationMills / 60000,
            recordAuthorName = prefs.recordAuthorName,
            isLegacyAppUser = prefs.isLegacyAppUser,
            selectedAudioSource = prefs.settingAudioSource,
            audioSourceOptions = supportedAudioSources(),
            safRootTreeUri = prefs.safRootTreeUri,
            safRootDisplayPath = safStorageManager.displayPath(prefs.safRootTreeUri),
            folderPattern = prefs.folderPattern,
            filePattern = prefs.filePattern,
            patternPreview = buildPatternPreview(prefs.folderPattern, prefs.filePattern),
            voiceFilterEnabled = prefs.voiceFilterEnabled,
            selectedCalendarId = prefs.selectedCalendarId,
            availableCalendars = emptyList(),
            identifierRulesText = prefs.identifierRulesText,
            seenIdentifiersText = prefs.seenIdentifiers.sorted().joinToString(", "),
            agcLiveEnabled = prefs.agcLiveEnabled,
            normalizeOnExport = prefs.normalizeOnExport,
            calendarReminderEnabled = prefs.calendarReminderEnabled,
            calendarReminderMinutes = prefs.calendarReminderMinutes,
        )
    }

    private fun selectedCalendarFilter(): Long? =
        prefs.selectedCalendarId.takeIf { it != PrefsV2.NO_CALENDAR_FILTER }

    private fun buildPatternPreview(folderPattern: String, filePattern: String): String {
        val event = calendarRepository.getActiveEvent(calendarId = selectedCalendarFilter())
        val resolved = TemplateEngine.resolve(
            event = event,
            folderPattern = folderPattern,
            filePattern = filePattern,
            identifierRulesText = prefs.identifierRulesText,
        )
        return "${resolved.relativeDir}/${resolved.fileBaseName}.${prefs.settingRecordingFormat.value}"
    }

    private fun loadCalendarsIntoState() {
        val calendars = calendarRepository.listCalendars().map {
            val account = it.accountName?.let { acc -> " ($acc)" }.orEmpty()
            CalendarOptionItem(id = it.id, label = it.displayName + account)
        }
        _state.value = _state.value.copy(
            availableCalendars = calendars,
            selectedCalendarId = prefs.selectedCalendarId,
            patternPreview = buildPatternPreview(prefs.folderPattern, prefs.filePattern),
        )
    }

    fun initSettings() {
        viewModelScope.launch(ioDispatcher) {
            val recordsCount = recordsDataSource.getRecordsCount()
            val recordsDuration = recordsDataSource.getRecordTotalDuration()
            val rawAvailableSpaceBytes = fileDataSource.getAvailableSpace()
            val settings = _state.value.recordingSettings.firstOrNull { it.recordingFormat.isSelected }
            val availableTimeMills = spaceToRecordingTimeMills(
                rawAvailableSpaceBytes,
                settings?.recordingFormat?.value,
                settings?.sampleRates?.firstOrNull { it.isSelected }?.value,
                settings?.bitRates?.firstOrNull { it.isSelected }?.value,
                settings?.channelCounts?.firstOrNull { it.isSelected }?.value,
            )
            val calendars = calendarRepository.listCalendars().map {
                val account = it.accountName?.let { acc -> " ($acc)" }.orEmpty()
                CalendarOptionItem(id = it.id, label = it.displayName + account)
            }
            withContext(mainDispatcher) {
                _state.value = _state.value.copy(
                    isRecordingSettingEditable = !audioRecorderDelegate.provideAudioRecorder().isRecording,
                    totalRecordCount = recordsCount,
                    totalRecordDuration = recordsDuration,
                    availableSpaceMills = availableTimeMills,
                    availableSpaceBytes = rawAvailableSpaceBytes,
                    // Load the selected audio source from preferences
                    selectedAudioSource = prefs.settingAudioSource,
                    audioSourceOptions = supportedAudioSources(),
                    availableCalendars = calendars,
                    selectedCalendarId = prefs.selectedCalendarId,
                    patternPreview = buildPatternPreview(prefs.folderPattern, prefs.filePattern),
                )
            }
            recordsDataSource.removeOutdatedTrashRecords()
        }
    }

    fun setAudioSource(audioSource: AudioSource) {
        if (audioSource !in supportedAudioSources()) return
        _state.value = _state.value.copy(selectedAudioSource = audioSource)
        prefs.settingAudioSource = audioSource
        validateFormatForAudioSource(audioSource)
    }

    /**
     * System audio is captured with `AudioRecord`, and 3GP is the one format recorded through
     * `MediaRecorder` only, so the pair cannot be honoured. Switching the source moves the format
     * to the default rather than refusing the switch, because the source is what the user just
     * asked for.
     */
    private fun validateFormatForAudioSource(audioSource: AudioSource) {
        if (audioSource.isSystemAudio && prefs.settingRecordingFormat == RecordingFormat.ThreeGp) {
            selectRecordingFormat(DefaultValues.DefaultRecordingFormat)
        }
    }

    /**
     * The audio sources offered on this device. System audio needs Android 10 and the feature
     * flag; the microphone sources are always available.
     */
    private fun supportedAudioSources(): List<AudioSource> =
        AudioSource.entries.filter { !it.isSystemAudio || isSystemAudioCaptureSupported() }

    fun executeFirstRun() {
        if (prefs.isFirstRun) {
            prefs.confirmFirstRunExecuted()
        }
    }

    fun handleUseAppV2(value: Boolean) {
        if (prefs.isAppV2 != value) {
            prefs.isAppV2 = value
            if (value) {
                analyticsTracker.trackSwitchToAppV2()
            } else {
                analyticsTracker.trackSwitchToLegacyApp()
            }
        }
        audioPlayer.stop()
    }

    fun setDarkTheme(value: Boolean) {
        if (prefs.isDarkTheme != value) {
            prefs.isDarkTheme = value
            _state.value = _state.value.copy(isDarkTheme = value)
        }
    }

    fun setDynamicTheme(value: Boolean) {
        if (prefs.isDynamicTheme != value) {
            prefs.isDynamicTheme = value
            _state.value = _state.value.copy(isDynamicColors = value)
        }
    }

    fun setKeepScreenOn(value: Boolean) {
        prefs.isKeepScreenOn = value
        _state.value = _state.value.copy(isKeepScreenOn = value)
    }

    fun setShowRenamingDialog(value: Boolean) {
        prefs.askToRenameAfterRecordingStopped = value
        _state.value = _state.value.copy(isShowRenameDialog = value)
    }

    fun setNameFormat(value: NameFormatItem) {
        prefs.settingNamingFormat = value.nameFormat
        _state.value = _state.value.copy(selectedNameFormat = value)
    }

    /** Re-reads the name format from preferences, where the name format constructor stores it. */
    private fun refreshNameFormat() {
        val customTokens = prefs.customNameFormat
        _state.value = _state.value.copy(
            selectedNameFormat = prefs.settingNamingFormat.toNameFormatItem(customTokens),
            nameFormats = makeNameFormats(customTokens),
        )
    }

    fun resetRecordingSettings() {
        prefs.settingRecordingFormat = DefaultValues.DefaultRecordingFormat
        prefs.settingSampleRate = DefaultValues.DefaultSampleRate
        prefs.settingBitrate = DefaultValues.DefaultBitRate
        prefs.settingChannelCount = DefaultValues.DefaultChannelCount
        prefs.settingAudioSource = DefaultValues.DefaultAudioSource
        _state.value = _state.value.copy(
            recordingSettings = _state.value.recordingSettings.map { formatSetting ->
                RecordingSetting(
                    recordingFormat = formatSetting.recordingFormat.updateSelected(
                        DefaultValues.DefaultRecordingFormat
                    ),
                    sampleRates = getSampleRates(
                        DefaultValues.DefaultRecordingFormat,
                        DefaultValues.DefaultSampleRate,
                        sampleRateStrings
                    ),
                    bitRates = getBitRates(
                        DefaultValues.DefaultRecordingFormat,
                        DefaultValues.DefaultBitRate,
                        bitRateStrings
                    ),
                    channelCounts = getChannelCounts(
                        DefaultValues.DefaultRecordingFormat,
                        DefaultValues.DefaultChannelCount,
                        channelCountsStrings
                    ),
                )
            },
        ).recordingSettingsUpdated()
    }

    fun selectRecordingFormat(value: RecordingFormat) {
        prefs.settingRecordingFormat = value
        // The mirror of validateFormatForAudioSource(): 3GP has no AudioRecord-backed recorder,
        // so picking it gives up system audio capture.
        if (value == RecordingFormat.ThreeGp && prefs.settingAudioSource.isSystemAudio) {
            prefs.settingAudioSource = DefaultValues.DefaultAudioSource
            _state.value = _state.value.copy(
                selectedAudioSource = DefaultValues.DefaultAudioSource
            )
        }
        _state.value = _state.value.copy(
            recordingSettings = _state.value.recordingSettings.map { item ->
                item.copy(
                    recordingFormat = item.recordingFormat.updateSelected(value),
                    sampleRates = getSampleRates(
                        value,
                        prefs.settingSampleRate,
                        sampleRateStrings
                    ),
                    bitRates = getBitRates(
                        value,
                        prefs.settingBitrate,
                        bitRateStrings
                    ),
                    channelCounts = getChannelCounts(
                        value,
                        prefs.settingChannelCount,
                        channelCountsStrings
                    ),
                )
            }
        ).adjustSelectionToFormat(value)
        .recordingSettingsUpdated()
    }

    /**
     * Ensures the persisted sample rate, bitrate and channel count are all supported by the newly
     * selected [format]. Any unsupported selection is replaced with the format's default, the new
     * value is persisted, and the chips for that format are rebuilt so a valid chip stays selected.
     */
    private fun SettingsState.adjustSelectionToFormat(format: RecordingFormat): SettingsState {
        val config = format.config
        if (!config.isSampleRateSupported(prefs.settingSampleRate)) {
            prefs.settingSampleRate = format.defaultSampleRate()
        }
        if (!config.isChannelCountSupported(prefs.settingChannelCount)) {
            prefs.settingChannelCount = format.defaultChannelCount()
        }
        if (config.hasBitrate && !config.isBitRateSupported(prefs.settingBitrate)) {
            prefs.settingBitrate = config.supportedBitRates.firstOrNull { it == BitRate.BR32 }
                ?: config.supportedBitRates.firstOrNull()
                ?: DefaultValues.DefaultBitRate
        }
        return this.copy(
            recordingSettings = recordingSettings.map { recordingSetting ->
                if (recordingSetting.recordingFormat.value == format) {
                    recordingSetting.copy(
                        sampleRates = getSampleRates(format, prefs.settingSampleRate, sampleRateStrings),
                        bitRates = getBitRates(format, prefs.settingBitrate, bitRateStrings),
                        channelCounts = getChannelCounts(format, prefs.settingChannelCount, channelCountsStrings),
                    )
                } else {
                    recordingSetting
                }
            }
        )
    }

    fun selectSampleRate(value: SampleRate) {
        prefs.settingSampleRate = value
        _state.value = _state.value.copy(
            recordingSettings = _state.value.recordingSettings.map { formatSetting ->
                if (formatSetting.recordingFormat.isSelected) {
                    formatSetting.copy(
                        sampleRates = formatSetting.sampleRates.map { item ->
                            item.updateSelected(value)
                        }
                    )
                } else {
                    formatSetting
                }
            }
        ).recordingSettingsUpdated()
    }

    fun selectBitrate(value: BitRate) {
        prefs.settingBitrate = value
        _state.value = _state.value.copy(
            recordingSettings = _state.value.recordingSettings.map { formatSetting ->
                if (formatSetting.recordingFormat.isSelected) {
                    formatSetting.copy(
                        bitRates = formatSetting.bitRates.map { item ->
                            item.updateSelected(value)
                        }
                    )
                } else {
                    formatSetting
                }
            }
        ).recordingSettingsUpdated()
    }

    fun selectChannelCount(value: ChannelCount) {
        prefs.settingChannelCount = value
        _state.value = _state.value.copy(
            recordingSettings = _state.value.recordingSettings.map { formatSetting ->
                if (formatSetting.recordingFormat.isSelected) {
                    formatSetting.copy(
                        channelCounts = formatSetting.channelCounts.map { item ->
                            item.updateSelected(value)
                        }
                    )
                } else {
                    formatSetting
                }
            }
        ).recordingSettingsUpdated()
    }

    fun setMaxRecordingDuration(durationMinutes: Int) {
        if (durationMinutes > 0) {
            prefs.maxRecordingDurationMills = durationMinutes * 60 * 1000
            _state.value = _state.value.copy(maxRecordingDurationMinutes = durationMinutes)
        }
    }

    fun setRecordAuthorName(name: String) {
        val trimmed = name.trim()
        prefs.recordAuthorName = trimmed
        _state.value = _state.value.copy(recordAuthorName = trimmed)
    }

    fun setSafRootTreeUri(uri: String?) {
        prefs.safRootTreeUri = uri
        _state.value = _state.value.copy(
            safRootTreeUri = uri,
            safRootDisplayPath = safStorageManager.displayPath(uri),
        )
    }

    fun setFolderPattern(pattern: String) {
        prefs.folderPattern = pattern
        _state.value = _state.value.copy(
            folderPattern = pattern,
            patternPreview = buildPatternPreview(pattern, prefs.filePattern),
        )
    }

    fun setFilePattern(pattern: String) {
        prefs.filePattern = pattern
        _state.value = _state.value.copy(
            filePattern = pattern,
            patternPreview = buildPatternPreview(prefs.folderPattern, pattern),
        )
    }

    fun setVoiceFilterEnabled(enabled: Boolean) {
        if (enabled == prefs.voiceFilterEnabled) return
        if (enabled) {
            voiceFilterBackupFormat = prefs.settingRecordingFormat
            voiceFilterBackupBitRate = prefs.settingBitrate
            voiceFilterBackupSampleRate = prefs.settingSampleRate
            voiceFilterBackupChannelCount = prefs.settingChannelCount
            voiceFilterBackupAudioSource = prefs.settingAudioSource
            prefs.voiceFilterEnabled = true
            if (RecordingFormat.Opus in RecordingFormat.availableFormats()) {
                selectRecordingFormat(RecordingFormat.Opus)
                selectBitrate(BitRate.BR32)
                selectSampleRate(SampleRate.SR16000)
                selectChannelCount(ChannelCount.Mono)
            }
            setAudioSource(AudioSource.VOICE_RECOGNITION)
        } else {
            prefs.voiceFilterEnabled = false
            voiceFilterBackupFormat?.let { selectRecordingFormat(it) }
            voiceFilterBackupBitRate?.let { selectBitrate(it) }
            voiceFilterBackupSampleRate?.let { selectSampleRate(it) }
            voiceFilterBackupChannelCount?.let { selectChannelCount(it) }
            voiceFilterBackupAudioSource?.let { setAudioSource(it) }
        }
        _state.value = _state.value.copy(voiceFilterEnabled = enabled)
    }

    fun setSelectedCalendarId(calendarId: Long) {
        prefs.selectedCalendarId = calendarId
        _state.value = _state.value.copy(
            selectedCalendarId = calendarId,
            patternPreview = buildPatternPreview(prefs.folderPattern, prefs.filePattern),
        )
        calendarReminderScheduler.reschedule()
        CourseRecordingWidget.requestUpdate(appContext)
    }

    fun setIdentifierRulesText(text: String) {
        val normalized = IdentifierRules.formatRules(IdentifierRules.parseRules(text))
        prefs.identifierRulesText = normalized
        _state.value = _state.value.copy(
            identifierRulesText = normalized,
            patternPreview = buildPatternPreview(prefs.folderPattern, prefs.filePattern),
        )
    }

    fun setAgcLiveEnabled(enabled: Boolean) {
        prefs.agcLiveEnabled = enabled
        _state.value = _state.value.copy(agcLiveEnabled = enabled)
    }

    fun setNormalizeOnExport(enabled: Boolean) {
        prefs.normalizeOnExport = enabled
        _state.value = _state.value.copy(normalizeOnExport = enabled)
    }

    fun setCalendarReminderEnabled(enabled: Boolean) {
        prefs.calendarReminderEnabled = enabled
        _state.value = _state.value.copy(calendarReminderEnabled = enabled)
        calendarReminderScheduler.reschedule()
    }

    fun setCalendarReminderMinutes(minutes: Int) {
        prefs.calendarReminderMinutes = minutes
        _state.value = _state.value.copy(calendarReminderMinutes = prefs.calendarReminderMinutes)
        calendarReminderScheduler.reschedule()
    }

    fun insertPatternTag(target: PatternField, tag: String) {
        when (target) {
            PatternField.Folder -> setFolderPattern(prefs.folderPattern + tag)
            PatternField.File -> setFilePattern(prefs.filePattern + tag)
        }
    }

    fun refreshCalendars() {
        viewModelScope.launch(ioDispatcher) {
            withContext(mainDispatcher) {
                loadCalendarsIntoState()
                calendarReminderScheduler.reschedule()
                CourseRecordingWidget.requestUpdate(appContext)
            }
        }
    }

    fun onAction(action: SettingsScreenAction) {
        when (action) {
            SettingsScreenAction.InitSettingsScreen -> initSettings()
            is SettingsScreenAction.SetDynamicTheme -> setDynamicTheme(action.value)
            is SettingsScreenAction.SetDarkTheme -> setDarkTheme(action.value)
            is SettingsScreenAction.SetKeepScreenOn -> setKeepScreenOn(action.value)
            is SettingsScreenAction.SetShowRenamingDialog -> setShowRenamingDialog(action.value)
            is SettingsScreenAction.SetNameFormat -> setNameFormat(action.value)
            SettingsScreenAction.RefreshNameFormat -> refreshNameFormat()
            SettingsScreenAction.ResetRecordingSettings -> resetRecordingSettings()
            is SettingsScreenAction.SelectRecordingFormat -> selectRecordingFormat(action.value)
            is SettingsScreenAction.SelectSampleRate -> selectSampleRate(action.value)
            is SettingsScreenAction.SelectBitrate -> selectBitrate(action.value)
            is SettingsScreenAction.SelectChannelCount -> selectChannelCount(action.value)
            is SettingsScreenAction.SetMaxRecordingDuration -> setMaxRecordingDuration(action.durationMinutes)
            is SettingsScreenAction.SetAudioSource -> setAudioSource(action.audioSource)
            is SettingsScreenAction.SetRecordAuthorName -> setRecordAuthorName(action.name)
            SettingsScreenAction.ExecuteFirstRun -> executeFirstRun()
            is SettingsScreenAction.SetAppV2 -> handleUseAppV2(action.value)
            SettingsScreenAction.UnlockLegacyAppSwitch -> unlockLegacyAppSwitch()
            is SettingsScreenAction.SetSafRootTreeUri -> setSafRootTreeUri(action.uri)
            is SettingsScreenAction.SetFolderPattern -> setFolderPattern(action.pattern)
            is SettingsScreenAction.SetFilePattern -> setFilePattern(action.pattern)
            is SettingsScreenAction.SetVoiceFilterEnabled -> setVoiceFilterEnabled(action.enabled)
            is SettingsScreenAction.SetSelectedCalendarId -> setSelectedCalendarId(action.calendarId)
            is SettingsScreenAction.InsertPatternTag -> insertPatternTag(action.target, action.tag)
            SettingsScreenAction.RefreshCalendars -> refreshCalendars()
            is SettingsScreenAction.SetIdentifierRulesText -> setIdentifierRulesText(action.text)
            is SettingsScreenAction.SetAgcLiveEnabled -> setAgcLiveEnabled(action.enabled)
            is SettingsScreenAction.SetNormalizeOnExport -> setNormalizeOnExport(action.enabled)
            is SettingsScreenAction.SetCalendarReminderEnabled -> setCalendarReminderEnabled(action.enabled)
            is SettingsScreenAction.SetCalendarReminderMinutes -> setCalendarReminderMinutes(action.minutes)
        }
    }

    fun unlockLegacyAppSwitch() {
        if (!prefs.isLegacyAppUser) {
            prefs.isLegacyAppUser = true
            _state.value = _state.value.copy(isLegacyAppUser = true)
        }
    }

    private fun SettingsState.recordingSettingsUpdated(): SettingsState {
        val settings = this.recordingSettings.firstOrNull { it.recordingFormat.isSelected }
        return this.copy(
            sizePerMin = decimalFormat.format(
                sizeMbPerMin(
                    settings?.recordingFormat?.value,
                    settings?.sampleRates?.firstOrNull { it.isSelected }?.value,
                    settings?.bitRates?.firstOrNull { it.isSelected }?.value,
                    settings?.channelCounts?.firstOrNull { it.isSelected }?.value,
                )
            ),
            recordingSettingsText = recordingSettingsCombinedText(
                settings?.recordingFormat?.value,
                formatRecordingFormat(formatsStrings, settings?.recordingFormat?.value),
                formatSampleRate(sampleRateStrings, settings?.sampleRates?.firstOrNull { it.isSelected }?.value),
                formatBitRate(bitRateStrings, settings?.bitRates?.firstOrNull { it.isSelected }?.value),
                formatChannelCount(channelCountsStrings, settings?.channelCounts?.firstOrNull { it.isSelected }?.value),
            ),
            availableSpaceMills = spaceToRecordingTimeMills(
                this.availableSpaceBytes,
                settings?.recordingFormat?.value,
                settings?.sampleRates?.firstOrNull { it.isSelected }?.value,
                settings?.bitRates?.firstOrNull { it.isSelected }?.value,
                settings?.channelCounts?.firstOrNull { it.isSelected }?.value,
            ),
            availableSpaceBytes = this.availableSpaceBytes,
        )
    }

    private fun <T : Parcelable> ChipItem<T>.updateSelected(value: T): ChipItem<T> {
        return if (this.value == value) {
            this.copy(isSelected = true)
        } else {
            this.copy(isSelected = false)
        }
    }
}

internal sealed class SettingsScreenAction {
    data object InitSettingsScreen : SettingsScreenAction()
    data class SetAppV2(val value: Boolean) : SettingsScreenAction()
    data class SetDynamicTheme(val value: Boolean) : SettingsScreenAction()
    data class SetDarkTheme(val value: Boolean) : SettingsScreenAction()
    data class SetKeepScreenOn(val value: Boolean) : SettingsScreenAction()
    data class SetShowRenamingDialog(val value: Boolean) : SettingsScreenAction()
    data class SetNameFormat(val value: NameFormatItem) : SettingsScreenAction()
    data object RefreshNameFormat : SettingsScreenAction()
    data object ResetRecordingSettings : SettingsScreenAction()
    data class SelectRecordingFormat(val value: RecordingFormat) : SettingsScreenAction()
    data class SelectSampleRate(val value: SampleRate) : SettingsScreenAction()
    data class SelectBitrate(val value: BitRate) : SettingsScreenAction()
    data class SelectChannelCount(val value: ChannelCount) : SettingsScreenAction()
    data class SetMaxRecordingDuration(val durationMinutes: Int) : SettingsScreenAction()
    data class SetAudioSource(val audioSource: AudioSource) : SettingsScreenAction()
    data class SetRecordAuthorName(val name: String) : SettingsScreenAction()
    data object ExecuteFirstRun : SettingsScreenAction()
    data object UnlockLegacyAppSwitch : SettingsScreenAction()
    data class SetSafRootTreeUri(val uri: String?) : SettingsScreenAction()
    data class SetFolderPattern(val pattern: String) : SettingsScreenAction()
    data class SetFilePattern(val pattern: String) : SettingsScreenAction()
    data class SetVoiceFilterEnabled(val enabled: Boolean) : SettingsScreenAction()
    data class SetSelectedCalendarId(val calendarId: Long) : SettingsScreenAction()
    data class InsertPatternTag(val target: PatternField, val tag: String) : SettingsScreenAction()
    data object RefreshCalendars : SettingsScreenAction()
    data class SetIdentifierRulesText(val text: String) : SettingsScreenAction()
    data class SetAgcLiveEnabled(val enabled: Boolean) : SettingsScreenAction()
    data class SetNormalizeOnExport(val enabled: Boolean) : SettingsScreenAction()
    data class SetCalendarReminderEnabled(val enabled: Boolean) : SettingsScreenAction()
    data class SetCalendarReminderMinutes(val minutes: Int) : SettingsScreenAction()
}

enum class PatternField {
    Folder,
    File,
}
