/*
 * Copyright 2026 PlanRecorder contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.dimowner.audiorecorder.v2.data.template

import com.dimowner.audiorecorder.v2.data.calendar.CalendarEvent
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class ResolvedPath(
    val relativeDir: String,
    val fileBaseName: String,
)

/**
 * Renders configurable folder/file patterns with calendar and time tags.
 *
 * Tags (case-sensitive):
 * `[dd]` `[mm]` `[yy]` `[yyyy]` `[HH]` `[MM]` `[SS]`
 * `[title]` `[title_rest]` `[id]` `[ue]` `[calendar]` `[nn]` `[NN]`
 *
 * `[ue]` is an alias of `[id]` (legacy).
 */
object TemplateEngine {

    const val DEFAULT_FOLDER_PATTERN = "[calendar]/[id] [title_rest]"
    const val DEFAULT_FILE_PATTERN = "[NN]-T[HH][MM][SS]-[dd][mm][yy]-[id]"
    const val FALLBACK_FOLDER = "Non classé"
    const val FALLBACK_FILE_PATTERN = "[NN]-T[HH][MM][SS]-[dd][mm][yy]"

    private val ILLEGAL_CHARS = Regex("""[/\\:*?"<>|]""")

    private val SESSION_PREFIX = Regex("""^(\d{1,3})(?:[-_.]|$)""")

    /** Only these extensions contribute to `[nn]` / `[NN]` session numbering. */
    private val SESSION_AUDIO_EXTENSIONS = setOf("opus", "wav", "mp3", "m4a", "3gp")

    fun resolve(
        event: CalendarEvent?,
        timeMillis: Long = System.currentTimeMillis(),
        folderPattern: String = DEFAULT_FOLDER_PATTERN,
        filePattern: String = DEFAULT_FILE_PATTERN,
        existingNamesInTargetDir: List<String> = emptyList(),
        rootFolderNames: List<String> = emptyList(),
        identifierRulesText: String? = IdentifierRules.DEFAULT_RULES_TEXT,
        timeZone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): ResolvedPath {
        val cal = Calendar.getInstance(timeZone, locale).apply { this.timeInMillis = timeMillis }
        val parsed = parseId(event?.title, identifierRulesText)
        val nextSession = nextSessionNumber(existingNamesInTargetDir)

        val values = buildMap {
            put("dd", "%02d".format(locale, cal.get(Calendar.DAY_OF_MONTH)))
            put("mm", "%02d".format(locale, cal.get(Calendar.MONTH) + 1))
            put("yy", "%02d".format(locale, cal.get(Calendar.YEAR) % 100))
            put("yyyy", "%04d".format(locale, cal.get(Calendar.YEAR)))
            put("HH", "%02d".format(locale, cal.get(Calendar.HOUR_OF_DAY)))
            put("MM", "%02d".format(locale, cal.get(Calendar.MINUTE)))
            put("SS", "%02d".format(locale, cal.get(Calendar.SECOND)))
            put("title", sanitize(event?.title.orEmpty()))
            put("title_rest", sanitize(parsed.titleRest))
            put("id", sanitize(parsed.id))
            put("ue", sanitize(parsed.id))
            put("calendar", sanitize(event?.calendarName.orEmpty()))
            put("nn", nextSession.toString())
            put("NN", "%02d".format(locale, nextSession))
        }

        val relativeDir = if (event == null) {
            FALLBACK_FOLDER
        } else {
            val rendered = render(folderPattern, values).trim().trim('/')
            val smart = matchRelativeDirToExisting(
                rendered.ifBlank { FALLBACK_FOLDER },
                rootFolderNames,
                identifierRulesText,
            )
            smart.ifBlank { FALLBACK_FOLDER }
        }

        val pattern = if (event == null) FALLBACK_FILE_PATTERN else filePattern
        val fileBaseName = render(pattern, values).trim().ifBlank {
            render(FALLBACK_FILE_PATTERN, values)
        }

        return ResolvedPath(
            relativeDir = relativeDir,
            fileBaseName = sanitize(fileBaseName),
        )
    }

    fun matchRelativeDirToExisting(
        relativeDir: String,
        siblingFolderNames: List<String>,
        identifierRulesText: String? = IdentifierRules.DEFAULT_RULES_TEXT,
    ): String {
        if (relativeDir.isBlank() || siblingFolderNames.isEmpty()) return relativeDir
        val parts = relativeDir.split('/', '\\').filter { it.isNotBlank() }
        if (parts.isEmpty()) return relativeDir
        val last = parts.last()
        val matchedLast = FolderMatcher.resolveExistingFolder(
            last,
            siblingFolderNames,
            identifierRulesText = identifierRulesText,
        )
        return (parts.dropLast(1) + matchedLast).joinToString("/")
    }

    fun parseId(title: String?, rulesText: String? = IdentifierRules.DEFAULT_RULES_TEXT): IdentifierRules.IdParts =
        IdentifierRules.parseId(title, rulesText)

    /** @deprecated Prefer [parseId]; kept for call sites / tests that still use UE naming. */
    fun parseUe(title: String?): UeParts {
        val parts = parseId(title, IdentifierRules.DEFAULT_RULES_TEXT)
        return UeParts(ue = parts.id, titleRest = parts.titleRest)
    }

    fun nextSessionNumber(existingNames: List<String>): Int {
        var max = 0
        for (name in existingNames) {
            val leaf = name.substringAfterLast('/')
            val ext = leaf.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
            if (ext !in SESSION_AUDIO_EXTENSIONS) continue
            val base = leaf.substringBeforeLast('.')
            val match = SESSION_PREFIX.find(base) ?: continue
            val n = match.groupValues[1].toIntOrNull() ?: continue
            if (n > max) max = n
        }
        return max + 1
    }

    fun sanitize(input: String): String {
        return ILLEGAL_CHARS.replace(input, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
    }

    private fun render(pattern: String, values: Map<String, String>): String {
        val keys = values.keys.sortedByDescending { it.length }
        var result = pattern
        for (key in keys) {
            result = result.replace("[$key]", values[key].orEmpty())
        }
        return result
    }

    data class UeParts(val ue: String, val titleRest: String)
}
