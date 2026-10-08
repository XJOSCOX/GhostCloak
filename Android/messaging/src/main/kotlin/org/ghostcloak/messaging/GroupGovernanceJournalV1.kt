package org.ghostcloak.messaging

import kotlinx.serialization.Serializable
import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.protocol.NetworkCodec
import java.security.MessageDigest

/** Signed entries are retained for the lifetime of this local group. */
@Serializable internal data class GovernanceJournalTailV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,val sequence:Long,
    val headDigest:ByteArray,val stateRevision:Long,val stateDigest:ByteArray,
)

@Serializable internal data class GovernanceResyncMarkerV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,
    val sequence:Long,val headDigest:ByteArray,val stateRevision:Long,val stateDigest:ByteArray,
)

@Serializable data class GroupGovernanceResyncRequestV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,
    val sequence:Long,val headDigest:ByteArray,val stateRevision:Long,val stateDigest:ByteArray,
    val requesterMemberId:String,
) { override fun toString()="GroupGovernanceResyncRequestV1(redacted)" }

@Serializable data class GroupGovernanceResyncResponseV1(
    val version:Int=1,val groupId:String,val activationDigest:ByteArray,
    val requesterSequence:Long,val requesterHeadDigest:ByteArray,
    val startSequence:Long,val startHeadDigest:ByteArray,
    val entries:List<GroupGovernanceEntryV1>,
    val endSequence:Long,val endHeadDigest:ByteArray,val more:Boolean,
) { override fun toString()="GroupGovernanceResyncResponseV1(redacted)" }

/** Exactly one successor, either frozen v1 membership or policy-only v2 action. */
@Serializable data class GroupGovernanceResyncResponseV2(
    val version:Int=2,val groupId:String,val activationDigest:ByteArray,
    val requesterSequence:Long,val requesterHeadDigest:ByteArray,
    val startSequence:Long,val startHeadDigest:ByteArray,
    val membershipEntry:GroupGovernanceEntryV1?=null,
    val policyEntry:GroupGovernancePolicyEntryV1?=null,
    val endSequence:Long,val endHeadDigest:ByteArray,val more:Boolean,
) { override fun toString()="GroupGovernanceResyncResponseV2(redacted)" }

internal enum class GovernanceJournalStatus { COMPLETE, LEGACY_INCOMPLETE }

