package org.ghostcloak.testing

import org.ghostcloak.crypto.EndpointRecords

/** Synthetic JVM fixtures only; never packaged in the Android application. */
class MemoryRecords : EndpointRecords {
    private var data = mutableMapOf<String, ByteArray>()
    private var transactionDepth = 0
    var failCommit = false
    var storageFailureAtCommit = false
    var failWritePrefix: String? = null
    var inspectCommitBuffer = false
    var commitBuffer: ByteArray? = null
    @Synchronized override fun <T> transaction(block: () -> T): T {
        if (transactionDepth > 0) {
            transactionDepth++
            try { return block() } finally { transactionDepth-- }
        }
        val previous = data
        data = previous.mapValues { it.value.copyOf() }.toMutableMap()
        transactionDepth = 1
        try {
            val result = block()
            if (inspectCommitBuffer && result is ByteArray) commitBuffer = result
            if (storageFailureAtCommit) throw org.ghostcloak.crypto.EndpointStorageFailure()
            if (failCommit) error("Injected commit failure")
            previous.values.forEach { it.fill(0) }
            return result
        } catch (e: Throwable) {
            data.values.forEach { it.fill(0) }
            data = previous
            throw e
        } finally { transactionDepth = 0 }
    }
    override fun read(key: String) = data[key]?.copyOf()
    override fun write(key: String, value: ByteArray) {
        if(failWritePrefix?.let(key::startsWith)==true)
            throw org.ghostcloak.crypto.EndpointStorageFailure()
        data.put(key, value.copyOf())?.fill(0)
    }
    override fun remove(key: String) { data.remove(key)?.fill(0) }
    override fun keys(prefix: String) = data.keys.filter { it.startsWith(prefix) }
}
