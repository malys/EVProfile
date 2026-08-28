package com.evsuite.profile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-909] R8 is activated in release and the code is very reflective. These rules cannot
 * be verified by a classic unit test (the tests run on classes not
 * minified), but their disappearance breaks the release in a SILENT way — hence this
 * guardrail on the file itself.
 */
class ProguardRulesTest {

    private val rules: String by lazy {
        val file = File("proguard-rules.pro")
        assertTrue("proguard-rules.pro not found from ${File(".").absolutePath}",
            file.exists())
        file.readText()
    }

    @Test
    fun `Signature attribute is preserved for Gson`() {
        // Without Signature, TypeToken<List<DrivingProfile>> loses its generic type and Gson
        // renders LinkedTreeMap: the profiles disappear on first launch.
        assertTrue("the Signature attribute must remain preserved",
            Regex("""-keepattributes[^\n]*\bSignature\b""").containsMatchIn(rules))
    }

    @Test
    fun `reflection targets remain preserved`() {
        listOf(
            "android.car.**",
            "android.os.ServiceManager",
            "android.os.SystemProperties",
            "com.saicmotor.**",
            "com.evsuite.hardware.model.**",
            "com.evsuite.hardware.EVHardware"
        ).forEach { target ->
            assertTrue("missing -keep rule for $target",
                rules.contains("-keep class $target"))
        }
    }

    @Test
    fun `line numbers are preserved for crash reports`() {
        assertTrue("without LineNumberTable, CrashLogger produces unusable traces",
            Regex("""-keepattributes[^\n]*\bLineNumberTable\b""").containsMatchIn(rules))
    }
}
