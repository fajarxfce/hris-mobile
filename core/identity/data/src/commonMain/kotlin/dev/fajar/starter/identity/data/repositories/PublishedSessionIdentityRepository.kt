package dev.fajar.starter.identity.data.repositories

import dev.fajar.starter.identity.data.datasources.SessionDataSource
import dev.fajar.starter.identity.data.mappers.toSession
import dev.fajar.starter.identity.domain.entities.SessionIdentity
import dev.fajar.starter.identity.domain.repositories.SessionIdentityRepository
import dev.fajar.starter.storage.safeStorageFlow
import org.koin.core.annotation.Single

/** Maps the session datasource's already-published value; no vault, database or session writer. */
@Single
class PublishedSessionIdentityRepository(private val memory: SessionDataSource) :
    SessionIdentityRepository {
    override fun observePublished() =
        safeStorageFlow(
            memory.record,
            mapValue = { record -> record?.toSession()?.let { SessionIdentity(it.id, it.user) } },
        )
}
