package org.ghostcloak.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ViewerGrantRevocationTest {
    @Test fun revokingViewerRootRemovesPreviouslyGrantedChildUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recipient = "${context.packageName}.test"
        val uid = context.packageManager.getPackageUid(recipient, 0)
        val root = Uri.parse("content://${context.packageName}.attachment-view/")
        val child = root.buildUpon().appendPath(UUID.randomUUID().toString()).build()
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            context.grantUriPermission(recipient, child, read)
            assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkUriPermission(child, -1, uid, read))
            context.revokeUriPermission(root, read)
            assertEquals(PackageManager.PERMISSION_DENIED,
                context.checkUriPermission(child, -1, uid, read))
        } finally { context.revokeUriPermission(child, read) }
    }
}
