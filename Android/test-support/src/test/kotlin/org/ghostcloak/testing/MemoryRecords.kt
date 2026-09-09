package org.ghostcloak.testing

import org.ghostcloak.crypto.EndpointRecords

/** Synthetic JVM fixtures only; never packaged in the Android application. */
class MemoryRecords : EndpointRecords {
    private var data = mutableMapOf<String, ByteArray>()
    var failCommit = false
    @Synchronized override fun <T> transaction(block: () -> T): T {
        val previous = data
        data = previous.mapValues { it.value.copyOf() }.toMutableMap()
        try {
            val result = block()
            if (failCommit) error("Injected commit failure")
            previous.values.forEach { it.fill(0) }
            return result
        } catch (e: Throwable) {
            data.values.forEach { it.fill(0) }
            data = previous
            throw e
        }
    }
    override fun read(key: String) = data[key]?.copyOf()
    override fun write(key: String, value: ByteArray) { data.put(key, value.copyOf())?.fill(0) }
    override fun remove(key: String) { data.remove(key)?.fill(0) }
    override fun keys(prefix: String) = data.keys.filter { it.startsWith(prefix) }
}
