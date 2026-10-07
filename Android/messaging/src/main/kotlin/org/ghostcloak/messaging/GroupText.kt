package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.crypto.EndpointStorageFailure
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.NetworkCodec
import org.ghostcloak.attachments.AttachmentFormat
import org.ghostcloak.attachments.AttachmentKind
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.EnvelopeCodec
import java.security.MessageDigest

/** This content is framed inside a pairwise authenticated Signal message, never sent as routing metadata. */
@Serializable data class GroupText(val version:Int=1,val groupId:String,val epoch:Long,
    val senderMemberId:String,val logicalId:String,val text:String) {
    override fun toString()="GroupText(redacted)"
}

object GroupTextCodec {
    const val MAX_TEXT_BYTES=2048
    const val MAX_BYTES=3072
    fun validate(value:GroupText) {
        require(value.version==1 && GroupIds.valid(value.groupId) && value.epoch>0 &&
            GroupIds.valid(value.senderMemberId) && GroupIds.valid(value.logicalId))
        val bytes=TextRules.encode(value.text)
        try {require(bytes.size in 1..MAX_TEXT_BYTES)} finally {bytes.fill(0)}
    }
    fun encode(value:GroupText):ByteArray {
        validate(value)
        return NetworkCodec.encode(value).also {require(it.size in 1..MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):GroupText {
        require(bytes.size in 1..MAX_BYTES)
        return NetworkCodec.decode<GroupText>(bytes,MAX_BYTES).also(::validate)
    }
}

enum class GroupRecipientState { PENDING, QUEUED, SENT, UNAVAILABLE }
@Serializable data class GroupRecipient(val deviceId:String,val state:GroupRecipientState=GroupRecipientState.PENDING,
    val outboxId:String?=null)
@Serializable enum class GroupModerationState { NONE, REMOVED_BY_ADMIN, DELETED_BY_SENDER }
data class GroupReactionBadge(val emoji:String,val count:Int,val mine:Boolean)
@Serializable internal data class GroupReactionRecord(val sequence:Long,val emoji:String?)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class GroupChatMessage(val groupId:String,val logicalId:String,val epoch:Long,
    val senderMemberId:String,val outgoing:Boolean,val text:String,val localOrder:Long,
    val recipients:List<GroupRecipient> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val moderationState:GroupModerationState=GroupModerationState.NONE,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val replyToLogicalId:String?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val editRevision:Long=0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mediaKind:AttachmentKind?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mediaCaption:String?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mediaFilename:String?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mediaBytes:Long=0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mediaDurationMillis:Long?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mediaSenderDeviceId:String?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mediaBodyDigest:ByteArray?=null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val mediaDeliveryPrepared:Boolean=false,
    @kotlinx.serialization.Transient val reactions:List<GroupReactionBadge> = emptyList()) {
    override fun toString()="GroupChatMessage(redacted)"
}
@Serializable data class PendingGroupText(val senderDeviceId:String,val text:GroupText) {
    override fun toString()="PendingGroupText(redacted)"
}

/** All rows live in the existing SQLCipher endpoint store and are destroyed with it. */
class GroupChatStore(private val records:EndpointRecords) {
    private fun reactionPrefix(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id))
        return "app/group-text/reaction/$groupId/$id/"
    }
    private fun senderDeleteKey(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id))
        return "app/group-text/sender-deleted/$groupId/$id"
    }
    private fun pendingSenderDeleteKey(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id))
        return "app/group-text/pending-sender-delete/$groupId/$id/"
    }
    private fun pendingSenderDeleteMatches(groupId:String,id:String,senderMemberId:String):Boolean {
        val keys=records.keys(pendingSenderDeleteKey(groupId,id))
        val matched=keys.any {it.substringAfterLast('/')==senderMemberId}
        keys.forEach(records::remove)
        return matched
    }
    internal fun senderDeleteFilter(groupId:String):ByteArray=records.transaction {
        require(GroupIds.valid(groupId))
        val bits=GroupSenderDeleteFilterV1.empty()
        val prefix="app/group-text/sender-deleted/$groupId/"
        val keys=records.keys(prefix)
        require(keys.size<=GroupSenderDeleteFilterV1.MAX_ENTRIES)
        keys.forEach {GroupSenderDeleteFilterV1.add(bits,groupId,it.removePrefix(prefix))}
        val inherited=GovernedAdmissionStore(records).joinV2(groupId)?.policyProof?.senderDeleteFilter
        if(inherited!=null) for(i in bits.indices)
            bits[i]=(bits[i].toInt() or inherited[i].toInt()).toByte()
        bits
    }
    internal fun isSenderDeleted(groupId:String,id:String):Boolean=records.transaction {
        records.read(senderDeleteKey(groupId,id))!=null ||
            GovernedAdmissionStore(records).joinV2(groupId)?.policyProof?.senderDeleteFilter?.let {
                GroupSenderDeleteFilterV1.contains(it,groupId,id)
            }==true
    }
    private fun clearReactions(groupId:String,id:String) {
        records.keys(reactionPrefix(groupId,id)).forEach(records::remove)
    }
    private fun clearPendingMedia(groupId:String,id:String) {
        for(path in records.keys("app/group-text/pending-media-v1/")) {
            val pending=NetworkCodec.decode<PendingGroupMediaV1>(
                records.read(path) ?: throw EndpointStorageFailure(),8192)
            if(pending.media.groupId==groupId && pending.media.logicalId==id)
                records.remove(path)
        }
    }
    private fun retirePendingRecipients(message:GroupChatMessage):GroupChatMessage {
        if(!message.outgoing) return message
        return message.copy(mediaDeliveryPrepared=false,recipients=message.recipients.map {recipient ->
            if(recipient.state !in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)) recipient
            else {
                val accepted=recipient.outboxId?.let {id ->
                    val outboxKey="outbox/$id"
                    val entry=records.read(outboxKey)?.let {NetworkCodec.decode<OutboxEntry>(it)}
                    if(entry?.state!=OutboxState.SERVER_ACCEPTED) records.remove(outboxKey)
                    records.remove("app/group-text/outbox/$id")
                    entry?.state==OutboxState.SERVER_ACCEPTED
                }==true
                recipient.copy(state=if(accepted) GroupRecipientState.SENT else
                    GroupRecipientState.UNAVAILABLE,outboxId=null)
            }
        })
    }
    internal fun deleteBySender(groupId:String,id:String,senderMemberId:String):Boolean=records.transaction {
        require(GroupIds.valid(senderMemberId))
        if(isModerated(groupId,id) || isSenderDeleted(groupId,id)) return@transaction false
        val key=messageKey(groupId,id)
        val old=records.read(key)?.let {NetworkCodec.decode<GroupChatMessage>(it,8192)}
        if(old!=null && (old.senderMemberId!=senderMemberId ||
                old.moderationState!=GroupModerationState.NONE)) return@transaction false
        if(old==null) {
            val candidate=pendingSenderDeleteKey(groupId,id)+senderMemberId
            if(records.read(candidate)!=null) return@transaction false
            require(records.keys("app/group-text/pending-sender-delete/").size<128)
            records.write(candidate,byteArrayOf(1))
            return@transaction true
        }
        val marker=senderDeleteKey(groupId,id)
        if(records.read(marker)!=null) return@transaction false
        require(records.keys("app/group-text/sender-deleted/").size<
            GroupSenderDeleteFilterV1.MAX_ENTRIES)
        records.write(marker,senderMemberId.encodeToByteArray())
        records.write(key,NetworkCodec.encode(retirePendingRecipients(old.copy(text="",
            mediaCaption=null,mediaFilename=null,moderationState=GroupModerationState.DELETED_BY_SENDER))))
        records.remove("app/attachment/$groupId/$id")
        records.remove("app/auto-download/$groupId/$id")
        clearPendingMedia(groupId,id)
        clearReactions(groupId,id)
        NotificationLedger.remove(records,groupId,id)
        true
    }
    internal fun edit(groupId:String,id:String,senderMemberId:String,revision:Long,
        replacement:String):Boolean=records.transaction {
        require(GroupIds.valid(senderMemberId) && revision in 1..GroupMessageControlCodecV1.MAX_REVISION)
        val encoded=TextRules.encode(replacement)
        try {require(encoded.size in 1..GroupTextCodec.MAX_TEXT_BYTES)} finally {encoded.fill(0)}
        val key=messageKey(groupId,id)
        val old=records.read(key)?.let {NetworkCodec.decode<GroupChatMessage>(it,8192)}
            ?: return@transaction false
        if(old.mediaKind!=null || old.senderMemberId!=senderMemberId || old.moderationState!=GroupModerationState.NONE ||
            revision<=old.editRevision) return@transaction false
        records.write(key,NetworkCodec.encode(old.copy(text=replacement,editRevision=revision)))
        NotificationLedger.remove(records,groupId,id)
        true
    }
    internal fun react(groupId:String,id:String,actorMemberId:String,sequence:Long,
        emoji:String?):Boolean=records.transaction {
        require(GroupIds.valid(actorMemberId) && sequence in 1..GroupMessageControlCodecV1.MAX_REVISION &&
            (emoji==null || emoji in ConversationPayload.reactionEmoji))
        val message=message(groupId,id) ?: return@transaction false
        if(message.moderationState!=GroupModerationState.NONE) return@transaction false
        val key=reactionPrefix(groupId,id)+actorMemberId
        val old=records.read(key)?.let {NetworkCodec.decode<GroupReactionRecord>(it,128)}
        if(sequence<=(old?.sequence ?: 0L)) return@transaction false
        require(old!=null || records.keys("app/group-text/reaction/").size<16384)
        records.write(key,NetworkCodec.encode(GroupReactionRecord(sequence,emoji)))
        true
    }
    internal fun nextReactionSequence(groupId:String,id:String,actorMemberId:String):Long=
        records.transaction {
            val key=reactionPrefix(groupId,id)+actorMemberId
            (records.read(key)?.let {NetworkCodec.decode<GroupReactionRecord>(it,128)}?.sequence
                ?: 0L)+1
        }
    internal fun reactionBadges(groupId:String,id:String,localMemberId:String?):List<GroupReactionBadge> =
        records.transaction {
            val recordsByActor=records.keys(reactionPrefix(groupId,id)).mapNotNull {key ->
                val value=records.read(key)?.let {NetworkCodec.decode<GroupReactionRecord>(it,128)}
                    ?: return@mapNotNull null
                value.emoji?.let {emoji -> key.substringAfterLast('/') to emoji}
            }
            recordsByActor.groupBy {it.second}.map {(emoji,actors) ->
                GroupReactionBadge(emoji,actors.size,actors.any {it.first==localMemberId})
            }.sortedBy {ConversationPayload.reactionEmoji.indexOf(it.emoji)}
        }
    private fun moderationKey(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id))
        return "app/group-text/moderated/$groupId/$id"
    }
    internal fun moderationFilter(groupId:String):ByteArray=records.transaction {
        require(GroupIds.valid(groupId))
        val bits=GroupModerationFilterV1.empty()
        val prefix="app/group-text/moderated/$groupId/"
        val keys=records.keys(prefix)
        require(keys.size<=GroupGovernanceJournalV1.MAX_ENTRIES)
        keys.forEach {key -> GroupModerationFilterV1.add(bits,groupId,key.removePrefix(prefix)) }
        // A join-point filter inherited from an earlier checkpoint also has to
        // accompany invitations issued by this newly joined member.
        val inherited=GovernedAdmissionStore(records).joinV2(groupId)?.policyProof?.moderationFilter
        if(inherited!=null) for(i in bits.indices)
            bits[i]=(bits[i].toInt() or inherited[i].toInt()).toByte()
        bits
    }
    /** The marker survives message arrival, restart, and replay. It carries no plaintext. */
    internal fun moderate(groupId:String,id:String):Boolean=records.transaction {
        val marker=moderationKey(groupId,id)
        if(records.read(marker)!=null) return@transaction false
        // One signed governance entry creates at most one marker; the journal itself stops at 511.
        require(records.keys("app/group-text/moderated/$groupId/").size<
            GroupGovernanceJournalV1.MAX_ENTRIES)
        val key=messageKey(groupId,id)
        val old=records.read(key)?.let {NetworkCodec.decode<GroupChatMessage>(it,8192)}
        if(old!=null) records.write(key,NetworkCodec.encode(retirePendingRecipients(old.copy(text="",
            mediaCaption=null,mediaFilename=null,moderationState=GroupModerationState.REMOVED_BY_ADMIN))))
        records.remove("app/attachment/$groupId/$id")
        records.remove("app/auto-download/$groupId/$id")
        clearPendingMedia(groupId,id)
        clearReactions(groupId,id)
        records.write(marker,byteArrayOf(1))
        records.keys(pendingSenderDeleteKey(groupId,id)).forEach(records::remove)
        NotificationLedger.remove(records,groupId,id)
        // Signal has already authenticated queued frames. Do not retain their plaintext
        // after the signed moderation entry commits, even if their old head cannot display.
        for(path in records.keys("app/group-text/pending-v2/")) {
            val pending=NetworkCodec.decode<PendingGroupTextV2>(
                records.read(path) ?: throw EndpointStorageFailure(),4096)
            if(pending.text.groupId==groupId && pending.text.logicalId==id)
                records.remove(path)
        }
        for(path in records.keys("app/group-text/pending-v3/")) {
            val pending=NetworkCodec.decode<PendingGroupTextV3>(
                records.read(path) ?: throw EndpointStorageFailure(),4096)
            if(pending.text.groupId==groupId && pending.text.logicalId==id)
                records.remove(path)
        }
        for(path in records.keys("app/group-text/pending/")) {
            val pending=NetworkCodec.decode<PendingGroupText>(
                records.read(path) ?: throw EndpointStorageFailure(),4096)
            if(pending.text.groupId==groupId && pending.text.logicalId==id)
                records.remove(path)
        }
        true
    }
    internal fun isModerated(groupId:String,id:String):Boolean=records.transaction {
        records.read(moderationKey(groupId,id))!=null ||
            GovernedAdmissionStore(records).joinV2(groupId)?.policyProof?.moderationFilter?.let {
                GroupModerationFilterV1.contains(it,groupId,id)
            }==true
    }
    private fun messageKey(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id));return "app/group-text/message/$groupId/$id"
    }
    private fun replayKey(groupId:String,sender:String,id:String):String {
        require(listOf(groupId,sender,id).all(GroupIds::valid))
        return "app/group-text/replay/$groupId/$sender/$id"
    }
    private fun pendingKey(envelopeId:String):String {
        require(RandomIdentifiers.valid(envelopeId));return "app/group-text/pending/$envelopeId"
    }
    private fun pendingV2Key(envelopeId:String):String {
        require(RandomIdentifiers.valid(envelopeId));return "app/group-text/pending-v2/$envelopeId"
    }
    private fun pendingV3Key(envelopeId:String):String {
        require(RandomIdentifiers.valid(envelopeId));return "app/group-text/pending-v3/$envelopeId"
    }
    private fun pendingMediaKey(envelopeId:String):String {
        require(RandomIdentifiers.valid(envelopeId));return "app/group-text/pending-media-v1/$envelopeId"
    }
    private fun bindingKey(groupId:String,id:String):String {
        require(GroupIds.valid(groupId) && GroupIds.valid(id))
        return "app/group-text/v2-binding/$groupId/$id"
    }
    private fun decodeMessage(key:String)=NetworkCodec.decode<GroupChatMessage>(
        records.read(key) ?: throw EndpointStorageFailure(),8192)
    fun groups():List<String> = records.transaction {
        records.keys("app/group/member/").map {it.removePrefix("app/group/member/")}.filter(GroupIds::valid).sorted()
    }
    fun messages(groupId:String,localMemberId:String?=null):List<GroupChatMessage> = records.transaction {
        require(GroupIds.valid(groupId))
        records.keys("app/group-text/message/$groupId/").map(::decodeMessage).map {message ->
            if(message.moderationState==GroupModerationState.NONE) message.copy(
                reactions=reactionBadges(groupId,message.logicalId,localMemberId))
            else message
        }
            .sortedWith(compareBy<GroupChatMessage> {it.localOrder}.thenBy {it.logicalId})
    }
    fun message(groupId:String,id:String):GroupChatMessage?=records.transaction {
        records.read(messageKey(groupId,id))?.let {NetworkCodec.decode<GroupChatMessage>(it,8192)}
    }
    fun create(value:GroupChatMessage)=records.transaction {
        require(value.outgoing && value.recipients.size in 1..4 && value.recipients.map {it.deviceId}.distinct().size==value.recipients.size)
        require(value.recipients.all {RandomIdentifiers.valid(it.deviceId) && it.state==GroupRecipientState.PENDING && it.outboxId==null})
        GroupTextCodec.validate(GroupText(groupId=value.groupId,epoch=value.epoch,
            senderMemberId=value.senderMemberId,logicalId=value.logicalId,text=value.text))
        require(records.keys("app/group-text/message/").size<4096 && records.read(messageKey(value.groupId,value.logicalId))==null &&
            !isModerated(value.groupId,value.logicalId))
        records.write(messageKey(value.groupId,value.logicalId),NetworkCodec.encode(value))
    }
    internal fun createV2(value:GroupChatMessage,binding:GroupTextV2Binding)=records.transaction {
        require(binding.activationDigest.size==32 && binding.headDigest.size==32 &&
            binding.policyDigest.size==32 &&
            binding.sequence in 0..GroupGovernanceJournalV1.MAX_ENTRIES.toLong())
        create(value)
        records.write(bindingKey(value.groupId,value.logicalId),NetworkCodec.encode(binding))
    }
    internal fun createMedia(value:GroupChatMessage,media:GroupMediaV1)=records.transaction {
        GroupMediaCodecV1.validate(media)
        require(value.outgoing && value.groupId==media.groupId && value.logicalId==media.logicalId &&
            value.senderMemberId==media.senderMemberId && value.mediaKind==media.kind &&
            value.recipients.size in 1..4 && value.recipients.map {it.deviceId}.distinct().size==value.recipients.size &&
            value.recipients.all {RandomIdentifiers.valid(it.deviceId) && it.state==GroupRecipientState.PENDING} &&
            value.mediaBodyDigest?.let {digest ->
                val payload=GroupMediaCodecV1.encode(media)
                try {MessageDigest.isEqual(digest,DeviceAuth.digest(payload))} finally {payload.fill(0)}
            }!=false)
        require(records.keys("app/group-text/message/").size<4096 &&
            records.read(messageKey(value.groupId,value.logicalId))==null &&
            !isModerated(value.groupId,value.logicalId))
        records.write(messageKey(value.groupId,value.logicalId),NetworkCodec.encode(value))
        records.write(bindingKey(value.groupId,value.logicalId),NetworkCodec.encode(
            GroupTextV2Binding(media.governanceActivationDigest,media.governanceSequence,
                media.governanceHeadDigest,media.policyDigest)))
        val encoded=AttachmentFormat.encode(media.descriptor)
        try {records.write("app/attachment/${value.groupId}/${value.logicalId}",encoded)}
        finally {encoded.fill(0)}
    }
    fun queueMedia(sender:String,envelopeId:String,value:GroupMediaV1)=records.transaction {
        require(RandomIdentifiers.valid(sender));GroupMediaCodecV1.validate(value)
        if(isModerated(value.groupId,value.logicalId) ||
            isSenderDeleted(value.groupId,value.logicalId)) return@transaction
        require(records.keys("app/group-text/pending-media-v1/").size<64 &&
            records.keys("app/group-text/pending/").size+
            records.keys("app/group-text/pending-v2/").size+
            records.keys("app/group-text/pending-v3/").size+
            records.keys("app/group-text/pending-media-v1/").size<128)
        records.write(pendingMediaKey(envelopeId),NetworkCodec.encode(PendingGroupMediaV1(sender,value)))
    }
    internal fun pendingMedia():List<Pair<String,PendingGroupMediaV1>> = records.transaction {
        records.keys("app/group-text/pending-media-v1/").sorted().take(16).map {key ->
            key.substringAfterLast('/') to NetworkCodec.decode<PendingGroupMediaV1>(
                records.read(key) ?: throw EndpointStorageFailure(),8192)
        }
    }
    internal fun discardPendingMedia(envelopeId:String)=records.transaction {records.remove(pendingMediaKey(envelopeId))}
    internal fun acceptMedia(envelopeId:String,pending:PendingGroupMediaV1):Boolean=records.transaction {
        val value=pending.media
        val replay=replayKey(value.groupId,value.senderMemberId,value.logicalId)
        val existing=message(value.groupId,value.logicalId)
        if(records.read(replay)!=null || existing!=null) {
            if(existing!=null && existing.senderMemberId!=value.senderMemberId)
                throw EndpointStorageFailure()
            if(existing?.moderationState==GroupModerationState.NONE) {
                val prior=records.read("app/attachment/${value.groupId}/${value.logicalId}")
                    ?: throw EndpointStorageFailure()
                val presented=AttachmentFormat.encode(value.descriptor)
                val matches=prior.contentEquals(presented) && existing.mediaCaption==value.caption &&
                    existing.mediaKind==value.kind
                prior.fill(0);presented.fill(0)
                if(!matches) throw EndpointStorageFailure()
            }
            records.remove(pendingMediaKey(envelopeId));return@transaction false
        }
        require(records.keys("app/group-text/replay/").size<8192 &&
            records.keys("app/group-text/message/").size<4096)
        val moderated=isModerated(value.groupId,value.logicalId)
        val pendingDelete=pendingSenderDeleteMatches(value.groupId,value.logicalId,value.senderMemberId)
        val senderDeleted=!moderated && (isSenderDeleted(value.groupId,value.logicalId) || pendingDelete)
        if(senderDeleted && !isSenderDeleted(value.groupId,value.logicalId)) {
            require(records.keys("app/group-text/sender-deleted/").size<GroupSenderDeleteFilterV1.MAX_ENTRIES)
            records.write(senderDeleteKey(value.groupId,value.logicalId),value.senderMemberId.encodeToByteArray())
        }
        val terminal=moderated || senderDeleted
        val order=nextOrder()
        records.write(messageKey(value.groupId,value.logicalId),NetworkCodec.encode(GroupChatMessage(
            value.groupId,value.logicalId,value.epoch,value.senderMemberId,false,"",order,
            moderationState=when {
                moderated -> GroupModerationState.REMOVED_BY_ADMIN
                senderDeleted -> GroupModerationState.DELETED_BY_SENDER
                else -> GroupModerationState.NONE
            },mediaKind=value.kind,mediaCaption=if(terminal) null else value.caption,
            mediaFilename=if(terminal) null else value.descriptor.filename,
            mediaBytes=value.descriptor.plaintextLength,
            mediaDurationMillis=value.descriptor.durationMillis,
            mediaSenderDeviceId=pending.senderDeviceId)))
        if(!terminal) {
            val encoded=AttachmentFormat.encode(value.descriptor)
            try {records.write("app/attachment/${value.groupId}/${value.logicalId}",encoded)}
            finally {encoded.fill(0)}
            if(LocalRepository(records).autoDownloadEnabled(value.kind))
                records.write("app/auto-download/${value.groupId}/${value.logicalId}",byteArrayOf(1))
        }
        records.write(replay,byteArrayOf(1))
        GroupMessageControlStoreV1(records).applyPendingTarget(value.groupId,value.logicalId)
        if(!terminal) NotificationLedger.acceptedGroup(records,value.groupId,value.logicalId)
        records.remove(pendingMediaKey(envelopeId))
        true
    }
    internal fun binding(groupId:String,id:String):GroupTextV2Binding?=records.transaction {
        records.read(bindingKey(groupId,id))?.let {NetworkCodec.decode<GroupTextV2Binding>(it,256)}
    }
    fun queue(sender:String,envelopeId:String,value:GroupText)=records.transaction {
        require(RandomIdentifiers.valid(sender));GroupTextCodec.validate(value)
        if(isModerated(value.groupId,value.logicalId) ||
            isSenderDeleted(value.groupId,value.logicalId)) {
            // The signed marker is already durable. Do not let an unvalidated late
            // sender supply attribution or ordering for a new visible row.
            return@transaction
        }
        require(records.keys("app/group-text/pending/").size+
            records.keys("app/group-text/pending-v2/").size+
            records.keys("app/group-text/pending-v3/").size+
            records.keys("app/group-text/pending-media-v1/").size<128)
        records.write(pendingKey(envelopeId),NetworkCodec.encode(PendingGroupText(sender,value)))
    }
    fun queueV2(sender:String,envelopeId:String,value:GroupTextV2)=records.transaction {
        require(RandomIdentifiers.valid(sender));GroupTextV2Codec.validate(value)
        if(isModerated(value.groupId,value.logicalId) ||
            isSenderDeleted(value.groupId,value.logicalId)) {
            // Signal receipt commits in the caller; retain no late plaintext.
            return@transaction
        }
        require(records.keys("app/group-text/pending/").size+
            records.keys("app/group-text/pending-v2/").size+
            records.keys("app/group-text/pending-v3/").size+
            records.keys("app/group-text/pending-media-v1/").size<128)
        val existing=records.keys("app/group-text/pending-v2/").count {key ->
            records.read(key)?.let {NetworkCodec.decode<PendingGroupTextV2>(it,4096)}
                ?.text?.groupId==value.groupId
        }+records.keys("app/group-text/pending-v3/").count {key ->
            records.read(key)?.let {NetworkCodec.decode<PendingGroupTextV3>(it,4096)}
                ?.text?.groupId==value.groupId
        }
        require(existing<32)
        records.write(pendingV2Key(envelopeId),NetworkCodec.encode(PendingGroupTextV2(sender,value)))
    }
    fun queueV3(sender:String,envelopeId:String,value:GroupTextV3)=records.transaction {
        require(RandomIdentifiers.valid(sender));GroupTextV3Codec.validate(value)
        if(isModerated(value.groupId,value.logicalId) ||
            isSenderDeleted(value.groupId,value.logicalId)) return@transaction
        require(records.keys("app/group-text/pending/").size+
            records.keys("app/group-text/pending-v2/").size+
            records.keys("app/group-text/pending-v3/").size+
            records.keys("app/group-text/pending-media-v1/").size<128)
        val groupPending=records.keys("app/group-text/pending-v2/").count {key ->
            records.read(key)?.let {NetworkCodec.decode<PendingGroupTextV2>(it,4096)}
                ?.text?.groupId==value.groupId
        }+records.keys("app/group-text/pending-v3/").count {key ->
            records.read(key)?.let {NetworkCodec.decode<PendingGroupTextV3>(it,4096)}
                ?.text?.groupId==value.groupId
        }
        require(groupPending<32)
        records.write(pendingV3Key(envelopeId),NetworkCodec.encode(PendingGroupTextV3(sender,value)))
    }
    internal fun pendingV3():List<Pair<String,PendingGroupTextV3>> = records.transaction {
        records.keys("app/group-text/pending-v3/").sorted().take(16).map {key ->
            key.removePrefix("app/group-text/pending-v3/") to NetworkCodec.decode<PendingGroupTextV3>(
                records.read(key) ?: throw EndpointStorageFailure(),4096)
        }
    }
    fun discardPendingV3(envelopeId:String)=records.transaction {records.remove(pendingV3Key(envelopeId))}
    internal fun acceptV3(envelopeId:String,pending:PendingGroupTextV3):Boolean=records.transaction {
        val value=pending.text
        val v2=GroupTextV2(groupId=value.groupId,epoch=value.epoch,
            senderMemberId=value.senderMemberId,logicalId=value.logicalId,
            governanceActivationDigest=value.governanceActivationDigest,
            governanceSequence=value.governanceSequence,governanceHeadDigest=value.governanceHeadDigest,
            policyDigest=value.policyDigest,text=value.text)
        val accepted=acceptV2(envelopeId,PendingGroupTextV2(pending.senderDeviceId,v2))
        if(accepted) {
            val key=messageKey(value.groupId,value.logicalId)
            val message=decodeMessage(key)
            records.write(key,NetworkCodec.encode(message.copy(replyToLogicalId=value.replyToLogicalId)))
        }
        records.remove(pendingV3Key(envelopeId))
        accepted
    }
    internal fun pendingV2():List<Pair<String,PendingGroupTextV2>> = records.transaction {
        records.keys("app/group-text/pending-v2/").sorted().take(16).map {key ->
            key.removePrefix("app/group-text/pending-v2/") to NetworkCodec.decode<PendingGroupTextV2>(
                records.read(key) ?: throw EndpointStorageFailure(),4096)
        }
    }
    fun discardPendingV2(envelopeId:String)=records.transaction {records.remove(pendingV2Key(envelopeId))}
    internal fun acceptV2(envelopeId:String,pending:PendingGroupTextV2):Boolean=records.transaction {
        val value=pending.text
        val replay=replayKey(value.groupId,value.senderMemberId,value.logicalId)
        if(records.read(replay)!=null) {records.remove(pendingV2Key(envelopeId));return@transaction false}
        if(records.read(messageKey(value.groupId,value.logicalId))!=null) {
            require(records.keys("app/group-text/replay/").size<8192)
            records.write(replay,byteArrayOf(1));records.remove(pendingV2Key(envelopeId));return@transaction false
        }
        require(records.keys("app/group-text/replay/").size<8192 &&
            records.keys("app/group-text/message/").size<4096)
        val order=(records.read("app/group-text/order")?.decodeToString()?.toLongOrNull() ?: 0L)+1
        records.write("app/group-text/order",order.toString().encodeToByteArray())
        val moderated=isModerated(value.groupId,value.logicalId)
        val pendingDelete=pendingSenderDeleteMatches(value.groupId,value.logicalId,value.senderMemberId)
        val senderDeleted=!moderated && (isSenderDeleted(value.groupId,value.logicalId) || pendingDelete)
        if(senderDeleted && !isSenderDeleted(value.groupId,value.logicalId)) {
            require(records.keys("app/group-text/sender-deleted/").size<
                GroupSenderDeleteFilterV1.MAX_ENTRIES)
            records.write(senderDeleteKey(value.groupId,value.logicalId),
                value.senderMemberId.encodeToByteArray())
        }
        records.write(messageKey(value.groupId,value.logicalId),NetworkCodec.encode(GroupChatMessage(
            value.groupId,value.logicalId,value.epoch,value.senderMemberId,false,
            if(moderated || senderDeleted) "" else value.text,order,moderationState=when {
                moderated -> GroupModerationState.REMOVED_BY_ADMIN
                senderDeleted -> GroupModerationState.DELETED_BY_SENDER
                else -> GroupModerationState.NONE
            })))
        records.write(replay,byteArrayOf(1))
        GroupMessageControlStoreV1(records).applyPendingTarget(value.groupId,value.logicalId)
        if(message(value.groupId,value.logicalId)?.moderationState==GroupModerationState.NONE)
            NotificationLedger.acceptedGroup(records,value.groupId,value.logicalId)
        records.remove(pendingV2Key(envelopeId))
        true
    }
    fun pending():List<Pair<String,PendingGroupText>> = records.transaction {
        records.keys("app/group-text/pending/").sorted().take(16).map {key ->
            key.removePrefix("app/group-text/pending/") to NetworkCodec.decode<PendingGroupText>(
                records.read(key) ?: throw EndpointStorageFailure(),4096)
        }
    }
    fun discardPending(envelopeId:String)=records.transaction {records.remove(pendingKey(envelopeId))}
    /** Replay marker and visible message are committed in the same endpoint transaction. */
    fun accept(envelopeId:String,pending:PendingGroupText):Boolean=records.transaction {
        val value=pending.text
        val replay=replayKey(value.groupId,value.senderMemberId,value.logicalId)
        if(records.read(replay)!=null) {records.remove(pendingKey(envelopeId));return@transaction false}
        if(records.read(messageKey(value.groupId,value.logicalId))!=null) {
            require(records.keys("app/group-text/replay/").size<8192)
            records.write(replay,byteArrayOf(1));records.remove(pendingKey(envelopeId));return@transaction false
        }
        // Never evict a replay marker while retained history might still display the message.
        require(records.keys("app/group-text/replay/").size<8192 &&
            records.keys("app/group-text/message/").size<4096)
        val order=(records.read("app/group-text/order")?.decodeToString()?.toLongOrNull() ?: 0L)+1
        records.write("app/group-text/order",order.toString().encodeToByteArray())
        val moderated=isModerated(value.groupId,value.logicalId)
        records.write(messageKey(value.groupId,value.logicalId),NetworkCodec.encode(GroupChatMessage(
            value.groupId,value.logicalId,value.epoch,value.senderMemberId,false,
            if(moderated) "" else value.text,order,moderationState=if(moderated)
                GroupModerationState.REMOVED_BY_ADMIN else GroupModerationState.NONE)))
        records.write(replay,byteArrayOf(1))
        if(!moderated) NotificationLedger.acceptedGroup(records,value.groupId,value.logicalId)
        records.remove(pendingKey(envelopeId))
        true
    }
    fun nextOrder():Long=records.transaction {
        val order=(records.read("app/group-text/order")?.decodeToString()?.toLongOrNull() ?: 0L)+1
        records.write("app/group-text/order",order.toString().encodeToByteArray());order
    }
    fun markQueued(groupId:String,id:String,device:String,outboxId:String)=records.transaction {
        require(RandomIdentifiers.valid(outboxId))
        val key=messageKey(groupId,id);val old=decodeMessage(key)
        require(old.outgoing)
        val recipient=old.recipients.single {it.deviceId==device}
        require(recipient.state==GroupRecipientState.PENDING && recipient.outboxId==null)
        records.write(key,NetworkCodec.encode(old.copy(recipients=old.recipients.map {
            if(it.deviceId==device) it.copy(state=GroupRecipientState.QUEUED,outboxId=outboxId) else it
        })))
        records.write("app/group-text/outbox/$outboxId",key.encodeToByteArray())
    }
    /** True only when every frozen recipient has exact Signal ciphertext in the protected outbox,
     * or the server has already accepted that recipient's immutable envelope. */
    fun markMediaDeliveryPreparedIfReady(groupId:String,id:String):Boolean=records.transaction {
        val key=messageKey(groupId,id)
        val old=decodeMessage(key)
        if(!old.outgoing || old.mediaKind==null || old.mediaBodyDigest?.size!=32)
            return@transaction false
        val binding=binding(groupId,id) ?: return@transaction false
        val ready=old.recipients.isNotEmpty() && old.recipients.all {recipient ->
            if(recipient.state==GroupRecipientState.SENT) true
            else if(recipient.state!=GroupRecipientState.QUEUED || recipient.outboxId==null) false
            else records.read("outbox/${recipient.outboxId}")?.let {
                val entry=NetworkCodec.decode<OutboxEntry>(it)
                val proof=entry.groupMediaBinding
                val envelope=runCatching {EnvelopeCodec.decode(entry.ciphertext)}.getOrNull()
                entry.state in setOf(OutboxState.CIPHERTEXT_READY,OutboxState.UPLOAD_PENDING,
                    OutboxState.SERVER_ACCEPTED) && entry.submissionId==recipient.outboxId &&
                    entry.deviceId==recipient.deviceId && envelope?.recipientDeviceId==recipient.deviceId &&
                    envelope.envelopeId==entry.envelopeId &&
                    proof!=null && proof.groupId==groupId && proof.logicalId==id &&
                    proof.recipientDeviceId==recipient.deviceId &&
                    MessageDigest.isEqual(proof.governanceHeadDigest,binding.headDigest) &&
                    MessageDigest.isEqual(proof.mediaBodyDigest,old.mediaBodyDigest)
            }==true
        }
        if(ready && !old.mediaDeliveryPrepared)
            records.write(key,NetworkCodec.encode(old.copy(mediaDeliveryPrepared=true)))
        ready
    }
    fun markResult(outboxId:String,success:Boolean) = records.transaction {
        require(RandomIdentifiers.valid(outboxId))
        val link="app/group-text/outbox/$outboxId"
        val key=records.read(link)?.decodeToString() ?: return@transaction
        val old=decodeMessage(key)
        val next=old.recipients.map {
            if(it.outboxId==outboxId) it.copy(state=if(success) GroupRecipientState.SENT else GroupRecipientState.UNAVAILABLE) else it
        }
        records.write(key,NetworkCodec.encode(old.copy(recipients=next,
            mediaDeliveryPrepared=old.mediaDeliveryPrepared && success)))
        records.remove(link)
    }
    fun outboxIds():Set<String> = records.transaction {
        records.keys("app/group-text/outbox/").map {it.removePrefix("app/group-text/outbox/")}.toSet()
    }
    fun markUnavailable(groupId:String,id:String,device:String)=records.transaction {
        val key=messageKey(groupId,id);val old=decodeMessage(key)
        require(old.outgoing)
        val next=old.recipients.map {
            if(it.deviceId==device && it.state==GroupRecipientState.PENDING)
                it.copy(state=GroupRecipientState.UNAVAILABLE) else it
        }
        records.write(key,NetworkCodec.encode(old.copy(recipients=next,
            mediaDeliveryPrepared=old.mediaDeliveryPrepared && next.none {
                it.state==GroupRecipientState.UNAVAILABLE})))
    }
    fun markRemoved(groupId:String,device:String) = records.transaction {
        require(GroupIds.valid(groupId) && RandomIdentifiers.valid(device))
        for(key in records.keys("app/group-text/message/$groupId/")) {
            val old=decodeMessage(key)
            if(!old.outgoing || old.recipients.none {it.deviceId==device && it.state in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)}) continue
            val next=old.recipients.map {recipient ->
                if(recipient.deviceId==device && recipient.state in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)) {
                    val accepted=recipient.outboxId?.let {id ->
                        val outboxKey="outbox/$id"
                        val entry=records.read(outboxKey)?.let {NetworkCodec.decode<OutboxEntry>(it)}
                        if(entry?.state!=OutboxState.SERVER_ACCEPTED) records.remove(outboxKey)
                        records.remove("app/group-text/outbox/$id")
                        entry?.state==OutboxState.SERVER_ACCEPTED
                    }==true
                    recipient.copy(state=if(accepted) GroupRecipientState.SENT else GroupRecipientState.UNAVAILABLE,outboxId=null)
                } else recipient
            }
            records.write(key,NetworkCodec.encode(old.copy(recipients=next,
                mediaDeliveryPrepared=old.mediaDeliveryPrepared && next.none {
                    it.state==GroupRecipientState.UNAVAILABLE})))
        }
    }
    fun cancelStale(groupId:String,currentEpoch:Long)=records.transaction {
        require(GroupIds.valid(groupId))
        for(key in records.keys("app/group-text/message/$groupId/")) {
            val old=decodeMessage(key)
            if(!old.outgoing || old.epoch==currentEpoch) continue
            val next=old.recipients.map {recipient ->
                if(recipient.state in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)) {
                    val accepted=recipient.outboxId?.let {id ->
                        val outboxKey="outbox/$id"
                        val entry=records.read(outboxKey)?.let {NetworkCodec.decode<OutboxEntry>(it)}
                        if(entry?.state!=OutboxState.SERVER_ACCEPTED) records.remove(outboxKey)
                        records.remove("app/group-text/outbox/$id")
                        entry?.state==OutboxState.SERVER_ACCEPTED
                    }==true
                    recipient.copy(state=if(accepted) GroupRecipientState.SENT else GroupRecipientState.UNAVAILABLE,outboxId=null)
                } else recipient
            }
            records.write(key,NetworkCodec.encode(old.copy(recipients=next,
                mediaDeliveryPrepared=old.mediaDeliveryPrepared && next.none {
                    it.state==GroupRecipientState.UNAVAILABLE})))
        }
    }
    /** Submitted ciphertext is recorded as sent; every unsent old-head slot is retired. */
    internal fun cancelStaleHead(groupId:String,head:GovernanceHeadFoundationV1?,
        policy:GroupGovernancePolicyV1?)=records.transaction {
        require(GroupIds.valid(groupId))
        for(key in records.keys("app/group-text/message/$groupId/")) {
            val old=decodeMessage(key)
            if(!old.outgoing) continue
            val binding=binding(groupId,old.logicalId)
            if(head==null && binding==null || head!=null && policy!=null &&
                binding?.matches(head,policy)==true) continue
            val next=old.recipients.map {recipient ->
                if(recipient.state !in setOf(GroupRecipientState.PENDING,GroupRecipientState.QUEUED)) recipient
                else {
                    val accepted=recipient.outboxId?.let {id ->
                        val outboxKey="outbox/$id"
                        val entry=records.read(outboxKey)?.let {NetworkCodec.decode<OutboxEntry>(it)}
                        if(entry?.state!=OutboxState.SERVER_ACCEPTED) records.remove(outboxKey)
                        records.remove("app/group-text/outbox/$id")
                        entry?.state==OutboxState.SERVER_ACCEPTED
                    }==true
                    recipient.copy(state=if(accepted) GroupRecipientState.SENT else
                        GroupRecipientState.UNAVAILABLE,outboxId=null)
                }
            }
            records.write(key,NetworkCodec.encode(old.copy(recipients=next,
                mediaDeliveryPrepared=old.mediaDeliveryPrepared && next.none {
                    it.state==GroupRecipientState.UNAVAILABLE})))
        }
    }
}
