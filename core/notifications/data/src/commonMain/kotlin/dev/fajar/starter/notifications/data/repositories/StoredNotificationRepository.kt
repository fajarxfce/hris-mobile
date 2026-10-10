package dev.fajar.starter.notifications.data.repositories

import dev.fajar.starter.database.InboxStore
import dev.fajar.starter.notifications.data.mappers.toNotification
import dev.fajar.starter.notifications.data.mappers.toRecord
import dev.fajar.starter.notifications.domain.entities.NotificationMessage
import dev.fajar.starter.notifications.domain.repositories.NotificationRepository
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single
class StoredNotificationRepository(private val store: InboxStore) : NotificationRepository {
    override fun observe() =
        safeStorageFlow(store.observe().map { rows -> rows.map { it.toNotification() } })

    override suspend fun save(message: NotificationMessage) = safeStorageCall {
        store.upsert(message.toRecord())
    }

    override suspend fun markRead(id: String) = safeStorageCall { store.markRead(id) }

    override suspend fun clear() = safeStorageCall { store.clear() }
}
