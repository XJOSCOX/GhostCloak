package org.ghostcloak.messaging

import java.util.Locale

/** Sort only presentation-safe activity; request content never influences placement. */
object ChatOrganization {
    fun main(contacts:List<ContactStatus>, previews:Map<String,Message>):List<ContactStatus> =
        contacts.filter { !it.contact.blocked && (it.contact.request || !it.contact.archived) }
            .sortedWith(order(previews))

    fun archived(contacts:List<ContactStatus>, previews:Map<String,Message>):List<ContactStatus> =
        contacts.filter { !it.contact.blocked && !it.contact.request && it.contact.archived }
            .sortedWith(order(previews))

    private fun order(previews:Map<String,Message>)=compareByDescending<ContactStatus> {
        it.contact.pinned && !it.contact.request
    }.thenByDescending {
        if(it.contact.request) 0L else previews[it.contact.remoteDeviceId]?.timestamp ?: 0L
    }.thenBy {it.contact.visibleName.lowercase(Locale.ROOT)}
        .thenBy {it.contact.remoteDeviceId}
}
