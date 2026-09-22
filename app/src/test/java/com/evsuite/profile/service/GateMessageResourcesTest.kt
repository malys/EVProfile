package com.evsuite.profile.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The write-gate refusal strings are read with `getString(id)` and no arguments
 * ([EVProfileService.onCreate] feeds them to `VehicleWriteGate.messageProvider`). A format
 * placeholder in one of them therefore reaches the driver verbatim — `%1$s km/h` printed on
 * the toast of a car that refused a setting. That shipped once; this keeps it from shipping
 * again, in every locale rather than only the one the author reads.
 */
class GateMessageResourcesTest {

    private val gateStrings = listOf("write_refused_moving", "write_refused_unknown_speed")

    private fun resDir(): File {
        val fromModule = File("../app/src/main/res")
        return if (fromModule.isDirectory) fromModule else File("app/src/main/res")
    }

    @Test
    fun `gate refusal strings take no format argument`() {
        val dirs = resDir().listFiles { f: File -> f.isDirectory && f.name.startsWith("values") }
            ?.filter { File(it, "strings.xml").isFile }
            .orEmpty()
        assertTrue("no strings.xml found under ${resDir().absolutePath}", dirs.isNotEmpty())

        val placeholder = Regex("""%\d*\$?[sdf]""")
        dirs.forEach { dir ->
            val xml = File(dir, "strings.xml").readText()
            gateStrings.forEach { name ->
                val value = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)?.groupValues?.get(1) ?: return@forEach
                assertTrue(
                    "${dir.name}/strings.xml: $name carries a format placeholder nobody fills: $value",
                    !placeholder.containsMatchIn(value)
                )
            }
        }
    }
}