internal class GroupGovernanceJournalV1(private val records:EndpointRecords) {
    companion object { const val MAX_ENTRIES=511 }
    private fun base(id:String):String { require(GroupIds.valid(id));return "app/group/governance-journal-v1/$id/" }
    private fun entryKey(id:String,sequence:Long):String {
        require(sequence in 1..MAX_ENTRIES.toLong());return base(id)+"entry/$sequence"
    }
    private fun policyKey(id:String,sequence:Long):String {
        require(sequence in 1..MAX_ENTRIES.toLong());return base(id)+"policy/$sequence"
    }
    private fun profileKey(id:String,sequence:Long):String {
        require(sequence in 1..MAX_ENTRIES.toLong());return base(id)+"profile/$sequence"
    }
    private fun timerKey(id:String,sequence:Long):String {
        require(sequence in 1..MAX_ENTRIES.toLong());return base(id)+"timer/$sequence"
    }
    private fun tailKey(id:String)=base(id)+"tail"
    private fun markerKey(id:String)=base(id)+"resync"
    fun entry(id:String,sequence:Long):GroupGovernanceEntryV1?=records.transaction {
        records.read(entryKey(id,sequence))?.let {
            NetworkCodec.decode<GroupGovernanceEntryV1>(it,GroupGovernanceV1.MAX_ENTRY_BYTES)
        }
    }
    fun policyEntry(id:String,sequence:Long):GroupGovernancePolicyEntryV1?=records.transaction {
        records.read(policyKey(id,sequence))?.let {
            NetworkCodec.decode<GroupGovernancePolicyEntryV1>(it,
                GroupGovernancePolicyRulesV1.MAX_ENTRY_BYTES)
        }
    }
    fun profileEntry(id:String,sequence:Long):GroupGovernanceProfileEntryV1?=records.transaction {
        records.read(profileKey(id,sequence))?.let {
            NetworkCodec.decode<GroupGovernanceProfileEntryV1>(it,GroupProfileRulesV1.MAX_ENTRY_BYTES)
        }
    }
    fun timerEntry(id:String,sequence:Long):GroupGovernanceTimerEntryV1?=records.transaction {
        records.read(timerKey(id,sequence))?.let {
            NetworkCodec.decode<GroupGovernanceTimerEntryV1>(it,GroupDisappearingRulesV1.MAX_ENTRY_BYTES)
        }
    }
    private fun tail(id:String):GovernanceJournalTailV1?=records.read(tailKey(id))?.let {
        NetworkCodec.decode<GovernanceJournalTailV1>(it,512)
    }
    fun marker(id:String):GovernanceResyncMarkerV1?=records.transaction {
        records.read(markerKey(id))?.let {NetworkCodec.decode<GovernanceResyncMarkerV1>(it,512)}
    }
    fun saveMarker(marker:GovernanceResyncMarkerV1)=records.transaction {
        val bytes=NetworkCodec.encode(marker)
        require(bytes.size<=512)
        records.write(markerKey(marker.groupId),bytes)
    }
    fun clearMarker(id:String)=records.transaction {records.remove(markerKey(id))}
    fun markedGroups():List<String> = records.transaction {
        records.keys("app/group/governance-journal-v1/").mapNotNull {key ->
            if(key.endsWith("/resync")) key.removePrefix("app/group/governance-journal-v1/")
                .removeSuffix("/resync").takeIf(GroupIds::valid) else null
        }.take(64)
    }
    /** Called inside the same protected transaction as the GroupState/head advance. */
    fun append(entry:GroupGovernanceEntryV1,barrier:GovernanceBarrierV1,
        head:GovernanceHeadFoundationV1)=records.transaction {
        require(entry.sequence in 1..MAX_ENTRIES.toLong() && entry.groupId==head.groupId &&
            MessageDigest.isEqual(entry.activationDigest,barrier.activationDigest) &&
            head.sequence==entry.sequence &&
            MessageDigest.isEqual(head.headDigest,GroupGovernanceV1.entryDigest(entry)) &&
            head.stateRevision==entry.postRevision &&
            MessageDigest.isEqual(head.stateDigest,entry.postDigest))
        val old=tail(entry.groupId)
        val join=GovernedAdmissionStore(records).join(entry.groupId)
        val base=join?.checkpoint
        val sequence=old?.sequence ?: base?.parentSequence ?: 0L
        val digest=old?.headDigest ?: base?.parentHeadDigest ?: barrier.activationDigest
        val revision=old?.stateRevision ?: base?.parentRevision ?: barrier.activationStateRevision
        val stateDigest=old?.stateDigest ?: base?.parentStateDigest ?: barrier.activationStateDigest
        require(entry.sequence==sequence+1 && entry.preRevision==revision &&
            MessageDigest.isEqual(entry.previousHeadDigest,digest) &&
            MessageDigest.isEqual(entry.preDigest,stateDigest) &&
            records.read(entryKey(entry.groupId,entry.sequence))==null &&
            records.read(policyKey(entry.groupId,entry.sequence))==null &&
            records.read(profileKey(entry.groupId,entry.sequence))==null &&
            records.read(timerKey(entry.groupId,entry.sequence))==null)
        val bytes=NetworkCodec.encode(entry)
        require(bytes.size<=GroupGovernanceV1.MAX_ENTRY_BYTES)
        // Every accepted entry must fit in its future one-entry resync response.
        GroupControlCodec.encode(GroupControl(kind=GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V1,
            groupId=entry.groupId,governanceResyncResponseV1=GroupGovernanceResyncResponseV1(
                groupId=entry.groupId,activationDigest=barrier.activationDigest,
                requesterSequence=sequence,requesterHeadDigest=digest,
                startSequence=entry.sequence,startHeadDigest=digest,entries=listOf(entry),
                endSequence=entry.sequence,endHeadDigest=GroupGovernanceV1.entryDigest(entry),
                more=false)))
        records.write(entryKey(entry.groupId,entry.sequence),bytes)
        val next=GovernanceJournalTailV1(groupId=entry.groupId,
            activationDigest=barrier.activationDigest,sequence=entry.sequence,
            headDigest=head.headDigest,stateRevision=head.stateRevision,stateDigest=head.stateDigest)
        records.write(tailKey(entry.groupId),NetworkCodec.encode(next))
    }
    /** Policy actions share the same sequence, tail, and protected transaction as v1 entries. */
    fun appendPolicy(entry:GroupGovernancePolicyEntryV1,barrier:GovernanceBarrierV1,
        head:GovernanceHeadFoundationV1)=records.transaction {
        require(entry.sequence in 1..MAX_ENTRIES.toLong() && entry.groupId==head.groupId &&
            MessageDigest.isEqual(entry.activationDigest,barrier.activationDigest) &&
            head.sequence==entry.sequence &&
            MessageDigest.isEqual(head.headDigest,GroupGovernancePolicyRulesV1.entryDigest(entry)) &&
            head.stateRevision==entry.stateRevision &&
            MessageDigest.isEqual(head.stateDigest,entry.stateDigest))
        val old=tail(entry.groupId)
        val join=GovernedAdmissionStore(records).join(entry.groupId)?.checkpoint
        val sequence=old?.sequence ?: join?.parentSequence ?: 0L
        val digest=old?.headDigest ?: join?.parentHeadDigest ?: barrier.activationDigest
        val revision=old?.stateRevision ?: join?.parentRevision ?: barrier.activationStateRevision
        val stateDigest=old?.stateDigest ?: join?.parentStateDigest ?: barrier.activationStateDigest
        require(entry.sequence==sequence+1 && entry.stateRevision==revision &&
            MessageDigest.isEqual(entry.previousHeadDigest,digest) &&
            MessageDigest.isEqual(entry.stateDigest,stateDigest) &&
            records.read(entryKey(entry.groupId,entry.sequence))==null &&
            records.read(policyKey(entry.groupId,entry.sequence))==null &&
            records.read(profileKey(entry.groupId,entry.sequence))==null &&
            records.read(timerKey(entry.groupId,entry.sequence))==null)
        val bytes=NetworkCodec.encode(entry)
        require(bytes.size<=GroupGovernancePolicyRulesV1.MAX_ENTRY_BYTES)
        GroupControlCodec.encode(GroupControl(kind=GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V2,
            groupId=entry.groupId,governanceResyncResponseV2=GroupGovernanceResyncResponseV2(
                groupId=entry.groupId,activationDigest=barrier.activationDigest,
                requesterSequence=sequence,requesterHeadDigest=digest,
                startSequence=entry.sequence,startHeadDigest=digest,policyEntry=entry,
                endSequence=entry.sequence,
                endHeadDigest=GroupGovernancePolicyRulesV1.entryDigest(entry),more=false)))
        records.write(policyKey(entry.groupId,entry.sequence),bytes)
        records.write(tailKey(entry.groupId),NetworkCodec.encode(GovernanceJournalTailV1(
            groupId=entry.groupId,activationDigest=barrier.activationDigest,
            sequence=entry.sequence,headDigest=head.headDigest,
            stateRevision=head.stateRevision,stateDigest=head.stateDigest)))
    }
    fun appendProfile(entry:GroupGovernanceProfileEntryV1,barrier:GovernanceBarrierV1,
        head:GovernanceHeadFoundationV1)=records.transaction {
        require(entry.sequence in 1..MAX_ENTRIES.toLong() && entry.groupId==head.groupId &&
            MessageDigest.isEqual(entry.activationDigest,barrier.activationDigest) &&
            head.sequence==entry.sequence &&
            MessageDigest.isEqual(head.headDigest,GroupProfileRulesV1.entryDigest(entry)) &&
            head.stateRevision==entry.stateRevision &&
            MessageDigest.isEqual(head.stateDigest,entry.stateDigest))
        val old=tail(entry.groupId)
        val join=GovernedAdmissionStore(records).join(entry.groupId)?.checkpoint
        val sequence=old?.sequence ?: join?.parentSequence ?: 0L
        val digest=old?.headDigest ?: join?.parentHeadDigest ?: barrier.activationDigest
        val revision=old?.stateRevision ?: join?.parentRevision ?: barrier.activationStateRevision
        val stateDigest=old?.stateDigest ?: join?.parentStateDigest ?: barrier.activationStateDigest
        require(entry.sequence==sequence+1 && entry.stateRevision==revision &&
            MessageDigest.isEqual(entry.previousHeadDigest,digest) &&
            MessageDigest.isEqual(entry.stateDigest,stateDigest) &&
            records.read(entryKey(entry.groupId,entry.sequence))==null &&
            records.read(policyKey(entry.groupId,entry.sequence))==null &&
            records.read(profileKey(entry.groupId,entry.sequence))==null &&
            records.read(timerKey(entry.groupId,entry.sequence))==null)
        val bytes=NetworkCodec.encode(entry)
        require(bytes.size<=GroupProfileRulesV1.MAX_ENTRY_BYTES)
        records.write(profileKey(entry.groupId,entry.sequence),bytes)
        records.write(tailKey(entry.groupId),NetworkCodec.encode(GovernanceJournalTailV1(
            groupId=entry.groupId,activationDigest=barrier.activationDigest,
            sequence=entry.sequence,headDigest=head.headDigest,
            stateRevision=head.stateRevision,stateDigest=head.stateDigest)))
    }
    /** Timer changes consume the same signed head and bounded history as every other mutation. */
    fun appendTimer(entry:GroupGovernanceTimerEntryV1,barrier:GovernanceBarrierV1,
        head:GovernanceHeadFoundationV1)=records.transaction {
        require(entry.sequence in 1..MAX_ENTRIES.toLong() && entry.groupId==head.groupId &&
            MessageDigest.isEqual(entry.activationDigest,barrier.activationDigest) &&
            head.sequence==entry.sequence &&
            MessageDigest.isEqual(head.headDigest,GroupDisappearingRulesV1.entryDigest(entry)) &&
            head.stateRevision==entry.stateRevision &&
            MessageDigest.isEqual(head.stateDigest,entry.stateDigest))
        val old=tail(entry.groupId)
        val join=GovernedAdmissionStore(records).join(entry.groupId)?.checkpoint
        val sequence=old?.sequence ?: join?.parentSequence ?: 0L
        val digest=old?.headDigest ?: join?.parentHeadDigest ?: barrier.activationDigest
        val revision=old?.stateRevision ?: join?.parentRevision ?: barrier.activationStateRevision
        val stateDigest=old?.stateDigest ?: join?.parentStateDigest ?: barrier.activationStateDigest
        require(entry.sequence==sequence+1 && entry.stateRevision==revision &&
            MessageDigest.isEqual(entry.previousHeadDigest,digest) &&
            MessageDigest.isEqual(entry.stateDigest,stateDigest) &&
            listOf(entryKey(entry.groupId,entry.sequence),policyKey(entry.groupId,entry.sequence),
                profileKey(entry.groupId,entry.sequence),timerKey(entry.groupId,entry.sequence))
                .all {records.read(it)==null})
        val bytes=NetworkCodec.encode(entry)
        require(bytes.size<=GroupDisappearingRulesV1.MAX_ENTRY_BYTES)
        GroupControlCodec.encode(GroupControl(kind=GroupControlKind.GOVERNANCE_TIMER_RESYNC_RESPONSE_V1,
            groupId=entry.groupId,governanceTimerResyncV1=GroupGovernanceTimerResyncResponseV1(
                groupId=entry.groupId,activationDigest=barrier.activationDigest,
                requesterSequence=sequence,requesterHeadDigest=digest,
                startSequence=entry.sequence,startHeadDigest=digest,timerEntry=entry,
                endSequence=entry.sequence,endHeadDigest=GroupDisappearingRulesV1.entryDigest(entry),
                more=false)))
        records.write(timerKey(entry.groupId,entry.sequence),bytes)
        records.write(tailKey(entry.groupId),NetworkCodec.encode(GovernanceJournalTailV1(
            groupId=entry.groupId,activationDigest=barrier.activationDigest,
            sequence=entry.sequence,headDigest=head.headDigest,
            stateRevision=head.stateRevision,stateDigest=head.stateDigest)))
    }
    /** Missing A6.2 history is classified, never reconstructed from GroupState. */
    fun status(id:String,barrier:GovernanceBarrierV1,
        head:GovernanceHeadFoundationV1,activationState:GroupState?,
        ledgerEvents:List<GroupTransition>,
        initialPolicy:GroupGovernancePolicyV1=GroupGovernancePolicyRulesV1.initial(),
        initialProfile:GroupProfileV1=GroupProfileRulesV1.default,
        initialTimer:GroupDisappearingPolicyV1=GroupDisappearingRulesV1.initial()):GovernanceJournalStatus=records.transaction {
        val join=GovernedAdmissionStore(records).join(id)
        val checkpoint=join?.checkpoint
        if(join!=null && (checkpoint==null ||
            !GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint,join.proof.state,
                join.proof,join.certificate,join.binding) ||
            !MessageDigest.isEqual(checkpoint.activationDigest,barrier.activationDigest) ||
            checkpoint.activationStateRevision!=barrier.activationStateRevision ||
            !MessageDigest.isEqual(checkpoint.activationStateDigest,
                barrier.activationStateDigest) ||
            !MessageDigest.isEqual(checkpoint.baselineCertificateDigest,
                barrier.baselineCertificateDigest)))
            return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
        if(head.sequence==0L) return@transaction if(tail(id)==null &&
            records.keys(base(id)+"entry/").isEmpty() &&
            records.keys(base(id)+"policy/").isEmpty() &&
            records.keys(base(id)+"profile/").isEmpty() &&
            records.keys(base(id)+"timer/").isEmpty() &&
            MessageDigest.isEqual(head.headDigest,barrier.activationDigest) &&
            head.stateRevision==barrier.activationStateRevision &&
            MessageDigest.isEqual(head.stateDigest,barrier.activationStateDigest))
                GovernanceJournalStatus.COMPLETE else GovernanceJournalStatus.LEGACY_INCOMPLETE
        val initial=activationState ?: return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
        val start=checkpoint?.parentSequence ?: 0L
        val baseRevision=checkpoint?.parentRevision ?: barrier.activationStateRevision
        val baseDigest=checkpoint?.parentStateDigest ?: barrier.activationStateDigest
        val baseHead=checkpoint?.parentHeadDigest ?: barrier.activationDigest
        if(head.sequence !in (start+1)..MAX_ENTRIES.toLong() ||
            initial.revision!=baseRevision ||
            !MessageDigest.isEqual(GroupStatements.digest(initial),baseDigest))
            return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
        val tail=tail(id) ?: return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
        if(tail.version!=1 || tail.groupId!=id || tail.sequence!=head.sequence ||
            !MessageDigest.isEqual(tail.activationDigest,barrier.activationDigest) ||
            !MessageDigest.isEqual(tail.headDigest,head.headDigest) ||
            tail.stateRevision!=head.stateRevision ||
            !MessageDigest.isEqual(tail.stateDigest,head.stateDigest) ||
            records.keys(base(id)+"entry/").size+
                records.keys(base(id)+"policy/").size+
                records.keys(base(id)+"profile/").size+
                records.keys(base(id)+"timer/").size!=(head.sequence-start).toInt())
            return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
        var pre=initial
        var previous=baseHead
        var policy=initialPolicy
        var profile=initialProfile
        var timer=initialTimer
        if(runCatching {GroupDisappearingRulesV1.validate(timer)}.isFailure)
            return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
        if(runCatching {GroupGovernancePolicyRulesV1.validate(policy,pre)}.isFailure)
            return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
        for(sequence in (start+1)..head.sequence) {
            val entry=runCatching {entry(id,sequence)}.getOrNull()
            val policyEntry=runCatching {policyEntry(id,sequence)}.getOrNull()
            val profileEntry=runCatching {profileEntry(id,sequence)}.getOrNull()
            val timerEntry=runCatching {timerEntry(id,sequence)}.getOrNull()
            if(listOf(entry,policyEntry,profileEntry,timerEntry).count {it!=null}!=1)
                return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
            val expected=GovernanceHeadFoundationV1(groupId=id,
                activationDigest=barrier.activationDigest,sequence=sequence-1,
                headDigest=previous,stateRevision=pre.revision,
                stateDigest=GroupStatements.digest(pre))
            if(entry!=null) {
                val ledgerEvent=ledgerEvents.singleOrNull {it.next.revision==entry.postRevision}
                if(!GroupGovernanceV1.verifyEntry(entry,pre,expected) || ledgerEvent==null ||
                    !NetworkCodec.encode(ledgerEvent).contentEquals(NetworkCodec.encode(entry.transition)))
                    return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
                pre=entry.transition.next
                policy=GroupGovernancePolicyRulesV1.afterTransition(policy,pre)
                previous=GroupGovernanceV1.entryDigest(entry)
            } else if(policyEntry!=null) {
                val action=policyEntry
                if(!GroupGovernancePolicyRulesV1.verifyEntry(action,pre,expected,policy))
                    return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
                policy=GroupGovernancePolicyRulesV1.apply(policy,pre,action.actorId,
                    action.action,action.targetMemberId,action.postingMode,action.targetLogicalId)
                previous=GroupGovernancePolicyRulesV1.entryDigest(action)
            } else if(profileEntry!=null) {
                val action=profileEntry
                if(!GroupProfileRulesV1.verifyEntry(action,pre,expected,profile))
                    return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
                profile=action.profile
                previous=GroupProfileRulesV1.entryDigest(action)
            } else {
                val action=checkNotNull(timerEntry)
                if(!GroupDisappearingRulesV1.verifyEntry(action,pre,expected,timer))
                    return@transaction GovernanceJournalStatus.LEGACY_INCOMPLETE
                timer=GroupDisappearingRulesV1.change(timer,action.seconds)
                previous=GroupDisappearingRulesV1.entryDigest(action)
            }
        }
        if(pre.revision!=head.stateRevision ||
            !MessageDigest.isEqual(GroupStatements.digest(pre),head.stateDigest) ||
            !MessageDigest.isEqual(previous,head.headDigest))
            GovernanceJournalStatus.LEGACY_INCOMPLETE else GovernanceJournalStatus.COMPLETE
    }
    fun currentPolicy(id:String,barrier:GovernanceBarrierV1,
        head:GovernanceHeadFoundationV1,activationState:GroupState?,
        ledgerEvents:List<GroupTransition>,
        initialPolicy:GroupGovernancePolicyV1=GroupGovernancePolicyRulesV1.initial(),
        throughSequence:Long=head.sequence,
        initialProfile:GroupProfileV1=GroupProfileRulesV1.default,
        initialTimer:GroupDisappearingPolicyV1=GroupDisappearingRulesV1.initial()):GroupGovernancePolicyV1?=
        records.transaction {
            if(status(id,barrier,head,activationState,ledgerEvents,initialPolicy,initialProfile,
                    initialTimer)!=GovernanceJournalStatus.COMPLETE)
                return@transaction null
            var policy=initialPolicy
            val start=GovernedAdmissionStore(records).join(id)?.checkpoint?.parentSequence ?: 0L
            if(throughSequence !in start..head.sequence) return@transaction null
            for(sequence in (start+1)..throughSequence) {
                val wrapped=entry(id,sequence)
                if(wrapped!=null) policy=GroupGovernancePolicyRulesV1.afterTransition(policy,wrapped.transition.next)
                else if(profileEntry(id,sequence)==null && timerEntry(id,sequence)==null) policy=checkNotNull(policyEntry(id,sequence)).let {
                    GroupGovernancePolicyRulesV1.apply(policy,
                        activationState?.let {initial ->
                            val revision=it.stateRevision
                            if(initial.revision==revision) initial else ledgerEvents.single {
                                event -> event.next.revision==revision
                            }.next
                        } ?: error("governance_activation_state_missing"),
                        it.actorId,it.action,it.targetMemberId,it.postingMode,it.targetLogicalId)
                }
            }
            policy
        }
    fun currentProfile(id:String,barrier:GovernanceBarrierV1,
        head:GovernanceHeadFoundationV1,activationState:GroupState?,
        ledgerEvents:List<GroupTransition>,
        initialPolicy:GroupGovernancePolicyV1=GroupGovernancePolicyRulesV1.initial(),
        throughSequence:Long=head.sequence,
        initialProfile:GroupProfileV1=GroupProfileRulesV1.default,
        initialTimer:GroupDisappearingPolicyV1=GroupDisappearingRulesV1.initial()):GroupProfileV1?=records.transaction {
        if(status(id,barrier,head,activationState,ledgerEvents,initialPolicy,initialProfile,
                initialTimer)!=GovernanceJournalStatus.COMPLETE)
            return@transaction null
        val start=GovernedAdmissionStore(records).join(id)?.checkpoint?.parentSequence ?: 0L
        if(throughSequence !in start..head.sequence) return@transaction null
        var profile=initialProfile
        for(sequence in (start+1)..throughSequence)
            profileEntry(id,sequence)?.let { profile=it.profile }
        profile
    }
    fun currentTimer(id:String,barrier:GovernanceBarrierV1,
        head:GovernanceHeadFoundationV1,activationState:GroupState?,
        ledgerEvents:List<GroupTransition>,
        initialPolicy:GroupGovernancePolicyV1=GroupGovernancePolicyRulesV1.initial(),
        initialProfile:GroupProfileV1=GroupProfileRulesV1.default,
        initialTimer:GroupDisappearingPolicyV1=GroupDisappearingRulesV1.initial(),
        throughSequence:Long=head.sequence):GroupDisappearingPolicyV1?=
        records.transaction {
            if(status(id,barrier,head,activationState,ledgerEvents,initialPolicy,initialProfile,
                    initialTimer)!=GovernanceJournalStatus.COMPLETE) return@transaction null
            var timer=initialTimer
            val start=GovernedAdmissionStore(records).join(id)?.checkpoint?.parentSequence ?: 0L
            if(throughSequence !in start..head.sequence) return@transaction null
            for(sequence in (start+1)..throughSequence)
                timerEntry(id,sequence)?.let {timer=GroupDisappearingRulesV1.change(timer,it.seconds)}
            timer
        }
}
