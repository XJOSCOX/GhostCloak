package org.ghostcloak.app.ui.screens

/** Put the last already-seen row above the first unread row; otherwise show the latest row. */
internal fun openingMessageIndex(ids:List<String>,firstUnreadId:String?):Int {
    if(ids.isEmpty()) return 0
    val unreadIndex=firstUnreadId?.let(ids::indexOf) ?: -1
    return if(unreadIndex>=0) (unreadIndex-1).coerceAtLeast(0) else ids.lastIndex
}
