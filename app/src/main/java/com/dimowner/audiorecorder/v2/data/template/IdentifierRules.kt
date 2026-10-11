/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data.template

import java.util.Locale
import java.util.regex.Pattern

/**
 * User-defined session identifiers.
 *
 * One rule per line (hash form, preferred):
 * - `UE###` → prefix "UE" + exactly 3 digits (UE323)
 * - `MATH##` → prefix "MATH" + exactly 2 digits
 * - `UE#+` → prefix "UE" + at least 1 digit (UE3, UE323, UE1234…)
 * - `UE##+` → at least 2 digits
 *
 * Legacy colon form still accepted: `UE:3`, `UE:1+`.
 */
object IdentifierRules {

    const val DEFAULT_RULES_TEXT = "UE#+"

    private val HASH_LINE = Regex("""^(.+?)(#+)(\+)?$""")
    private val SAMPLE_TITLES = listOf(
        "UE323 - Technocraties III",
        "UE12 Intro",
        "MATH101 Algebra",
        "Session sans code",
    )

    data class Rule(
        val prefix: String,
        val minDigits: Int,
        val maxDigits: Int,
    ) {
        fun toLine(): String {
            val hashes = "#".repeat(minDigits.coerceAtLeast(1))
            return if (minDigits == maxDigits) "$prefix$hashes" else "$prefix$hashes+"
        }

        fun describe(): String =
            if (minDigits == maxDigits) {
                "$prefix + exactement $minDigits chiffre(s)"
            } else {
                "$prefix + au moins $minDigits chiffre(s)"
            }
    }

    data class IdParts(val id: String, val titleRest: String)

    fun parseRules(text: String?): List<Rule> {
        val lines = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        if (lines.isEmpty()) return parseRules(DEFAULT_RULES_TEXT)
        val parsed = lines.mapNotNull { parseLine(it) }
        return parsed.ifEmpty { parseRules(DEFAULT_RULES_TEXT) }
    }

    fun parseLine(line: String): Rule? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        HASH_LINE.matchEntire(trimmed)?.let { m ->
            val prefix = m.groupValues[1]
            if (prefix.isEmpty() || prefix.contains(':')) return@let null
            val hashCount = m.groupValues[2].length
            val flexible = m.groupValues[3].isNotEmpty()
            return if (flexible) {
                Rule(prefix = prefix, minDigits = hashCount, maxDigits = 12)
            } else {
                Rule(prefix = prefix, minDigits = hashCount, maxDigits = hashCount)
            }
        }

        // Legacy: PREFIX:N or PREFIX:N+
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return null
        val prefix = trimmed.substring(0, colon).trim()
        if (prefix.isEmpty()) return null
        val digitsSpec = trimmed.substring(colon + 1).trim()
        return when {
            digitsSpec.endsWith('+') -> {
                val min = digitsSpec.dropLast(1).toIntOrNull() ?: return null
                if (min < 1) return null
                Rule(prefix = prefix, minDigits = min, maxDigits = 12)
            }
            else -> {
                val n = digitsSpec.toIntOrNull() ?: return null
                if (n < 1) return null
                Rule(prefix = prefix, minDigits = n, maxDigits = n)
            }
        }
    }

    fun formatRules(rules: List<Rule>): String =
        rules.joinToString("\n") { it.toLine() }

    fun parseId(title: String?, rulesText: String?): IdParts {
        if (title.isNullOrBlank()) return IdParts("", "")
        val trimmed = title.trim()
        for (rule in parseRules(rulesText)) {
            val matcher = buildTitlePattern(rule).matcher(trimmed)
            if (matcher.matches()) {
                val id = matcher.group(1)?.uppercase(Locale.ROOT).orEmpty()
                val rest = matcher.group(2)?.trim().orEmpty()
                return IdParts(id = id, titleRest = rest)
            }
        }
        return IdParts(id = "", titleRest = trimmed)
    }

    fun extractId(text: String?, rulesText: String?): String? {
        if (text.isNullOrBlank()) return null
        for (rule in parseRules(rulesText)) {
            val matcher = buildFindPattern(rule).matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.uppercase(Locale.ROOT)
            }
        }
        return null
    }

    fun listIdFolders(rootFolderNames: List<String>, rulesText: String?): List<String> {
        return rootFolderNames
            .filter { extractId(it, rulesText) != null }
            .sortedWith(
                compareBy(
                    { extractId(it, rulesText).orEmpty() },
                    { it.lowercase(Locale.ROOT) },
                )
            )
    }

    /**
     * Human-readable preview lines for settings, e.g.
     * `UE323 - Technocraties III → [id]=UE323 · [title_rest]=Technocraties III`
     */
    fun buildPreview(rulesText: String?, sampleTitles: List<String> = SAMPLE_TITLES): String {
        val rules = parseRules(rulesText)
        val header = rules.joinToString(" · ") { it.toLine() + " (" + it.describe() + ")" }
        val lines = sampleTitles.map { title ->
            val parts = parseId(title, rulesText)
            if (parts.id.isBlank()) {
                "• $title → aucun id"
            } else {
                "• $title → [id]=${parts.id}" +
                    if (parts.titleRest.isNotBlank()) " · [title_rest]=${parts.titleRest}" else ""
            }
        }
        return (listOf(header) + lines).joinToString("\n")
    }

    private fun buildTitlePattern(rule: Rule): Pattern {
        val prefix = Pattern.quote(rule.prefix)
        val digits = digitClass(rule)
        return Pattern.compile(
            "^($prefix$digits)\\s*[-–:]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE,
        )
    }

    private fun buildFindPattern(rule: Rule): Pattern {
        val prefix = Pattern.quote(rule.prefix)
        val digits = digitClass(rule)
        return Pattern.compile(
            "\\b($prefix$digits)\\b",
            Pattern.CASE_INSENSITIVE,
        )
    }

    private fun digitClass(rule: Rule): String =
        if (rule.minDigits == rule.maxDigits) {
            "\\d{${rule.minDigits}}"
        } else {
            "\\d{${rule.minDigits},${rule.maxDigits}}"
        }
}
