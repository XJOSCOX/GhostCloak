package org.ghostcloak.messaging

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ghostcloak.crypto.*
import org.ghostcloak.protocol.*

/** One owner per endpoint. Pending public batches and cooldown survive process death. */
class PrekeyRefill(private val records: EndpointRecords, private val engine: SecureSessionEngine,
    namespace: String, private val device: () -> String,
    private val call: suspend (ApiRequest.Prekeys) -> ApiResponse,
    private val eligible: () -> Boolean = { true },
    private val time: () -> Long = System::currentTimeMillis,
    private val diagnostic: (String) -> Unit = {}) {
    private val mutex = Mutex()
    private val prefix = "app/prekey-refill/${DeviceAuth.digest(namespace.toByteArray()).joinToString("") { "%02x".format(it) }}/"
    private fun emit(value: String) { try { diagnostic(value) } catch (_: Exception) { } }
    private fun deadline(delay: Long) = records.transaction {
        val now = time().coerceAtLeast(0)
        records.write(prefix+"next", (now+delay.coerceAtMost(Long.MAX_VALUE-now)).toString().toByteArray())
    }
    private fun pending() = records.transaction { records.read(prefix+"pending")?.let {
        val saved = NetworkCodec.decode<ApiRequest.Prekeys>(it)
        requireApi(saved.deviceId == device() && !saved.inspect && saved.probeIds.isEmpty() &&
            saved.bundles.size in 1..16 && saved.bundles.map { b -> b.preKeyId }.distinct().size == saved.bundles.size,
            "prekey_conflict", 409)
        saved.bundles.forEach { b -> b.validate(); requireApi(b.deviceId == device(), "prekey_conflict", 409) }
        saved.bundles
    } ?: emptyList() }
    private fun save(bundles: List<PublicBundle>) = records.transaction {
        records.write(prefix+"pending", NetworkCodec.encode(ApiRequest.Prekeys(device(), bundles)))
    }
    suspend fun maintain() = mutex.withLock {
        if (!eligible()) return@withLock
        val next = records.transaction { records.read(prefix+"next")?.decodeToString()?.toLongOrNull() ?: 0 }
        if (next > time()) return@withLock
        // Also bounds cancellation/process-death retries before any network call.
        deadline(300_000)
        try {
            var batch = pending()
            val inventory = call(ApiRequest.Prekeys(device(), emptyList(), inspect=true, probeIds=batch.map { it.preKeyId })).prekeyInventory
                ?: throw ApiFailure(502, "invalid_response")
            requireApi(inventory.available in 0..32 && inventory.acceptedIds.distinct().size == inventory.acceptedIds.size &&
                inventory.acceptedIds.all { id -> batch.any { it.preKeyId == id } }, "invalid_response", 502)
            emit("PREKEY_POOL_STATE=${if(inventory.available==0) "empty" else if(inventory.available<4) "low" else "healthy"}")
            if (batch.isNotEmpty() && inventory.acceptedIds.size == batch.size) {
                // Atomic server upload succeeded even if its response was lost (possibly already consumed).
                records.transaction { records.remove(prefix+"pending") }; batch=emptyList()
                emit("PREKEY_REFILL_OK")
            } else requireApi(inventory.acceptedIds.isEmpty(), "prekey_conflict", 409)
            if (batch.isEmpty() && inventory.available >= 4) { deadline(60_000); return@withLock }
            emit("PREKEY_REFILL_START")
            if (batch.isEmpty()) {
                repeat((16-inventory.available).coerceIn(1,16)) {
                    requireApi(eligible(), "connect_required", 401)
                    batch = batch + engine.preKeys.createPublicationBundle().publicData()
                    save(batch) // A crash may waste an unrecorded key, but cannot reuse or publish it twice.
                }
            }
            requireApi(eligible(), "connect_required", 401)
            call(ApiRequest.Prekeys(device(), batch))
            records.transaction { records.remove(prefix+"pending") }
            deadline(60_000); emit("PREKEY_REFILL_OK")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val retry = (e as? ApiFailure)?.retryAfterMillis ?: 300_000L
            deadline(retry.coerceAtLeast(300_000L))
            emit("PREKEY_POOL_STATE=unknown")
            emit("PREKEY_REFILL_FAILED=${when((e as? ApiFailure)?.status) {401->"auth";429->"rate_limit";409->"conflict";400->"unsupported";else->"retryable"}}")
        }
    }
}
