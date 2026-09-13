package org.ghostcloak.app.application

import org.ghostcloak.crypto.EndpointRecords
import org.ghostcloak.transport.GhostCloakTransport

/** Release performs no diagnostic reads, wrapping or logging. */
internal object AccountRecoveryDiagnostics {
    fun recovery(event:String) = Unit
    fun path(category: String) = Unit
    fun snapshot(records: EndpointRecords, host: String?) = Unit
    fun wrap(delegate: GhostCloakTransport): GhostCloakTransport = delegate
}
