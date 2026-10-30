@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain.metadata.working

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataReadAdmission
import dev.fajar.starter.documents.domain.metadata.repositories.DocumentMetadataAdmissionRepository
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingView
import dev.fajar.starter.documents.domain.metadata.working.usecases.ObserveDocumentMetadataWorkingCopies
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DocumentMetadataWorkingOverviewTest {
    @Test
    fun retainedPagesKeepNeutralRecoveryTargetsAndReleasePreviousPageSubscriptions() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val records =
            (1..45).map { n ->
                original.copy(
                    head =
                        original.head.copy(
                            id = "11111111-1111-4111-8111-${n.toString().padStart(12, '0')}"
                        )
                )
            }
        f.copies.records.value = records.associateBy { it.head.id }
        val active = mutableSetOf<String>()
        val denied = MutableStateFlow(setOf(records[0].head.id, records[19].head.id))
        val unknown = MutableStateFlow(setOf(records[1].head.id))
        val failure = Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied")
        val gates =
            object : DocumentMetadataAdmissionRepository by f.reads.admission {
                override fun observe(access: DocumentAccess, documentId: String) = flow {
                    assertTrue(active.add(documentId))
                    try {
                        emitAll(
                            combine(denied, unknown) { rejected, missing ->
                                if (documentId in missing)
                                    AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
                                else
                                    AppResult.Success(
                                        DocumentMetadataReadAdmission(
                                            null,
                                            failure.takeIf { documentId in rejected },
                                        )
                                    )
                            }
                        )
                    } finally {
                        active.remove(documentId)
                    }
                }
            }
        val observe =
            ObserveDocumentMetadataWorkingCopies(
                f.copies,
                gates,
                f.reads.catalog.store,
                f.base.sessionRepository,
                f.base.workspaceRepository,
                f.base.clock,
                f.reads.catalog.build,
            )
        val cursor = MutableStateFlow<String?>(null)
        val values = mutableListOf<AppResult<DocumentMetadataWorkingView>>()
        val task =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observe(f.command.workspaceId, after = cursor).toList(values)
            }
        runCurrent()
        fun latest() = assertIs<AppResult.Success<DocumentMetadataWorkingView>>(values.last()).value
        assertEquals(17, latest().page.items.size)
        assertEquals(
            listOf(records[0].head.id, records[1].head.id, records[19].head.id),
            latest().restrictions.map { it.documentId },
        )
        assertEquals(records[19].head.id, latest().page.nextDocumentId)
        assertNull(latest().afterDocumentId)
        assertEquals(records.take(20).map { it.head.id }.toSet(), active)
        cursor.value = latest().page.nextDocumentId
        runCurrent()
        assertEquals(records[19].head.id, latest().afterDocumentId)
        assertEquals(records.drop(20).take(20), latest().page.items)
        assertEquals(records.drop(20).take(20).map { it.head.id }.toSet(), active)
        cursor.value = latest().page.nextDocumentId
        runCurrent()
        assertEquals(records[39].head.id, latest().afterDocumentId)
        assertEquals(records.takeLast(5), latest().page.items)
        assertNull(latest().page.nextDocumentId)
        cursor.value = null
        denied.value = emptySet()
        unknown.value = emptySet()
        runCurrent()
        assertEquals(records.take(20), latest().page.items)
        assertTrue(latest().restrictions.isEmpty())
        task.cancelAndJoin()
        assertTrue(active.isEmpty())
        assertTrue(f.command.sent.isEmpty())
    }
}
