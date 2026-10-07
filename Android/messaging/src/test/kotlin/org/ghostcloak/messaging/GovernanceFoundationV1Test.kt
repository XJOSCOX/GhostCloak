package org.ghostcloak.messaging

import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class GovernanceFoundationV1Test {
    private class Records:EndpointRecords {
        val values=mutableMapOf<String,ByteArray>()
        var failPrefix:String?=null
        var failCommit=false
        private var depth=0
        override fun <T> transaction(block:()->T):T {
            if(depth>0) return block()
            val before=values.mapValues {it.value.copyOf()}.toMutableMap()
            depth++
            try {
                val result=block()
                if(failCommit) error("injected commit failure")
                return result
            } catch(error:Throwable) {values.clear();values.putAll(before);throw error}
            finally {depth--}
        }
        override fun read(key:String)=values[key]?.copyOf()
        override fun write(key:String,value:ByteArray) {
            if(failPrefix?.let(key::startsWith)==true) error("injected write failure")
            values[key]=value.copyOf()
        }
        override fun remove(key:String) {values.remove(key)}
        override fun keys(prefix:String)=values.keys.filter {it.startsWith(prefix)}
    }
    private data class Person(val member:GroupMember,val key:KeyPair)
    private data class Fixture(val records:Records,val state:GroupState,val people:List<Person>,
        val ledger:GroupLedger) {
        val owner get()=people.single {it.member.memberId==state.ownerId}
        val trusted=GroupTrustedPeer {true}
    }
    private fun key():KeyPair=KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private fun sign(key:KeyPair,bytes:ByteArray)=Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private);update(bytes);sign()
    }
    private fun fixture(count:Int=1,revision:Long=1):Fixture {
        val people=List(count) {
            val pair=key()
            Person(GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
                pair.public.encoded,DeviceAuth.digest(byteArrayOf(it.toByte())),GroupRole.MEMBER,1),pair)
        }.sortedBy {it.member.memberId}
        val owner=people.first()
        val members=people.mapIndexed {index,p -> p.member.copy(
            role=when(index) {0 -> GroupRole.OWNER;1 -> if(count>2) GroupRole.ADMIN else GroupRole.MEMBER
                else -> GroupRole.MEMBER},joinedEpoch=(index+1).toLong())}
        val groupId=GroupIds.create()
        val genesisState=GroupState(groupId,1,1,owner.member.memberId,owner.member.memberId,
            listOf(members.first().copy(joinedEpoch=1)),ByteArray(32))
        val genesis=GroupGenesis(genesisState,sign(owner.key,GroupStatements.genesis(genesisState)))
        val records=Records()
        val ledger=GroupLedger(records,GroupTrustedPeer {true},owner.member.memberId)
        assertEquals(GroupApply.ACCEPTED,ledger.acceptGenesis(genesis))
        val state=if(count==1 && revision==1L) genesisState else GroupState(groupId,revision,
            count.toLong(),owner.member.memberId,owner.member.memberId,members,ByteArray(32))
        if(state!==genesisState) records.transaction {
            records.write("group/state/v1/$groupId",NetworkCodec.encode(GroupRecord(state,genesis=genesis)))
        }
        return Fixture(records,state,people,ledger)
    }
    private fun baseline(f:Fixture) {
        val unsigned=GroupAuthorityBaselineV1.unsignedProposal(f.state,GroupIds.create())
        val proposal=unsigned.copy(coordinatorSignature=sign(f.owner.key,
            GroupAuthorityBaselineV1.proposalStatement(unsigned)))
        val approvals=f.people.map {person ->
            val unsignedApproval=GroupAuthorityBaselineV1.unsignedApproval(proposal,person.member.memberId)
            unsignedApproval.copy(signature=sign(person.key,
                GroupAuthorityBaselineV1.approvalStatement(unsignedApproval)))
        }
        val certificate=GroupAuthorityBaselineV1.certificate(proposal,approvals)
        val own=approvals.single {it.approverId==f.state.ownerId}
        val store=GroupAuthorityBaselineStore(f.records)
        store.saveOwn(OwnBaselineApprovalV1(f.state.groupId,proposal.proposalId,f.state.revision,
            GroupAuthorityBaselineV1.proposalDigest(proposal),proposal.memberSetDigest,own))
        store.activate(f.state,certificate,f.state.ownerId)
    }
    private fun install(f:Fixture):GovernanceHeadFoundationV1 {
        baseline(f)
        assertEquals(GroupApply.ACCEPTED,f.ledger.installGovernanceBarrierForFutureActivation(
            f.state.groupId,DeviceAuth.digest(byteArrayOf(4,5,6))))
        return GovernanceFoundationStore(f.records).head(f.state.groupId)!!
    }
    private fun transition(f:Fixture,parent:GroupState,change:GroupChange):GroupTransition {
        val next=GroupRules.derive(parent,change,emptySet(),f.trusted)
        val actor=f.people.single {it.member.memberId==change.actorId}
        val coordinator=f.people.single {it.member.memberId==parent.coordinatorId}
        return GroupTransition(change,next,sign(actor.key,GroupStatements.actor(parent,change,next)),
            sign(coordinator.key,GroupStatements.coordinator(parent,change,next)))
    }
    private fun profile(f:Fixture,parent:GroupState)=transition(f,parent,
        GroupChange(GroupIds.create(),GroupAction.PROFILE,parent.ownerId,
            newProfileRevision=parent.profileRevision+1,
            newProfileDigest=DeviceAuth.digest(parent.profileDigest+byteArrayOf(parent.revision.toByte()))))
    private fun governanceEntry(f:Fixture,head:GovernanceHeadFoundationV1,
        event:GroupTransition):GroupGovernanceEntryV1 {
        val unsigned=GroupGovernanceEntryV1(groupId=f.state.groupId,
            activationDigest=head.activationDigest,sequence=head.sequence+1,
            previousHeadDigest=head.headDigest,eventId=event.change.eventId,
            preRevision=head.stateRevision,preDigest=head.stateDigest,
            actorId=event.change.actorId,action=event.change.action,
            transitionDigest=DeviceAuth.digest(NetworkCodec.encode(event)),
            postRevision=event.next.revision,postDigest=GroupStatements.digest(event.next),
            transition=event,actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
        return unsigned.copy(actorSignature=sign(f.people.single {
            it.member.memberId==event.change.actorId}.key,
            GroupGovernanceV1.actorStatement(unsigned)),coordinatorSignature=sign(f.owner.key,
            GroupGovernanceV1.coordinatorStatement(unsigned)))
    }
    private fun policyEntry(f:Fixture,head:GovernanceHeadFoundationV1,state:GroupState,
        policy:GroupGovernancePolicyV1,mode:GroupPostingModeV1):GroupGovernancePolicyEntryV1 {
        val next=GroupGovernancePolicyRulesV1.apply(policy,state,state.ownerId,
            GroupPolicyActionV1.SET_POSTING_MODE,null,mode)
        val unsigned=GroupGovernancePolicyEntryV1(groupId=state.groupId,
            activationDigest=head.activationDigest,sequence=head.sequence+1,
            previousHeadDigest=head.headDigest,eventId=GroupIds.create(),
            stateRevision=state.revision,stateDigest=GroupStatements.digest(state),
            actorId=state.ownerId,action=GroupPolicyActionV1.SET_POSTING_MODE,
            postingMode=mode,
            prePolicyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy),
            postPolicyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,next),
            actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
        return unsigned.copy(actorSignature=sign(f.owner.key,
            GroupGovernancePolicyRulesV1.actorStatement(unsigned)),
            coordinatorSignature=sign(f.owner.key,
                GroupGovernancePolicyRulesV1.coordinatorStatement(unsigned)))
    }

    private fun moderationEntry(f:Fixture,head:GovernanceHeadFoundationV1,state:GroupState,
        actorId:String,logicalId:String,policy:GroupGovernancePolicyV1=GroupGovernancePolicyRulesV1.initial()):
        GroupGovernancePolicyEntryV1 {
        val digest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy)
        val unsigned=GroupGovernancePolicyEntryV1(groupId=state.groupId,
            activationDigest=head.activationDigest,sequence=head.sequence+1,
            previousHeadDigest=head.headDigest,eventId=GroupIds.create(),
            stateRevision=state.revision,stateDigest=GroupStatements.digest(state),
            actorId=actorId,action=GroupPolicyActionV1.REMOVE_GROUP_MESSAGE,
            targetLogicalId=logicalId,prePolicyDigest=digest,postPolicyDigest=digest,
            actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
        return unsigned.copy(actorSignature=sign(f.people.single {it.member.memberId==actorId}.key,
            GroupGovernancePolicyRulesV1.actorStatement(unsigned)),
            coordinatorSignature=sign(f.owner.key,
                GroupGovernancePolicyRulesV1.coordinatorStatement(unsigned)))
    }

    @Test fun boundedJoinFilterCoversEveryModeratedIdWithoutPlaintext() {
        val groupId=GroupIds.create()
        val ids=(1..GroupGovernanceJournalV1.MAX_ENTRIES).map {GroupIds.create()}
        val filter=GroupModerationFilterV1.empty()
        ids.forEach {GroupModerationFilterV1.add(filter,groupId,it)}
        assertEquals(GroupModerationFilterV1.BYTES,filter.size)
        assertTrue(ids.all {GroupModerationFilterV1.contains(filter,groupId,it)})
        assertFalse(GroupModerationFilterV1.contains(filter,groupId,GroupIds.create()))
        assertFalse(String(filter,Charsets.ISO_8859_1).contains("message text"))
    }

    @Test fun unknownTargetMarkersStopAtJournalCapacity() {
        val records=Records();val groupId=GroupIds.create();val store=GroupChatStore(records)
        repeat(GroupGovernanceJournalV1.MAX_ENTRIES) {
            records.write("app/group-text/moderated/$groupId/${GroupIds.create()}",byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.moderate(groupId,GroupIds.create())
        }
    }

    @Test fun moderationUsesGovernanceSequenceScrubsBodyAndRejectsRepeat() {
        val f=fixture();val head0=install(f)
        val id=GroupIds.create();val chat=GroupChatStore(f.records)
        chat.create(GroupChatMessage(f.state.groupId,id,f.state.epoch,f.state.ownerId,true,
            "sensitive group text",chat.nextOrder(),listOf(GroupRecipient(RandomIdentifiers.create()))))
        val entry=moderationEntry(f,head0,f.state,f.state.ownerId,id)
        assertTrue(GroupGovernancePolicyRulesV1.verifyEntry(entry,f.state,head0,
            GroupGovernancePolicyRulesV1.initial()))
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedPolicy(entry))
        assertEquals(1L,f.ledger.governanceHead(f.state.groupId)?.sequence)
        assertEquals("",chat.message(f.state.groupId,id)?.text)
        assertEquals(GroupModerationState.REMOVED_BY_ADMIN,
            chat.message(f.state.groupId,id)?.moderationState)
        assertEquals(GovernanceJournalStatus.COMPLETE,
            f.ledger.governanceJournalStatus(f.state.groupId))
        assertEquals(GroupApply.DUPLICATE,f.ledger.markGovernancePolicyForkIfValid(entry))
        val current=checkNotNull(f.ledger.governanceHead(f.state.groupId))
        val repeated=moderationEntry(f,current,f.state,f.state.ownerId,id)
        assertEquals(GroupApply.REJECTED,f.ledger.applyGovernedPolicy(repeated))
        assertEquals(1L,f.ledger.governanceHead(f.state.groupId)?.sequence)
        assertEquals(GovernanceJournalStatus.COMPLETE,f.ledger.governanceJournalStatus(f.state.groupId))
    }

    @Test fun adminCanModerateAnyRoleButMemberAndDemotedAdminCannot() {
        val f=fixture(3,10);val head=install(f)
        val admin=f.state.members.single {it.role==GroupRole.ADMIN}.memberId
        val member=f.state.members.single {it.role==GroupRole.MEMBER}.memberId
        val adminEntry=moderationEntry(f,head,f.state,admin,GroupIds.create())
        val memberEntry=moderationEntry(f,head,f.state,member,GroupIds.create())
        assertTrue(GroupGovernancePolicyRulesV1.verifyEntry(adminEntry,f.state,head,
            GroupGovernancePolicyRulesV1.initial()))
        assertFalse(GroupGovernancePolicyRulesV1.verifyEntry(memberEntry,f.state,head,
            GroupGovernancePolicyRulesV1.initial()))
        val demotion=transition(f,f.state,GroupChange(GroupIds.create(),GroupAction.DEMOTE,
            f.state.ownerId,targetId=admin))
        val nextHead=head.copy(sequence=head.sequence+1,headDigest=DeviceAuth.digest(byteArrayOf(9)),
            stateRevision=demotion.next.revision,stateDigest=GroupStatements.digest(demotion.next))
        assertFalse(GroupGovernancePolicyRulesV1.verifyEntry(adminEntry,demotion.next,nextHead,
            GroupGovernancePolicyRulesV1.initial()))
        val fresh=moderationEntry(f,nextHead,demotion.next,admin,GroupIds.create())
        assertFalse(GroupGovernancePolicyRulesV1.verifyEntry(fresh,demotion.next,nextHead,
            GroupGovernancePolicyRulesV1.initial()))
    }

    @Test fun moderationBeforeTargetSurvivesRestartAndPreventsNotificationOrResurrection() {
        val f=fixture();val head=install(f)
        val id=GroupIds.create()
        val entry=moderationEntry(f,head,f.state,f.state.ownerId,id)
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedPolicy(entry))
        val restarted=GroupChatStore(f.records)
        val target=GroupTextV2(groupId=f.state.groupId,epoch=f.state.epoch,
            senderMemberId=f.state.ownerId,logicalId=id,
            governanceActivationDigest=head.activationDigest,governanceSequence=head.sequence,
            governanceHeadDigest=head.headDigest,
            policyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,
                GroupGovernancePolicyRulesV1.initial()),text="late sensitive text")
        val envelope=RandomIdentifiers.create()
        restarted.queueV2(RandomIdentifiers.create(),envelope,target)
        assertTrue(restarted.pendingV2().isEmpty())
        assertNull(restarted.message(f.state.groupId,id))
        assertTrue(f.records.keys("app/notification/${f.state.groupId}/").isEmpty())
        val duplicate=RandomIdentifiers.create()
        restarted.queueV2(RandomIdentifiers.create(),duplicate,target)
        assertTrue(restarted.pendingV2().isEmpty())
        assertTrue(restarted.messages(f.state.groupId).isEmpty())
        assertTrue(restarted.isModerated(f.state.groupId,id))
    }

    @Test fun moderationScrubsAlreadyQueuedTargetInSameTransaction() {
        val f=fixture();val head=install(f);val id=GroupIds.create()
        val target=GroupTextV2(groupId=f.state.groupId,epoch=f.state.epoch,
            senderMemberId=f.state.ownerId,logicalId=id,
            governanceActivationDigest=head.activationDigest,governanceSequence=head.sequence,
            governanceHeadDigest=head.headDigest,
            policyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,
                GroupGovernancePolicyRulesV1.initial()),text="queued secret")
        val chat=GroupChatStore(f.records)
        chat.queueV2(RandomIdentifiers.create(),RandomIdentifiers.create(),target)
        assertEquals(1,chat.pendingV2().size)
        val entry=moderationEntry(f,head,f.state,f.state.ownerId,id)
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedPolicy(entry))
        assertTrue(chat.pendingV2().isEmpty())
        assertNull(GroupChatStore(f.records).message(f.state.groupId,id))
        assertTrue(chat.isModerated(f.state.groupId,id))
        assertTrue(f.records.keys("app/notification/${f.state.groupId}/").isEmpty())
    }

    @Test fun policyAndMembershipShareOneDurableJournalAndMissingMiddleLocksIt() {
        val f=fixture()
        val original=install(f)
        val journal=GroupGovernanceJournalV1(f.records)
        val barrier=GovernanceFoundationStore(f.records).barrier(f.state.groupId)!!
        val first=policyEntry(f,original,f.state,GroupGovernancePolicyRulesV1.initial(),
            GroupPostingModeV1.ADMINS_ONLY)
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedPolicy(first))
        val head1=f.ledger.governanceHead(f.state.groupId)!!
        val transition=profile(f,f.state)
        val second=governanceEntry(f,head1,transition)
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedTransition(f.state.groupId,
            head1,transition,GroupGovernanceV1.entryDigest(second)) {
            journal.append(second,barrier,f.ledger.governanceHead(f.state.groupId)!!)
        })
        val head2=f.ledger.governanceHead(f.state.groupId)!!
        val activePolicy=f.ledger.governancePolicy(f.state.groupId)!!
        assertEquals(GroupPostingModeV1.ADMINS_ONLY,activePolicy.postingMode)
        val third=policyEntry(f,head2,transition.next,activePolicy,GroupPostingModeV1.EVERYONE)
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedPolicy(third))
        val restarted=GroupLedger(f.records,f.trusted,f.state.ownerId)
        assertEquals(3L,restarted.governanceHead(f.state.groupId)?.sequence)
        assertEquals(GroupPostingModeV1.EVERYONE,
            restarted.governancePolicy(f.state.groupId)?.postingMode)
        assertEquals(GovernanceJournalStatus.COMPLETE,
            restarted.governanceJournalStatus(f.state.groupId))
        assertNotNull(journal.policyEntry(f.state.groupId,1))
        assertNotNull(journal.entry(f.state.groupId,2))
        assertNotNull(journal.policyEntry(f.state.groupId,3))
        f.records.remove("app/group/governance-journal-v1/${f.state.groupId}/entry/2")
        assertEquals(GovernanceJournalStatus.LEGACY_INCOMPLETE,
            restarted.governanceJournalStatus(f.state.groupId))
        assertNull(restarted.governancePolicy(f.state.groupId))
    }
    private fun add(f:Fixture):GroupTransition {
        val targetKey=key()
        val target=GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
            targetKey.public.encoded,ByteArray(32),GroupRole.MEMBER,f.state.epoch+1)
        val unsigned=GroupInvite(GroupIds.create(),f.state.groupId,GroupStatements.digest(f.state),
            f.state.revision,f.state.epoch,f.state.ownerId,target,byteArrayOf(),byteArrayOf())
        val offered=unsigned.copy(inviterSignature=sign(f.owner.key,GroupStatements.invite(unsigned)))
        val accepted=offered.copy(targetAcceptance=sign(targetKey,GroupStatements.acceptance(offered)))
        return transition(f,f.state,GroupChange(GroupIds.create(),GroupAction.ADD,f.state.ownerId,
            added=target,invite=accepted))
    }

    @Test fun policyActionsUseOneSignedHeadAndRejectUnauthorizedTargets() {
        val f=fixture(3,3)
        val owner=f.state.ownerId
        val admin=f.state.members.single {it.role==GroupRole.ADMIN}.memberId
        val member=f.state.members.single {it.role==GroupRole.MEMBER}.memberId
        val initial=GroupGovernancePolicyRulesV1.initial()
        assertTrue(GroupGovernancePolicyRulesV1.canSend(initial,f.state,member))
        val admins=GroupGovernancePolicyRulesV1.apply(initial,f.state,owner,
            GroupPolicyActionV1.SET_POSTING_MODE,null,GroupPostingModeV1.ADMINS_ONLY)
        assertTrue(GroupGovernancePolicyRulesV1.canSend(admins,f.state,owner))
        assertTrue(GroupGovernancePolicyRulesV1.canSend(admins,f.state,admin))
        assertFalse(GroupGovernancePolicyRulesV1.canSend(admins,f.state,member))
        val restricted=GroupGovernancePolicyRulesV1.apply(admins,f.state,owner,
            GroupPolicyActionV1.RESTRICT_MEMBER,admin,null)
        assertFalse(GroupGovernancePolicyRulesV1.canSend(restricted,f.state,admin))
        val head=install(f)
        val unsignedRestriction=GroupGovernancePolicyEntryV1(groupId=f.state.groupId,
            activationDigest=head.activationDigest,sequence=head.sequence+1,
            previousHeadDigest=head.headDigest,eventId=GroupIds.create(),
            stateRevision=f.state.revision,stateDigest=GroupStatements.digest(f.state),
            actorId=owner,action=GroupPolicyActionV1.RESTRICT_MEMBER,targetMemberId=admin,
            prePolicyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,initial),
            postPolicyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,
                GroupGovernancePolicyRulesV1.apply(initial,f.state,owner,
                    GroupPolicyActionV1.RESTRICT_MEMBER,admin,null)),
            actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
        val signedRestriction=unsignedRestriction.copy(actorSignature=sign(f.owner.key,
            GroupGovernancePolicyRulesV1.actorStatement(unsignedRestriction)),
            coordinatorSignature=sign(f.owner.key,
                GroupGovernancePolicyRulesV1.coordinatorStatement(unsignedRestriction)))
        println("A7 RESTRICT_MEMBER entry: ${NetworkCodec.encode(signedRestriction).size} bytes")
        assertTrue(GroupGovernancePolicyRulesV1.verifyEntry(signedRestriction,f.state,head,initial))
        assertThrows(IllegalArgumentException::class.java) {
            GroupGovernancePolicyRulesV1.apply(admins,f.state,admin,
                GroupPolicyActionV1.RESTRICT_MEMBER,owner,null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupGovernancePolicyRulesV1.apply(admins,f.state,admin,
                GroupPolicyActionV1.RESTRICT_MEMBER,admin,null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupGovernancePolicyRulesV1.apply(admins,f.state,member,
                GroupPolicyActionV1.SET_POSTING_MODE,null,GroupPostingModeV1.EVERYONE)
        }
        val normalized=GroupGovernancePolicyRulesV1.afterTransition(restricted,
            f.state.copy(ownerId=admin,members=f.state.members.map {
                if(it.memberId==owner) it.copy(role=GroupRole.ADMIN) else if(it.memberId==admin)
                    it.copy(role=GroupRole.OWNER) else it
            }))
        assertTrue(normalized.restrictedMemberIds.isEmpty())
    }

    @Test fun policyEntryAdvancesGovernanceWithoutChangingGroupState() {
        val f=fixture()
        val head=install(f)
        val initial=GroupGovernancePolicyRulesV1.initial()
        val next=GroupGovernancePolicyRulesV1.apply(initial,f.state,f.state.ownerId,
            GroupPolicyActionV1.SET_POSTING_MODE,null,GroupPostingModeV1.ADMINS_ONLY)
        val unsigned=GroupGovernancePolicyEntryV1(groupId=f.state.groupId,
            activationDigest=head.activationDigest,sequence=1,previousHeadDigest=head.headDigest,
            eventId=GroupIds.create(),stateRevision=f.state.revision,
            stateDigest=GroupStatements.digest(f.state),actorId=f.state.ownerId,
            action=GroupPolicyActionV1.SET_POSTING_MODE,postingMode=GroupPostingModeV1.ADMINS_ONLY,
            prePolicyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,initial),
            postPolicyDigest=GroupGovernancePolicyRulesV1.digest(head.activationDigest,next),
            actorSignature=byteArrayOf(),coordinatorSignature=byteArrayOf())
        val entry=unsigned.copy(actorSignature=sign(f.owner.key,
            GroupGovernancePolicyRulesV1.actorStatement(unsigned)),
            coordinatorSignature=sign(f.owner.key,
                GroupGovernancePolicyRulesV1.coordinatorStatement(unsigned)))
        val response=GroupControl(kind=GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V2,
            groupId=f.state.groupId,governanceResyncResponseV2=GroupGovernanceResyncResponseV2(
                groupId=f.state.groupId,activationDigest=head.activationDigest,
                requesterSequence=0,requesterHeadDigest=head.headDigest,
                startSequence=1,startHeadDigest=head.headDigest,policyEntry=entry,
                endSequence=1,endHeadDigest=GroupGovernancePolicyRulesV1.entryDigest(entry),more=false))
        println("A7 SET_MODE entry: ${NetworkCodec.encode(entry).size} bytes")
        println("A7 SET_MODE resync: ${GroupControlCodec.encode(response).size} bytes")
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedPolicy(entry))
        assertEquals(1L,f.ledger.governanceHead(f.state.groupId)?.sequence)
        assertEquals(f.state.revision,f.ledger.state(f.state.groupId)?.revision)
        assertEquals(GroupPostingModeV1.ADMINS_ONLY,
            f.ledger.governancePolicy(f.state.groupId)?.postingMode)
        assertEquals(GovernanceJournalStatus.COMPLETE,f.ledger.governanceJournalStatus(f.state.groupId))
    }

    @Test fun oneEntryResyncFitsControlCapWhileLongerChainsRequireChunks() {
        val f=fixture(5,5)
        var head=install(f)
        var state=f.state
        val entries=mutableListOf<GroupGovernanceEntryV1>()
        repeat(25) {
            val event=profile(f,state)
            val entry=governanceEntry(f,head,event)
            entries+=entry
            state=event.next
            head=head.copy(sequence=entry.sequence,
                headDigest=GroupGovernanceV1.entryDigest(entry),
                stateRevision=entry.postRevision,stateDigest=entry.postDigest)
        }
        for(count in listOf(1,5,10,25)) {
            val slice=entries.take(count)
            val response=GroupGovernanceResyncResponseV1(groupId=f.state.groupId,
                activationDigest=head.activationDigest,requesterSequence=0,
                requesterHeadDigest=head.activationDigest,startSequence=1,
                startHeadDigest=head.activationDigest,entries=slice,
                endSequence=count.toLong(),endHeadDigest=GroupGovernanceV1.entryDigest(slice.last()),
                more=count<25)
            val bytes=NetworkCodec.encode(GroupControl(
                kind=GroupControlKind.GOVERNANCE_RESYNC_RESPONSE_V1,
                groupId=f.state.groupId,governanceResyncResponseV1=response))
            println("A6.3 five-member resync $count entries: ${bytes.size} bytes")
            if(count==1) assertTrue(bytes.size<=GroupControlCodec.MAX_BYTES)
            if(count==25) assertTrue(bytes.size>GroupControlCodec.MAX_BYTES)
        }
    }

    @Test fun fiveMemberAdmissionCheckpointControlsStayBounded() {
        val f=fixture(4,4)
        val head=install(f)
        val targetKey=key()
        val target=GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
            targetKey.public.encoded,ByteArray(32),GroupRole.MEMBER,f.state.epoch+1)
        val inviteId=GroupIds.create()
        val offer=GroupInvite(inviteId,f.state.groupId,GroupStatements.digest(f.state),
            f.state.revision,f.state.epoch,f.state.ownerId,target,byteArrayOf(),byteArrayOf())
        val invite=offer.copy(inviterSignature=sign(f.owner.key,GroupStatements.invite(offer)))
        val accepted=invite.copy(targetAcceptance=sign(targetKey,GroupStatements.acceptance(invite)))
        val proof=GroupAdmission(f.state,inviteId,target,
            sign(f.owner.key,GroupStatements.admission(f.state,inviteId,target)),
            sign(f.owner.key,GroupStatements.admission(f.state,inviteId,target)))
        val unsigned=AdmissionProposalV2(groupId=f.state.groupId,parent=f.state,
            parentDigest=GroupStatements.digest(f.state),inviteId=inviteId,
            inviterId=f.state.ownerId,candidate=target,
            candidateDigest=GroupStatements.digestMember(target),eventId=GroupIds.create(),
            inviterSignature=byteArrayOf())
        val proposal=unsigned.copy(inviterSignature=sign(f.owner.key,
            AdmissionV2.proposalStatement(unsigned)))
        val approvals=f.people.map {person ->
            val approval=AdmissionV2.approval(proposal,person.member.memberId,byteArrayOf())
            approval.copy(signature=sign(person.key,AdmissionV2.approvalStatement(approval)))
        }
        val certificate=AdmissionV2.certificate(proposal,approvals)
        assertTrue(AdmissionV2.verifyCertificate(certificate,f.state))
        val bindingUnsigned=GroupGovernedAdmissionV1.unsignedBinding(certificate,head)
        val binding=bindingUnsigned.copy(coordinatorSignature=sign(f.owner.key,
            GroupGovernedAdmissionV1.bindingStatement(bindingUnsigned)))
        val barrier=GovernanceFoundationStore(f.records).barrier(f.state.groupId)!!
        val checkpointUnsigned=GroupGovernedAdmissionV1.unsignedCheckpoint(binding,proof,
            barrier,f.state,GroupIds.create())
        val checkpoint=checkpointUnsigned.copy(ownerSignature=sign(f.owner.key,
            GroupGovernedAdmissionV1.ownerStatement(checkpointUnsigned)),
            coordinatorSignature=sign(f.owner.key,
                GroupGovernedAdmissionV1.coordinatorStatement(checkpointUnsigned)))
        assertTrue(GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint,f.state,proof,
            certificate,binding))
        val moderatedBeforeJoin=GroupIds.create()
        val filter=GroupModerationFilterV1.empty().also {
            GroupModerationFilterV1.add(it,f.state.groupId,moderatedBeforeJoin)
        }
        val unsignedPolicy=GroupGovernancePolicyCheckpointRulesV1.unsigned(checkpoint,
            GroupGovernancePolicyRulesV1.initial(),filter)
        val policyProof=unsignedPolicy.copy(ownerSignature=sign(f.owner.key,
            GroupGovernancePolicyCheckpointRulesV1.ownerStatement(unsignedPolicy)),
            coordinatorSignature=sign(f.owner.key,
                GroupGovernancePolicyCheckpointRulesV1.coordinatorStatement(unsignedPolicy)))
        assertTrue(GroupGovernancePolicyCheckpointRulesV1.verify(policyProof,checkpoint,f.state))
        assertFalse(GroupGovernancePolicyCheckpointRulesV1.verify(policyProof.copy(
            policyDigest=ByteArray(32)),checkpoint,f.state))
        assertFalse(GroupGovernancePolicyCheckpointRulesV1.verify(policyProof.copy(
            parentHeadDigest=ByteArray(32)),checkpoint,f.state))
        assertFalse(GroupGovernancePolicyCheckpointRulesV1.verify(policyProof.copy(
            moderationFilter=GroupModerationFilterV1.empty()),checkpoint,f.state))
        val evidence=GovernedJoinEvidenceV1(checkpoint,binding,proof,certificate)
        val add=transition(f,f.state,GroupChange(proposal.eventId,GroupAction.ADD,
            f.state.ownerId,added=target,invite=accepted))
        val entry=governanceEntry(f,head,add)
        assertEquals(GroupApply.REJECTED,f.ledger.acceptGovernedBootstrap(evidence,accepted,entry))
        val joiningRecords=Records()
        val joining=GroupLedger(joiningRecords,GroupTrustedPeer {true},target.memberId)
        joiningRecords.failPrefix="app/group/governance-journal-v1/${f.state.groupId}/entry/"
        assertThrows(IllegalStateException::class.java) {
            joining.acceptGovernedBootstrap(evidence,accepted,entry)
        }
        joiningRecords.failPrefix=null
        assertNull(joining.state(f.state.groupId))
        assertNull(joining.governanceHead(f.state.groupId))
        assertEquals(GroupApply.ACCEPTED,
            joining.acceptGovernedBootstrap(evidence,accepted,entry))
        assertEquals(GovernanceJournalStatus.COMPLETE,
            GroupLedger(joiningRecords,GroupTrustedPeer {true},target.memberId)
                .governanceJournalStatus(f.state.groupId))
        assertEquals(GroupApply.DUPLICATE,
            joining.acceptGovernedBootstrap(evidence,accepted,entry))
        val joinedWithPolicy=Records()
        val policyLedger=GroupLedger(joinedWithPolicy,GroupTrustedPeer {true},target.memberId)
        assertEquals(GroupApply.ACCEPTED,policyLedger.acceptGovernedBootstrap(evidence,
            accepted,entry,policyProof))
        assertEquals(GovernanceJournalStatus.COMPLETE,
            policyLedger.governanceJournalStatus(f.state.groupId))
        assertEquals(GroupGovernancePolicyRulesV1.initial(),
            policyLedger.governancePolicy(f.state.groupId))
        assertTrue(GroupChatStore(joinedWithPolicy).isModerated(f.state.groupId,moderatedBeforeJoin))
        assertNull(GroupChatStore(joinedWithPolicy).message(f.state.groupId,moderatedBeforeJoin))
        assertFalse(GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint.copy(
            inviteId=GroupIds.create()),f.state,proof,certificate,binding))
        assertFalse(GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint.copy(
            candidateDigest=ByteArray(32)),f.state,proof,certificate,binding))
        assertFalse(GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint.copy(
            parentHeadDigest=ByteArray(32)),f.state,proof,certificate,binding))
        assertFalse(GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint.copy(
            ownerSignature=ByteArray(64)),f.state,proof,certificate,binding))
        assertFalse(GroupGovernedAdmissionV1.verifyCheckpoint(checkpoint.copy(
            coordinatorSignature=ByteArray(64)),f.state,proof,certificate,binding))
        val controls=mapOf(
            "invitation" to GroupControl(kind=GroupControlKind.INVITE,groupId=f.state.groupId,
                state=f.state,admission=proof,invite=invite,certificateV2=certificate,
                governedAdmissionBindingV1=binding),
            "checkpoint sign" to GroupControl(
                kind=GroupControlKind.GOVERNANCE_CHECKPOINT_SIGN_REQUEST_V1,
                groupId=f.state.groupId,inviteId=inviteId,invite=accepted,admission=proof,
                certificateV2=certificate,governedAdmissionBindingV1=binding,
                governanceCheckpointV1=checkpoint),
            "bootstrap" to GroupControl(kind=GroupControlKind.GOVERNANCE_BOOTSTRAP_V1,
                groupId=f.state.groupId,governanceEntryV1=entry,
                governanceCheckpointV1=checkpoint),
            "resync request" to GroupControl(kind=GroupControlKind.GOVERNANCE_RESYNC_REQUEST_V1,
                groupId=f.state.groupId,governanceResyncRequestV1=GroupGovernanceResyncRequestV1(
                    groupId=f.state.groupId,activationDigest=head.activationDigest,
                    sequence=0,headDigest=head.headDigest,stateRevision=f.state.revision,
                    stateDigest=GroupStatements.digest(f.state),requesterMemberId=f.state.ownerId)))
        for((name,control) in controls) {
            val size=NetworkCodec.encode(control).size
            println("A6.3 five-member $name: $size bytes")
            assertTrue("$name too large: $size",size<=GroupControlCodec.MAX_BYTES)
        }
        println("A6.3 binding: ${NetworkCodec.encode(binding).size} bytes")
        println("A6.3 checkpoint: ${NetworkCodec.encode(checkpoint).size} bytes")
        val bootstrapWithPolicy=GroupControl(kind=GroupControlKind.GOVERNANCE_BOOTSTRAP_V1,
            groupId=f.state.groupId,governanceEntryV1=entry,
            governanceCheckpointV1=checkpoint,governancePolicyCheckpointV1=policyProof)
        val bootstrapSize=GroupControlCodec.encode(bootstrapWithPolicy).size
        println("A7 signed policy proof: ${NetworkCodec.encode(policyProof).size} bytes")
        println("A7 bootstrap with policy proof: $bootstrapSize bytes")
        assertTrue(bootstrapSize<=GroupControlCodec.MAX_BYTES)
        val profile=GroupProfileV1(name="N".repeat(64),about="A".repeat(256),
            photo=GroupProfilePhotoRefV1(digest=ByteArray(32),length=8192))
        val unsignedProfile=GroupProfileCheckpointRulesV1.unsigned(checkpoint,profile)
        val profileProof=unsignedProfile.copy(ownerSignature=sign(f.owner.key,
            GroupProfileCheckpointRulesV1.ownerStatement(unsignedProfile)),
            coordinatorSignature=sign(f.owner.key,
                GroupProfileCheckpointRulesV1.coordinatorStatement(unsignedProfile)))
        println("P13_7_PROOF profileProof=${NetworkCodec.encode(profileProof).size}")
        assertTrue(GroupProfileCheckpointRulesV1.verify(profileProof,checkpoint,f.state))
        val profileBootstrap=bootstrapWithPolicy.copy(groupProfileCheckpointV1=profileProof)
        val profileBootstrapSize=GroupControlCodec.encode(profileBootstrap).size
        val profileFrame=ConversationPayload.encodeGroup(profileBootstrap).size
        println("P13_7_BOOTSTRAP old=$bootstrapSize profileProof=${NetworkCodec.encode(profileProof).size} new=$profileBootstrapSize frame=$profileFrame headroom=${GroupControlCodec.MAX_BYTES-profileBootstrapSize}")
        assertTrue(profileBootstrapSize<=GroupControlCodec.MAX_BYTES)
        assertTrue(profileFrame<=16_384)
    }

    @Test fun groupTextV2BindsExactHeadAndFitsEncryptedContentLimit() {
        val f=fixture()
        val head=install(f)
        val policy=GroupGovernancePolicyRulesV1.initial()
        val binding=GroupTextV2Binding(head.activationDigest,head.sequence,head.headDigest,
            GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy))
        assertTrue(binding.matches(head,policy))
        assertFalse(binding.matches(head.copy(sequence=head.sequence+1),policy))
        assertFalse(binding.matches(head.copy(headDigest=ByteArray(32)),policy))
        val text=GroupTextV2(groupId=f.state.groupId,epoch=f.state.epoch,
            senderMemberId=f.state.ownerId,logicalId=GroupIds.create(),
            governanceActivationDigest=head.activationDigest,governanceSequence=head.sequence,
            governanceHeadDigest=head.headDigest,policyDigest=binding.policyDigest,
            text="x".repeat(GroupTextV2Codec.MAX_TEXT_BYTES))
        val body=GroupTextV2Codec.encode(text)
        val padded=ConversationPayload.encodeGroupTextV2(text)
        val pendingBytes=NetworkCodec.encode(PendingGroupTextV2(RandomIdentifiers.create(),text))
        println("A7 maximum text: ${body.size} frame bytes, ${padded.size} padded bytes")
        println("A7 maximum pending text: ${pendingBytes.size} bytes")
        assertTrue(body.size<=GroupTextV2Codec.MAX_BYTES)
        assertTrue(padded.size<=4096)
        assertTrue(pendingBytes.size<=4096)
        assertEquals(text.text,ConversationPayload.decode(padded).groupTextV2?.text)
        assertThrows(IllegalArgumentException::class.java) {
            GroupTextV2Codec.encode(text.copy(text="x".repeat(GroupTextV2Codec.MAX_TEXT_BYTES+1)))
        }
    }

    @Test fun futureTextAndOutgoingHeadBindingSurviveRestartWithoutDuplicateDisplay() {
        val f=fixture()
        val head=install(f)
        val policy=GroupGovernancePolicyRulesV1.initial()
        val binding=GroupTextV2Binding(head.activationDigest,head.sequence,head.headDigest,
            GroupGovernancePolicyRulesV1.digest(head.activationDigest,policy))
        val store=GroupChatStore(f.records)
        val outgoingId=GroupIds.create()
        val recipient=RandomIdentifiers.create()
        store.createV2(GroupChatMessage(f.state.groupId,outgoingId,f.state.epoch,
            f.state.ownerId,true,"queued",store.nextOrder(),listOf(GroupRecipient(recipient))),binding)
        val restarted=GroupChatStore(f.records)
        assertTrue(restarted.binding(f.state.groupId,outgoingId)!!.matches(head,policy))
        restarted.cancelStaleHead(f.state.groupId,head.copy(sequence=1,
            headDigest=DeviceAuth.digest(byteArrayOf(11))),policy)
        assertEquals(GroupRecipientState.UNAVAILABLE,
            restarted.message(f.state.groupId,outgoingId)!!.recipients.single().state)

        val frame=GroupTextV2(groupId=f.state.groupId,epoch=f.state.epoch,
            senderMemberId=f.state.ownerId,logicalId=GroupIds.create(),
            governanceActivationDigest=head.activationDigest,governanceSequence=head.sequence+2,
            governanceHeadDigest=DeviceAuth.digest(byteArrayOf(12)),
            policyDigest=binding.policyDigest,text="future")
        val envelope=RandomIdentifiers.create()
        restarted.queueV2(f.owner.member.deviceId,envelope,frame)
        val afterRestart=GroupChatStore(f.records)
        assertEquals(1,afterRestart.pendingV2().size)
        assertTrue(afterRestart.acceptV2(envelope,afterRestart.pendingV2().single().second))
        assertEquals(2,afterRestart.messages(f.state.groupId).size)
        val duplicate=RandomIdentifiers.create()
        afterRestart.queueV2(f.owner.member.deviceId,duplicate,frame.copy(policyDigest=ByteArray(32)))
        assertFalse(afterRestart.acceptV2(duplicate,afterRestart.pendingV2().single().second))
        assertEquals(2,afterRestart.messages(f.state.groupId).size)
    }

    @Test fun barrierRequiresExactActiveA5AndIsDurableAndIdempotent() {
        val f=fixture()
        val activation=DeviceAuth.digest(byteArrayOf(4,5,6))
        assertEquals(GroupApply.REJECTED,f.ledger.installGovernanceBarrierForFutureActivation(
            f.state.groupId,activation))
        val head=install(f)
        assertEquals(0L,head.sequence)
        assertEquals(f.state.revision,head.stateRevision)
        assertEquals(GroupApply.DUPLICATE,GroupLedger(f.records,f.trusted,f.state.ownerId)
            .installGovernanceBarrierForFutureActivation(f.state.groupId,activation))
        assertEquals(GroupApply.REJECTED,f.ledger.installGovernanceBarrierForFutureActivation(
            f.state.groupId,ByteArray(32)))
        assertEquals(GroupApply.REJECTED,GroupLedger(f.records,f.trusted,f.state.ownerId)
            .apply(f.state.groupId,profile(f,f.state)))
        assertNotNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
    }

    @Test fun staleBaselineCannotInstallBarrier() {
        val f=fixture();baseline(f)
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,profile(f,f.state)))
        assertEquals(GroupApply.REJECTED,f.ledger.installGovernanceBarrierForFutureActivation(
            f.state.groupId,DeviceAuth.digest(byteArrayOf(4,5,6))))
        assertNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
    }

    @Test fun rawMutationAndLegacyAddAreFencedButPreBarrierAddWorks() {
        val old=fixture();val legacyAdd=add(old)
        assertEquals(GroupApply.ACCEPTED,old.ledger.apply(old.state.groupId,legacyAdd))
        val f=fixture();val event=add(f);install(f)
        var sideEffect=false
        assertEquals(GroupApply.REJECTED,f.ledger.apply(f.state.groupId,event) {sideEffect=true})
        assertFalse(sideEffect)
        assertEquals(f.state.revision,f.ledger.state(f.state.groupId)!!.revision)
        assertEquals(GroupApply.REJECTED,GroupLedger(f.records,f.trusted,f.state.ownerId)
            .apply(f.state.groupId,event))
    }

    @Test fun legacyAdmissionCannotReplaceBarrierAndGenesisCannotEraseIt() {
        val f=fixture();install(f)
        val before=f.records.read("group/state/v1/${f.state.groupId}")!!
        val head=GovernanceFoundationStore(f.records).head(f.state.groupId)!!
        val proof=GroupAdmission(f.state,GroupIds.create(),f.state.members.single(),
            ByteArray(64),ByteArray(64))
        assertEquals(GroupApply.REJECTED,f.ledger.acceptAdmission(proof))
        val signed=GroupGenesis(f.state,sign(f.owner.key,GroupStatements.genesis(f.state)))
        assertEquals(GroupApply.DUPLICATE,f.ledger.acceptGenesis(signed))
        assertArrayEquals(before,f.records.read("group/state/v1/${f.state.groupId}"))
        assertTrue(GovernanceFoundationStore(f.records).matches(head,
            GovernanceFoundationStore(f.records).head(f.state.groupId)!!))
        assertNotNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
    }

    @Test fun allExistingRawManagementActionsStopAtBarrier() {
        val f=fixture(3,10);install(f)
        val admin=f.state.members.single {it.role==GroupRole.ADMIN}.memberId
        val member=f.state.members.single {it.role==GroupRole.MEMBER}.memberId
        val changes=listOf(
            GroupChange(GroupIds.create(),GroupAction.REMOVE,f.state.ownerId,targetId=member),
            GroupChange(GroupIds.create(),GroupAction.PROMOTE,f.state.ownerId,targetId=member),
            GroupChange(GroupIds.create(),GroupAction.DEMOTE,f.state.ownerId,targetId=admin),
            GroupChange(GroupIds.create(),GroupAction.DISSOLVE,f.state.ownerId))
        changes.forEach {change ->
            val event=transition(f,f.state,change)
            assertEquals(change.action.toString(),GroupApply.REJECTED,
                f.ledger.apply(f.state.groupId,event))
        }
        assertEquals(10L,f.ledger.state(f.state.groupId)!!.revision)
    }

    @Test fun legacySnapshotCanReachExactAnchorButCannotCrossIt() {
        val f=fixture(1,10);val original=f.records.read("group/state/v1/${f.state.groupId}")!!
        val eleven=profile(f,f.state)
        val twelve=profile(f,eleven.next)
        val thirteen=profile(f,twelve.next)
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,eleven))
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,twelve))
        val anchor=Fixture(f.records,twelve.next,f.people,f.ledger)
        install(anchor)
        f.records.write("group/state/v1/${f.state.groupId}",original)
        assertThrows(IllegalStateException::class.java) {f.ledger.state(f.state.groupId)}
        assertEquals(GroupApply.REJECTED,f.ledger.applySnapshot(f.state.groupId,
            GroupSnapshot(thirteen.next,listOf(eleven,twelve,thirteen))))
        assertEquals(10L,f.ledger.stateForLegacyReplay(f.state.groupId)!!.revision)
        assertEquals(GroupApply.ACCEPTED,f.ledger.applySnapshot(f.state.groupId,
            GroupSnapshot(twelve.next,listOf(eleven,twelve))))
        assertEquals(12L,f.ledger.state(f.state.groupId)!!.revision)
        assertEquals(GroupApply.REJECTED,f.ledger.applySnapshot(f.state.groupId,
            GroupSnapshot(thirteen.next,listOf(thirteen))))
    }

    @Test fun invalidClaimAtAnchorCannotForceForkButValidAlternateAnchorDoes() {
        val f=fixture(1,10)
        val eleven=profile(f,f.state)
        val twelve=profile(f,eleven.next)
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,eleven))
        val atEleven=f.records.read("group/state/v1/${f.state.groupId}")!!
        assertEquals(GroupApply.ACCEPTED,f.ledger.apply(f.state.groupId,twelve))
        install(Fixture(f.records,twelve.next,f.people,f.ledger))
        f.records.write("group/state/v1/${f.state.groupId}",atEleven)
        val alternate=transition(f,eleven.next,GroupChange(GroupIds.create(),GroupAction.PROFILE,
            f.state.ownerId,newProfileRevision=eleven.next.profileRevision+1,
            newProfileDigest=DeviceAuth.digest(byteArrayOf(98))))
        val forged=alternate.copy(actorSignature=ByteArray(64))
        assertEquals(GroupApply.REJECTED,f.ledger.apply(f.state.groupId,forged))
        assertFalse(f.ledger.isForked(f.state.groupId))
        assertEquals(GroupApply.FORKED,f.ledger.apply(f.state.groupId,alternate))
        assertTrue(f.ledger.isForked(f.state.groupId))
        assertNotNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
    }

    @Test fun incompleteBoundaryAndInstallerWriteFailureFailClosed() {
        val f=fixture();baseline(f)
        val groupId=f.state.groupId
        f.records.failPrefix="app/group/governance-foundation-v1/barrier/"
        assertThrows(IllegalStateException::class.java) {
            f.ledger.installGovernanceBarrierForFutureActivation(groupId,ByteArray(32))
        }
        f.records.failPrefix=null
        assertNull(GovernanceFoundationStore(f.records).head(groupId))
        assertNull(GovernanceFoundationStore(f.records).barrier(groupId))
        assertEquals(GroupApply.ACCEPTED,f.ledger.installGovernanceBarrierForFutureActivation(
            groupId,DeviceAuth.digest(byteArrayOf(4,5,6))))
        f.records.remove("app/group/governance-foundation-v1/barrier/$groupId")
        assertThrows(IllegalStateException::class.java) {f.ledger.state(groupId)}
        assertThrows(IllegalStateException::class.java) {f.ledger.apply(groupId,profile(f,f.state))}
    }

    @Test fun governedTransitionAndHeadCommitOrRollbackTogether() {
        val f=fixture();val head=install(f);val event=profile(f,f.state)
        val nextDigest=DeviceAuth.digest(byteArrayOf(7,8,9))
        val groupKey="group/state/v1/${f.state.groupId}"
        val beforeState=f.records.read(groupKey)!!
        val headKey="app/group/governance-foundation-v1/head/${f.state.groupId}"
        val beforeHead=f.records.read(headKey)!!
        for(failingPrefix in listOf("app/group/governance-foundation-v1/head/","group/state/v1/")) {
            f.records.failPrefix=failingPrefix
            assertThrows(IllegalStateException::class.java) {
                f.ledger.applyGovernedTransition(f.state.groupId,head,event,nextDigest)
            }
            assertArrayEquals(beforeState,f.records.read(groupKey))
            assertArrayEquals(beforeHead,f.records.read(headKey))
            f.records.failPrefix=null
        }
        f.records.failCommit=true
        assertThrows(IllegalStateException::class.java) {
            f.ledger.applyGovernedTransition(f.state.groupId,head,event,nextDigest)
        }
        f.records.failCommit=false
        assertArrayEquals(beforeState,f.records.read(groupKey))
        assertArrayEquals(beforeHead,f.records.read(headKey))
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedTransition(
            f.state.groupId,head,event,nextDigest))
        val after=GovernanceFoundationStore(f.records).head(f.state.groupId)!!
        assertEquals(1L,after.sequence)
        assertEquals(event.next.revision,f.ledger.state(f.state.groupId)!!.revision)
        assertEquals(GroupApply.REJECTED,f.ledger.applyGovernedTransition(
            f.state.groupId,head,event,nextDigest))
    }

    @Test fun wrongAnchorAndConflictingGovernedSuccessorFailClosed() {
        val f=fixture();val head=install(f)
        val event=profile(f,f.state)
        val rival=transition(f,f.state,GroupChange(GroupIds.create(),GroupAction.PROFILE,
            f.state.ownerId,newProfileRevision=1,
            newProfileDigest=DeviceAuth.digest(byteArrayOf(99))))
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedTransition(f.state.groupId,
            head,event,DeviceAuth.digest(byteArrayOf(1))))
        val nextHead=GovernanceFoundationStore(f.records).head(f.state.groupId)!!
        assertEquals(GroupApply.FORKED,f.ledger.applyGovernedTransition(f.state.groupId,
            nextHead,rival,DeviceAuth.digest(byteArrayOf(2))))
        assertTrue(f.ledger.isForked(f.state.groupId))
        assertNotNull(GovernanceFoundationStore(f.records).barrier(f.state.groupId))
        assertEquals(GroupApply.REJECTED,f.ledger.apply(f.state.groupId,profile(f,event.next)))

        val other=fixture();install(other)
        val wrong=other.state.copy(profileDigest=DeviceAuth.digest(byteArrayOf(1)))
        other.records.write("group/state/v1/${other.state.groupId}",NetworkCodec.encode(
            GroupRecord(wrong,genesis=GroupGenesis(other.state,
                sign(other.owner.key,GroupStatements.genesis(other.state))))))
        assertThrows(IllegalStateException::class.java) {other.ledger.state(other.state.groupId)}
    }
    @Test fun activationEvidenceAndEntryEvidenceRollbackWithLedgerBoundary() {
        val f=fixture();baseline(f)
        val id=f.state.groupId
        val activation=DeviceAuth.digest(byteArrayOf(81))
        val evidenceKey="app/group/governance-v1/commit/$id"
        f.records.failPrefix="app/group/governance-v1/commit/"
        assertThrows(IllegalStateException::class.java) {
            f.ledger.installGovernanceBarrierForFutureActivation(id,activation) {
                f.records.write(evidenceKey,byteArrayOf(1))
            }
        }
        assertNull(GovernanceFoundationStore(f.records).barrier(id))
        assertNull(GovernanceFoundationStore(f.records).head(id))
        f.records.failPrefix=null
        assertEquals(GroupApply.ACCEPTED,f.ledger.installGovernanceBarrierForFutureActivation(
            id,activation) {f.records.write(evidenceKey,byteArrayOf(1))})
        assertArrayEquals(byteArrayOf(1),f.records.read(evidenceKey))
        val head=checkNotNull(GovernanceFoundationStore(f.records).head(id))
        val event=profile(f,f.state)
        val entryKey="app/group/governance-v1/entry/$id"
        f.records.failPrefix="app/group/governance-v1/entry/"
        assertThrows(IllegalStateException::class.java) {
            f.ledger.applyGovernedTransition(id,head,event,DeviceAuth.digest(byteArrayOf(82))) {
                f.records.write(entryKey,byteArrayOf(2))
            }
        }
        assertEquals(f.state.revision,f.ledger.state(id)!!.revision)
        assertTrue(GovernanceFoundationStore(f.records).matches(head,
            checkNotNull(GovernanceFoundationStore(f.records).head(id))))
        f.records.failPrefix=null
    }
    @Test fun missingA62JournalLocksOnlyGovernanceAndRetainedEntriesSurviveRestart() {
        val old=fixture();val oldHead=install(old)
        val oldEvent=profile(old,old.state)
        assertEquals(GovernanceJournalStatus.COMPLETE,
            old.ledger.governanceJournalStatus(old.state.groupId))
        assertEquals(GroupApply.ACCEPTED,old.ledger.applyGovernedTransition(old.state.groupId,
            oldHead,oldEvent,DeviceAuth.digest(byteArrayOf(61))))
        assertEquals(GovernanceJournalStatus.LEGACY_INCOMPLETE,
            old.ledger.governanceJournalStatus(old.state.groupId))
        assertEquals(oldEvent.next.revision,old.ledger.state(old.state.groupId)!!.revision)
        val oldNext=profile(old,oldEvent.next)
        assertEquals(GroupApply.REJECTED,old.ledger.applyGovernedTransition(old.state.groupId,
            checkNotNull(old.ledger.governanceHead(old.state.groupId)),oldNext,
            DeviceAuth.digest(byteArrayOf(62))))
        assertNull(GroupLedger(old.records,old.trusted,old.state.ownerId)
            .governanceJournalStatus(GroupIds.create()))

        val fresh=fixture();val id=fresh.state.groupId;install(fresh)
        val journal=GroupGovernanceJournalV1(fresh.records)
        assertEquals(GovernanceJournalStatus.COMPLETE,fresh.ledger.governanceJournalStatus(id))
        repeat(3) {index ->
            val head=checkNotNull(fresh.ledger.governanceHead(id))
            val event=profile(fresh,checkNotNull(fresh.ledger.state(id)))
            val entry=governanceEntry(fresh,head,event)
            assertTrue(GroupGovernanceV1.verifyEntry(entry,checkNotNull(fresh.ledger.state(id)),head))
            assertEquals(GroupApply.ACCEPTED,fresh.ledger.applyGovernedTransition(id,head,event,
                GroupGovernanceV1.entryDigest(entry)) {
                journal.append(entry,checkNotNull(fresh.ledger.governanceBarrier(id)),
                    checkNotNull(fresh.ledger.governanceHead(id)))
            })
            assertEquals((index+1).toLong(),checkNotNull(journal.entry(id,index+1L)).sequence)
            assertEquals(GovernanceJournalStatus.COMPLETE,fresh.ledger.governanceJournalStatus(id))
        }
        val restarted=GroupLedger(fresh.records,fresh.trusted,fresh.state.ownerId)
        assertEquals(GovernanceJournalStatus.COMPLETE,restarted.governanceJournalStatus(id))
        assertNotNull(journal.entry(id,1))
        assertNotNull(journal.entry(id,2))
        assertNotNull(journal.entry(id,3))
        fresh.records.remove("app/group/governance-journal-v1/$id/entry/2")
        assertEquals(GovernanceJournalStatus.LEGACY_INCOMPLETE,
            restarted.governanceJournalStatus(id))
        assertEquals(4L,restarted.state(id)!!.revision)
    }

    @Test fun conflictingSignedGovernanceSuccessorForksWithoutReplacingJournal() {
        val f=fixture();val id=f.state.groupId;val head=install(f)
        val first=profile(f,f.state)
        val entry=governanceEntry(f,head,first)
        val journal=GroupGovernanceJournalV1(f.records)
        assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedTransition(id,head,first,
            GroupGovernanceV1.entryDigest(entry)) {
            journal.append(entry,checkNotNull(f.ledger.governanceBarrier(id)),
                checkNotNull(f.ledger.governanceHead(id)))
        })
        val rival=transition(f,f.state,GroupChange(GroupIds.create(),GroupAction.PROFILE,
            f.state.ownerId,newProfileRevision=1,
            newProfileDigest=DeviceAuth.digest(byteArrayOf(99))))
        val rivalEntry=governanceEntry(f,head,rival)
        assertEquals(GroupApply.FORKED,f.ledger.markGovernanceForkIfValid(rivalEntry))
        assertTrue(f.ledger.isForked(id))
        assertArrayEquals(GroupGovernanceV1.entryDigest(entry),
            GroupGovernanceV1.entryDigest(checkNotNull(journal.entry(id,1))))
    }

    @Test fun journalWriteFailureRollsBackGovernedStateAndHead() {
        val f=fixture();val id=f.state.groupId;val head=install(f)
        val event=profile(f,f.state)
        val entry=governanceEntry(f,head,event)
        val before=f.records.read("group/state/v1/$id")!!
        f.records.failPrefix="app/group/governance-journal-v1/$id/entry/"
        assertThrows(IllegalStateException::class.java) {
            f.ledger.applyGovernedTransition(id,head,event,
                GroupGovernanceV1.entryDigest(entry)) {
                GroupGovernanceJournalV1(f.records).append(entry,
                    checkNotNull(f.ledger.governanceBarrier(id)),
                    checkNotNull(f.ledger.governanceHead(id)))
            }
        }
        f.records.failPrefix=null
        assertArrayEquals(before,f.records.read("group/state/v1/$id"))
        assertEquals(0L,checkNotNull(f.ledger.governanceHead(id)).sequence)
        assertNull(GroupGovernanceJournalV1(f.records).entry(id,1))
    }

    @Test fun threeEntryReplayResumesAfterRestartWithoutSkippingAHead() {
        val source=fixture();val id=source.state.groupId;install(source)
        val targetRecords=Records()
        source.records.values.forEach {(key,value)->targetRecords.values[key]=value.copyOf()}
        val entries=mutableListOf<GroupGovernanceEntryV1>()
        repeat(3) {
            val head=checkNotNull(source.ledger.governanceHead(id))
            val event=profile(source,checkNotNull(source.ledger.state(id)))
            val entry=governanceEntry(source,head,event)
            assertEquals(GroupApply.ACCEPTED,source.ledger.applyGovernedTransition(id,head,event,
                GroupGovernanceV1.entryDigest(entry)) {
                GroupGovernanceJournalV1(source.records).append(entry,
                    checkNotNull(source.ledger.governanceBarrier(id)),
                    checkNotNull(source.ledger.governanceHead(id)))
            })
            entries+=entry
        }
        fun replay(entry:GroupGovernanceEntryV1):Boolean {
            val ledger=GroupLedger(targetRecords,source.trusted,source.state.ownerId)
            val head=checkNotNull(ledger.governanceHead(id))
            val parent=checkNotNull(ledger.state(id))
            if(!GroupGovernanceV1.verifyEntry(entry,parent,head)) return false
            return ledger.applyGovernedTransition(id,head,entry.transition,
                GroupGovernanceV1.entryDigest(entry)) {
                GroupGovernanceJournalV1(targetRecords).append(entry,
                    checkNotNull(ledger.governanceBarrier(id)),
                    checkNotNull(ledger.governanceHead(id)))
            }==GroupApply.ACCEPTED
        }
        assertTrue(replay(entries[0]))
        assertFalse(replay(entries[0]))
        assertFalse(replay(entries[2]))
        val restarted=GroupLedger(targetRecords,source.trusted,source.state.ownerId)
        assertEquals(GovernanceJournalStatus.COMPLETE,restarted.governanceJournalStatus(id))
        assertEquals(1L,checkNotNull(restarted.governanceHead(id)).sequence)
        assertTrue(replay(entries[1]))
        assertTrue(replay(entries[2]))
        assertEquals(GovernanceJournalStatus.COMPLETE,
            GroupLedger(targetRecords,source.trusted,source.state.ownerId).governanceJournalStatus(id))
        assertEquals(3L,checkNotNull(GroupLedger(targetRecords,source.trusted,
            source.state.ownerId).governanceHead(id)).sequence)
    }

    @Test fun historicalAdminSignatureReplaysOnlyBeforeItsRemoval() {
        val original=fixture();val id=original.state.groupId
        val added=List(2) {index ->
            val pair=key()
            Person(GroupMember(GroupIds.create(),RandomIdentifiers.create(),RandomIdentifiers.create(),
                pair.public.encoded,DeviceAuth.digest(byteArrayOf(index.toByte())),
                GroupRole.MEMBER,original.ledger.state(id)!!.epoch+index+1),pair)
        }
        for(person in added) {
            val prior=checkNotNull(original.ledger.state(id))
            val target=person.member.copy(joinedEpoch=prior.epoch+1)
            val offer=GroupInvite(GroupIds.create(),id,GroupStatements.digest(prior),
                prior.revision,prior.epoch,prior.ownerId,target,byteArrayOf(),byteArrayOf())
            val invited=offer.copy(inviterSignature=sign(original.owner.key,
                GroupStatements.invite(offer)))
            val accepted=invited.copy(targetAcceptance=sign(person.key,
                GroupStatements.acceptance(invited)))
            val event=transition(original,prior,GroupChange(GroupIds.create(),GroupAction.ADD,
                prior.ownerId,added=target,invite=accepted))
            assertEquals(GroupApply.ACCEPTED,original.ledger.apply(id,event))
        }
        val beforePromotion=checkNotNull(original.ledger.state(id))
        assertEquals(GroupApply.ACCEPTED,original.ledger.apply(id,transition(original,
            beforePromotion,GroupChange(GroupIds.create(),GroupAction.PROMOTE,
                beforePromotion.ownerId,targetId=added[0].member.memberId))))
        val start=checkNotNull(original.ledger.state(id))
        val f=Fixture(original.records,start,original.people+added,original.ledger)
        install(f)
        val targetRecords=Records()
        f.records.values.forEach {(key,value)->targetRecords.values[key]=value.copyOf()}
        val historicalId=added[0].member.memberId
        val adminEvent=transition(f,start,GroupChange(GroupIds.create(),GroupAction.PROFILE,
            historicalId,newProfileRevision=start.profileRevision+1,
            newProfileDigest=DeviceAuth.digest(byteArrayOf(76))))
        val removal=transition(f,adminEvent.next,GroupChange(GroupIds.create(),GroupAction.REMOVE,
            f.state.ownerId,targetId=historicalId))
        val finalEvent=profile(f,removal.next)
        val events=listOf(adminEvent,removal,finalEvent)
        val entries=mutableListOf<GroupGovernanceEntryV1>()
        for(event in events) {
            val head=checkNotNull(f.ledger.governanceHead(id))
            val entry=governanceEntry(f,head,event)
            assertEquals(GroupApply.ACCEPTED,f.ledger.applyGovernedTransition(id,head,event,
                GroupGovernanceV1.entryDigest(entry)) {
                GroupGovernanceJournalV1(f.records).append(entry,
                    checkNotNull(f.ledger.governanceBarrier(id)),
                    checkNotNull(f.ledger.governanceHead(id)))
            })
            entries+=entry
        }
        val currentTrust=GroupTrustedPeer {it.memberId!=historicalId}
        val historical:(GroupMember,Long)->Boolean={member,revision ->
            member.memberId==historicalId && revision==start.revision
        }
        for(entry in entries) {
            val ledger=GroupLedger(targetRecords,currentTrust,f.state.ownerId)
            val head=checkNotNull(ledger.governanceHead(id))
            assertTrue(GroupGovernanceV1.verifyEntry(entry,checkNotNull(ledger.state(id)),head))
            assertEquals(GroupApply.ACCEPTED,ledger.applyGovernedTransition(id,head,
                entry.transition,GroupGovernanceV1.entryDigest(entry),historical) {
                GroupGovernanceJournalV1(targetRecords).append(entry,
                    checkNotNull(ledger.governanceBarrier(id)),
                    checkNotNull(ledger.governanceHead(id)))
            })
        }
        assertEquals(GovernanceJournalStatus.COMPLETE,
            GroupLedger(targetRecords,currentTrust,f.state.ownerId).governanceJournalStatus(id))
        assertFalse(historical(added[0].member,removal.next.revision))
        assertThrows(NoSuchElementException::class.java) {
            GroupRules.derive(removal.next,GroupChange(GroupIds.create(),GroupAction.PROFILE,
                historicalId,newProfileRevision=removal.next.profileRevision+1,
                newProfileDigest=DeviceAuth.digest(byteArrayOf(77))),emptySet(),currentTrust)
        }
    }
}
