package org.ghostcloak.testing

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ArchitectureTest {
    private val root = File(System.getProperty("ghostcloak.root"))
    @Test fun libsignalTypesStayInsideCryptoAndTests() {
        val roots = listOf("app", "identity", "protocol", "storage", "transport", "messaging", "attachments").map { File(root, "Android/$it/src/main") } + File(root, "backend/src/main")
        val leaks = roots.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension in setOf("kt", "java") }.toList() }
            .filter { it.readText().contains("org.signal.libsignal") }
        assertEquals("Vendor types outside crypto: ${leaks.map { it.relativeTo(root) }}", emptyList<File>(), leaks)
        assertFalse(File(root, "backend/build.gradle.kts").readText().contains("project(\":crypto\")"))
        assertFalse(File(root, "backend/build.gradle.kts").readText().contains("project(\":storage\")"))
        // Public signature verification is a narrow shared boundary, never an endpoint engine.
        val verifier=File(root,"Android/capabilities/src/main").walkTopDown().filter {it.isFile}.joinToString("\n") {it.readText()}
        listOf("ECPrivateKey", "IdentityKeyPair", "EndpointRecords", "SessionBuilder", "SignalProtocolEngine")
            .forEach {assertFalse("Private endpoint dependency in public verifier",verifier.contains(it))}
        assertEquals(listOf("import org.signal.libsignal.protocol.ecc.ECPublicKey"),
            verifier.lines().filter {it.startsWith("import org.signal.")})
    }
    @Test fun productionSourcesHaveNoDirectPayloadLogging() {
        val roots = listOf(File(root, "Android"), File(root, "backend"))
        val forbidden = Regex("(?:android\\.util\\.Log|\\bLog\\.|\\bprintln\\s*\\(|\\bprint\\s*\\(|System\\.out|printStackTrace\\s*\\()")
        val leaks = roots.flatMap { base -> base.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".idea") }
            .filter { it.isFile && it.extension in setOf("kt", "java") && it.invariantSeparatorsPath.contains("/src/main/") }.toList() }
            .filter { forbidden.containsMatchIn(it.readText()) && it.name != "SafeExitRecovery.kt" }
        assertEquals("Unreviewed production logging: ${leaks.map { it.relativeTo(root) }}", emptyList<File>(), leaks)
        val recovery = File(root, "Android/app/src/main/java/org/ghostcloak/app/access/SafeExitRecovery.kt").readText()
        assertEquals(listOf("Log.i(TAG, event.name)", "Log.i(TAG, \"SAFE_EXIT_RECOVERY_STATE=${'$'}{state.name}\")"),
            recovery.lines().map { it.trim() }.filter { it.contains("Log.i(") }.map { it.substringAfter("{ ").substringBeforeLast(" }") })
    }
    @Test fun routerHasNoPlaintextOrCryptoApiAndDemoIsDebugOnly() {
        val source = File(root, "Android/transport/src/main/kotlin/org/ghostcloak/transport/LocalEncryptedRouter.kt").readText()
        assertFalse(source.contains("org.ghostcloak.crypto"))
        assertFalse(source.contains("org.ghostcloak.messaging"))
        val api = org.ghostcloak.transport.EncryptedMessageTransport::class.java.methods.single { it.name == "send" }
        assertEquals(org.ghostcloak.protocol.EncryptedEnvelope::class.java, api.parameterTypes[1])
        val release = File(root, "Android/app/src/release/java/org/ghostcloak/app/developer/DeveloperMode.kt").readText()
        assertTrue(release.contains("available = false")); assertFalse(release.contains("demo-alice")); assertFalse(release.contains("demo-bob"))
    }
}
