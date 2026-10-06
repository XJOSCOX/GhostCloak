package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.SecureSessionEngine
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.ApiFailure
import org.ghostcloak.protocol.ApiRequest
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.DeviceBinding
import org.ghostcloak.protocol.NetworkCodec
import org.ghostcloak.transport.HttpGhostClient
import java.security.MessageDigest

/** An internal P13.2 prerequisite; this does not send or accept group controls. */
class GroupAuthorityResolver(private val repository:LocalRepository,private val engine:SecureSessionEngine,
    private val client:HttpGhostClient,private val records:EndpointRecords) {
    @Serializable private data class Pin(val accountId:String,val identityDigest:ByteArray,val authPublicKey:ByteArray)
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
            val prior=records.read(key(contactId))?.let { NetworkCodec.decode<Pin>(it,512) }
            // An auth-key change under the same Signal identity is never silently accepted.
            if(prior!=null && MessageDigest.isEqual(prior.identityDigest,digest) &&
                (!MessageDigest.isEqual(prior.authPublicKey,binding.authPublicKey) ||
                    prior.accountId!=binding.accountId)) throw ApiFailure(409,"group_binding_changed")
            records.write(key(contactId),NetworkCodec.encode(Pin(binding.accountId,digest,binding.authPublicKey)))
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
}
