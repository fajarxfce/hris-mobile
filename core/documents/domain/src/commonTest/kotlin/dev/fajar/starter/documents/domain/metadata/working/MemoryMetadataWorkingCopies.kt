package dev.fajar.starter.documents.domain.metadata.working

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import dev.fajar.starter.documents.domain.metadata.working.repositories.DocumentMetadataWorkingRepository
import kotlinx.coroutines.flow.*

class MemoryMetadataWorkingCopies : DocumentMetadataWorkingRepository {
    val records = MutableStateFlow<Map<String, DocumentMetadataWorkingCopy>>(emptyMap())
    var beforeCurrent: suspend () -> Unit = {}
    var beforeSave: suspend () -> Unit = {}
    var afterSave: suspend () -> Unit = {}
    var beforeRemove: suspend () -> Unit = {}
    var loseSaveAcknowledgement = false
    var loseRemoveAcknowledgement = false
    var removeFailure = false
    var observationFailure: Failure? = null
    var active = 0
    var observations = 0
    var saves = 0
    var removals = 0

    override fun observe(
        access: DocumentAccess,
        documentId: String,
    ): Flow<AppResult<DocumentMetadataWorkingCopy?>> = flow {
        observations++
        val failure = observationFailure
        if (failure != null) {
            emit(AppResult.Failed(failure))
            return@flow
        }
        active++
        try {
            emitAll(records.map { AppResult.Success(it[documentId]) })
        } finally {
            active--
        }
    }

    override fun observePage(access: DocumentAccess, afterDocumentId: String?) =
        records.map { values ->
            val selected =
                values.values
                    .filter { afterDocumentId == null || it.head.id > afterDocumentId }
                    .sortedBy { it.head.id }
            val page = selected.take(20)
            AppResult.Success(
                DocumentMetadataWorkingPage(
                    page,
                    page.lastOrNull()?.head?.id?.takeIf { selected.size > 20 },
                )
            )
        }

    override suspend fun current(
        access: DocumentAccess,
        documentId: String,
    ): AppResult<DocumentMetadataWorkingCopy?> {
        beforeCurrent()
        return AppResult.Success(records.value[documentId])
    }

    override suspend fun save(
        access: DocumentAccess,
        expected: DocumentMetadataWorkingCopy?,
        next: DocumentMetadataWorkingCopy,
    ): AppResult<Boolean> {
        saves++
        beforeSave()
        if (records.value[next.head.id] != expected) return AppResult.Success(false)
        records.value += next.head.id to next
        afterSave()
        return if (loseSaveAcknowledgement)
            AppResult.Failed(Failure(FailureKind.Storage, "Lost local response"))
        else AppResult.Success(true)
    }

    override suspend fun remove(
        access: DocumentAccess,
        expected: DocumentMetadataWorkingCopy,
    ): AppResult<Boolean> {
        removals++
        beforeRemove()
        if (removeFailure)
            return AppResult.Failed(Failure(FailureKind.Storage, "Fixture remove failure"))
        if (records.value[expected.head.id] != expected) return AppResult.Success(false)
        records.value -= expected.head.id
        return if (loseRemoveAcknowledgement)
            AppResult.Failed(Failure(FailureKind.Storage, "Lost local response"))
        else AppResult.Success(true)
    }
}
