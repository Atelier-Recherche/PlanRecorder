package com.dimowner.audiorecorder.v2.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifierRulesTest {

    @Test
    fun `hash plus means one or more digits`() {
        val parts = IdentifierRules.parseId("UE323 - Technocraties III", "UE#+")
        assertEquals("UE323", parts.id)
        assertEquals("Technocraties III", parts.titleRest)
        assertEquals("UE3", IdentifierRules.parseId("UE3 Intro", "UE#+").id)
    }

    @Test
    fun `hash hashes mean exact digit count`() {
        val rules = "MATH###"
        val parts = IdentifierRules.parseId("MATH101 Algebra", rules)
        assertEquals("MATH101", parts.id)
        assertEquals("Algebra", parts.titleRest)
        assertNull(IdentifierRules.extractId("MATH12 x", rules))
    }

    @Test
    fun `legacy colon form still works and formats to hashes`() {
        val rule = IdentifierRules.parseLine("UE:1+")!!
        assertEquals("UE#+", rule.toLine())
        val exact = IdentifierRules.parseLine("UE:3")!!
        assertEquals("UE###", exact.toLine())
    }

    @Test
    fun `preview lists sample matches`() {
        val preview = IdentifierRules.buildPreview("UE###")
        assertTrue(preview.contains("[id]=UE323"))
        assertTrue(preview.contains("aucun id") || preview.contains("UE12"))
    }

    @Test
    fun `obsidian marker line format`() {
        val md = com.dimowner.audiorecorder.v2.data.markers.ObsidianMarkersMarkdown.build(
            "01-T163638-140426-UE323.opus",
            listOf(
                com.dimowner.audiorecorder.v2.data.markers.RecordingMarker(508_000L, "debut de session"),
            ),
        )
        assertTrue(md.contains("[[01-T163638-140426-UE323.opus#t=508|08:28]] debut de session"))
    }
}
