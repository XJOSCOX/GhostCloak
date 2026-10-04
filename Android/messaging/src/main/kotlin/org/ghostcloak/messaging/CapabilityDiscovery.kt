package org.ghostcloak.messaging

import org.ghostcloak.crypto.*
import org.ghostcloak.protocol.*
import org.ghostcloak.capabilities.CapabilitySignatures
import org.ghostcloak.transport.HttpGhostClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Authenticated opt-in extension. Uses the existing engine, credential and unused prekeys. */
class CapabilityDiscovery(private val engine: SecureSessionEngine, private val state: EndpointNetworkState,
    private val audience: String, private val client: HttpGhostClient,
    private val eligible: () -> Boolean = {true}, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex=Mutex()
    private var nextPublication=0L
    suspend fun publish()=mutex.withLock {
        if(!eligible() || clock()<nextPublication) return@withLock
        nextPublication=clock()+300000L // Failed/unsupported probes are best-effort; never every poll.
        try {
            val response=client.call(ApiRequest.Capabilities(),reportTransientFailure=false)
            val time=response.serverTime ?: throw ApiFailure(502,"invalid_response")
            val account=state.accountId(); val routing=state.ownRouting(); val device=state.ownDevice()
            requireApi(response.capabilityInventory.size<=32,"invalid_response",502)
            val needed=response.capabilityInventory.filter { b ->
                requireApi(b.deviceId==device,"capability_binding")
                !CapabilitySignatures.verify(audience,account,routing,b,maxOf(time,clock())) ||
                    b.capability!!.expiresAt-maxOf(time,clock())<AttachmentCapabilities.LIFETIME/2
            }
            for(batch in needed.chunked(NetworkLimits.BUNDLES)) {
                if(!eligible()) return@withLock
                val signed=batch.map {it.withCapability(engine.signAttachmentCapability(audience,account,routing,it,time))}
                client.call(ApiRequest.Capabilities(signed),reportTransientFailure=false)
            }
        } catch(e:CancellationException) {throw e}
        catch(_:ApiFailure) { /* Consumed-key race or offline: retry later; text stays usable. */ }
    }
    suspend fun discover(ghostCloakId:String):DirectorySummary {
        val normalized=GhostCloakIds.normalize(ghostCloakId)
        val summary=client.call(ApiRequest.Lookup(normalized),reportTransientFailure=false).discovery
            ?: throw ApiFailure(502,"incompatible_server")
        requireApi(summary.ghostCloakId==normalized,"directory_mismatch")
        return summary
    }
    suspend fun lookup(ghostCloakId:String,allocationId:String): Pair<DirectoryEntry,Long?> {
        val summary=discover(ghostCloakId)
        val (entry,time)=allocate(summary.ghostCloakId,allocationId)
        requireApi(entry.accountId==summary.accountId && entry.deviceId==summary.deviceId &&
            entry.routingId==summary.routingId && entry.ghostCloakId==summary.ghostCloakId,"directory_mismatch")
        return entry to time
    }
    suspend fun allocate(ghostCloakId:String,allocationId:String):Pair<DirectoryEntry,Long?> {
        val normalized=GhostCloakIds.normalize(ghostCloakId)
        val response=client.call(ApiRequest.Allocate(normalized,allocationId),reportTransientFailure=false)
        val entry=response.directory ?: throw ApiFailure(502,"incompatible_server")
        requireApi(entry.ghostCloakId==normalized && entry.bundle.deviceId==entry.deviceId,"directory_mismatch")
        return entry to response.serverTime
    }
    suspend fun accept(entry:DirectoryEntry,time:Long?,service:ConversationService) {
        requireApi(entry.deviceId==entry.bundle.deviceId,"capability_binding")
        state.remember(entry) // Reject a changed routing binding before accepting proof.
        val proof=entry.bundle.capability
        if(proof==null) {service.directoryCapability(entry,0,false);return}
        val now=maxOf(time ?: throw ApiFailure(502,"invalid_response"),clock())
        val valid=engine.verifyAttachmentCapability(audience,entry,now)
        service.directoryCapability(entry,now,valid)
        requireApi(valid,"invalid_capability")
    }
    suspend fun refresh(id:String,service:ConversationService):Boolean {
        if(service.attachmentPeer(id)) return true
        requireApi(eligible(),"connect_required",401)
        val response=try {client.call(ApiRequest.CapabilityLookup(id))} catch(e:ApiFailure) {
            if(e.status in setOf(400,404)) return service.attachmentPeer(id)
            throw e
        }
        val entry=response.directory ?: throw ApiFailure(502,"invalid_response")
        requireApi(entry.deviceId==id && entry.bundle.deviceId==id,"capability_binding")
        accept(entry,response.serverTime,service)
        return service.attachmentPeer(id)
    }
}
