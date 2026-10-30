package dev.fajar.starter.documents.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataReadAdmission
import dev.fajar.starter.documents.domain.metadata.repositories.DocumentMetadataAdmissionRepository
import kotlinx.coroutines.flow.*

class CatalogResourceAdmission : DocumentMetadataAdmissionRepository {
    private val states =
        mutableMapOf<String, MutableStateFlow<AppResult<DocumentMetadataReadAdmission>>>()
    var active = 0
    private var revision = 0

    private fun state(id: String) =
        states.getOrPut(id) {
            MutableStateFlow(AppResult.Success(DocumentMetadataReadAdmission(null, null)))
        }

    fun fail(id: String, failure: Failure) {
        state(id).value = AppResult.Failed(failure)
    }

    override fun observe(access: DocumentAccess, documentId: String) = flow {
        active++
        try {
            emitAll(state(documentId))
        } finally {
            active--
        }
    }

    override suspend fun current(access: DocumentAccess, documentId: String) =
        state(documentId).value

    override suspend fun restrict(
        access: DocumentAccess,
        documentId: String,
        failure: Failure,
    ): AppResult<Boolean> {
        state(documentId).value =
            AppResult.Success(DocumentMetadataReadAdmission((++revision).toString(), failure))
        return AppResult.Success(true)
    }

    override suspend fun admit(
        access: DocumentAccess,
        documentId: String,
        expectedRevision: String?,
    ): AppResult<Boolean> {
        val previous = state(documentId).value
        if (previous !is AppResult.Success || previous.value.revision != expectedRevision)
            return AppResult.Success(false)
        state(documentId).value = AppResult.Success(DocumentMetadataReadAdmission(null, null))
        return AppResult.Success(true)
    }
}
