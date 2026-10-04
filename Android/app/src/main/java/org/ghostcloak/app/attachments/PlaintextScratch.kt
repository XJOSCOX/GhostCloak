package org.ghostcloak.app.attachments

import org.ghostcloak.attachments.AttachmentFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Private, no-backup plaintext presentation scratch. A directory sweep is its durable retry
 * marker: no filename, contact, or message identifier is written to another store. */
internal class PlaintextScratch(
    val root: File,
    private val remove: (File) -> Boolean = File::delete,
) {
    @Volatile private var ready = false

    init {
        check(root.isDirectory || root.mkdirs()) { "scratch_unavailable" }
        sweep()
    }

    val clean: Boolean get() = ready

    fun invalidate() { ready = false }

    fun newFile(): File {
        check(ready) { "scratch_cleanup_pending" }
        return File(root, AttachmentFormat.newId())
    }

    /** A failed unlink keeps presentation unavailable until the next successful sweep. */
    @Synchronized fun delete(file: File): Boolean {
        if (file.parentFile != root) { ready = false; error("invalid_scratch_target") }
        return try {
            val path = file.toPath()
            // notExists is false when the filesystem cannot establish absence.
            val deleted = Files.notExists(path, LinkOption.NOFOLLOW_LINKS) ||
                (remove(file) && Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
            if (!deleted) ready = false
            deleted
        } catch (_: Exception) {
            ready = false
            false
        }
    }

    /** Call after all owned streams and leases are closed. Unknown entries fail closed. */
    @Synchronized fun sweep(): Boolean {
        val entries = root.listFiles() ?: run { ready = false; return false }
        var complete = true
        entries.forEach { entry ->
            if (!entry.isFile || !delete(entry)) complete = false
        }
        ready = complete && (root.listFiles()?.isEmpty() == true)
        return ready
    }
}
