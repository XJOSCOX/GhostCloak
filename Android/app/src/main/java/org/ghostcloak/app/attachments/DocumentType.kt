package org.ghostcloak.app.attachments

import java.io.File

/** A bounded viewer-routing hint, never a claim that an arbitrary document is safe. */
object DocumentType {
    fun sniff(file: File): String = file.inputStream().use {
        val prefix=ByteArray(5)
        if(it.read(prefix)==5 && prefix.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) "application/pdf"
        else "application/octet-stream"
    }
}
