package dev.fajar.starter.inbox.domain.push.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.inbox.domain.push.entities.InboxPushQueue

interface InboxPushQueueRepository {
    suspend fun read(): AppResult<InboxPushQueue>

    suspend fun compareAndSet(expected: InboxPushQueue, updated: InboxPushQueue): AppResult<Boolean>
}
