package dev.fajar.starter.documents.domain.metadata.working

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.metadata.commands.MetadataCommandFixture
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingCopy
import dev.fajar.starter.documents.domain.metadata.working.usecases.*
import kotlin.test.assertIs
import kotlinx.coroutines.sync.Mutex

class MetadataWorkingFixture {
    val command = MetadataCommandFixture()
    val reads = command.reads
    val base = command.base
    val copies = command.copies
    val mutex = Mutex()
    val open =
        OpenDocumentMetadataWorkingCopy(
            copies,
            reads.admission,
            reads.catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            reads.catalog.build,
            mutex,
            reads.cache,
        )
    val save =
        SaveDocumentMetadataWorkingCopy(
            copies,
            reads.admission,
            reads.catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            reads.catalog.build,
            mutex,
        )
    val discard =
        DiscardDocumentMetadataWorkingCopy(
            copies,
            reads.admission,
            reads.catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            reads.catalog.build,
            mutex,
        )
    val observe =
        ObserveDocumentMetadataWorkingCopies(
            copies,
            reads.admission,
            reads.catalog.store,
            base.sessionRepository,
            base.workspaceRepository,
            base.clock,
            reads.catalog.build,
        )

    suspend fun opened(): DocumentMetadataWorkingCopy {
        command.view()
        return assertIs<AppResult.Success<DocumentMetadataWorkingCopy>>(
                open(command.workspaceId, reads.id)
            )
            .value
    }
}
