package org.ghostcloak.app.application

import org.ghostcloak.messaging.ExpiryMoment

/** Also filters a stale ViewModel snapshot before its first frame after unlock/resume. */
internal fun AppState.withoutExpired(now: ExpiryMoment): AppState {
    val counts = unreadByConversation.mapValues { (id, count) ->
        (count - unreadExpiries[id].orEmpty().count { it.reached(now) }).coerceAtLeast(0)
    }
    return copy(messages = messages.filterNot { it.expiry?.reached(now) == true },
        previews = previews.filterValues { it.expiry?.reached(now) != true },
        unreadByConversation = counts, unreadCount = counts.values.sum())
}
internal fun AppState.nextExpiryUiDelay(now: ExpiryMoment): Long =
    (messages.mapNotNull { it.expiry } + previews.values.mapNotNull { it.expiry } + unreadExpiries.values.flatten())
        .filterNot { it.reached(now) }.minOfOrNull {
            minOf(it.wall - now.wall, if (it.boot == now.boot) it.elapsed - now.elapsed else Long.MAX_VALUE)
        }?.coerceIn(1, 1000) ?: 1000
