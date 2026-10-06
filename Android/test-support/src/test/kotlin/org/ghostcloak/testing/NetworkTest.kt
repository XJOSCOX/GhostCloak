package org.ghostcloak.testing

import kotlinx.coroutines.runBlocking
import org.ghostcloak.backend.*
import org.ghostcloak.crypto.*
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.messaging.*
import org.ghostcloak.protocol.*
import org.ghostcloak.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors

class NetworkTest {
    @Test fun existingBaselineSurvivesProspectiveAdmissionAndRemoval() = runBlocking {
        Fixture().use {f ->
            val people=listOf(f.person("alice",true),f.person("bob",true),
                f.person("charlie",true),f.person("dana",true))
            val (a,b,c,d)=people
            val repos=people.associateWith {LocalRepository(it.records)}
            val outboxes=people.associateWith {p ->DurableOutbox(p.records,p.engine,
                NetworkMailboxTransport(f.client(p),p.state))}
            fun group(p:Person)=GroupMembershipTransport(p.records,repos.getValue(p),p.engine,p.state,
                GroupAuthorityResolver(repos.getValue(p),p.engine,f.client(p),p.records),outboxes.getValue(p))
            val conversations=people.associateWith {p ->ConversationService(p.engine,repos.getValue(p)).also {it.open()}}
            for(p in people) for(q in people) if(p!=q) {
                p.engine.establishSession(q.engine.publicBundle())
                p.state.remember(SenderProfile(q.registration.accountId,q.registration.deviceId,
                    q.registration.routingId,q.state.ghostCloakId()))
                repos.getValue(p).save(Contact(RandomIdentifiers.create(),q.registration.accountId,q.name,q.registration.deviceId))
                repos.getValue(p).groupPeer(q.registration.deviceId,true)
            }
            suspend fun push(p:Person) {
                val outbox=outboxes.getValue(p)
                outbox.pendingIds().forEach {id ->
                    if(outbox.get(id).state!=OutboxState.SERVER_ACCEPTED) outbox.process(id)
                }
            }
            suspend fun pull(p:Person) {
                val mailbox=NetworkMailboxTransport(f.client(p),p.state)
                val deliveries=mailbox.fetch()
                deliveries.forEach {conversations.getValue(p).acceptNetwork(EnvelopeCodec.decode(it.encryptedEnvelope))}
                if(deliveries.isNotEmpty())
                    mailbox.acknowledgeAccepted(deliveries.map {it.serverMessageId})
                group(p).processPending()
            }
            val id=group(a).createAndInvite(b.registration.deviceId)
            push(a);pull(b);group(b).accept(group(b).invitations().single().id)
            push(b);pull(a);push(a);pull(b)
            group(a).invite(id,c.registration.deviceId)
            push(a);pull(c);group(c).accept(group(c).invitations().single().id)
            push(c);pull(a);push(a);pull(b);pull(c)
            val before=group(a).state(id)!!
            assertEquals(3,before.members.size)
            group(a).beginAuthorityBaseline(id)
            push(a);pull(b);pull(c)
            push(b);push(c);pull(a)
            push(a);pull(b);pull(c)
            push(b);push(c);pull(a)
            push(a);pull(b);pull(c)
            for(p in listOf(a,b,c)) assertNotNull(p.records.read("app/group/baseline-v1/active/$id"))
            val aMember=before.members.single {it.deviceId==a.registration.deviceId}
            for(p in listOf(a,b,c)) {
                for(member in before.members)
                    assertNotNull(p.records.read("app/group/historical-existing-baseline/$id/${member.memberId}"))
            }
            for(p in people) for(q in people) if(p!=q)
                repos.getValue(p).admissionV2Peer(q.registration.deviceId,true)
            group(a).invite(id,d.registration.deviceId)
            push(a);pull(b);pull(c)
            push(b);push(c);pull(a)
            push(a);pull(d)
            assertEquals(1,group(d).invitations().size)
            group(d).accept(group(d).invitations().single().id)
            push(d);pull(a)
            push(a);pull(b);pull(c);pull(d)
            val after=group(a).state(id)!!
            assertEquals(4,after.members.size)
            val added=after.members.single {it.deviceId==d.registration.deviceId}
            for(p in listOf(a,b,c)) {
                assertNotNull(p.records.read("app/group/historical-existing-baseline/$id/${aMember.memberId}"))
                assertNotNull(p.records.read("app/group/historical-authority/$id/${added.memberId}"))
            }
            assertNotNull(d.records.read("app/group/historical-baseline/$id/${aMember.memberId}"))
            group(a).removeMember(id,added.memberId)
            assertNotNull(a.records.read("app/group/historical-authority/$id/${added.memberId}"))
            assertNull(a.records.read("app/group/current-authority/$id/${added.memberId}"))
        }
    }
    @Test fun baselineWaitsForBlockedMemberAndActivatesAllAnchorsAfterRestart() = runBlocking {
        Fixture().use {f ->
            val a=f.person("alice",true);val b=f.person("bob",true)
            val ar=LocalRepository(a.records);val br=LocalRepository(b.records)
            val ao=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(f.client(a),a.state))
            val bo=DurableOutbox(b.records,b.engine,NetworkMailboxTransport(f.client(b),b.state))
            fun aGroup()=GroupMembershipTransport(a.records,ar,a.engine,a.state,
                GroupAuthorityResolver(ar,a.engine,f.client(a),a.records),ao)
            fun bGroup()=GroupMembershipTransport(b.records,br,b.engine,b.state,
                GroupAuthorityResolver(br,b.engine,f.client(b),b.records),bo)
            val ac=ConversationService(a.engine,ar).also {it.open()}
            val bc=ConversationService(b.engine,br).also {it.open()}
            for((p,q,repo) in listOf(Triple(a,b,ar),Triple(b,a,br))) {
                p.engine.establishSession(q.engine.publicBundle())
                p.state.remember(SenderProfile(q.registration.accountId,q.registration.deviceId,
                    q.registration.routingId,q.state.ghostCloakId()))
                repo.save(Contact(RandomIdentifiers.create(),q.registration.accountId,q.name,q.registration.deviceId))
                repo.groupPeer(q.registration.deviceId,true)
            }
            suspend fun push(outbox:DurableOutbox) {
                outbox.pendingIds().forEach {id ->
                    if(outbox.get(id).state!=OutboxState.SERVER_ACCEPTED) outbox.process(id)
                }
            }
            suspend fun pull(p:Person,conversation:ConversationService,group:GroupMembershipTransport) {
                val mailbox=NetworkMailboxTransport(f.client(p),p.state)
                val deliveries=mailbox.fetch()
                deliveries.forEach {conversation.acceptNetwork(EnvelopeCodec.decode(it.encryptedEnvelope))}
                mailbox.acknowledgeAccepted(deliveries.map {it.serverMessageId})
                group.processPending()
            }
            val groupId=aGroup().createAndInvite(b.registration.deviceId)
            push(ao);pull(b,bc,bGroup())
            bGroup().accept(bGroup().invitations().single().id)
            push(bo);pull(a,ac,aGroup())
            push(ao);pull(b,bc,bGroup())
            val state=aGroup().state(groupId)!!
            assertEquals(2,state.members.size)
            ac.block(b.registration.deviceId,true)
            assertEquals(RelationshipState.BLOCKED,ar.relationshipState(b.registration.deviceId))
            aGroup().beginAuthorityBaseline(groupId)
            assertNull(a.records.read("app/group/baseline-v1/active/$groupId"))
            push(ao)
            // Offline B has not independently verified A; restarting A cannot waive B's approval.
            aGroup().processPending()
            assertNull(a.records.read("app/group/baseline-v1/active/$groupId"))
            pull(b,bc,bGroup())
            assertTrue(br.baselineV1Peer(a.registration.deviceId))
            assertNull(b.records.read("app/group/baseline-v1/own/$groupId"))
            push(bo);pull(a,ac,aGroup())
            assertTrue(ar.baselineV1Peer(b.registration.deviceId))
            push(ao);pull(b,bc,bGroup())
            assertNotNull(b.records.read("app/group/baseline-v1/own/$groupId"))
            assertNull(b.records.read("app/group/baseline-v1/active/$groupId"))
            push(bo);pull(a,ac,aGroup())
            assertNotNull(a.records.read("app/group/baseline-v1/active/$groupId"))
            push(ao);pull(b,bc,bGroup())
            assertNotNull(b.records.read("app/group/baseline-v1/active/$groupId"))
            for(p in listOf(a,b)) for(member in state.members)
                assertNotNull(p.records.read(
                    "app/group/historical-existing-baseline/$groupId/${member.memberId}"))
            assertEquals(RelationshipState.BLOCKED,ar.relationshipState(b.registration.deviceId))
            assertEquals(2L,aGroup().state(groupId)!!.revision)
            assertTrue(ac.messages(b.registration.deviceId).isEmpty())
            // Duplicate delivery after commit cannot create another activation or change the digest.
            val before=a.records.read("app/group/baseline-v1/active/$groupId")!!
            aGroup().processPending()
            assertArrayEquals(before,a.records.read("app/group/baseline-v1/active/$groupId"))
        }
    }
    @Test fun blockedCanonicalPeerExchangesOnlyGroupMaintenanceWithoutRestoringContact() = runBlocking {
        Fixture().use {f ->
            val (a,b,c)=listOf(f.person("alice",true),f.person("bob",true),f.person("charlie",true))
            val people=listOf(a,b,c)
            val repos=people.associateWith {LocalRepository(it.records)}
            val outboxes=people.associateWith {p ->DurableOutbox(p.records,p.engine,
                NetworkMailboxTransport(f.client(p),p.state))}
            val groups=people.associateWith {p ->GroupMembershipTransport(p.records,repos.getValue(p),p.engine,p.state,
                GroupAuthorityResolver(repos.getValue(p),p.engine,f.client(p),p.records),outboxes.getValue(p))}
            val conversations=people.associateWith {p ->ConversationService(p.engine,repos.getValue(p)).also {it.open()}}
            for(p in people) for(q in people) if(p!=q) {
                p.engine.establishSession(q.engine.publicBundle())
                p.state.remember(SenderProfile(q.registration.accountId,q.registration.deviceId,
                    q.registration.routingId,q.state.ghostCloakId()))
                repos.getValue(p).save(Contact(RandomIdentifiers.create(),q.registration.accountId,q.name,q.registration.deviceId))
                repos.getValue(p).groupPeer(q.registration.deviceId,true)
            }
            suspend fun push(p:Person) {
                outboxes.getValue(p).pendingIds().forEach {id ->
                    if(outboxes.getValue(p).get(id).state!=OutboxState.SERVER_ACCEPTED) outboxes.getValue(p).process(id)
                }
            }
            suspend fun pull(p:Person):List<Delivery> {
                val mailbox=NetworkMailboxTransport(f.client(p),p.state)
                val deliveries=mailbox.fetch()
                deliveries.forEach {conversations.getValue(p).acceptNetwork(EnvelopeCodec.decode(it.encryptedEnvelope))}
                mailbox.acknowledgeAccepted(deliveries.map {it.serverMessageId})
                return deliveries
            }
            val groupId=groups.getValue(a).createAndInvite(b.registration.deviceId)
            push(a);pull(b);groups.getValue(b).processPending()
            groups.getValue(b).accept(groups.getValue(b).invitations().single().id)
            push(b);pull(a);groups.getValue(a).processPending()
            push(a);pull(b);groups.getValue(b).processPending()
            val otherGroup=groups.getValue(a).createAndInvite(c.registration.deviceId)
            push(a);pull(c);groups.getValue(c).processPending()
            groups.getValue(c).accept(groups.getValue(c).invitations().single().id)
            push(c);pull(a);groups.getValue(a).processPending()
            push(a);pull(c);groups.getValue(c).processPending()
            assertEquals(2,groups.getValue(a).state(otherGroup)!!.members.size)
            val memberA=groups.getValue(a).state(groupId)!!.members.single {it.deviceId==a.registration.deviceId}
            val memberB=groups.getValue(a).state(groupId)!!.members.single {it.deviceId==b.registration.deviceId}
            // Simulate a valid P13.3 group created before group-scoped authority existed.
            val scopedKey="app/group/current-authority/$groupId/${memberB.memberId}"
            a.records.transaction {a.records.remove(scopedKey)}
            conversations.getValue(a).block(b.registration.deviceId,true)
            assertNotNull(a.records.transaction {a.records.read(scopedKey)})
            assertEquals(RelationshipState.BLOCKED,repos.getValue(a).relationshipState(b.registration.deviceId))
            groups.getValue(a).invite(groupId,c.registration.deviceId)
            push(a);pull(c);groups.getValue(c).processPending()
            groups.getValue(c).accept(groups.getValue(c).invitations().single().id)
            push(c);pull(a);groups.getValue(a).processPending()
            push(a);assertEquals(1,pull(b).size);groups.getValue(b).processPending()
            pull(c);groups.getValue(c).processPending()
            assertEquals(3,groups.getValue(a).state(groupId)!!.members.size)
            assertEquals(3,groups.getValue(b).state(groupId)!!.members.size)
            val genesisDigest=GroupLedger(a.records,GroupTrustedPeer {false},memberA.memberId)
                .digestAtRevision(groupId,1)!!
            val resync=ConversationPayload.encodeGroup(GroupControl(kind=GroupControlKind.RESYNC_REQUEST,
                groupId=groupId,fromRevision=1,fromDigest=genesisDigest))
            outboxes.getValue(b).enqueue(a.registration.deviceId,resync)
            push(b);assertEquals(1,pull(a).size)
            assertEquals(1,repos.getValue(a).pendingGroupControls().size)
            assertTrue(conversations.getValue(a).messages(b.registration.deviceId).isEmpty())
            groups.getValue(a).processPending()
            assertTrue(repos.getValue(a).pendingGroupControls().isEmpty())
            // A's response is sent through the group-only outbox even though B is blocked locally.
            assertEquals(1,pull(b).size)
            groups.getValue(b).processPending()
            assertEquals(RelationshipState.BLOCKED,repos.getValue(a).relationshipState(b.registration.deviceId))
            assertEquals(memberB.memberId,groups.getValue(a).state(groupId)!!.members.single {
                it.deviceId==b.registration.deviceId
            }.memberId)
            val direct=ConversationPayload.encode("blocked private text",0)
            outboxes.getValue(b).enqueue(a.registration.deviceId,direct)
            push(b);assertEquals(1,pull(a).size)
            assertTrue(conversations.getValue(a).messages(b.registration.deviceId).isEmpty())
            assertTrue(repos.getValue(a).pendingGroupControls().isEmpty())
            val text=ConversationPayload.encodeGroupText(GroupText(groupId=groupId,epoch=3,
                senderMemberId=memberB.memberId,logicalId=GroupIds.create(),text="hidden"))
            outboxes.getValue(b).enqueue(a.registration.deviceId,text)
            push(b);assertEquals(1,pull(a).size)
            assertTrue(repos.getValue(a).pendingGroupControls().isEmpty())
            assertTrue(groups.getValue(a).conversation(groupId)!!.messages.isEmpty())
            val canonical=groups.getValue(a).state(groupId)!!
            val revision=canonical.revision
            val forged=GroupTransition(GroupChange(GroupIds.create(),GroupAction.REMOVE,
                memberA.memberId,targetId=memberB.memberId),canonical,ByteArray(64),ByteArray(64))
            outboxes.getValue(b).enqueue(a.registration.deviceId,ConversationPayload.encodeGroup(
                GroupControl(kind=GroupControlKind.STATE_UPDATE,groupId=groupId,transition=forged)))
            push(b);assertEquals(1,pull(a).size)
            groups.getValue(a).processPending()
            assertEquals(revision,groups.getValue(a).state(groupId)!!.revision)
            assertTrue(repos.getValue(a).pendingGroupControls().isEmpty())
            val nonMaintenance=ConversationPayload.encodeGroup(GroupControl(
                kind=GroupControlKind.INVITE_EXPIRED,groupId=groupId,inviteId=GroupIds.create()))
            outboxes.getValue(b).enqueue(a.registration.deviceId,nonMaintenance)
            push(b);pull(a)
            assertTrue(repos.getValue(a).pendingGroupControls().isEmpty())
            val wrongGroup=ConversationPayload.encodeGroup(GroupControl(
                kind=GroupControlKind.RESYNC_REQUEST,groupId=otherGroup,
                fromRevision=1,fromDigest=genesisDigest))
            outboxes.getValue(b).enqueue(a.registration.deviceId,wrongGroup)
            push(b);pull(a)
            assertTrue(repos.getValue(a).pendingGroupControls().isEmpty())
            val removedC=groups.getValue(a).state(groupId)!!.members.single {
                it.deviceId==c.registration.deviceId
            }
            groups.getValue(a).removeMember(groupId,removedC.memberId)
            assertNull(a.records.transaction {a.records.read(
                "app/group/current-authority/$groupId/${removedC.memberId}")})
            assertEquals(1,a.records.transaction {a.records.keys("app/group/system-outbox/").size})
            val restartedA=GroupMembershipTransport(a.records,repos.getValue(a),a.engine,a.state,
                GroupAuthorityResolver(repos.getValue(a),a.engine,f.client(a),a.records),outboxes.getValue(a))
            restartedA.processPending()
            assertEquals(1,pull(b).size)
            groups.getValue(b).processPending()
            assertEquals(2,groups.getValue(b).state(groupId)!!.members.size)
            assertTrue(a.records.transaction {a.records.keys("app/group/system-outbox/").isEmpty()})
            conversations.getValue(a).unblock(b.registration.deviceId)
            assertEquals(RelationshipState.DORMANT_UNACCEPTED,
                repos.getValue(a).relationshipState(b.registration.deviceId))
            outboxes.getValue(b).enqueue(a.registration.deviceId,resync)
            push(b);pull(a)
            assertEquals(1,repos.getValue(a).pendingGroupControls().size)
            groups.getValue(a).processPending()
            assertEquals(1,pull(b).size)
            assertEquals(RelationshipState.DORMANT_UNACCEPTED,
                repos.getValue(a).relationshipState(b.registration.deviceId))
        }
    }

    @Test fun groupTextFanoutOfflineCatchupReplayAndRemoval() = runBlocking {
        Fixture().use {f ->
            val people=listOf(f.person("alice",true),f.person("bob",true),f.person("charlie",true))
            val repos=people.associateWith {LocalRepository(it.records)}
            val outboxes=people.associateWith {p ->DurableOutbox(p.records,p.engine,
                NetworkMailboxTransport(f.client(p),p.state))}
            val groups=people.associateWith {p ->GroupMembershipTransport(p.records,repos.getValue(p),p.engine,p.state,
                GroupAuthorityResolver(repos.getValue(p),p.engine,f.client(p),p.records),outboxes.getValue(p))}
            val conversations=people.associateWith {p ->ConversationService(p.engine,repos.getValue(p)).also {it.open()}}
            for(p in people) for(q in people) if(p!=q) {
                p.engine.establishSession(q.engine.publicBundle())
                p.state.remember(SenderProfile(q.registration.accountId,q.registration.deviceId,
                    q.registration.routingId,q.state.ghostCloakId()))
                repos.getValue(p).save(Contact(RandomIdentifiers.create(),q.registration.accountId,q.name,q.registration.deviceId))
                repos.getValue(p).groupPeer(q.registration.deviceId,true)
            }
            suspend fun push(p:Person) {
                val outbox=outboxes.getValue(p)
                outbox.pendingIds().forEach {id ->if(outbox.get(id).state!=OutboxState.SERVER_ACCEPTED) outbox.process(id)}
            }
            suspend fun pull(p:Person,ack:Boolean=true):List<Delivery> {
                val mailbox=NetworkMailboxTransport(f.client(p),p.state)
                val deliveries=mailbox.fetch()
                deliveries.forEach {conversations.getValue(p).acceptNetwork(EnvelopeCodec.decode(it.encryptedEnvelope))}
                if(ack) mailbox.acknowledgeAccepted(deliveries.map {it.serverMessageId})
                groups.getValue(p).processPending()
                return deliveries
            }
            val (a,b,c)=people
            val groupId=groups.getValue(a).createAndInvite(b.registration.deviceId)
            push(a);pull(b)
            groups.getValue(b).accept(groups.getValue(b).invitations().single().id)
            push(b);pull(a);push(a);pull(b)
            groups.getValue(a).invite(groupId,c.registration.deviceId)
            push(a);pull(c)
            groups.getValue(c).accept(groups.getValue(c).invitations().single().id)
            push(c);pull(a);push(a);pull(b);pull(c)
            assertEquals(3,groups.getValue(a).state(groupId)!!.members.size)
            val id=groups.getValue(a).sendText(groupId,"private group hello")
            groups.getValue(a).processPending()
            val sent=groups.getValue(a).conversation(groupId)!!.messages.single {it.logicalId==id}
            assertEquals(2,sent.recipients.count {it.state==GroupRecipientState.SENT})
            pull(b)
            assertEquals(listOf("private group hello"),groups.getValue(b).conversation(groupId)!!.messages.map {it.text})
            val notice=NotificationLedger(b.records).eligible().single {it.presentation.body==null}
            assertNull(notice.presentation.name)
            assertNull(notice.presentation.body)
            // C is offline; replaying an unacknowledged FETCH after process recreation is idempotent.
            val unacked=pull(c,false)
            val restarted=GroupMembershipTransport(c.records,repos.getValue(c),c.engine,c.state,
                GroupAuthorityResolver(repos.getValue(c),c.engine,f.client(c),c.records),outboxes.getValue(c))
            unacked.forEach {conversations.getValue(c).acceptNetwork(EnvelopeCodec.decode(it.encryptedEnvelope))}
            NetworkMailboxTransport(f.client(c),c.state).acknowledgeAccepted(unacked.map {it.serverMessageId})
            restarted.processPending()
            assertEquals(listOf("private group hello"),restarted.conversation(groupId)!!.messages.map {it.text})
            val removed=groups.getValue(a).state(groupId)!!.members.single {it.deviceId==c.registration.deviceId}
            val stale=groups.getValue(a).sendText(groupId,"stale unsent")
            groups.getValue(a).removeMember(groupId,removed.memberId)
            assertEquals(0,groups.getValue(a).conversation(groupId)!!.messages.single {it.logicalId==stale}
                .recipients.count {it.state==GroupRecipientState.PENDING})
            push(a);pull(b)
            val fresh=groups.getValue(a).sendText(groupId,"after removal")
            groups.getValue(a).processPending();pull(b)
            assertTrue(groups.getValue(b).conversation(groupId)!!.messages.any {it.logicalId==fresh})
            assertFalse(groups.getValue(c).conversation(groupId)!!.messages.any {it.logicalId==fresh})
            assertFalse(f.database.dump().decodeToString().contains(groupId))
        }
    }
    @Test fun admissionV2WaitsForEveryParentApprovalAndTargetAcceptance() = runBlocking {
        Fixture().use { f ->
            val people=listOf(f.person("alice",true),f.person("bob",true),f.person("charlie",true))
            val repos=people.associateWith {LocalRepository(it.records)}
            val outboxes=people.associateWith {p ->DurableOutbox(p.records,p.engine,
                NetworkMailboxTransport(f.client(p),p.state))}
            val groups=people.associateWith {p ->GroupMembershipTransport(p.records,repos.getValue(p),p.engine,p.state,
                GroupAuthorityResolver(repos.getValue(p),p.engine,f.client(p),p.records),outboxes.getValue(p))}
            val conversations=people.associateWith {p ->ConversationService(p.engine,repos.getValue(p)).also {it.open()}}
            for(p in people) for(q in people) if(p!=q) {
                p.engine.establishSession(q.engine.publicBundle())
                p.state.remember(SenderProfile(q.registration.accountId,q.registration.deviceId,
                    q.registration.routingId,q.state.ghostCloakId()))
                repos.getValue(p).save(Contact(RandomIdentifiers.create(),q.registration.accountId,q.name,q.registration.deviceId))
                repos.getValue(p).groupPeer(q.registration.deviceId,true)
                repos.getValue(p).admissionV2Peer(q.registration.deviceId,true)
            }
            suspend fun push(p:Person) {
                outboxes.getValue(p).pendingIds().forEach {id ->
                    if(outboxes.getValue(p).get(id).state!=OutboxState.SERVER_ACCEPTED) outboxes.getValue(p).process(id)
                }
            }
            suspend fun pull(p:Person) {
                val mailbox=NetworkMailboxTransport(f.client(p),p.state)
                val deliveries=mailbox.fetch()
                deliveries.forEach {conversations.getValue(p).acceptNetwork(EnvelopeCodec.decode(it.encryptedEnvelope))}
                if(deliveries.isNotEmpty()) mailbox.acknowledgeAccepted(deliveries.map {it.serverMessageId})
                groups.getValue(p).processPending()
            }
            val (a,b,c)=people
            val groupId=groups.getValue(a).createAndInvite(b.registration.deviceId)
            push(a);pull(b)
            groups.getValue(b).accept(groups.getValue(b).invitations().single().id)
            push(b);pull(a);push(a);pull(b)
            assertEquals(2,groups.getValue(a).state(groupId)!!.members.size)
            assertEquals(GroupLocalStatus.ACTIVE,groups.getValue(b).status(groupId))
            conversations.getValue(a).block(b.registration.deviceId,true)
            assertEquals(RelationshipState.BLOCKED,repos.getValue(a).relationshipState(b.registration.deviceId))
            groups.getValue(a).invite(groupId,c.registration.deviceId)
            assertEquals(2,groups.getValue(a).state(groupId)!!.members.size)
            assertTrue(groups.getValue(c).invitations().isEmpty())
            push(a);pull(c)
            assertTrue(groups.getValue(c).invitations().isEmpty())
            b.records.failWritePrefix="app/group/admission-v2/sent/"
            try {pull(b);fail("approval send did not hit injected persistence failure")}
            catch(_:EndpointStorageFailure) { /* Signed approval was persisted first. */ }
            val approvalKey=b.records.keys("app/group/admission-v2/own/$groupId/").single()
            val signedApproval=b.records.read(approvalKey)!!.copyOf()
            b.records.failWritePrefix=null
            val resumedB=GroupMembershipTransport(b.records,repos.getValue(b),b.engine,b.state,
                GroupAuthorityResolver(repos.getValue(b),b.engine,f.client(b),b.records),outboxes.getValue(b))
            resumedB.processPending()
            assertArrayEquals(signedApproval,b.records.read(approvalKey))
            assertTrue(groups.getValue(c).invitations().isEmpty())
            push(b);pull(a)
            push(a);pull(c)
            assertEquals(1,groups.getValue(c).invitations().size)
            assertEquals(2,groups.getValue(a).state(groupId)!!.members.size)
            groups.getValue(c).accept(groups.getValue(c).invitations().single().id)
            val historicalBefore=a.records.keys("app/group/historical-authority/$groupId/").toSet()
            a.records.failWritePrefix="app/group/historical-authority/"
            push(c)
            try {pull(a);fail("ADD committed without historical anchor")}
            catch(_:EndpointStorageFailure) { /* The ledger and anchor share one transaction. */ }
            assertEquals(2,groups.getValue(a).state(groupId)!!.members.size)
            assertEquals(historicalBefore,a.records.keys("app/group/historical-authority/$groupId/").toSet())
            a.records.failWritePrefix=null
            groups.getValue(a).processPending()
            assertEquals(3,groups.getValue(a).state(groupId)!!.members.size)
            push(a)
            val bMailbox=NetworkMailboxTransport(f.client(b),b.state)
            val missed=bMailbox.fetch()
            assertEquals(1,missed.size)
            val bOwnKey=b.records.keys("app/group/admission-v2/own/$groupId/").single()
            val bOwnApproval=b.records.read(bOwnKey)!!.copyOf()
            b.records.remove(bOwnKey)
            // Simulate a lost state notification; B later sees ADD without its
            // certificate and must request that evidence rather than infer trust.
            bMailbox.acknowledgeAccepted(missed.map {it.serverMessageId})
            val ownerId=a.records.read("app/group/member/$groupId")!!.decodeToString()
            val add=GroupLedger(a.records,GroupTrustedPeer {false},ownerId)
                .transitionsAfter(groupId,2)!!.single {it.change.action==GroupAction.ADD}
            val stripped=ConversationPayload.encodeGroup(GroupControl(kind=GroupControlKind.STATE_UPDATE,
                groupId=groupId,transition=add))
            val strippedId=outboxes.getValue(a).enqueue(b.registration.deviceId,stripped)
            outboxes.getValue(a).process(strippedId)
            pull(b);pull(c)
            assertEquals(2,groups.getValue(b).state(groupId)!!.members.size)
            push(b);pull(a)
            push(a);pull(b)
            assertEquals(2,groups.getValue(b).state(groupId)!!.members.size)
            b.records.write(bOwnKey,bOwnApproval)
            groups.getValue(b).processPending()
            // Pending state and evidence can be enumerated in either order.
            groups.getValue(b).processPending()
            assertEquals(3,groups.getValue(b).state(groupId)!!.members.size)
            assertEquals(3,groups.getValue(c).state(groupId)!!.members.size)
            assertEquals(RelationshipState.BLOCKED,repos.getValue(a).relationshipState(b.registration.deviceId))
            val candidate=groups.getValue(a).state(groupId)!!.members.single {
                it.deviceId==c.registration.deviceId
            }
            val historicalKey="app/group/historical-authority/$groupId/${candidate.memberId}"
            assertNotNull(a.records.read(historicalKey))
            val historicalBytes=a.records.read(historicalKey)!!.size
            assertTrue(historicalBytes<=1024)
            println("A3_SIZE historicalAuthority=$historicalBytes")
            conversations.getValue(a).block(c.registration.deviceId,true)
            groups.getValue(a).removeMember(groupId,candidate.memberId)
            assertNotNull(a.records.read(historicalKey))
            assertNull(a.records.read("app/group/current-authority/$groupId/${candidate.memberId}"))
            val unauthorized=ConversationPayload.encodeGroup(GroupControl(
                kind=GroupControlKind.ADMISSION_V2_EVIDENCE_REQUEST,groupId=groupId,
                evidenceEventId=GroupIds.create()))
            outboxes.getValue(c).process(outboxes.getValue(c).enqueue(a.registration.deviceId,unauthorized))
            pull(a)
            assertTrue(repos.getValue(a).pendingGroupControls().isEmpty())
        }
    }
    @Test fun removedAdmissionSignerRemainsValidOnlyForHistoricalResync() = runBlocking {
        Fixture().use { f ->
            val (a,c,b)=listOf(f.person("alice",true),f.person("charlie",true),f.person("bob",true))
            val people=listOf(a,c,b)
            val repos=people.associateWith {LocalRepository(it.records)}
            val outboxes=people.associateWith {p ->DurableOutbox(p.records,p.engine,
                NetworkMailboxTransport(f.client(p),p.state))}
            val groups=people.associateWith {p ->GroupMembershipTransport(p.records,repos.getValue(p),p.engine,p.state,
                GroupAuthorityResolver(repos.getValue(p),p.engine,f.client(p),p.records),outboxes.getValue(p))}
            val conversations=people.associateWith {p ->ConversationService(p.engine,repos.getValue(p)).also {it.open()}}
            for(p in people) for(q in people) if(p!=q) {
                p.engine.establishSession(q.engine.publicBundle())
                p.state.remember(SenderProfile(q.registration.accountId,q.registration.deviceId,
                    q.registration.routingId,q.state.ghostCloakId()))
                repos.getValue(p).save(Contact(RandomIdentifiers.create(),q.registration.accountId,q.name,q.registration.deviceId))
                repos.getValue(p).groupPeer(q.registration.deviceId,true)
                repos.getValue(p).admissionV2Peer(q.registration.deviceId,true)
            }
            suspend fun push(p:Person) {
                outboxes.getValue(p).pendingIds().forEach {id ->
                    if(outboxes.getValue(p).get(id).state!=OutboxState.SERVER_ACCEPTED) outboxes.getValue(p).process(id)
                }
            }
            suspend fun pull(p:Person) {
                val mailbox=NetworkMailboxTransport(f.client(p),p.state)
                val deliveries=mailbox.fetch()
                deliveries.forEach {conversations.getValue(p).acceptNetwork(EnvelopeCodec.decode(it.encryptedEnvelope))}
                if(deliveries.isNotEmpty()) mailbox.acknowledgeAccepted(deliveries.map {it.serverMessageId})
                groups.getValue(p).processPending()
            }
            val groupId=groups.getValue(a).createAndInvite(c.registration.deviceId)
            push(a);pull(c)
            groups.getValue(c).accept(groups.getValue(c).invitations().single().id)
            push(c);pull(a);push(a);pull(c)
            groups.getValue(a).invite(groupId,b.registration.deviceId)
            push(a);pull(c)
            push(c);pull(a)
            push(a);pull(b)
            groups.getValue(b).accept(groups.getValue(b).invitations().single().id)
            push(b);pull(a);push(a);pull(c);pull(b)
            val onC=groups.getValue(c).state(groupId)!!
            val admitted=onC.members.single {it.deviceId==b.registration.deviceId}
            assertNotNull(c.records.read("app/group/historical-authority/$groupId/${admitted.memberId}"))
            val change=GroupChange(GroupIds.create(),GroupAction.LEAVE,admitted.memberId,
                targetId=admitted.memberId)
            val next=GroupRules.derive(onC,change,emptySet(),GroupTrustedPeer {true})
            val event=GroupTransition(change,next,
                b.state.signGroupStatement(GroupStatements.actor(onC,change,next)),
                a.state.signGroupStatement(GroupStatements.coordinator(onC,change,next)))
            val ownerId=a.records.read("app/group/member/$groupId")!!.decodeToString()
            assertEquals(GroupApply.ACCEPTED,
                GroupLedger(a.records,GroupTrustedPeer {true},ownerId).apply(groupId,event))
            repos.getValue(c).removeContact(b.registration.deviceId)
            c.records.remove("app/group/current-authority/$groupId/${admitted.memberId}")
            assertNull(c.records.read("app/group/current-authority/$groupId/${admitted.memberId}"))
            val response=GroupControl(kind=GroupControlKind.RESYNC_RESPONSE,groupId=groupId,
                state=next,chain=listOf(event),headRevision=next.revision)
            val id=outboxes.getValue(a).enqueue(c.registration.deviceId,ConversationPayload.encodeGroup(response))
            outboxes.getValue(a).process(id)
            pull(c)
            assertEquals(next.revision,groups.getValue(c).state(groupId)!!.revision)
            assertTrue(groups.getValue(c).state(groupId)!!.members.none {it.memberId==admitted.memberId})
            assertNotNull(c.records.read("app/group/historical-authority/$groupId/${admitted.memberId}"))
        }
    }
    @Test fun staleInvitationExpiresAndFreshInviteCanJoin() = runBlocking {
        Fixture().use {f ->
            val people=listOf(f.person("alice",true),f.person("bob",true),f.person("charlie",true))
            val repos=people.associateWith {LocalRepository(it.records)}
            val outboxes=people.associateWith {p ->DurableOutbox(p.records,p.engine,NetworkMailboxTransport(f.client(p),p.state))}
            val groups=people.associateWith {p ->GroupMembershipTransport(p.records,repos.getValue(p),p.engine,p.state,
                GroupAuthorityResolver(repos.getValue(p),p.engine,f.client(p),p.records),outboxes.getValue(p))}
            val conversations=people.associateWith {p ->ConversationService(p.engine,repos.getValue(p)).also {it.open()}}
            for(p in people) for(q in people) if(p!=q) {
                p.engine.establishSession(q.engine.publicBundle())
                p.state.remember(SenderProfile(q.registration.accountId,q.registration.deviceId,
                    q.registration.routingId,q.state.ghostCloakId()))
                repos.getValue(p).save(Contact(RandomIdentifiers.create(),q.registration.accountId,q.name,q.registration.deviceId))
                repos.getValue(p).groupPeer(q.registration.deviceId,true)
            }
            suspend fun transfer(from:Person,to:Person) {
                val outbox=outboxes.getValue(from)
                outbox.pendingIds().forEach {id -> if(outbox.get(id).state!=OutboxState.SERVER_ACCEPTED) outbox.process(id)}
                val mailbox=NetworkMailboxTransport(f.client(to),to.state)
                val messages=mailbox.fetch()
                for(message in messages) conversations.getValue(to).acceptNetwork(EnvelopeCodec.decode(message.encryptedEnvelope))
                if(messages.isNotEmpty()) mailbox.acknowledgeAccepted(messages.map {it.serverMessageId})
                groups.getValue(to).processPending()
            }
            val (a,b,c)=people
            val groupId=groups.getValue(a).createAndInvite(b.registration.deviceId)
            transfer(a,b)
            groups.getValue(a).invite(groupId,c.registration.deviceId)
            transfer(a,c)
            val stale=groups.getValue(c).invitations().single().id
            groups.getValue(b).accept(groups.getValue(b).invitations().single().id)
            transfer(b,a)
            transfer(a,b)
            groups.getValue(c).accept(stale)
            transfer(c,a)
            transfer(a,c)
            assertTrue(groups.getValue(c).invitations().isEmpty())
            assertEquals(GroupLocalStatus.INVITED,groups.getValue(c).status(groupId))
            groups.getValue(a).invite(groupId,c.registration.deviceId)
            transfer(a,c)
            groups.getValue(c).accept(groups.getValue(c).invitations().single().id)
            transfer(c,a);transfer(a,c);transfer(a,b)
            assertEquals(GroupLocalStatus.ACTIVE,groups.getValue(c).status(groupId))
            assertEquals(3,groups.getValue(b).state(groupId)!!.members.size)
        }
    }
    @Test fun delegatedCoordinatorWaitsForOwnerCoSignature() = runBlocking {
        Fixture().use { f ->
            val people=listOf(f.person("alice",true),f.person("bob",true),f.person("charlie",true))
            val repos=people.associateWith {LocalRepository(it.records)}
            val outboxes=people.associateWith {p ->
                DurableOutbox(p.records,p.engine,NetworkMailboxTransport(f.client(p),p.state))
            }
            val groups=people.associateWith {p ->
                GroupMembershipTransport(p.records,repos.getValue(p),p.engine,p.state,
                    GroupAuthorityResolver(repos.getValue(p),p.engine,f.client(p),p.records),outboxes.getValue(p))
            }
            val conversations=people.associateWith {p -> ConversationService(p.engine,repos.getValue(p)).also {it.open()} }
            for(p in people) for(q in people) if(p!=q) {
                p.engine.establishSession(q.engine.publicBundle())
                p.state.remember(SenderProfile(q.registration.accountId,q.registration.deviceId,
                    q.registration.routingId,q.state.ghostCloakId()))
                repos.getValue(p).save(Contact(RandomIdentifiers.create(),q.registration.accountId,q.name,q.registration.deviceId))
                repos.getValue(p).groupPeer(q.registration.deviceId,true)
            }
            suspend fun push(p:Person) {
                outboxes.getValue(p).pendingIds().forEach {id ->
                    if(outboxes.getValue(p).get(id).state!=OutboxState.SERVER_ACCEPTED) outboxes.getValue(p).process(id)
                }
            }
            suspend fun pull(p:Person) {
                val mailbox=NetworkMailboxTransport(f.client(p),p.state)
                val messages=mailbox.fetch()
                for(message in messages) conversations.getValue(p).acceptNetwork(EnvelopeCodec.decode(message.encryptedEnvelope))
                if(messages.isNotEmpty()) mailbox.acknowledgeAccepted(messages.map {it.serverMessageId})
                groups.getValue(p).processPending()
            }
            val (a,b,c)=people
            val groupId=groups.getValue(a).createAndInvite(b.registration.deviceId)
            push(a);pull(b)
            groups.getValue(b).accept(groups.getValue(b).invitations().single().id)
            push(b);pull(a);push(a);pull(b)
            val first=groups.getValue(a).state(groupId)!!
            val target=first.members.single {it.deviceId==b.registration.deviceId}
            fun signed(previous:GroupState,action:GroupAction):GroupTransition {
                val change=GroupChange(GroupIds.create(),action,previous.ownerId,targetId=target.memberId)
                val next=GroupRules.derive(previous,change,emptySet(),GroupTrustedPeer {true})
                return GroupTransition(change,next,a.state.signGroupStatement(GroupStatements.actor(previous,change,next)),
                    a.state.signGroupStatement(GroupStatements.coordinator(previous,change,next)))
            }
            val promoted=signed(first,GroupAction.PROMOTE)
            val delegated=signed(promoted.next,GroupAction.DELEGATE_COORDINATOR)
            for(p in listOf(a,b)) for(event in listOf(promoted,delegated)) {
                val memberId=p.records.read("app/group/member/$groupId")!!.decodeToString()
                assertEquals(GroupApply.ACCEPTED,GroupLedger(p.records,GroupTrustedPeer {true},memberId).apply(groupId,event))
            }
            // Owner A blocks canonical coordinator B. The signed admission request for C
            // still reaches A's internal processor, and A's co-signature returns to B.
            conversations.getValue(a).block(b.registration.deviceId,true)
            groups.getValue(b).invite(groupId,c.registration.deviceId)
            assertTrue(groups.getValue(c).invitations().isEmpty())
            push(b);pull(a)
            assertEquals(RelationshipState.BLOCKED,repos.getValue(a).relationshipState(b.registration.deviceId))
            assertTrue(groups.getValue(c).invitations().isEmpty())
            push(a);pull(b)
            push(b);pull(c)
            assertEquals(1,groups.getValue(c).invitations().size)
            groups.getValue(c).accept(groups.getValue(c).invitations().single().id)
            push(c);pull(b)
            assertEquals(3,groups.getValue(b).state(groupId)!!.members.size)
            push(b);pull(a);pull(c)
            assertEquals(GroupLocalStatus.ACTIVE,groups.getValue(c).status(groupId))
            assertEquals(3,groups.getValue(a).state(groupId)!!.members.size)
            val removed=groups.getValue(b).state(groupId)!!.members.single {it.deviceId==c.registration.deviceId}
            groups.getValue(b).removeMember(groupId,removed.memberId)
            val oldRevision=groups.getValue(c).state(groupId)!!.revision
            push(b)
            pull(a)
            pull(c)
            assertEquals(2,groups.getValue(a).state(groupId)!!.members.size)
            assertEquals(oldRevision,groups.getValue(c).state(groupId)!!.revision)
        }
    }
    @Test fun groupInvitationJoinsOnlyAfterSignedMemberAdded() = runBlocking {
        Fixture().use { f ->
            val a=f.person("alice",groupCredential=true); val b=f.person("bob",groupCredential=true)
            NetworkAccount(f.client(a),a.state).connect(b.state.ghostCloakId(),a.engine)
            NetworkAccount(f.client(b),b.state).connect(a.state.ghostCloakId(),b.engine)
            val ar=LocalRepository(a.records); val br=LocalRepository(b.records)
            ar.save(Contact(RandomIdentifiers.create(),b.registration.accountId,"Bob",b.registration.deviceId))
            br.save(Contact(RandomIdentifiers.create(),a.registration.accountId,"Alice",a.registration.deviceId))
            ar.groupPeer(b.registration.deviceId,true); br.groupPeer(a.registration.deviceId,true)
            val ao=DurableOutbox(a.records,a.engine,NetworkMailboxTransport(f.client(a),a.state))
            val bo=DurableOutbox(b.records,b.engine,NetworkMailboxTransport(f.client(b),b.state))
            val ag=GroupMembershipTransport(a.records,ar,a.engine,a.state,
                GroupAuthorityResolver(ar,a.engine,f.client(a),a.records),ao)
            val bg=GroupMembershipTransport(b.records,br,b.engine,b.state,
                GroupAuthorityResolver(br,b.engine,f.client(b),b.records),bo)
            val ac=ConversationService(a.engine,ar); val bc=ConversationService(b.engine,br)
            ac.open(); bc.open()
            suspend fun send(outbox:DurableOutbox) {
                outbox.pendingIds().forEach { id -> if(outbox.get(id).state!=OutboxState.SERVER_ACCEPTED) outbox.process(id) }
            }
            suspend fun receive(p:Person,conversation:ConversationService,group:GroupMembershipTransport) {
                val transport=NetworkMailboxTransport(f.client(p),p.state)
                val deliveries=transport.fetch()
                for(delivery in deliveries) conversation.acceptNetwork(EnvelopeCodec.decode(delivery.encryptedEnvelope))
                transport.acknowledgeAccepted(deliveries.map {it.serverMessageId})
                group.processPending()
            }
            val groupId=ag.createAndInvite(b.registration.deviceId)
            assertEquals(GroupLocalStatus.ACTIVE,ag.status(groupId))
            send(ao); receive(b,bc,bg)
            val invite=bg.invitations().single()
            assertEquals(listOf(invite.id to false),bg.pendingInvitationNotices())
            bg.markInvitationNotice(invite.id,false)
            assertEquals(listOf(invite.id to true),bg.pendingInvitationNotices())
            bg.markInvitationNotice(invite.id,true)
            assertTrue(bg.pendingInvitationNotices().isEmpty())
            val backendBytes=f.database.dump().decodeToString()
            assertFalse(backendBytes.contains(groupId))
            assertFalse(backendBytes.contains(invite.id))
            assertEquals(GroupLocalStatus.INVITED,bg.status(groupId))
            bg.accept(invite.id)
            assertEquals(GroupLocalStatus.INVITED,bg.status(groupId))
            send(bo); receive(a,ac,ag)
            assertEquals(2,ag.state(groupId)!!.members.size)
            send(ao); receive(b,bc,bg)
            assertEquals(GroupLocalStatus.ACTIVE,bg.status(groupId))
            assertEquals(2L,bg.state(groupId)!!.revision)
            assertEquals(2L,bg.state(groupId)!!.epoch)
            var prior=ag.state(groupId)!!
            var last:GroupTransition?=null
            for(revision in 1..2) {
                val change=GroupChange(GroupIds.create(),GroupAction.PROFILE,prior.ownerId,
                    newProfileRevision=prior.profileRevision+1,newProfileDigest=ByteArray(32){revision.toByte()})
                val next=GroupRules.derive(prior,change,emptySet(),GroupTrustedPeer {true})
                val event=GroupTransition(change,next,a.state.signGroupStatement(GroupStatements.actor(prior,change,next)),
                    a.state.signGroupStatement(GroupStatements.coordinator(prior,change,next)))
                val ownerId=a.records.read("app/group/member/$groupId")!!.decodeToString()
                assertEquals(GroupApply.ACCEPTED,GroupLedger(a.records,GroupTrustedPeer {true},ownerId).apply(groupId,event))
                prior=next;last=event
            }
            // The first update is missed while offline. The second must trigger a complete-chain resync.
            ao.enqueue(b.registration.deviceId,ConversationPayload.encodeGroup(GroupControl(
                kind=GroupControlKind.STATE_UPDATE,groupId=groupId,transition=last!!)))
            send(ao);receive(b,bc,bg)
            assertEquals(2L,bg.state(groupId)!!.revision)
            send(bo);receive(a,ac,ag)
            send(ao)
            // Simulate a response lost after server acceptance. A reconstructed coordinator
            // must use the durable resync marker to request the signed chain again.
            val mailbox=NetworkMailboxTransport(f.client(b),b.state)
            val lost=mailbox.fetch()
            assertTrue(lost.isNotEmpty())
            mailbox.acknowledgeAccepted(lost.map {it.serverMessageId})
            val restarted=GroupMembershipTransport(b.records,br,b.engine,b.state,
                GroupAuthorityResolver(br,b.engine,f.client(b),b.records),bo)
            restarted.processPending()
            send(bo);receive(a,ac,ag)
            send(ao);receive(b,bc,bg)
            assertEquals(4L,bg.state(groupId)!!.revision)
            assertEquals(2L,bg.state(groupId)!!.profileRevision)
        }
    }
    private class Time : Clock() {
        var time = System.currentTimeMillis()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(time)
    }
    private class Person(val name: String,groupCredential:Boolean=false) {
        val records = MemoryRecords(); val engine = SignalProtocolEngine(records)
        val state = EndpointNetworkState(records, "ghostcloak.local",if(groupCredential) RecoveryCredential() else null)
        lateinit var registration: Registration
        suspend fun create() {
            engine.createIdentity(name)
            registration = state.registration(listOf(engine.publicBundle().publicData()))
        }
    }
    private class Fixture : AutoCloseable {
        val time = Time(); val database = MemoryBackendDatabase()
        val service = MailboxService(database, time, rate = RateLimiter { _, _, _ -> true })
        val server = LocalServer(service).start()
        suspend fun person(name: String,groupCredential:Boolean=false): Person = Person(name,groupCredential).also { p ->
            p.create(); val client = client(p); val account = NetworkAccount(client, p.state)
            account.register(p.registration); account.login(p.registration.accountId, p.registration.deviceId)
        }
        fun client(p: Person) = HttpGhostClient(server.baseUrl, p.state, allowLoopbackForTests = true)
        fun call(p: Person, request: ApiRequest) = service.execute(request, p.state.read())
        fun challenge(p: Person): Challenge = service.execute(ApiRequest.Issue(p.registration.accountId, p.registration.deviceId, "login")).challenge!!
        override fun close() = server.close()
    }
    private fun reject(status: Int? = null, block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: ApiFailure) { if (status != null) assertEquals(status, e.status) }
    }
    private suspend fun rejectAsync(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: ApiFailure) { }
    }
    @Test fun registeredDeviceBindingIsAuthenticatedExactAndNonConsuming() = runBlocking {
        Fixture().use { f ->
            val a=f.person("alice");val b=f.person("bob")
            val id=b.registration.deviceId
            val digest=DeviceAuth.digest(b.registration.bundles.first().identity)
            val request=ApiRequest.CapabilityLookup(id,expectedAccountId=b.registration.accountId,
                expectedIdentityDigest=digest)
            reject(401) { f.service.execute(request) }
            val before=f.database.transaction {f.database.prekeys.get(id)!!.pool.map {it.preKeyId}}
            val binding=f.call(a,request).deviceBinding!!
            assertEquals(1,binding.version)
            assertEquals(b.registration.accountId,binding.accountId)
            assertEquals(id,binding.deviceId)
            assertArrayEquals(b.registration.authPublicKey,binding.authPublicKey)
            assertArrayEquals(digest,binding.identityDigest)
            assertEquals(before,f.database.transaction {f.database.prekeys.get(id)!!.pool.map {it.preKeyId}})
            assertNull(f.call(a,ApiRequest.CapabilityLookup(id)).deviceBinding)
            reject(404) {f.call(a,ApiRequest.CapabilityLookup(RandomIdentifiers.create(),
                expectedAccountId=b.registration.accountId,expectedIdentityDigest=digest))}
            reject(404) {f.call(a,ApiRequest.CapabilityLookup(id,
                expectedAccountId=a.registration.accountId,expectedIdentityDigest=digest))}
            reject(404) {f.call(a,ApiRequest.CapabilityLookup(id,
                expectedAccountId=b.registration.accountId,expectedIdentityDigest=ByteArray(32)))}
            reject(400) {f.call(a,ApiRequest.CapabilityLookup("bad",
                expectedAccountId=b.registration.accountId,expectedIdentityDigest=digest))}
            reject(400) {f.call(a,ApiRequest.CapabilityLookup(id,
                expectedAccountId=b.registration.accountId,expectedIdentityDigest=ByteArray(1024)))}
            assertEquals(ServerOperation.LOOKUP,f.service.operation(request))
        }
    }
    @Test fun groupAuthorityRequiresAcceptedCurrentSignalPinAndStableRegisteredKey() = runBlocking {
        Fixture().use { f ->
            val a=f.person("alice");val b=f.person("bob")
            val repo=LocalRepository(a.records)
            a.engine.establishSession(b.engine.publicBundle())
            val contact=Contact(RandomIdentifiers.create(),b.registration.accountId,"Bob",b.registration.deviceId)
            repo.save(contact.copy(request=true))
            val resolver=GroupAuthorityResolver(repo,a.engine,f.client(a),a.records)
            rejectAsync {resolver.resolveTrustedGroupAuthority(contact.remoteDeviceId)}
            repo.save(contact)
            assertArrayEquals(b.registration.authPublicKey,
                resolver.resolveTrustedGroupAuthority(contact.remoteDeviceId).authPublicKey)
            // An otherwise valid replacement auth key under the same Signal pin is rejected.
            val replacement=java.security.KeyPairGenerator.getInstance("EC").apply {
                initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            }.generateKeyPair().public.encoded
            val row=f.database.transaction {f.database.devices.get(contact.remoteDeviceId)!!}
            f.database.transaction {f.database.devices.put(contact.remoteDeviceId,DeviceRow(row.id,row.accountId,row.routingId,
                replacement,row.identity))}
            rejectAsync {resolver.resolveTrustedGroupAuthority(contact.remoteDeviceId)}
            f.database.transaction {f.database.devices.put(contact.remoteDeviceId,row)}
            a.records.write("trust-state/${contact.remoteDeviceId}","CHANGED".encodeToByteArray())
            rejectAsync {resolver.resolveTrustedGroupAuthority(contact.remoteDeviceId)}
            a.records.write("trust-state/${contact.remoteDeviceId}","UNVERIFIED".encodeToByteArray())
            repo.block(contact.remoteDeviceId,true)
            rejectAsync {resolver.resolveTrustedGroupAuthority(contact.remoteDeviceId)}
            assertNull(a.records.read("app/group-authority/${contact.remoteDeviceId}"))
            val c=f.person("charlie")
            a.engine.establishSession(c.engine.publicBundle())
            val removed=Contact(RandomIdentifiers.create(),c.registration.accountId,"Charlie",c.registration.deviceId)
            repo.save(removed)
            resolver.resolveTrustedGroupAuthority(removed.remoteDeviceId)
            repo.removeContact(removed.remoteDeviceId)
            rejectAsync {resolver.resolveTrustedGroupAuthority(removed.remoteDeviceId)}
            assertNull(a.records.read("app/group-authority/${removed.remoteDeviceId}"))
            val d=f.person("dave");val e=f.person("eve")
            a.engine.establishSession(d.engine.publicBundle())
            val changed=Contact(RandomIdentifiers.create(),d.registration.accountId,"Dave",d.registration.deviceId)
            repo.save(changed)
            resolver.resolveTrustedGroupAuthority(changed.remoteDeviceId)
            val replacementIdentity=e.engine.publicBundle()
            try {
                a.engine.establishSession(RemoteKeyBundle(changed.remoteDeviceId,replacementIdentity.registrationId,
                    replacementIdentity.identity,replacementIdentity.preKeyId,replacementIdentity.preKey,
                    replacementIdentity.signedId,replacementIdentity.signedKey,replacementIdentity.signature,
                    replacementIdentity.kyberId,replacementIdentity.kyberKey,replacementIdentity.kyberSignature))
                fail("Identity change accepted")
            } catch (failure:CryptoFailure) {assertEquals(CryptoError.IdentityChanged,failure.error)}
            assertNull(a.records.read("app/group-authority/${changed.remoteDeviceId}"))
            rejectAsync {resolver.resolveTrustedGroupAuthority(changed.remoteDeviceId)}
        }
    }
    @Test fun admissionCandidateBindingIsExactAndDoesNotPersistGlobalAuthority() = runBlocking {
        Fixture().use { f ->
            val a=f.person("alice");val b=f.person("bob")
            val repo=LocalRepository(a.records)
            a.engine.establishSession(b.engine.publicBundle())
            val contact=Contact(RandomIdentifiers.create(),b.registration.accountId,"Bob",b.registration.deviceId)
            repo.save(contact)
            val resolver=GroupAuthorityResolver(repo,a.engine,f.client(a),a.records)
            val digest=a.engine.trustedRemoteIdentityDigest(b.registration.deviceId)!!
            val member=GroupMember(GroupIds.create(),b.registration.accountId,b.registration.deviceId,
                b.registration.authPublicKey,digest,GroupRole.MEMBER,2)
            assertTrue(resolver.verifyAdmissionCandidate(member))
            assertNull(a.records.read("app/group-authority/${member.deviceId}"))
            assertFalse(resolver.verifyAdmissionCandidate(member.copy(authPublicKey=ByteArray(member.authPublicKey.size))))
            assertFalse(resolver.verifyAdmissionCandidate(member.copy(signalIdentityDigest=ByteArray(32))))
            assertFalse(resolver.verifyAdmissionCandidate(member.copy(accountId=RandomIdentifiers.create())))
            repo.block(member.deviceId,true)
            assertFalse(resolver.verifyAdmissionCandidate(member))
            assertNull(a.records.read("app/group-authority/${member.deviceId}"))
        }
    }
    @Test fun realHttpOfflineSignalDeliveryAckAndDatabaseCompromise() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob"); val c = f.person("charlie")
            val aliceClient = f.client(a)
            NetworkAccount(aliceClient, a.state).connect(b.state.ghostCloakId(), a.engine)
            val transport = NetworkMailboxTransport(aliceClient, a.state)
            val packet = a.engine.encrypt(b.registration.deviceId, "hello bob".toByteArray())
            val submission = RandomIdentifiers.create()
            val serverId = transport.submit(submission, b.registration.deviceId, packet)
            assertEquals(serverId, transport.submit(submission, b.registration.deviceId, packet))
            assertTrue(NetworkMailboxTransport(f.client(c), c.state).fetch().isEmpty())
            reject(403) { f.call(c, ApiRequest.Ack(listOf(serverId))) }
            val dump = f.database.dump()
            fun contains(haystack: ByteArray, needle: ByteArray): Boolean = needle.isNotEmpty() && haystack.asList().windowed(needle.size).any { it == needle.asList() }
            assertFalse(contains(dump, "hello bob".toByteArray()))
            assertFalse(contains(dump, a.state.read()!!.toByteArray()))
            for (p in listOf(a, b)) for (key in p.records.keys("").filter { it == "local/key" || it.startsWith("session/") || it.startsWith("pre/") || it.startsWith("signed/") || it.startsWith("kyber/") || it.endsWith("auth-private") }) {
                assertFalse("Endpoint secret leaked: $key", contains(dump, p.records.read(key)!!))
            }
            // Bob was offline during submission. Login again and fetch without deleting.
            val bobClient = f.client(b); NetworkAccount(bobClient, b.state).login(b.registration.accountId, b.registration.deviceId)
            val inbox = NetworkMailboxTransport(bobClient, b.state)
            val delivery = inbox.fetch().single()
            assertEquals(serverId, delivery.serverMessageId)
            assertEquals(serverId, inbox.fetch().single().serverMessageId)
            assertEquals("hello bob", b.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)).decodeToString())
            try { b.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)); fail() } catch (e: CryptoFailure) { assertEquals(CryptoError.Replay, e.error) }
            inbox.acknowledgeAccepted(listOf(serverId)); inbox.acknowledgeAccepted(listOf(serverId))
            assertTrue(inbox.fetch().isEmpty())
            assertEquals(serverId, transport.submit(submission, b.registration.deviceId, packet))
            assertTrue(inbox.fetch().isEmpty()) // Retry after ACK cannot recreate delivery.
        }
    }
    @Test fun authenticationReplayBindingsExpiryAndRevocation() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob")
            val c = f.challenge(a); val sig = a.state.sign(c)
            val proof = ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, c.id, sig)
            val session = f.service.execute(proof).session!!
            reject(401) { f.service.execute(proof) }
            f.service.execute(ApiRequest.Revoke(), session.token)
            reject(401) { f.service.execute(ApiRequest.Fetch(), session.token) }
            val expired = f.challenge(a); val expiredSig = a.state.sign(expired)
            f.time.time += 60001
            reject(401) { f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, expired.id, expiredSig)) }
            val wrong = f.challenge(a)
            val bChallenge = f.challenge(b)
            reject(401) { f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, wrong.id, b.state.sign(bChallenge))) }
            reject(401) { f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, wrong.id, a.state.sign(wrong))) }
            val binding = f.challenge(a)
            reject(401) { f.service.execute(ApiRequest.Verify(b.registration.accountId, a.registration.deviceId, binding.id, a.state.sign(binding))) }
            val altered = f.challenge(a)
            val alteredStatement = Challenge(altered.id, altered.random.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, altered.expiresAt, altered.audience, altered.accountId, altered.deviceId, altered.purpose, altered.registrationHash)
            reject(401) { f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, altered.id, a.state.sign(alteredStatement))) }
            val audience = f.challenge(a)
            reject { a.state.sign(Challenge(audience.id, audience.random, audience.expiresAt, "evil.example", audience.accountId, audience.deviceId, audience.purpose, audience.registrationHash)) }
            val valid = f.challenge(a)
            val token = f.service.execute(ApiRequest.Verify(a.registration.accountId, a.registration.deviceId, valid.id, a.state.sign(valid))).session!!.token
            f.time.time += 300001
            reject(401) { f.service.execute(ApiRequest.Fetch(), token) }
            reject(401) { f.service.execute(ApiRequest.Fetch(), "not-a-token") }
        }
    }
    @Test fun registrationPossessionUniquenessAndGhostCloakIdRules() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = Person("bob").also { it.create() }
            for (bad in listOf("", "..", " 7K4M9Q2FX8DR", "ADMIN", "ＧK4M9Q2FX8DR", "7K4M/9Q2F/X8DR", "A".repeat(13))) reject { GhostCloakIds.normalize(bad) }
            assertEquals("7K4M9Q2FX8DR", GhostCloakIds.normalize("7k4m-9q2f-x8dr"))
            val registration = b.registration
            val c = f.service.execute(ApiRequest.Issue(registration.accountId, registration.deviceId, "register", DeviceAuth.digest(NetworkCodec.encode(registration)))).challenge!!
            // An attacker cannot register someone else's credential with their own signature.
            reject { f.service.execute(ApiRequest.Register(registration, c.id, a.state.sign(f.challenge(a)))) }
            NetworkAccount(f.client(b), b.state).register(registration)
            rejectAsync { NetworkAccount(f.client(b), b.state).register(registration) }
            val duplicateName = Person("alice").also { it.create() }
            NetworkAccount(f.client(duplicateName), duplicateName.state).run { register(duplicateName.registration); login(duplicateName.registration.accountId, duplicateName.registration.deviceId) }
            assertNotEquals(a.state.ghostCloakId(), duplicateName.state.ghostCloakId())
            val malformed = Registration(RandomIdentifiers.create(), RandomIdentifiers.create(), RandomIdentifiers.create(), ByteArray(91), registration.bundles)
            val challenge = f.service.execute(ApiRequest.Issue(malformed.accountId, malformed.deviceId, "register", DeviceAuth.digest(NetworkCodec.encode(malformed)))).challenge!!
            reject { f.service.execute(ApiRequest.Register(malformed, challenge.id, ByteArray(72))) }
            reject(400) { f.call(a, ApiRequest.Lookup("nobody")) }
            assertEquals(a.registration.deviceId, f.call(a, ApiRequest.Lookup(a.state.ghostCloakId())).discovery!!.deviceId)
        }
    }
    @Test fun prekeysConsumeAtomicallyAndEnforceOwnershipAndBounds() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob")
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results = (1..2).map { executor.submit<Boolean> { try { f.call(a, ApiRequest.Allocate(b.state.ghostCloakId(),RandomIdentifiers.create())); true } catch (_: ApiFailure) { false } } }.map { it.get() }
                assertEquals(1, results.count { it })
            } finally { executor.shutdownNow() }
            val keys = b.engine.preKeys.createPublicationBundle().publicData()
            reject(403) { f.call(a, ApiRequest.Prekeys(b.registration.deviceId, listOf(keys))) }
            f.call(b, ApiRequest.Prekeys(b.registration.deviceId, listOf(keys)))
            reject(409) { f.call(b, ApiRequest.Prekeys(b.registration.deviceId, listOf(keys))) }
            reject { f.call(b, ApiRequest.Prekeys(b.registration.deviceId, List(17) { keys })) }
            val other = b.engine.preKeys.createPublicationBundle().publicData()
            reject { f.call(b, ApiRequest.Prekeys(b.registration.deviceId, listOf(other, other))) }
            val invalid = PublicBundle(other.deviceId, other.registrationId, ByteArray(33), other.preKeyId, other.preKey, other.signedId, other.signedKey, other.signature, other.kyberId, other.kyberKey, other.kyberSignature)
            reject { f.call(b, ApiRequest.Prekeys(b.registration.deviceId, listOf(invalid))) }
            assertEquals(keys.preKeyId, f.call(a, ApiRequest.Allocate(b.state.ghostCloakId(),RandomIdentifiers.create())).directory!!.bundle.preKeyId)
        }
    }
    @Test fun mailboxValidationIdempotencyAndExpiry() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob")
            a.engine.establishSession(b.registration.bundles.single().remote())
            val wire = EnvelopeCodec.encode(a.engine.encrypt(b.registration.deviceId, "secret".toByteArray()))
            val id = RandomIdentifiers.create(); val request = ApiRequest.Send(id, b.registration.routingId, wire)
            val serverId = f.call(a, request).serverMessageId
            assertEquals(serverId, f.call(a, request).serverMessageId)
            val different = EnvelopeCodec.encode(a.engine.encrypt(b.registration.deviceId, "different".toByteArray()))
            reject(409) { f.call(a, ApiRequest.Send(id, b.registration.routingId, different)) }
            reject { f.call(a, ApiRequest.Send(RandomIdentifiers.create(), b.registration.routingId, ByteArray(20))) }
            reject { f.call(a, ApiRequest.Send(RandomIdentifiers.create(), b.registration.routingId, ByteArray(EnvelopeCodec.MAX_PACKET + 1))) }
            reject(404) { f.call(a, ApiRequest.Send(RandomIdentifiers.create(), RandomIdentifiers.create(), wire)) }
            reject { f.call(b, ApiRequest.Send(RandomIdentifiers.create(), b.registration.routingId, wire)) }
            reject(401) { f.service.execute(ApiRequest.Fetch()) }
            f.call(b, ApiRequest.Ack(listOf(serverId!!)))
            // A full batch of near-limit envelopes must fit the documented binary response cap.
            repeat(NetworkLimits.BATCH) {
                val large = EncryptedEnvelope(1, RandomIdentifiers.create(), a.registration.deviceId, b.registration.deviceId, 2, ByteArray(65300) { 127 })
                f.call(a, ApiRequest.Send(RandomIdentifiers.create(), b.registration.routingId, EnvelopeCodec.encode(large)))
            }
            assertEquals(NetworkLimits.BATCH, NetworkMailboxTransport(f.client(b), b.state).fetch().size)
            f.time.time += 604800001; f.service.cleanup()
            assertEquals(0, f.database.transaction { f.database.mailbox.size() })
            // Expired unacknowledged ciphertext cannot be inferred as delivered.
            assertEquals(1, f.database.transaction { f.database.submissions.all().count { it.acknowledged } })
            assertEquals(NetworkLimits.BATCH, f.database.transaction { f.database.submissions.all().count { !it.acknowledged } })
        }
    }
    @Test fun durableOutboxRecoversEveryCrashBoundaryWithoutReencrypting() = runBlocking {
        for (point in CrashPoint.entries) Fixture().use { f ->
            val a = f.person("alice"); val b = f.person("bob")
            val client = f.client(a); NetworkAccount(client, a.state).connect(b.state.ghostCloakId(), a.engine)
            val transport = NetworkMailboxTransport(client, a.state)
            val outbox = DurableOutbox(a.records, a.engine, transport, crash = { if (it == point) throw SimulatedDeath() })
            var id: String? = null
            try { id = outbox.enqueue(b.registration.deviceId, "hello bob".toByteArray()); outbox.process(id) } catch (_: SimulatedDeath) { }
            id = id ?: a.records.keys("outbox/").single().removePrefix("outbox/")
            val restart = DurableOutbox(a.records, SignalProtocolEngine(a.records), transport)
            val before = restart.get(id)
            val recovered = restart.process(id)
            if (point == CrashPoint.AFTER_ENCRYPTION) {
                assertEquals(OutboxState.FAILED, recovered.state)
                assertTrue(f.call(b, ApiRequest.Fetch()).deliveries.isEmpty())
            } else {
                assertEquals(OutboxState.SERVER_ACCEPTED, recovered.state)
                val delivery = f.call(b, ApiRequest.Fetch()).deliveries.single()
                if (before.ciphertext.isNotEmpty()) assertArrayEquals(before.ciphertext, delivery.encryptedEnvelope)
                assertEquals("hello bob", b.engine.decrypt(EnvelopeCodec.decode(delivery.encryptedEnvelope)).decodeToString())
                restart.process(id)
                assertEquals(1, f.call(b, ApiRequest.Fetch()).deliveries.size)
            }
        }
    }
    private class SimulatedDeath : RuntimeException()
    @Test fun httpRejectsOversizeSchemaVersionContentTypeAndCleartext() = runBlocking {
        Fixture().use { f ->
            val a = f.person("alice")
            fun raw(bytes: ByteArray, type: String = NetworkLimits.CONTENT_TYPE): Int {
                val c = URI(f.server.baseUrl + "/v2/auth/challenge").toURL().openConnection() as HttpURLConnection
                try { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", type); c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) }; return c.responseCode }
                finally { c.disconnect() }
            }
            // JDK HttpServer may close/reset a connection rejected before draining an oversized body.
            val oversized = try { raw(ByteArray(NetworkLimits.BODY + 1)) } catch (_: java.net.SocketException) { -1 }
            assertTrue(oversized == 413 || oversized == -1)
            assertEquals(415, raw(byteArrayOf(1), "text/plain"))
            assertEquals(400, raw(byteArrayOf(1, 2, 3)))
            rejectAsync { f.client(a).unauthenticated(ApiRequest.Issue(a.registration.accountId, a.registration.deviceId, "login", version = 1)) }
            try { HttpGhostClient("http://example.com", a.state, true); fail() } catch (_: IllegalArgumentException) { }
            try { HttpGhostClient(f.server.baseUrl, a.state); fail() } catch (_: IllegalArgumentException) { }
            reject { f.service.execute(ApiRequest.Register(a.registration, RandomIdentifiers.create(), ByteArray(NetworkLimits.BODY))) }
        }
    }
    @Test fun rateLimitAndLogsContainOnlyFixedCategories() {
        val log = mutableListOf<Pair<ServerOperation, ServerResult>>()
        val service = MailboxService(MemoryBackendDatabase(), rate = DevelopmentRateLimiter(1), logger = ServerLogger { op, result -> log.add(op to result) })
        val request = ApiRequest.Issue(RandomIdentifiers.create(), RandomIdentifiers.create(), "login")
        service.execute(request)
        reject(429) { service.execute(request) }
        assertEquals(listOf(ServerOperation.CHALLENGE to ServerResult.OK, ServerOperation.CHALLENGE to ServerResult.REJECTED), log)
    }
    @Test fun alteredRegistrationCannotReplaceCredentialAndReturnedChallengesAreCopies() = runBlocking {
        Fixture().use { f ->
            val owner = f.person("owner")
            val attacker = Person("attacker").also { it.create() }
            val original = attacker.registration
            val c = f.service.execute(ApiRequest.Issue(original.accountId, original.deviceId, "register", DeviceAuth.digest(NetworkCodec.encode(original)))).challenge!!
            val changed = Registration(original.accountId, original.deviceId, RandomIdentifiers.create(), original.authPublicKey, original.bundles)
            reject(401) { f.service.execute(ApiRequest.Register(changed, c.id, attacker.state.sign(c))) }
            val replacement = Registration(owner.registration.accountId, original.deviceId, original.routingId, original.authPublicKey, original.bundles)
            val claim = f.service.execute(ApiRequest.Issue(replacement.accountId, replacement.deviceId, "register", DeviceAuth.digest(NetworkCodec.encode(replacement)))).challenge!!
            // Sign with the attacker's credential while binding the victim account in the statement.
            val privateBytes = attacker.records.read(attacker.records.keys("network/").single { it.endsWith("auth-private") })!!
            val privateKey = java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateBytes))
            privateBytes.fill(0)
            val signature = java.security.Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(DeviceAuth.statement(claim)); sign() }
            reject(409) { f.service.execute(ApiRequest.Register(replacement, claim.id, signature)) }
            val copy = f.challenge(owner)
            copy.random[0] = (copy.random[0].toInt() xor 1).toByte()
            reject(401) { f.service.execute(ApiRequest.Verify(owner.registration.accountId, owner.registration.deviceId, copy.id, owner.state.sign(copy))) }
            assertEquals(owner.registration.deviceId, f.call(owner, ApiRequest.Lookup(owner.state.ghostCloakId())).discovery!!.deviceId)
        }
    }
}
