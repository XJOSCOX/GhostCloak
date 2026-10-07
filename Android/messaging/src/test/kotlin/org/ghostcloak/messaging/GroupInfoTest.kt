package org.ghostcloak.messaging

import org.junit.Assert.*
import org.junit.Test

class GroupInfoTest {
    private val owner=GroupInfoMember("o","od",GroupRole.OWNER,false,true,false)
    private val admin=GroupInfoMember("a","ad",GroupRole.ADMIN,false,false,false)
    private val member=GroupInfoMember("m","md",GroupRole.MEMBER,false,false,false)
    private fun info(role:GroupRole,pending:Boolean=false)=GroupInfo("g",role,
        GroupLocalStatus.ACTIVE,GroupManagementStatus.READY,listOf(owner,admin,member),
        GroupPostingModeV1.EVERYONE,pending,false,false)

    @Test fun ownerAdminMemberProjectionMatchesCanonicalActionMatrix() {
        val ownerInfo=info(GroupRole.OWNER)
        assertTrue(ownerInfo.canInvite)
        assertTrue(ownerInfo.canChangePosting)
        assertTrue(ownerInfo.canRestrict(admin))
        assertTrue(ownerInfo.canRemove(admin))
        assertTrue(ownerInfo.canPromote(member))
        assertTrue(ownerInfo.canDemote(admin))
        assertTrue(ownerInfo.canTransfer(member))
        assertTrue(ownerInfo.canDissolve)
        assertFalse(ownerInfo.canLeave)
        val adminInfo=info(GroupRole.ADMIN)
        assertTrue(adminInfo.canInvite)
        assertTrue(adminInfo.canChangePosting)
        assertTrue(adminInfo.canRestrict(member))
        assertTrue(adminInfo.canRemove(member))
        assertFalse(adminInfo.canRestrict(admin))
        assertFalse(adminInfo.canRemove(owner))
        assertFalse(adminInfo.canPromote(member))
        assertFalse(adminInfo.canDemote(admin))
        assertFalse(adminInfo.canTransfer(member))
        assertFalse(adminInfo.canDissolve)
        assertTrue(adminInfo.canLeave)
        val memberInfo=info(GroupRole.MEMBER)
        assertFalse(memberInfo.canInvite)
        assertFalse(memberInfo.canChangePosting)
        assertFalse(memberInfo.canRestrict(member))
        assertFalse(memberInfo.canRemove(member))
        assertTrue(memberInfo.canLeave)
    }

    @Test fun coordinatorPendingAndReadOnlyNeverExposeActions() {
        val coordinator=admin.copy(isCoordinator=true)
        assertFalse(info(GroupRole.OWNER).copy(members=listOf(owner,coordinator,member))
            .canDemote(coordinator))
        assertFalse(info(GroupRole.OWNER,true).canRemove(member))
        assertFalse(info(GroupRole.OWNER).copy(journalFull=true).canDissolve)
        for(status in listOf(GroupManagementStatus.FORKED,
            GroupManagementStatus.LEGACY_INCOMPLETE,GroupManagementStatus.NEEDS_RESYNC)) {
            val locked=info(GroupRole.OWNER).copy(managementStatus=status)
            assertFalse(locked.canChangePosting)
            assertFalse(locked.canRemove(member))
        }
    }
}
