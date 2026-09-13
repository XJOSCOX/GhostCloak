package org.ghostcloak.app.attachments

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.ghostcloak.app.application.GhostApplication

/** No filesystem path mapping. Exactly one short-lived, read-only explicit viewer capability. */
class AttachmentViewerProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri) = (context!!.applicationContext as GhostApplication).media.leaseFile(uri)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if(mode != "r") throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String = DocumentType.sniff(file(uri))
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val source=file(uri)
        val columns=projection?.filter { it in setOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE) }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if(it==OpenableColumns.SIZE) source.length() else "Attachment" }) }
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri? = throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int = throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int = throw UnsupportedOperationException()
}
