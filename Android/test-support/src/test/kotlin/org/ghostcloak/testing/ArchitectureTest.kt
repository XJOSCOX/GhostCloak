package org.ghostcloak.testing

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ArchitectureTest {
    private val root = File(System.getProperty("ghostcloak.root"))
    @Test fun libsignalTypesStayInsideCryptoAndTests() {
        val roots = listOf("app", "identity", "protocol", "storage", "transport").map { File(root, "Android/$it/src/main") } + File(root, "backend/src/main")
        val leaks = roots.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in setOf("kt", "java") }.toList() }
            .filter { it.readText().contains("org.signal.libsignal") }
        assertEquals("Vendor types outside crypto: ${leaks.map { it.relativeTo(root) }}", emptyList<File>(), leaks)
        assertFalse(File(root, "backend/build.gradle.kts").readText().contains("project(\":crypto\")"))
        assertFalse(File(root, "backend/build.gradle.kts").readText().contains("project(\":storage\")"))
    }
    @Test fun productionSourcesHaveNoDirectPayloadLogging() {
        val roots = listOf(File(root, "Android"), File(root, "backend"))
        val forbidden = Regex("(?:android\\.util\\.Log|\\bLog\\.|\\bprintln\\s*\\(|\\bprint\\s*\\(|System\\.out|printStackTrace\\s*\\()")
        val leaks = roots.flatMap { base -> base.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".idea") }
            .filter { it.isFile && it.extension in setOf("kt", "java") && it.invariantSeparatorsPath.contains("/src/main/") }.toList() }
            .filter { forbidden.containsMatchIn(it.readText()) }
        assertEquals("Unreviewed production logging: ${leaks.map { it.relativeTo(root) }}", emptyList<File>(), leaks)
    }
}
