package org.ghostcloak.app.application

import org.ghostcloak.messaging.ConversationService
import org.ghostcloak.messaging.Message

interface DemoSession {
    val service: ConversationService
    suspend fun deliver(message: Message)
}
