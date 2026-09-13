package org.ghostcloak.attachments

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AttachmentFormatTest {
    @Test fun streamingRoundTripAtSegmentAndPaddingBoundaries() {
        for (length in listOf(1, 65535, 65536, 65537, 1048536, 1048576, 1048577, 2097152)) {
            val directory = Files.createTempDirectory("attachment-test").toFile()
            try {
                val source = ByteArray(length) { (it * 31).toByte() }
                val cipher = directory.resolve("cipher")
                val descriptor = AttachmentFormat.encrypt(source.inputStream(), cipher, length.toLong(), AttachmentKind.DOCUMENT)
                assertEquals(AttachmentFormat.encryptedLength(descriptor.paddedLength), cipher.length())
                val restored = AttachmentFormat.decode(AttachmentFormat.encode(descriptor))
                val output = directory.resolve("output")
                AttachmentFormat.decrypt(restored, cipher, output)
                assertArrayEquals(source, output.readBytes())
                assertEquals("AttachmentDescriptor(redacted)", descriptor.toString())
            } finally { directory.deleteRecursively() }
        }
    }
    @Test fun tamperTruncateAppendAndWrongKeyNeverPublishPlaintext() {
        val directory = Files.createTempDirectory("attachment-test").toFile()
        try {
            val cipher = directory.resolve("cipher")
            val descriptor = AttachmentFormat.encrypt(ByteArray(1500000) { 42 }.inputStream(), cipher, 1500000, AttachmentKind.DOCUMENT)
            val original = cipher.readBytes()
            for (bad in listOf(original.copyOf(original.size - 1), original + byteArrayOf(0), original.copyOf().also { it[50] = (it[50].toInt() xor 1).toByte() })) {
                cipher.writeBytes(bad)
                val output = directory.resolve("output")
                assertThrows(Exception::class.java) { AttachmentFormat.decrypt(descriptor, cipher, output) }
                assertFalse(output.exists())
            }
            cipher.writeBytes(original)
            // Even a matching advertised ciphertext digest cannot bypass AEAD authentication.
            val altered=original.copyOf().also { it[200]=(it[200].toInt() xor 1).toByte() }
            cipher.writeBytes(altered)
            AttachmentFormat.digest(cipher).copyInto(descriptor.digest)
            assertThrows(Exception::class.java) { AttachmentFormat.decrypt(descriptor,cipher,directory.resolve("output")) }
            assertFalse(directory.resolve("output").exists())
            cipher.writeBytes(original)
            AttachmentFormat.digest(cipher).copyInto(descriptor.digest)
            val substituted=AttachmentDescriptor(id="f".repeat(64),capability=descriptor.capability,key=descriptor.key,
                digest=descriptor.digest,plaintextLength=descriptor.plaintextLength,paddedLength=descriptor.paddedLength,
                ciphertextLength=descriptor.ciphertextLength,kind=descriptor.kind)
            assertThrows(Exception::class.java) { AttachmentFormat.decrypt(substituted,cipher,directory.resolve("output")) }
            assertFalse(directory.resolve("output").exists())
            descriptor.key[0] = (descriptor.key[0].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { AttachmentFormat.decrypt(descriptor, cipher, directory.resolve("output")) }
            assertFalse(directory.resolve("output").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun forgedDescriptorParametersAreRejectedBeforeIo() {
        fun descriptor(version:Int=1,algorithm:String=AttachmentFormat.ALGORITHM,key:ByteArray=ByteArray(16),
            length:Long=1,cipherLength:Long=65576,id:String="a".repeat(64),filename:String?=null) =
            AttachmentDescriptor(version,algorithm,id,ByteArray(32),key,ByteArray(32),length,65536,cipherLength,AttachmentKind.IMAGE,filename)
        listOf(descriptor(version=2),descriptor(algorithm="AES-GCM"),descriptor(key=ByteArray(32)),
            descriptor(length=Long.MAX_VALUE),descriptor(cipherLength=Long.MAX_VALUE),descriptor(id="../bad"),
            descriptor(filename="../private.txt")).forEach {
            assertThrows(Exception::class.java) {AttachmentFormat.encode(it)}
        }
        assertEquals(65576L,AttachmentFormat.encryptedLength(65536))
    }
    @Test fun freshKeysIdsAndCapabilitiesAndBoundedSources() {
        val directory = Files.createTempDirectory("attachment-test").toFile()
        try {
            val a = AttachmentFormat.encrypt(byteArrayOf(1).inputStream(), directory.resolve("a"), 1, AttachmentKind.IMAGE)
            val b = AttachmentFormat.encrypt(byteArrayOf(1).inputStream(), directory.resolve("b"), 1, AttachmentKind.IMAGE)
            assertNotEquals(a.id, b.id); assertFalse(a.key.contentEquals(b.key)); assertFalse(a.capability.contentEquals(b.capability))
            assertThrows(Exception::class.java) { AttachmentFormat.encrypt(byteArrayOf(1,2).inputStream(), directory.resolve("c"), 1, AttachmentKind.IMAGE) }
            assertFalse(directory.resolve("c").exists())
            assertThrows(Exception::class.java) { AttachmentFormat.decode(AttachmentFormat.encode(a) + byteArrayOf(0)) }
        } finally { directory.deleteRecursively() }
    }
}
