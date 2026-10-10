/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data.template

import java.util.Locale
import java.util.regex.Pattern

/**
 * User-defined course identifiers, e.g. "UE with digits" or "MATH + 3 digits".
 *
 * Text format (one rule per line): `PREFIX:N` or `PREFIX:N+`
 * - `UE:1+` → UE + at least 1 digit (default)
 * - `MATH:3` → MATH + exactly 3 digits
 */
object IdentifierRules {

    const val DEFAULT_RULES_TEXT = "UE:1+"

    data class Rule(
        val prefix: String,
        val minDigits: Int,
        val maxDigits: Int,
    ) {
        fun toLine(): String =
            if (minDigits == maxDigits) "$prefix:$minDigits" else "$prefix:$minDigits+"
    }

    data class IdParts(val id: String, val titleRest: String)

    fun parseRules(text: String?): List<Rule> {
        val lines = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return parseRules(DEFAULT_RULES_TEXT)
        val parsed = lines.mapNotNull { parseLine(it) }
        return parsed.ifEmpty { parseRules(DEFAULT_RULES_TEXT) }
    }

    fun parseLine(line: String): Rule? {
        val trimmed = line.trim()
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

    /**
     * Extracts the first matching identifier at the start of [title]
     * (optional separator `-` / `:` / en-dash afterwards).
     */
    fun parseId(title: String?, rulesText: String?): IdParts {
        if (title.isNullOrBlank()) return IdParts("", "")
        val trimmed = title.trim()
        for (rule in parseRules(rulesText)) {
            val pattern = buildTitlePattern(rule)
            val matcher = pattern.matcher(trimmed)
            if (matcher.matches()) {
                val id = matcher.group(1)?.uppercase(Locale.ROOT).orEmpty()
                val rest = matcher.group(2)?.trim().orEmpty()
                return IdParts(id = id, titleRest = rest)
            }
        }
        return IdParts(id = "", titleRest = trimmed)
    }

    /** Finds any identifier occurrence inside [text] (folder names, etc.). */
    fun extractId(text: String?, rulesText: String?): String? {
        if (text.isNullOrBlank()) return null
        for (rule in parseRules(rulesText)) {
            val pattern = buildFindPattern(rule)
            val matcher = pattern.matcher(text)
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
