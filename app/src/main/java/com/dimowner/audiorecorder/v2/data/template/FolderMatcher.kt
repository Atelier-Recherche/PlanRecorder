/*
 * Copyright 2026 PlanRecorder contributors
 */
package com.dimowner.audiorecorder.v2.data.template

import java.util.Locale

/**
 * Matches a rendered folder name against existing directories under the SAF root,
 * and ranks ID-like folders for manual selection when calendar data is missing/ambiguous.
 */
object FolderMatcher {

    private val TOKEN_SPLIT = Regex("""[\s_\-–—./]+""")

    data class Match(
        val folderName: String,
        val score: Double,
        val idCode: String?,
    )

    fun resolveExistingFolder(
        desiredFolderName: String,
        rootFolderNames: List<String>,
        minScore: Double = 0.45,
        identifierRulesText: String? = IdentifierRules.DEFAULT_RULES_TEXT,
    ): String {
        val desired = desiredFolderName.trim()
        if (desired.isEmpty() || desired == TemplateEngine.FALLBACK_FOLDER) return desired
        val exact = rootFolderNames.firstOrNull { it.equals(desired, ignoreCase = true) }
        if (exact != null) return exact

        val best = rankMatches(desired, rootFolderNames, identifierRulesText).firstOrNull()
        return if (best != null && best.score >= minScore) best.folderName else desired
    }

    fun rankMatches(
        query: String,
        rootFolderNames: List<String>,
        identifierRulesText: String? = IdentifierRules.DEFAULT_RULES_TEXT,
    ): List<Match> {
        val qId = extractId(query, identifierRulesText)
        val qTokens = tokens(query, identifierRulesText)
        return rootFolderNames
            .filter { it.isNotBlank() && !it.equals(TemplateEngine.FALLBACK_FOLDER, ignoreCase = true) }
            .map { name ->
                val fId = extractId(name, identifierRulesText)
                val fTokens = tokens(name, identifierRulesText)
                val score = score(qId, qTokens, fId, fTokens, query, name)
                Match(folderName = name, score = score, idCode = fId)
            }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
    }

    fun listUeFolders(
        rootFolderNames: List<String>,
        identifierRulesText: String? = IdentifierRules.DEFAULT_RULES_TEXT,
    ): List<String> = IdentifierRules.listIdFolders(rootFolderNames, identifierRulesText)

    fun extractUe(text: String, identifierRulesText: String? = IdentifierRules.DEFAULT_RULES_TEXT): String? =
        extractId(text, identifierRulesText)

    fun extractId(text: String, identifierRulesText: String? = IdentifierRules.DEFAULT_RULES_TEXT): String? =
        IdentifierRules.extractId(text, identifierRulesText)

    private fun tokens(text: String, rulesText: String?): Set<String> {
        val id = extractId(text, rulesText)?.lowercase(Locale.ROOT)
        return text.lowercase(Locale.ROOT)
            .split(TOKEN_SPLIT)
            .map { it.trim() }
            .filter { it.length >= 2 && it != id }
            .toSet()
    }

    private fun score(
        qId: String?,
        qTokens: Set<String>,
        fId: String?,
        fTokens: Set<String>,
        query: String,
        folder: String,
    ): Double {
        val qNorm = normalize(query)
        val fNorm = normalize(folder)
        if (qNorm == fNorm) return 1.0
        if (qNorm.contains(fNorm) || fNorm.contains(qNorm)) {
            val containment = minOf(qNorm.length, fNorm.length).toDouble() /
                maxOf(qNorm.length, fNorm.length).toDouble()
            var s = 0.55 + 0.35 * containment
            if (qId != null && qId == fId) s += 0.1
            return s.coerceAtMost(0.99)
        }
        if (qId != null && fId != null && qId != fId) return 0.0
        if (qId != null && fId == qId) {
            if (qTokens.isEmpty() || fTokens.isEmpty()) return 0.7
            val inter = qTokens.intersect(fTokens).size.toDouble()
            val union = qTokens.union(fTokens).size.toDouble().coerceAtLeast(1.0)
            return 0.55 + 0.4 * (inter / union)
        }
        if (qTokens.isEmpty() || fTokens.isEmpty()) return 0.0
        val inter = qTokens.intersect(fTokens).size.toDouble()
        val union = qTokens.union(fTokens).size.toDouble().coerceAtLeast(1.0)
        val jaccard = inter / union
        return if (jaccard >= 0.34) jaccard else 0.0
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("""[\s_\-–—./]+"""), " ")
            .trim()
}
