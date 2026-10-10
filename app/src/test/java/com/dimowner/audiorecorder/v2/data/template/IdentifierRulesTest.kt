package com.dimowner.audiorecorder.v2.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentifierRulesTest {

    @Test
    fun `default UE rule parses title`() {
        val parts = IdentifierRules.parseId("UE323 - Technocraties III", "UE:1+")
        assertEquals("UE323", parts.id)
        assertEquals("Technocraties III", parts.titleRest)
    }

    @Test
    fun `custom MATH rule with exact digits`() {
        val rules = "MATH:3"
        val parts = IdentifierRules.parseId("MATH101 Intro", rules)
        assertEquals("MATH101", parts.id)
        assertEquals("Intro", parts.titleRest)
        assertNull(IdentifierRules.extractId("MATH12 x", rules))
    }

    @Test
    fun `obsidian marker line format`() {
        val md = com.dimowner.audiorecorder.v2.data.markers.ObsidianMarkersMarkdown.build(
            "01-T163638-140426-UE323.opus",
            listOf(
                com.dimowner.audiorecorder.v2.data.markers.RecordingMarker(508_000L, "debut du cours"),
            ),
        )
        assertEquals(
            true,
            md.contains("[[01-T163638-140426-UE323.opus#t=508|08:28]] debut du cours"),
        )
    }
}
