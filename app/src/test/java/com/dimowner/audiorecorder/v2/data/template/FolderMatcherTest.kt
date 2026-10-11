package com.dimowner.audiorecorder.v2.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderMatcherTest {

    private val root = listOf(
        "UE168 Sciences, techniques, savoirs",
        "UE250 histoire (en théorie)",
        "UE323 Technocraties III",
        "UE413 carbone et science social",
        "UE581 session croquette et tech",
        "UE728 Science Sociale & IA",
        "Perso",
    )

    @Test
    fun `matches shorter event title to longer existing folder`() {
        val resolved = FolderMatcher.resolveExistingFolder(
            desiredFolderName = "UE581 session croquette",
            rootFolderNames = root,
        )
        assertEquals("UE581 session croquette et tech", resolved)
    }

    @Test
    fun `matches longer event title to shorter folder`() {
        val resolved = FolderMatcher.resolveExistingFolder(
            desiredFolderName = "UE581 derniere session croquette et technocraties",
            rootFolderNames = listOf("UE581 CT 2026", "UE323 Technocraties III"),
        )
        // Same UE code is the decisive signal when titles diverge a lot
        assertEquals("UE581 CT 2026", resolved)
    }

    @Test
    fun `does not cross different UE codes`() {
        val resolved = FolderMatcher.resolveExistingFolder(
            desiredFolderName = "UE323 Technocraties III",
            rootFolderNames = root,
        )
        assertEquals("UE323 Technocraties III", resolved)
    }

    @Test
    fun `lists UE folders sorted`() {
        val list = FolderMatcher.listUeFolders(root)
        assertEquals("UE168 Sciences, techniques, savoirs", list.first())
        assertTrue(list.any { it.startsWith("UE728") })
        assertTrue(list.none { it == "Perso" })
    }
}
