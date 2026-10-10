package com.dimowner.audiorecorder.v2.data.template

import com.dimowner.audiorecorder.v2.data.calendar.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TemplateEngineTest {

    private val tz = TimeZone.getTimeZone("Europe/Paris")
    private val locale = Locale.FRANCE

    private fun timeAt(
        year: Int = 2026,
        month: Int = Calendar.APRIL,
        day: Int = 14,
        hour: Int = 16,
        minute: Int = 35,
        second: Int = 55,
    ): Long {
        return Calendar.getInstance(tz, locale).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `example seminar path is fully automated`() {
        val event = CalendarEvent(
            title = "UE323 - Technocraties III",
            calendarName = "Seminaires",
            description = null,
            startTimeMillis = 0L,
            endTimeMillis = 1L,
        )
        val existing = listOf(
            "01-T100000-010126-UE323.opus",
            "06-T120000-010126-UE323.opus",
        )
        val resolved = TemplateEngine.resolve(
            event = event,
            timeMillis = timeAt(),
            folderPattern = TemplateEngine.DEFAULT_FOLDER_PATTERN,
            filePattern = TemplateEngine.DEFAULT_FILE_PATTERN,
            existingNamesInTargetDir = existing,
            timeZone = tz,
            locale = locale,
        )
        assertEquals("Seminaires/UE323 Technocraties III", resolved.relativeDir)
        assertEquals("07-T163555-140426-UE323", resolved.fileBaseName)
    }

    @Test
    fun `fallback when no event`() {
        val resolved = TemplateEngine.resolve(
            event = null,
            timeMillis = timeAt(),
            existingNamesInTargetDir = emptyList(),
            timeZone = tz,
            locale = locale,
        )
        assertEquals(TemplateEngine.FALLBACK_FOLDER, resolved.relativeDir)
        assertEquals("01-T163555-140426", resolved.fileBaseName)
    }

    @Test
    fun `parseUe extracts code and rest`() {
        val parts = TemplateEngine.parseUe("UE581 - Les trajectoires techniques en question")
        assertEquals("UE581", parts.ue)
        assertEquals("Les trajectoires techniques en question", parts.titleRest)
    }

    @Test
    fun `sanitize strips illegal path characters`() {
        assertEquals("a b c", TemplateEngine.sanitize("a/b\\c:"))
    }

    @Test
    fun `nextSessionNumber increments max prefix`() {
        assertEquals(3, TemplateEngine.nextSessionNumber(listOf("01-x.opus", "2-y.opus")))
        assertEquals(1, TemplateEngine.nextSessionNumber(emptyList()))
    }

    @Test
    fun `title without UE keeps full title_rest`() {
        val event = CalendarEvent(
            title = "Standup",
            calendarName = "Perso",
            description = null,
            startTimeMillis = 0,
            endTimeMillis = 1,
        )
        val resolved = TemplateEngine.resolve(
            event = event,
            timeMillis = timeAt(),
            folderPattern = "[calendar]/[title]",
            filePattern = "[title]-[dd]",
            timeZone = tz,
            locale = locale,
        )
        assertEquals("Perso/Standup", resolved.relativeDir)
        assertTrue(resolved.fileBaseName.startsWith("Standup-"))
    }
}
