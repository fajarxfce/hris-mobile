@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain.metadata

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.repositories.DocumentMetadataCommandRepository
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataReadAdmission
import dev.fajar.starter.documents.domain.metadata.policies.documentMetadataPendingSummary
import dev.fajar.starter.documents.domain.metadata.repositories.DocumentMetadataAdmissionRepository
import dev.fajar.starter.documents.domain.metadata.usecases.ObserveDocumentMetadataPending
import dev.fajar.starter.documents.domain.metadata.working.MetadataWorkingFixture
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import dev.fajar.starter.documents.domain.metadata.working.repositories.DocumentMetadataWorkingRepository
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DocumentMetadataPendingTest {
    @Test
    fun originalOutcomesCountOnceAndLaterOrIncompleteEditsRemainDrafts() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val record = f.command.prepared()
        val matching =
            original.copy(revision = 1, input = f.command.input, reason = " User correction ")
        f.copies.records.value = mapOf(original.head.id to matching)
        val outcomes =
            listOf(
                DocumentMetadataCommandOutcome.Pending to PendingActionSummary(queued = 1),
                DocumentMetadataCommandOutcome.Unconfirmed() to
                    PendingActionSummary(unconfirmed = 1),
                DocumentMetadataCommandOutcome.Rejected(
                    Failure(FailureKind.Conflict, "Rejected", code = "stale_version")
                ) to PendingActionSummary(needsReview = 1),
                DocumentMetadataCommandOutcome.Confirmed(4) to PendingActionSummary(),
            )
        for ((outcome, expected) in outcomes) {
            f.command.commands.records.value = listOf(record.copy(outcome = outcome))
            assertEquals(expected, summary(f))
        }
        for (later in
            listOf(
                matching.copy(updatedAt = record.command.createdAt + 1.seconds),
                matching.copy(input = matching.input.copy(validUntil = "202")),
                matching.copy(reason = "New reason"),
                matching.copy(head = matching.head.copy(version = 4)),
            )) {
            f.copies.records.value = mapOf(original.head.id to later)
            assertEquals(PendingActionSummary(drafts = 1), summary(f))
        }
        assertTrue(f.command.sent.isEmpty())
    }

    @Test
    fun untouchedInputIsExcludedAndOlderOwnedDraftsUseOnlyTheFirstTwentySlots() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        assertEquals(PendingActionSummary(), summary(f))
        val values =
            (1..45).map { n ->
                original.copy(
                    reason = "Edited $n",
                    head =
                        original.head.copy(
                            id = "11111111-1111-4111-8111-${n.toString().padStart(12, '0')}"
                        ),
                )
            }
        f.copies.records.value = values.associateBy { it.head.id }
        f.base.now += 365.days
        var reads = 0
        var active = 0
        val admissions =
            object : DocumentMetadataAdmissionRepository by f.reads.admission {
                override fun observe(access: DocumentAccess, documentId: String) = flow {
                    reads++
                    active++
                    try {
                        emitAll(f.reads.admission.observe(access, documentId))
                    } finally {
                        active--
                    }
                }
            }
        val remoteReads = f.reads.headReads
        assertEquals(
            PendingActionSummary(drafts = 20, moreDrafts = true),
            assertIs<AppResult.Success<PendingActionSummary>>(
                    observe(f, admission = admissions).observePending(f.command.workspaceId).first()
                )
                .value,
        )
        assertEquals(20, reads)
        assertEquals(0, active)
        assertEquals(remoteReads, f.reads.headReads)
        assertTrue(f.command.sent.isEmpty())
    }

    @Test
    fun companyDenialPreventsPrivateStorageAndOnlyAdmittedRecoveryReopensTheSummary() = runTest {
        val f = MetadataWorkingFixture()
        f.opened()
        f.command.prepared()
        val denial = Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required")
        f.reads.catalog.store.restrict(f.command.access, denial)
        var reads = 0
        val commands =
            object : DocumentMetadataCommandRepository by f.command.commands {
                override fun observeAll(
                    access: DocumentAccess
                ): Flow<AppResult<List<DocumentMetadataCommandRecord>>> {
                    reads++
                    return f.command.commands.observeAll(access)
                }
            }
        val observer = observe(f, commands = commands)
        assertEquals(
            AppResult.Failed(denial),
            observer.observePending(f.command.workspaceId).first(),
        )
        assertEquals(0, reads)
        val gate =
            assertIs<
                    AppResult.Success<
                        dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogReadAdmission
                    >
                >(
                    f.reads.catalog.store.admission(f.command.access)
                )
                .value
        assertEquals(
            AppResult.Success(true),
            f.reads.catalog.store.admit(f.command.access, gate.revision),
        )
        assertEquals(
            PendingActionSummary(queued = 1),
            assertIs<AppResult.Success<PendingActionSummary>>(
                    observer.observePending(f.command.workspaceId).first()
                )
                .value,
        )
        assertEquals(1, reads)
    }

    @Test
    fun discoveredResourceDenialSurvivesFailedStorageAndUnknownGatesUntilAdmitted() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened()
        val record = f.command.prepared()
        val second =
            original.copy(
                head = original.head.copy(id = "99999999-9999-4999-8999-999999999999"),
                reason = "Second",
            )
        f.copies.records.value = mapOf(original.head.id to original, second.head.id to second)
        val stored =
            MutableStateFlow<AppResult<List<DocumentMetadataCommandRecord>>>(
                AppResult.Success(listOf(record))
            )
        val commands =
            object : DocumentMetadataCommandRepository by f.command.commands {
                override fun observeAll(access: DocumentAccess) = stored
            }
        val gate =
            MutableStateFlow<Map<String, AppResult<DocumentMetadataReadAdmission>>>(emptyMap())
        val active = mutableSetOf<String>()
        val admissions =
            object : DocumentMetadataAdmissionRepository by f.reads.admission {
                override fun observe(access: DocumentAccess, documentId: String) = flow {
                    assertTrue(active.add(documentId))
                    try {
                        emitAll(
                            gate.map {
                                it[documentId]
                                    ?: AppResult.Success(DocumentMetadataReadAdmission(null, null))
                            }
                        )
                    } finally {
                        active.remove(documentId)
                    }
                }
            }
        val values = mutableListOf<AppResult<PendingActionSummary>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observe(f, commands = commands, admission = admissions)
                    .observePending(f.command.workspaceId)
                    .toList(values)
            }
        runCurrent()
        assertEquals(AppResult.Success(PendingActionSummary(drafts = 1, queued = 1)), values.last())
        assertEquals(setOf(original.head.id, second.head.id), active)
        val unavailable = Failure(FailureKind.Storage, "Unavailable")
        val denial = Failure(FailureKind.AccessDenied, "Verify", code = "mfa_required")
        gate.value =
            mapOf(
                original.head.id to AppResult.Failed(unavailable),
                second.head.id to AppResult.Success(DocumentMetadataReadAdmission("denial", denial)),
            )
        runCurrent()
        assertEquals(AppResult.Failed(denial), values.last())
        stored.value = AppResult.Failed(unavailable)
        runCurrent()
        assertEquals(AppResult.Failed(denial), values.last())
        assertEquals(setOf(original.head.id, second.head.id), active)
        gate.value = emptyMap()
        runCurrent()
        assertEquals(AppResult.Failed(unavailable), values.last())
        stored.value = AppResult.Success(listOf(record))
        runCurrent()
        assertEquals(AppResult.Success(PendingActionSummary(drafts = 1, queued = 1)), values.last())
        reader.cancelAndJoin()
        assertTrue(active.isEmpty())
        assertTrue(f.command.sent.isEmpty())
    }

    @Test
    fun delayedStorageCannotPublishCountsIntoAReplacementSessionAndReleasesItsOwner() = runTest {
        val f = MetadataWorkingFixture()
        val original = f.opened().let { it.copy(reason = "Retained draft") }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = 0
        val copies =
            object : DocumentMetadataWorkingRepository by f.copies {
                override fun observePage(access: DocumentAccess, afterDocumentId: String?) = flow {
                    active++
                    try {
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                        }
                        emit(AppResult.Success(DocumentMetadataWorkingPage(listOf(original), null)))
                        awaitCancellation()
                    } finally {
                        active--
                    }
                }
            }
        val values = mutableListOf<AppResult<PendingActionSummary>>()
        val observer = observe(f, copies = copies)
        val task =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observer.observePending(f.command.workspaceId).toList(values)
            }
        entered.await()
        f.base.sessions.value = AppResult.Success(f.base.session.copy(id = "replacement"))
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertTrue(values.none { it is AppResult.Success })
        assertIs<AppResult.Failed>(values.last())
        assertEquals(0, active)
        f.base.sessions.value = AppResult.Success(f.base.session)
        runCurrent()
        assertEquals(AppResult.Success(PendingActionSummary(drafts = 1)), values.last())
        task.cancelAndJoin()
        assertEquals(0, active)
    }

    @Test
    fun malformedScopeDuplicateCommandsAndUnboundedPagesCannotProduceCounts() = runTest {
        val f = MetadataWorkingFixture()
        val copy = f.opened()
        val record = f.command.prepared()
        val page = DocumentMetadataWorkingPage(listOf(copy), null)
        for ((commands, selected) in
            listOf(
                listOf(
                    record.copy(
                        command =
                            record.command.copy(companyId = "99999999-9999-4999-8999-999999999999")
                    )
                ) to page,
                listOf(record, record) to page,
                List(129) { record } to page,
                listOf(record) to page.copy(items = List(21) { copy }),
                listOf(record) to page.copy(nextDocumentId = copy.head.id),
            )) assertIs<AppResult.Failed>(
            documentMetadataPendingSummary(f.command.access, commands, selected)
        )
    }

    private suspend fun summary(f: MetadataWorkingFixture) =
        assertIs<AppResult.Success<PendingActionSummary>>(
                observe(f).observePending(f.command.workspaceId).first()
            )
            .value

    private fun observe(
        f: MetadataWorkingFixture,
        commands: DocumentMetadataCommandRepository = f.command.commands,
        copies: DocumentMetadataWorkingRepository = f.copies,
        admission: DocumentMetadataAdmissionRepository = f.reads.admission,
    ) =
        ObserveDocumentMetadataPending(
            commands,
            copies,
            admission,
            f.reads.catalog.store,
            f.base.sessionRepository,
            f.base.workspaceRepository,
            f.base.clock,
            f.reads.catalog.build,
        )
}
