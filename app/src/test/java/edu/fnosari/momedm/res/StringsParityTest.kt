package edu.fnosari.momedm.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringsParityTest {

    /** Resolves the res/ dir whether the JVM working dir is the :app module root or the repo root. */
    private fun resDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
        )
        return candidates.firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error(
                "Could not locate res/ (tried: ${candidates.joinToString { it.path }}); " +
                    "working dir = ${File(".").absolutePath}"
            )
    }

    private fun keys(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        // Plurals count too: a quantity string present in one locale only crashes at runtime the
        // same way a missing plain string would.
        return listOf("string", "plurals").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { tag + "/" + nodes.item(it).attributes.getNamedItem("name").nodeValue }
        }.toSet()
    }

    @Test
    fun englishAndFrenchHaveIdenticalKeys() {
        val base = resDir()
        val en = keys(File(base, "values/strings.xml"))
        val fr = keys(File(base, "values-fr/strings.xml"))
        assertTrue("English strings.xml has no keys — check resolved path: $base", en.isNotEmpty())
        assertEquals("keys only in EN: ${en - fr}; keys only in FR: ${fr - en}", en, fr)
    }
}
