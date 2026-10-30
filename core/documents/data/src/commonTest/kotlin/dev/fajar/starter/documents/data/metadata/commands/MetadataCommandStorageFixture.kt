package dev.fajar.starter.documents.data.metadata.commands

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.DocumentMetadataRemoteFixture
import dev.fajar.starter.documents.data.metadata.commands.mappers.*
import dev.fajar.starter.documents.data.metadata.commands.repositories.EncryptedDocumentMetadataCommandRepository
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlin.time.Instant

class MetadataCommandStorageFixture(private val open: () -> AppDatabase) {
    private val remote = DocumentMetadataRemoteFixture()
    val access = remote.access
    var db = open()
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var repository = repository()
    val command =
        DocumentMetadataCommand(
            access.accountId,
            access.audience,
            access.companyId,
            remote.head.employmentId,
            remote.head.title,
            remote.change.copy(reason = " Original PRIVATE reason "),
            Instant.parse(remote.head.createdAt),
        )

    fun repository(store: CommandStore = db.commands, content: ContentCipher = cipher) =
        EncryptedDocumentMetadataCommandRepository(store, content, keys)

    suspend fun activate(owner: DocumentAccess = access) {
        db.accounts.activate(owner.sessionId, metadataCommandScope(owner).accountKey)
    }

    suspend fun stored(owner: DocumentAccess = access, id: String = command.change.operationId) =
        assertNotNull(
            assertIs<AppResult.Success<DocumentMetadataCommandRecord?>>(repository.find(owner, id))
                .value
        )

    suspend fun reopen(owner: DocumentAccess = access) {
        db.close()
        db = open()
        repository = repository()
        activate(owner)
    }

    suspend fun close() {
        db.close()
        keys.write(null)
    }
}
