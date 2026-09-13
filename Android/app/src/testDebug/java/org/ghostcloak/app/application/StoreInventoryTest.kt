package org.ghostcloak.app.application

import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.junit.Assert.*
import org.junit.Test

class StoreInventoryTest {
    private val origin = "https://api.ghostcloak.org"
    private val prefix = "network/${DeviceAuth.digest("api.ghostcloak.org".toByteArray()).joinToString("") { "%02x".format(it) }}/"
    private class ReadOnlyRecords(val values: Map<String, ByteArray>) : EndpointRecords {
        val reads = mutableListOf<String>()
        override fun <T> transaction(block: () -> T) = block()
        override fun keys(prefix: String) = values.keys.filter { it.startsWith(prefix) }
        override fun read(key: String): ByteArray? { reads.add(key); return values[key]?.copyOf() }
        override fun write(key: String, value: ByteArray): Unit = error("Write forbidden")
        override fun remove(key: String): Unit = error("Delete forbidden")
    }
    private fun capture(block: () -> Unit): List<String> {
        val old = LocalStateDiagnostics.sink
        val result = mutableListOf<String>()
        LocalStateDiagnostics.sink = { result.add(it); Unit }
        try { block() } finally { LocalStateDiagnostics.sink = old }
        assertTrue(result.all { it.matches(Regex("[A-Z_]+=(true|false|[0-9]+|NOT_CHECKED)")) })
        return result
    }
    @Test fun emptyAndPartialInventoriesNeverCreateOrValidateIdentity() {
        for (keys in listOf(emptyList(), listOf("local/key", "app/contact/private-id", "app/message/private-id/private-message"))) {
            val records = ReadOnlyRecords(keys.associateWith { byteArrayOf(0) })
            val logs = capture { LocalStateDiagnostics.inventory(records, origin) { error("No alias exists") } }
            assertTrue(records.reads.isEmpty())
            assertTrue(logs.contains("LOCAL_DEVICE_PRESENT=false"))
            assertTrue(logs.contains("LOCAL_IDENTITY_KEY_PRESENT=${keys.isNotEmpty()}"))
            assertTrue(logs.contains("CONTACT_RECORD_COUNT=${if (keys.isEmpty()) 0 else 1}"))
            assertTrue(logs.contains("TOTAL_RECORD_COUNT=${keys.size}"))
            assertTrue(logs.contains("REFERENCED_AUTH_KEYSTORE_ENTRY_PRESENT=false"))
            assertFalse(logs.joinToString().contains("private-id"))
        }
    }
    @Test fun healthyCategoryFixtureOnlyReadsReferencedAliasAndPreservesEveryByte() {
        val keys = listOf("local/key", "local/device", "local/user", "local/username", "local/registration",
            "app/contact/a", "app/message/a/b", "trust-state/a", "outbox/a", "app/disappearing/a",
            "app/attachment/a/b", "attachment/transfer/a", "attachment/delete/b", "app/access-lock") +
            listOf("account", "routing", "registered", "token", "auth-alias", "auth-public").map { prefix + it }
        val values = keys.associateWith { "private-fixture-value".toByteArray() }
        val before = values.mapValues { it.value.copyOf() }
        val records = ReadOnlyRecords(values)
        var lookups = 0
        val logs = capture { LocalStateDiagnostics.inventory(records, origin) { ++lookups; true } }
        assertEquals(1, lookups)
        assertEquals(listOf(prefix + "auth-alias"), records.reads)
        before.forEach { (key, value) -> assertArrayEquals(value, values[key]) }
        assertTrue(logs.contains("ATTACHMENT_RECORD_COUNT=3"))
        assertTrue(logs.contains("NETWORK_REGISTERED_MARKER_PRESENT=true"))
        assertTrue(logs.contains("REFERENCED_AUTH_KEYSTORE_ENTRY_PRESENT=true"))
        assertFalse(logs.joinToString().contains("private-fixture-value"))
        assertFalse(logs.joinToString().contains(prefix))
    }
    @Test fun otherNetworkNamespaceDoesNotBecomeCurrentRegistration() {
        val records = ReadOnlyRecords(mapOf("network/other/account" to byteArrayOf(1), "local/device" to byteArrayOf(1)))
        val logs = capture { LocalStateDiagnostics.inventory(records, origin) { error("Forbidden") } }
        assertTrue(logs.contains("LOCAL_DEVICE_PRESENT=true"))
        assertTrue(logs.contains("NETWORK_ACCOUNT_PRESENT=false"))
        assertTrue(logs.contains("TOTAL_RECORD_COUNT=2"))
    }
    @Test fun failedKeystoreQueryIsNotReportedAsMissingOrCorruptDatabase() {
        val records = ReadOnlyRecords(mapOf(prefix + "auth-alias" to "private-alias".toByteArray()))
        val logs = capture { LocalStateDiagnostics.inventory(records, origin) { error("Private exception") } }
        assertEquals(listOf("DATABASE_INTEGRITY=NOT_CHECKED"), logs)
    }
    @Test fun missingReferencedKeyIsReportedWithoutChangingAlias() {
        val records = ReadOnlyRecords(mapOf(prefix + "auth-alias" to "private-alias".toByteArray()))
        val logs = capture { LocalStateDiagnostics.inventory(records, origin) { false } }
        assertTrue(logs.contains("NETWORK_AUTH_ALIAS_RECORD_PRESENT=true"))
        assertTrue(logs.contains("REFERENCED_AUTH_KEYSTORE_ENTRY_PRESENT=false"))
        assertEquals("private-alias", records.values.values.single().decodeToString())
    }
}
