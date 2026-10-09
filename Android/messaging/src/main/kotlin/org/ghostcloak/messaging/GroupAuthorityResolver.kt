package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.SecureSessionEngine
import org.ghostcloak.identity.SessionLifecycle
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.ApiFailure
import org.ghostcloak.protocol.ApiRequest
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.DeviceBinding
import org.ghostcloak.protocol.NetworkCodec
import org.ghostcloak.transport.HttpGhostClient
import java.security.MessageDigest

@Serializable internal data class GroupAuthorityPin(val accountId:String,val identityDigest:ByteArray,
    val authPublicKey:ByteArray)

/** Resolve a registered group signer only through the active accepted Signal contact. */
class GroupAuthorityResolver(private val repository:LocalRepository,private val engine:SecureSessionEngine,
    private val client:HttpGhostClient,private val records:EndpointRecords) {
    private fun key(deviceId:String):String {
        require(RandomIdentifiers.valid(deviceId));return "app/group-authority/$deviceId"
    }
    suspend fun resolveTrustedGroupAuthority(contactId:String):DeviceBinding {
        if(!repository.isActiveContact(contactId)) throw ApiFailure(403,"group_binding_unavailable")
        val contact=repository.contact(contactId)
        val digest=engine.trustedRemoteIdentityDigest(contactId)
            ?: throw ApiFailure(409,"group_binding_unavailable")
        val response=client.call(ApiRequest.CapabilityLookup(contactId,expectedAccountId=contact.publicUserId,
            expectedIdentityDigest=digest),reportTransientFailure=false)
        val binding=response.deviceBinding ?: throw ApiFailure(502,"group_binding_unavailable")
        if(binding.version!=1 || binding.accountId!=contact.publicUserId || binding.deviceId!=contactId ||
            binding.identityDigest.size!=32 || !MessageDigest.isEqual(binding.identityDigest,digest) ||
            binding.authPublicKey.size !in 80..128)
            throw ApiFailure(409,"group_binding_unavailable")
        try { DeviceAuth.publicKey(binding.authPublicKey) }
        catch (_:ApiFailure) {throw ApiFailure(409,"group_binding_unavailable")}
        // Recheck after the network round trip; a changed pin or relationship must fail closed.
        if(!repository.isActiveContact(contactId) || repository.contact(contactId).publicUserId!=contact.publicUserId ||
            !MessageDigest.isEqual(engine.trustedRemoteIdentityDigest(contactId)
                ?: throw ApiFailure(409,"group_binding_unavailable"),digest))
            throw ApiFailure(409,"group_binding_unavailable")
        records.transaction {
            val prior=records.read(key(contactId))?.let { NetworkCodec.decode<GroupAuthorityPin>(it,512) }
            // An auth-key change under the same Signal identity is never silently accepted.
            if(prior!=null && MessageDigest.isEqual(prior.identityDigest,digest) &&
                (!MessageDigest.isEqual(prior.authPublicKey,binding.authPublicKey) ||
                    prior.accountId!=binding.accountId)) throw ApiFailure(409,"group_binding_changed")
            records.write(key(contactId),NetworkCodec.encode(GroupAuthorityPin(binding.accountId,digest,binding.authPublicKey)))
        }
        val currentDigest=engine.trustedRemoteIdentityDigest(contactId)
        if(!repository.isActiveContact(contactId) ||
            repository.contact(contactId).publicUserId!=contact.publicUserId || currentDigest==null ||
            !MessageDigest.isEqual(currentDigest,digest)) {
            records.transaction {records.remove(key(contactId))}
            throw ApiFailure(409,"group_binding_unavailable")
        }
        return binding
    }
    suspend fun matchesTrustedMember(member:GroupMember):Boolean {
        val binding=try { resolveTrustedGroupAuthority(member.deviceId) }
            catch (e:kotlinx.coroutines.CancellationException) {throw e}
            catch (_:Exception) {return false}
        return binding.accountId==member.accountId && binding.deviceId==member.deviceId &&
            MessageDigest.isEqual(binding.authPublicKey,member.authPublicKey) &&
            MessageDigest.isEqual(binding.identityDigest,member.signalIdentityDigest)
    }
    /** Admission-scoped check: no direct or global group-authority record is written. */
    suspend fun verifyAdmissionCandidate(candidate:GroupMember):Boolean {
        if(!repository.isActiveContact(candidate.deviceId) ||
            repository.contact(candidate.deviceId).publicUserId!=candidate.accountId) return false
        val pinned=engine.trustedRemoteIdentityDigest(candidate.deviceId) ?: return false
        if(!MessageDigest.isEqual(pinned,candidate.signalIdentityDigest)) return false
        val response=client.call(ApiRequest.CapabilityLookup(candidate.deviceId,
            expectedAccountId=candidate.accountId,expectedIdentityDigest=pinned),
            reportTransientFailure=false)
        val binding=response.deviceBinding ?: return false
        val after=engine.trustedRemoteIdentityDigest(candidate.deviceId) ?: return false
        return repository.isActiveContact(candidate.deviceId) &&
            repository.contact(candidate.deviceId).publicUserId==candidate.accountId &&
            MessageDigest.isEqual(after,pinned) && binding.version==1 &&
            binding.accountId==candidate.accountId && binding.deviceId==candidate.deviceId &&
            MessageDigest.isEqual(binding.identityDigest,candidate.signalIdentityDigest) &&
            MessageDigest.isEqual(binding.authPublicKey,candidate.authPublicKey)
    }
    /** Verify a noncontact candidate only for the exact owner-signed admission parent. */
    suspend fun verifyOwnerIntroducedCandidate(introduction:GroupOwnerIntroductionV1,
        parent:GroupState,network:EndpointNetworkState):Boolean {
        if(!GroupOwnerIntroductionRulesV1.verify(introduction,parent)) return false
        val candidate=introduction.candidate
        if(repository.relationshipState(candidate.deviceId)==RelationshipState.BLOCKED) return false
        val owner=parent.members.single {it.memberId==parent.ownerId}
        val ownerTrusted=if(owner.deviceId==network.ownDevice())
            owner.accountId==network.accountId() &&
                MessageDigest.isEqual(owner.authPublicKey,network.registeredGroupPublicKey()) &&
                MessageDigest.isEqual(owner.signalIdentityDigest,
                    DeviceAuth.digest(engine.createIdentity("Local").publicKey))
        else matchesTrustedMember(owner)
        if(!ownerTrusted) return false
        val pinned=engine.trustedRemoteIdentityDigest(candidate.deviceId) ?: return false
        if(!MessageDigest.isEqual(pinned,candidate.signalIdentityDigest)) return false
        val response=client.call(ApiRequest.CapabilityLookup(candidate.deviceId,
            expectedAccountId=candidate.accountId,expectedIdentityDigest=pinned),
            reportTransientFailure=false)
        val binding=response.deviceBinding ?: return false
        val after=engine.trustedRemoteIdentityDigest(candidate.deviceId) ?: return false
        return repository.relationshipState(candidate.deviceId)!=RelationshipState.BLOCKED &&
            MessageDigest.isEqual(after,pinned) && binding.version==1 &&
            binding.accountId==candidate.accountId && binding.deviceId==candidate.deviceId &&
            MessageDigest.isEqual(binding.identityDigest,candidate.signalIdentityDigest) &&
            MessageDigest.isEqual(binding.authPublicKey,candidate.authPublicKey)
    }
    /** Pre-join Signal setup from owner-signed routes; no Contact or direct profile is written. */
    suspend fun connectIntroducedRoster(introduction:GroupOwnerIntroductionV1,parent:GroupState,
        network:EndpointNetworkState):Boolean {
        if(!GroupOwnerIntroductionRulesV1.verify(introduction,parent) ||
            introduction.candidate.deviceId!=network.ownDevice() ||
            introduction.candidate.accountId!=network.accountId() ||
            !MessageDigest.isEqual(introduction.candidate.authPublicKey,
                network.registeredGroupPublicKey()) ||
            !MessageDigest.isEqual(introduction.candidate.signalIdentityDigest,
                DeviceAuth.digest(engine.createIdentity("Local").publicKey))) return false
        val owner=parent.members.single {it.memberId==parent.ownerId}
        if(!matchesTrustedMember(owner)) return false
        for(member in parent.members) {
            if(repository.relationshipState(member.deviceId)==RelationshipState.BLOCKED) return false
            val existing=engine.trustedRemoteIdentityDigest(member.deviceId)
            if(existing!=null && !MessageDigest.isEqual(existing,member.signalIdentityDigest)) return false
            val route=introduction.routes.singleOrNull {it.memberId==member.memberId} ?: return false
            val binding=client.call(ApiRequest.CapabilityLookup(member.deviceId,
                expectedAccountId=member.accountId,expectedIdentityDigest=member.signalIdentityDigest),
                reportTransientFailure=false).deviceBinding ?: return false
            if(binding.version!=1 || binding.accountId!=member.accountId ||
                binding.deviceId!=member.deviceId ||
                !MessageDigest.isEqual(binding.identityDigest,member.signalIdentityDigest) ||
                !MessageDigest.isEqual(binding.authPublicKey,member.authPublicKey)) return false
            if(existing!=null && network.route(member.deviceId)!=null &&
                engine.getSessionLifecycle(member.deviceId)==SessionLifecycle.ACTIVE) continue
            val entry=client.lookup(route.ghostCloakId,
                network.allocationIdFor(route.ghostCloakId))
            if(entry.ghostCloakId!=route.ghostCloakId || entry.deviceId!=member.deviceId ||
                entry.accountId!=member.accountId || entry.bundle.deviceId!=member.deviceId ||
                !MessageDigest.isEqual(DeviceAuth.digest(entry.bundle.identity),
                    member.signalIdentityDigest)) return false
            entry.bundle.validate()
            if(existing==null) engine.establishSession(entry.bundle.remote())
            else {
                engine.validateRetainedIdentity(entry.bundle.remote())
                if(engine.getSessionLifecycle(member.deviceId)!=SessionLifecycle.ACTIVE)
                    return false
            }
            if(!MessageDigest.isEqual(engine.trustedRemoteIdentityDigest(member.deviceId)
                    ?: return false,member.signalIdentityDigest)) return false
            network.remember(entry)
            network.completeAllocation(route.ghostCloakId)
        }
        return true
    }
    suspend fun matchesIntroducedRosterMember(introduction:GroupOwnerIntroductionV1,
        parent:GroupState,member:GroupMember,network:EndpointNetworkState):Boolean {
        if(!GroupOwnerIntroductionRulesV1.verify(introduction,parent) ||
            introduction.candidate.deviceId!=network.ownDevice() ||
            parent.members.none {it.memberId==member.memberId &&
                it.deviceId==member.deviceId} ||
            repository.relationshipState(member.deviceId)==RelationshipState.BLOCKED) return false
        val pinned=engine.trustedRemoteIdentityDigest(member.deviceId) ?: return false
        if(!MessageDigest.isEqual(pinned,member.signalIdentityDigest)) return false
        val binding=client.call(ApiRequest.CapabilityLookup(member.deviceId,
            expectedAccountId=member.accountId,expectedIdentityDigest=pinned),
            reportTransientFailure=false).deviceBinding ?: return false
        val after=engine.trustedRemoteIdentityDigest(member.deviceId) ?: return false
        return MessageDigest.isEqual(after,pinned) && binding.version==1 &&
            binding.deviceId==member.deviceId && binding.accountId==member.accountId &&
            MessageDigest.isEqual(binding.identityDigest,member.signalIdentityDigest) &&
            MessageDigest.isEqual(binding.authPublicKey,member.authPublicKey)
    }
    /** Group-only current binding check. Never records or restores direct-contact acceptance. */
    suspend fun matchesCurrentGroupMember(groupId:String,member:GroupMember):Boolean {
        val digest=engine.trustedRemoteIdentityDigest(member.deviceId) ?: return false
        val scoped=GroupCurrentAuthority(records).current(groupId,member.deviceId,digest) ?: return false
        if(scoped.memberId!=member.memberId || scoped.accountId!=member.accountId ||
            !MessageDigest.isEqual(scoped.authPublicKey,member.authPublicKey) ||
            !MessageDigest.isEqual(scoped.signalIdentityDigest,member.signalIdentityDigest)) return false
        val response=client.call(ApiRequest.CapabilityLookup(member.deviceId,
            expectedAccountId=member.accountId,expectedIdentityDigest=digest),reportTransientFailure=false)
        val binding=response.deviceBinding ?: return false
        val after=engine.trustedRemoteIdentityDigest(member.deviceId) ?: return false
        return MessageDigest.isEqual(after,digest) &&
            GroupCurrentAuthority(records).current(groupId,member.deviceId,after)!=null &&
            binding.version==1 && binding.accountId==member.accountId && binding.deviceId==member.deviceId &&
            MessageDigest.isEqual(binding.identityDigest,member.signalIdentityDigest) &&
            MessageDigest.isEqual(binding.authPublicKey,member.authPublicKey)
    }
}
