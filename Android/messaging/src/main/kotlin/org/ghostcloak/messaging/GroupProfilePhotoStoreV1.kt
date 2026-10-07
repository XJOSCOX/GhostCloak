package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

@Serializable internal data class GroupProfileStoredPhotoV1(
    val version:Int=1,val activationDigest:ByteArray,val digest:ByteArray,val bytes:ByteArray,
    val pending:List<GroupProfilePhotoCompanionV1> = emptyList(),
) { override fun toString()="GroupProfileStoredPhotoV1(redacted)" }
@Serializable internal data class GroupProfileStagedPhotoV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,
    val parentHeadDigest:ByteArray,val profileDigest:ByteArray,
    val photoDigest:ByteArray,val bytes:ByteArray,
) { override fun toString()="GroupProfileStagedPhotoV1(redacted)" }

/** EndpointRecords encrypts this bounded record; no file or server blob is created. */
internal class GroupProfilePhotoStoreV1(private val records:EndpointRecords) {
    private fun key(id:String):String {
        require(GroupIds.valid(id));return "app/group-profile/photo-v1/$id"
    }
    private fun stageKey(id:String):String {
        require(GroupIds.valid(id));return "app/group-profile/stage-v1/$id"
    }
    fun staged(id:String):GroupProfileStagedPhotoV1?=records.transaction {
        records.read(stageKey(id))?.let {NetworkCodec.decode<GroupProfileStagedPhotoV1>(it,9000)}
    }
    fun stage(value:GroupProfileStagedPhotoV1)=records.transaction {
        require(value.version==1 && GroupIds.valid(value.groupId) &&
            value.activationDigest.size==32 && value.parentHeadDigest.size==32 &&
            value.profileDigest.size==32 && value.photoDigest.size==32 &&
            value.bytes.size in 128..ProfileRules.MAX_PHOTO_BYTES &&
            MessageDigest.isEqual(DeviceAuth.digest(value.bytes),value.photoDigest))
        ProfileRules.photo(value.bytes)
        records.write(stageKey(value.groupId),NetworkCodec.encode(value))
    }
    fun clearStage(id:String)=records.transaction {records.remove(stageKey(id))}
    private fun load(id:String):GroupProfileStoredPhotoV1?=records.read(key(id))?.let {
        NetworkCodec.decode<GroupProfileStoredPhotoV1>(it,40_000)
    }
    private fun save(id:String,value:GroupProfileStoredPhotoV1?) {
        if(value==null) {records.remove(key(id));return}
        require(value.version==1 && value.activationDigest.size==32 &&
            value.pending.size<=3 && value.pending.all(GroupProfileRulesV1::validCompanion) &&
            value.bytes.size<=ProfileRules.MAX_PHOTO_BYTES &&
            (value.bytes.isEmpty() ||
                MessageDigest.isEqual(DeviceAuth.digest(value.bytes),value.digest)))
        records.write(key(id),NetworkCodec.encode(value))
    }
    fun verified(id:String,activationDigest:ByteArray,photo:GroupProfilePhotoRefV1?):ByteArray?=
        records.transaction {
            if(photo==null) return@transaction null
            val current=load(id) ?: return@transaction null
            current.bytes.takeIf {it.size==photo.length &&
                MessageDigest.isEqual(current.activationDigest,activationDigest) &&
                MessageDigest.isEqual(current.digest,photo.digest) &&
                MessageDigest.isEqual(DeviceAuth.digest(it),photo.digest)}?.copyOf()
        }
    /** A future companion remains hidden until its exact signed commitment is installed. */
    fun hold(companion:GroupProfilePhotoCompanionV1) = records.transaction {
        require(GroupProfileRulesV1.validCompanion(companion))
        val old=load(companion.groupId)
        if(old!=null && !MessageDigest.isEqual(old.activationDigest,companion.activationDigest))
            throw IllegalArgumentException("group_photo_activation_mismatch")
        val pending=old?.pending.orEmpty()
        if(pending.any {it.governanceSequence==companion.governanceSequence &&
            MessageDigest.isEqual(it.governanceHeadDigest,companion.governanceHeadDigest) &&
            MessageDigest.isEqual(it.photoDigest,companion.photoDigest)}) return@transaction
        require(pending.size<3)
        save(companion.groupId,(old ?: GroupProfileStoredPhotoV1(
            activationDigest=companion.activationDigest,digest=byteArrayOf(),bytes=byteArrayOf()))
            .copy(pending=pending+companion))
    }
    fun acceptCurrent(id:String,activationDigest:ByteArray,sequence:Long,headDigest:ByteArray,
        profile:GroupProfileV1,profileDigest:ByteArray) = records.transaction {
        val old=load(id) ?: return@transaction
        if(!MessageDigest.isEqual(old.activationDigest,activationDigest)) {
            save(id,null);return@transaction
        }
        val future=old.pending.filter {it.governanceSequence>sequence}
        val ref=profile.photo
        if(ref==null) {
            save(id,if(future.isEmpty()) null else old.copy(digest=byteArrayOf(),
                bytes=byteArrayOf(),pending=future))
            return@transaction
        }
        val keep=old.bytes.size==ref.length && MessageDigest.isEqual(old.digest,ref.digest)
        val exact=old.pending.firstOrNull {it.governanceSequence==sequence &&
            MessageDigest.isEqual(it.governanceHeadDigest,headDigest) &&
            MessageDigest.isEqual(it.profileDigest,profileDigest) &&
            MessageDigest.isEqual(it.photoDigest,ref.digest) && it.photoLength==ref.length}
        val bytes=if(keep) old.bytes else exact?.photoBytes ?: byteArrayOf()
        save(id,old.copy(digest=if(bytes.isEmpty()) byteArrayOf() else ref.digest,
            bytes=bytes,pending=future))
    }
    fun accept(companion:GroupProfilePhotoCompanionV1,profile:GroupProfileV1,
        currentSequence:Long,currentHeadDigest:ByteArray,currentProfileDigest:ByteArray) =
        records.transaction {
            require(GroupProfileRulesV1.validCompanion(companion))
            val ref=profile.photo ?: return@transaction false
            if(companion.governanceSequence!=currentSequence ||
                !MessageDigest.isEqual(companion.governanceHeadDigest,currentHeadDigest) ||
                !MessageDigest.isEqual(companion.profileDigest,currentProfileDigest) ||
                !MessageDigest.isEqual(companion.photoDigest,ref.digest) ||
                companion.photoLength!=ref.length) return@transaction false
            val old=load(companion.groupId)
            if(old!=null && !MessageDigest.isEqual(old.activationDigest,companion.activationDigest))
                return@transaction false
            save(companion.groupId,GroupProfileStoredPhotoV1(
                activationDigest=companion.activationDigest,digest=ref.digest,
                bytes=companion.photoBytes.copyOf(),
                pending=old?.pending.orEmpty().filter {it.governanceSequence>currentSequence}))
            true
        }
    fun clear(id:String)=records.transaction {save(id,null)}
}
