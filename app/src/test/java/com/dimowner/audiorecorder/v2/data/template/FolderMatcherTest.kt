package com.dimowner.audiorecorder.v2.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderMatcherTest {

    private val root = listOf(
        "UE168 Sciences fictives",
        "UE250 histoire (exemple)",
        "UE101 Introduction à l'exemple",
        "UE413 carbone et exemples",
        "UE581 session demo et tech",
        "UE728 Science Sociale & IA",
        "Perso",
    )

    @Test
    fun `matches shorter event title to longer existing folder`() {
        val resolved = FolderMatcher.resolveExistingFolder(
            desiredFolderName = "UE581 session demo",
            rootFolderNames = root,
        )
        assertEquals("UE581 session demo et tech", resolved)
    }

    @Test
    fun `matches longer event title to shorter folder`() {
        val resolved = FolderMatcher.resolveExistingFolder(
            desiredFolderName = "UE581 derniere session demo et exemples",
            rootFolderNames = listOf("UE581 CT 2026", "UE101 Introduction à l'exemple"),
        )
        // Same UE code is the decisive signal when titles diverge a lot
        assertEquals("UE581 CT 2026", resolved)
    }

    @Test
    fun `does not cross different UE codes`() {
        val resolved = FolderMatcher.resolveExistingFolder(
            desiredFolderName = "UE101 Introduction à l'exemple",
            rootFolderNames = root,
        )
        assertEquals("UE101 Introduction à l'exemple", resolved)
    }

    @Test
    fun `lists UE folders sorted`() {
        val list = FolderMatcher.listUeFolders(root)
        assertEquals("UE101 Introduction à l'exemple", list.first())
        assertTrue(list.any { it.startsWith("UE728") })
        assertTrue(list.none { it == "Perso" })
    }
}
