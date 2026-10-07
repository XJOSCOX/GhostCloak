package org.ghostcloak.messaging

import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.identity.RandomIdentifiers
import org.ghostcloak.protocol.DeviceAuth
import org.ghostcloak.protocol.NetworkCodec
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.imageio.ImageIO

class GroupProfileV1Test {
    private class Records : EndpointRecords {
        val values = mutableMapOf<String, ByteArray>()
        override fun <T> transaction(block: () -> T): T = block()
        override fun read(key: String) = values[key]?.copyOf()
        override fun write(key: String, value: ByteArray) { values[key] = value.copyOf() }
        override fun remove(key: String) { values.remove(key) }
        override fun keys(prefix: String) = values.keys.filter { it.startsWith(prefix) }
    }

    private fun jpeg(): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "jpg", output))
        return output.toByteArray()
    }

    @Test fun canonicalTextAndPhotoCommitmentRejectInvalidInput() {
        val activation = DeviceAuth.digest(byteArrayOf(1))
        val photo = jpeg()
        val ref = GroupProfilePhotoRefV1(digest = DeviceAuth.digest(photo), length = photo.size)
        val profile = GroupProfileRulesV1.normalize("  A group  ", "  About  ", ref)
        assertEquals("A group", profile.name)
        assertEquals("About", profile.about)
        assertTrue(GroupProfileRulesV1.validPhotoBytes(photo, ref))
        assertFalse(GroupProfileRulesV1.validPhotoBytes(photo, ref.copy(digest = ByteArray(32))))
        assertFalse(GroupProfileRulesV1.digest(activation, profile).contentEquals(
            GroupProfileRulesV1.digest(activation, profile.copy(photo = null))))
        for (invalid in listOf("x\u202e", "x\u2066", "x\u200f", "x\u061c", "x\ny", "\nx"))
            assertThrows(IllegalArgumentException::class.java) {
                GroupProfileRulesV1.normalize(invalid, "", null)
            }
        assertThrows(IllegalArgumentException::class.java) {
            GroupProfileRulesV1.normalize("x".repeat(65), "", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupProfileRulesV1.normalize("x", "a".repeat(257), null)
        }
    }

    @Test fun companionIsBoundedEvidenceAndCannotSelectPhoto() {
        val photo = jpeg()
        val id = GroupIds.create()
        val activation = DeviceAuth.digest(byteArrayOf(2))
        val head = DeviceAuth.digest(byteArrayOf(3))
        val profile = GroupProfileV1(photo = GroupProfilePhotoRefV1(
            digest = DeviceAuth.digest(photo), length = photo.size))
        val profileDigest = GroupProfileRulesV1.digest(activation, profile)
        val companion = GroupProfilePhotoCompanionV1(groupId = id,
            activationDigest = activation, governanceSequence = 1,
            governanceHeadDigest = head, profileDigest = profileDigest,
            photoDigest = DeviceAuth.digest(photo), photoLength = photo.size, photoBytes = photo)
        assertTrue(GroupProfileRulesV1.validCompanion(companion))
        val records = Records()
        val store = GroupProfilePhotoStoreV1(records)
        store.hold(companion)
        assertNull(store.verified(id, activation, profile.photo))
        store.acceptCurrent(id, activation, 1, head, profile, profileDigest)
        assertArrayEquals(photo, store.verified(id, activation, profile.photo))
        assertFalse(store.accept(companion.copy(governanceHeadDigest = ByteArray(32)), profile,
            1, head, profileDigest))
        assertFalse(store.accept(companion.copy(profileDigest = ByteArray(32)), profile,
            1, head, profileDigest))
        assertFalse(GroupProfileRulesV1.validCompanion(companion.copy(photoDigest = ByteArray(32))))
        assertFalse(store.accept(companion, profile.copy(photo = null), 1, head, profileDigest))
        assertTrue(store.accept(companion, profile, 1, head, profileDigest))
        store.acceptCurrent(id, activation, 2, DeviceAuth.digest(byteArrayOf(4)),
            profile.copy(photo = null), GroupProfileRulesV1.digest(activation, profile.copy(photo = null)))
        assertNull(store.verified(id, activation, profile.photo))
        assertTrue(records.keys("app/group-profile/photo-v1/").isEmpty())
    }

    @Test fun maximumCompanionFitsUnchangedControlAndSignalFrame() {
        val source = jpeg()
        val photo = source.copyOfRange(0, source.size - 2) +
            ByteArray(ProfileRules.MAX_PHOTO_BYTES - source.size) { 0x7f } +
            source.copyOfRange(source.size - 2, source.size)
        assertEquals(8192, photo.size)
        ProfileRules.photo(photo)
        val activation = DeviceAuth.digest(byteArrayOf(5))
        val profile = GroupProfileV1(name = "N".repeat(64), about = "A".repeat(256),
            photo = GroupProfilePhotoRefV1(digest = DeviceAuth.digest(photo), length = photo.size))
        val companion = GroupProfilePhotoCompanionV1(groupId = GroupIds.create(),
            activationDigest = activation, governanceSequence = 511,
            governanceHeadDigest = ByteArray(32),
            profileDigest = GroupProfileRulesV1.digest(activation, profile),
            photoDigest = DeviceAuth.digest(photo), photoLength = photo.size,
            photoBytes = photo)
        val control = GroupControl(kind = GroupControlKind.GOVERNANCE_PROFILE_PHOTO_V1,
            groupId = companion.groupId, groupProfilePhotoV1 = companion)
        val encoded = GroupControlCodec.encode(control)
        val frame = ConversationPayload.encodeGroup(control)
        assertTrue(GroupProfileRulesV1.validCompanion(companion))
        assertTrue(encoded.size <= GroupControlCodec.MAX_BYTES)
        assertTrue(frame.size <= 16_384)
        assertEquals(control.kind, ConversationPayload.decode(frame).groupControl!!.kind)
        println("P13_7_COMPANION photo=${photo.size} companion=${NetworkCodec.encode(companion).size} control=${encoded.size} frame=${frame.size} controlHeadroom=${GroupControlCodec.MAX_BYTES-encoded.size}")
    }

    @Test fun remainingMemberRetainsPhotoAfterOriginalEditorLeavesAndCanRebindCurrentHead() {
        val photo = jpeg()
        val id = GroupIds.create()
        val activation = DeviceAuth.digest(byteArrayOf(21))
        val originalHead = DeviceAuth.digest(byteArrayOf(22))
        val laterHead = DeviceAuth.digest(byteArrayOf(23))
        val profile = GroupProfileV1(name = "Family", photo = GroupProfilePhotoRefV1(
            digest = DeviceAuth.digest(photo), length = photo.size))
        val digest = GroupProfileRulesV1.digest(activation, profile)
        val companion = GroupProfilePhotoCompanionV1(groupId = id, activationDigest = activation,
            governanceSequence = 4, governanceHeadDigest = originalHead,
            profileDigest = digest, photoDigest = DeviceAuth.digest(photo),
            photoLength = photo.size, photoBytes = photo)
        val editor = GroupProfilePhotoStoreV1(Records())
        val remaining = GroupProfilePhotoStoreV1(Records())
        assertTrue(editor.accept(companion, profile, 4, originalHead, digest))
        assertTrue(remaining.accept(companion, profile, 4, originalHead, digest))
        editor.clear(id)
        assertArrayEquals(photo, remaining.verified(id, activation, profile.photo))
        val rebound = companion.copy(governanceSequence = 5, governanceHeadDigest = laterHead)
        val joining = GroupProfilePhotoStoreV1(Records())
        assertTrue(joining.accept(rebound, profile, 5, laterHead, digest))
        assertArrayEquals(photo, joining.verified(id, activation, profile.photo))
    }

    @Test fun earlyEvidenceIsBoundedAndSurvivesStoreRestartButStaysHidden() {
        val photo = jpeg()
        val id = GroupIds.create()
        val activation = DeviceAuth.digest(byteArrayOf(31))
        val profile = GroupProfileV1(photo = GroupProfilePhotoRefV1(
            digest = DeviceAuth.digest(photo), length = photo.size))
        val digest = GroupProfileRulesV1.digest(activation, profile)
        val records = Records()
        val store = GroupProfilePhotoStoreV1(records)
        val companions = (1L..4L).map { sequence -> GroupProfilePhotoCompanionV1(
            groupId = id, activationDigest = activation, governanceSequence = sequence,
            governanceHeadDigest = DeviceAuth.digest(byteArrayOf(sequence.toByte())),
            profileDigest = digest, photoDigest = DeviceAuth.digest(photo),
            photoLength = photo.size, photoBytes = photo) }
        companions.take(3).forEach(store::hold)
        store.hold(companions.first()) // exact replay is idempotent
        assertNull(GroupProfilePhotoStoreV1(records).verified(id, activation, profile.photo))
        assertThrows(IllegalArgumentException::class.java) { store.hold(companions.last()) }
        val noPhoto=GroupProfileV1(name="Waiting")
        GroupProfilePhotoStoreV1(records).acceptCurrent(id, activation, 1,
            companions.first().governanceHeadDigest,noPhoto,
            GroupProfileRulesV1.digest(activation,noPhoto))
        assertNull(store.verified(id,activation,profile.photo))
        val head = companions[1].governanceHeadDigest
        GroupProfilePhotoStoreV1(records).acceptCurrent(id, activation, 2, head, profile, digest)
        assertArrayEquals(photo, GroupProfilePhotoStoreV1(records).verified(id, activation, profile.photo))
    }

    @Test fun signedEntryUsesSameGovernanceHeadAndRejectsStaleOrMemberActor() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val owner = generator.generateKeyPair()
        val member = generator.generateKeyPair()
        val ownerId = GroupIds.create()
        val memberId = GroupIds.create()
        val people = listOf(ownerId to owner, memberId to member).sortedBy { it.first }
        val actualOwner = people.first()
        val actualMember = people.last()
        val state = GroupState(GroupIds.create(), 2, 2, actualOwner.first, actualOwner.first,
            people.mapIndexed { index, pair -> GroupMember(pair.first,
                RandomIdentifiers.create(), RandomIdentifiers.create(), pair.second.public.encoded,
                ByteArray(32), if(index==0) GroupRole.OWNER else GroupRole.MEMBER,
                (index+1).toLong()) }, ByteArray(32))
        val activation = DeviceAuth.digest(byteArrayOf(6))
        val head = GovernanceHeadFoundationV1(groupId = state.groupId,
            activationDigest = activation, sequence = 0, headDigest = activation,
            stateRevision = state.revision, stateDigest = GroupStatements.digest(state))
        val next = GroupProfileV1(name = "New name")
        val unsigned = GroupGovernanceProfileEntryV1(groupId = state.groupId,
            activationDigest = activation, sequence = 1, previousHeadDigest = activation,
            eventId = GroupIds.create(), stateRevision = state.revision,
            stateDigest = GroupStatements.digest(state), actorId = actualOwner.first,
            preProfileDigest = GroupProfileRulesV1.digest(activation, GroupProfileRulesV1.default),
            postProfileDigest = GroupProfileRulesV1.digest(activation, next), profile = next,
            actorSignature = byteArrayOf(), coordinatorSignature = byteArrayOf())
        fun sign(bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initSign(actualOwner.second.private); update(bytes); sign()
        }
        val entry = unsigned.copy(actorSignature = sign(GroupProfileRulesV1.actorStatement(unsigned)),
            coordinatorSignature = sign(GroupProfileRulesV1.coordinatorStatement(unsigned)))
        assertTrue(GroupProfileRulesV1.verifyEntry(entry, state, head, GroupProfileRulesV1.default))
        assertFalse(GroupProfileRulesV1.verifyEntry(entry, state, head.copy(sequence = 1),
            GroupProfileRulesV1.default))
        assertFalse(GroupProfileRulesV1.verifyEntry(entry, state, head, next))
        val changedActor = unsigned.copy(actorId = actualMember.first)
        val memberSigned = changedActor.copy(actorSignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(actualMember.second.private); update(GroupProfileRulesV1.actorStatement(changedActor)); sign()
        }, coordinatorSignature = sign(GroupProfileRulesV1.coordinatorStatement(changedActor)))
        assertFalse(GroupProfileRulesV1.verifyEntry(memberSigned, state, head,
            GroupProfileRulesV1.default))
    }
}
