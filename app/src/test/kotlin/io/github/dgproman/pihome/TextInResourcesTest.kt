package io.github.dgproman.pihome

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Holds the app to keeping what a person reads in its resources, where both
 * languages are, and lint checks that neither is missing a string.
 *
 * A narrow look at the source, for the ways words most often end up in Kotlin:
 * a quoted string handed straight to `Text`, a `title` or a content
 * description. Anything else is for review to catch.
 */
class TextInResourcesTest {
    private val literalText =
        listOf(
            Regex("""\bText\(\s*(text\s*=\s*)?""""),
            Regex("""\bcontentDescription\s*=\s*""""),
            Regex("""\btitle\s*=\s*""""),
        )

    @Test
    fun `no words for a person are written in kotlin`() {
        val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.toList()
        check(sources.isNotEmpty()) { "found no sources from ${File(".").absolutePath}" }

        val found =
            sources.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    "${file.path}:${index + 1}: ${line.trim()}".takeIf { literalText.any { it.containsMatchIn(line) } }
                }
            }

        assertEquals("text that belongs in strings.xml", emptyList<String>(), found)
    }
}
