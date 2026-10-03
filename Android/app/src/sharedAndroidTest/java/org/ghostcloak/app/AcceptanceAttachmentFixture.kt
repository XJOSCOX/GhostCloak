package org.ghostcloak.app

import org.ghostcloak.attachments.*
import org.ghostcloak.protocol.BlobResult
import java.io.File

/** Socket-free blob service; exercises public production transitions without editing journals. */
internal suspend fun prepareAcceptanceAttachment(
    store: AttachmentStore, bytes: ByteArray, kind: AttachmentKind, reference: String,
): AttachmentDescriptor {
    val descriptor = store.prepare(bytes.inputStream(), bytes.size.toLong(), kind, 0,
        if (kind == AttachmentKind.DOCUMENT) "synthetic.txt" else null) { true }
    check(store.entry(descriptor.id)?.state == TransferState.ENCRYPTED)
    val client = object : BlobTransferClient {
        override suspend fun reserve(descriptor: AttachmentDescriptor): BlobResult {
            check(store.entry(descriptor.id)?.state == TransferState.ENCRYPTED)
            return BlobResult(complete = false, expiresAt = Long.MAX_VALUE)
        }
        override suspend fun upload(descriptor: AttachmentDescriptor, ciphertext: File): BlobResult {
            check(store.entry(descriptor.id)?.state == TransferState.UPLOADING)
            check(ciphertext.length() == descriptor.ciphertextLength)
            check(AttachmentFormat.digest(ciphertext).contentEquals(descriptor.digest))
            return BlobResult(complete = true, expiresAt = Long.MAX_VALUE)
        }
        override suspend fun download(descriptor: AttachmentDescriptor, ciphertext: File) {
            error("bound_upload_should_use_local_ciphertext")
        }
    }
    store.upload(descriptor.id, client) { true }
    check(store.entry(descriptor.id)?.state == TransferState.UPLOADED)
    store.bind(descriptor.id, reference)
    check(store.entry(descriptor.id)?.references?.contains(reference) == true)
    store.download(descriptor, reference, client) { true }.use { verified ->
        val output = java.io.ByteArrayOutputStream()
        verified.copyTo(output)
        check(output.toByteArray().contentEquals(bytes))
    }
    check(store.entry(descriptor.id)?.state == TransferState.READY)
    return descriptor
}
