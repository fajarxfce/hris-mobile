@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain.metadata.commands

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.repositories.DocumentMetadataCommandRepository
import dev.fajar.starter.documents.domain.metadata.commands.usecases.ObserveDocumentMetadataCommands
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DocumentMetadataCommandAdmissionTest {
    @Test
    fun aKnownDocumentDenialDoesNotSubscribeToUnreadableCommandStorage() = runTest {
        val f = MetadataCommandFixture()
        f.prepared()
        var subscriptions = 0
        val storage = Failure(FailureKind.Storage, "Unavailable", code = "client.storage")
        val commands =
            object : DocumentMetadataCommandRepository by f.commands {
                override fun observe(
                    access: DocumentAccess,
                    documentId: String,
                ): Flow<AppResult<DocumentMetadataCommandRecord?>> = flow {
                    subscriptions++
                    emit(AppResult.Failed(storage))
                }
            }
        val denial = Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied")
        f.reads.admission.restrict(f.access, f.reads.id, denial)
        val result = observe(f, commands)(f.workspaceId, f.reads.id).first()
        val view = assertIs<AppResult.Success<DocumentMetadataCommandsView>>(result).value
        assertEquals(listOf(denial), view.restrictions)
        assertTrue(view.records.isEmpty())
        assertEquals(0, subscriptions)
    }

    @Test
    fun aLaterDenialClosesFailedStorageAndReopensOnlyAfterResourceAdmission() = runTest {
        val f = MetadataCommandFixture()
        val pending = f.prepared()
        val records =
            MutableStateFlow<AppResult<DocumentMetadataCommandRecord?>>(AppResult.Success(pending))
        var active = 0
        val commands =
            object : DocumentMetadataCommandRepository by f.commands {
                override fun observe(access: DocumentAccess, documentId: String) = flow {
                    active++
                    try {
                        emitAll(records)
                    } finally {
                        active--
                    }
                }
            }
        val values = mutableListOf<AppResult<DocumentMetadataCommandsView>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observe(f, commands)(f.workspaceId, f.reads.id).collect { values += it }
            }
        runCurrent()
        assertEquals(
            listOf(pending),
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(values.last()).value.records,
        )
        val failure = Failure(FailureKind.Storage, "Unavailable", code = "client.storage")
        records.value = AppResult.Failed(failure)
        runCurrent()
        assertEquals(AppResult.Failed(failure), values.last())
        val denial = Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied")
        f.reads.admission.restrict(f.access, f.reads.id, denial)
        runCurrent()
        val denied = assertIs<AppResult.Success<DocumentMetadataCommandsView>>(values.last()).value
        assertTrue(denied.records.isEmpty())
        assertEquals(listOf(denial), denied.restrictions)
        assertEquals(0, active)
        records.value = AppResult.Success(pending)
        runCurrent()
        assertEquals(
            denied,
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(values.last()).value,
        )
        f.reads.admission.admit(f.access, f.reads.id, f.reads.admission.state.value.revision)
        runCurrent()
        assertEquals(
            listOf(pending),
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(values.last()).value.records,
        )
        assertEquals(1, active)
        reader.cancelAndJoin()
        assertEquals(0, active)
        assertEquals(0, f.reads.admission.state.subscriptionCount.value)
    }

    private fun observe(f: MetadataCommandFixture, commands: DocumentMetadataCommandRepository) =
        ObserveDocumentMetadataCommands(
            commands,
            f.reads.admission,
            f.reads.catalog.store,
            f.base.sessionRepository,
            f.base.workspaceRepository,
            f.base.clock,
            f.reads.catalog.build,
        )
}
