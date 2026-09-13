package org.ghostcloak.app.application

import org.ghostcloak.attachments.AttachmentEvent

internal object AttachmentDiagnostics {
    val observer: ((AttachmentEvent)->Unit)? = { event -> android.util.Log.d("GhostCloakAttach",event.name); Unit }
}
