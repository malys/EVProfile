package com.evsuite.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnglishContentTest {

    private fun repositoryFile(path: String): File {
        val fromModule = File("../$path")
        return if (fromModule.exists()) fromModule else File(path)
    }

    @Test
    fun `changelog remains in English`() {
        val changelog = repositoryFile("CHANGELOG.md")
        assertTrue("CHANGELOG.md not found from ${File(".").absolutePath}", changelog.isFile)

        val frenchMarkers = Regex(
            """[àâäçéèêëîïôöùûüÿœæ]|\b(?:une|les|des|dans|avec|pour|sans|désormais|aucun|réglages|véhicule|voiture)\b""",
            RegexOption.IGNORE_CASE
        )
        assertFalse("CHANGELOG.md contains French text", frenchMarkers.containsMatchIn(changelog.readText()))
    }

    @Test
    fun `French documentation and locale do not return`() {
        assertFalse(repositoryFile("README.fr.md").exists())
        assertFalse(repositoryFile("READMEbackup.md").exists())
        assertFalse(repositoryFile("app/src/main/res/values-fr").exists())

        val defaultStrings = repositoryFile("app/src/main/res/values/strings.xml").readText()
        assertFalse(defaultStrings.contains("settings_language_fr"))
        assertFalse(defaultStrings.contains("Français"))
    }
}
