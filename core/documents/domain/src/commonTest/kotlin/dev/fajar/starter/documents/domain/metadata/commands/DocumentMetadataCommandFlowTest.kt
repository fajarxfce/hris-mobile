@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.documents.domain.metadata.commands

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.usecases.ObserveDocumentMetadataCommands
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataReadAdmission
import dev.fajar.starter.documents.domain.metadata.repositories.DocumentMetadataAdmissionRepository
import dev.fajar.starter.documents.domain.policies.documentFailure
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DocumentMetadataCommandFlowTest {
    @Test
    fun confirmationFreezesInputWithoutDeliveryAndLocalCommandsOutliveCredentialAge() = runTest {
        val f = MetadataCommandFixture()
        val record = f.prepared()
        assertEquals("User correction", record.command.change.reason)
        assertEquals("Corrected number", record.command.change.metadata.identityNumber)
        assertEquals(3L, record.command.change.expectedVersion)
        assertIs<DocumentMetadataCommandOutcome.Pending>(record.outcome)
        assertTrue(f.sent.isEmpty())
        val reads = f.reads.headReads
        f.base.now += 365.days
        val observed =
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(
                    f.observe(f.workspaceId).first()
                )
                .value
        assertEquals(listOf(record), observed.records)
        assertEquals(reads, f.reads.headReads)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun staleHeadersUnsupportedMetadataAndQueueCapacityCannotStartAnotherIntent() = runTest {
        val f = MetadataCommandFixture()
        val view = f.view()
        val checkpoint = assertNotNull(f.reads.cache.stored.value)
        f.reads.cache.stored.value = checkpoint.copy(head = checkpoint.head.copy(version = 4))
        assertIs<AppResult.Failed>(f.prepare(f.workspaceId, view, f.input, "Reason"))
        f.reads.cache.stored.value = checkpoint
        assertIs<AppResult.Failed>(
            f.prepare(
                f.workspaceId,
                view.copy(
                    snapshot =
                        view.snapshot.copy(
                            checkpoint =
                                checkpoint.copy(
                                    head = checkpoint.head.copy(metadataSupported = false)
                                )
                        )
                ),
                f.input,
                "Reason",
            )
        )
        f.commands.insertion = DocumentMetadataCommandAdmission.CAPACITY_EXCEEDED
        assertEquals(
            "client.command_queue_full",
            assertIs<AppResult.Failed>(f.prepare(f.workspaceId, view, f.input, "Reason"))
                .failure
                .code,
        )
        assertTrue(f.commands.records.value.isEmpty())
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun historyProgressDoesNotInvalidateUnchangedReviewedHeaderAndOccupiedSlotStaysOriginal() =
        runTest {
            val f = MetadataCommandFixture()
            val view = f.view()
            val checkpoint = assertNotNull(f.reads.cache.stored.value)
            f.reads.cache.stored.value = checkpoint.copy(revision = checkpoint.revision + 1)
            val original =
                assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
                        f.prepare(f.workspaceId, view, f.input, "Reason")
                    )
                    .value
            assertEquals(
                "client.document_command_occupied",
                assertIs<AppResult.Failed>(
                        f.prepare(
                            f.workspaceId,
                            view,
                            f.input.copy(identityNumber = "Other input"),
                            "Other reason",
                        )
                    )
                    .failure
                    .code,
            )
            assertEquals(original, f.record())
        }

    @Test
    fun firstDefinitiveRejectionAllowsExplicitReviewButLaterRejectionKeepsUncertainty() = runTest {
        val first = MetadataCommandFixture()
        val record = first.prepared()
        first.saveCall = { documentFailure("stale_version", FailureKind.Conflict) }
        val rejected =
            assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
                    first.replay()(first.workspaceId, record.command.change.operationId)
                )
                .value
        assertIs<DocumentMetadataCommandOutcome.Rejected>(rejected.outcome)
        assertEquals(AppResult.Success(true), first.review(first.workspaceId, rejected))
        val retry = MetadataCommandFixture()
        val pending = retry.prepared()
        retry.saveCall = { documentFailure("network", FailureKind.Network) }
        assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
            retry.replay()(retry.workspaceId, pending.command.change.operationId)
        )
        retry.saveCall = { documentFailure("stale_version", FailureKind.Conflict) }
        val uncertain =
            assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
                    retry.replay()(retry.workspaceId, pending.command.change.operationId)
                )
                .value
        assertIs<DocumentMetadataCommandOutcome.Unconfirmed>(uncertain.outcome)
        assertEquals(listOf(pending.command.change, pending.command.change), retry.sent)
        assertIs<AppResult.Failed>(retry.review(retry.workspaceId, uncertain))
        assertEquals(uncertain, retry.record())
    }

    @Test
    fun originalReceiptSurvivesLaterHeadAndDoesNotRollItBack() = runTest {
        val f = MetadataCommandFixture()
        val pending = f.prepared()
        f.saveCall = { documentFailure("network", FailureKind.Network) }
        f.replay()(f.workspaceId, pending.command.change.operationId)
        val newer =
            f.reads.head.copy(
                version = 12,
                metadataVersion = 11,
                metadata = f.reads.metadata.copy(identityNumber = "Later edit"),
            )
        f.reads.headCall = { AppResult.Success(newer) }
        f.reads.cache.stored.value = assertNotNull(f.reads.cache.stored.value).copy(head = newer)
        val checkpoint = f.reads.cache.stored.value
        f.saveCall = { AppResult.Success(DocumentMutationReceipt(it.documentId, 4)) }
        val confirmed =
            assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
                    f.replay()(f.workspaceId, pending.command.change.operationId)
                )
                .value
        assertEquals(DocumentMetadataCommandOutcome.Confirmed(4), confirmed.outcome)
        assertEquals(checkpoint, f.reads.cache.stored.value)
        assertEquals(pending.command.change, f.sent.last())
        assertEquals(AppResult.Success(true), f.review(f.workspaceId, confirmed))
    }

    @Test
    fun receiptAcknowledgementWaitsForCurrentFactsAndCannotDeleteAStaleRecord() = runTest {
        val f = MetadataCommandFixture()
        val pending = f.prepared()
        val confirmed =
            assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
                    f.replay()(f.workspaceId, pending.command.change.operationId)
                )
                .value
        assertEquals(AppResult.Success(false), f.review(f.workspaceId, pending))
        assertEquals(
            "client.document_updates_pending",
            assertIs<AppResult.Failed>(f.review(f.workspaceId, confirmed)).failure.code,
        )
        assertEquals(confirmed, f.record())
        // Repeating a terminal operation does not send it again.
        assertEquals(
            AppResult.Success(confirmed),
            f.replay()(f.workspaceId, pending.command.change.operationId),
        )
        assertEquals(1, f.sent.size)
    }

    @Test
    fun deliveryMustPersistUncertaintyBeforeHttpAndStorageFailurePreventsSending() = runTest {
        val f = MetadataCommandFixture()
        val pending = f.prepared()
        f.commands.updateFailureAt = 1
        assertIs<AppResult.Failed>(f.replay()(f.workspaceId, pending.command.change.operationId))
        assertTrue(f.sent.isEmpty())
        assertEquals(pending, f.record())
        f.commands.updateFailureAt = null
        f.saveCall = {
            assertIs<DocumentMetadataCommandOutcome.Unconfirmed>(f.record().outcome)
            assertEquals(1, f.record().attempts)
            AppResult.Success(DocumentMutationReceipt(it.documentId, 4))
        }
        assertIs<AppResult.Success<DocumentMetadataCommandRecord>>(
            f.replay()(f.workspaceId, pending.command.change.operationId)
        )
    }

    @Test
    fun cancellationAndTimeoutKeepOriginalCommandAndReleaseExecution() = runTest {
        val f = MetadataCommandFixture()
        val pending = f.prepared()
        val entered = CompletableDeferred<Unit>()
        f.saveCall = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.replay()(f.workspaceId, pending.command.change.operationId) }
        entered.await()
        task.cancelAndJoin()
        assertIs<DocumentMetadataCommandOutcome.Unconfirmed>(f.record().outcome)
        assertFalse(f.mutex.isLocked)
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(
                    f.replay()(f.workspaceId, pending.command.change.operationId)
                )
                .failure
                .kind,
        )
        assertFalse(f.mutex.isLocked)
        assertEquals(listOf(pending.command.change, pending.command.change), f.sent)
        assertEquals(pending.command, f.record().command)
    }

    @Test
    fun changedAccountOrExpiredPolicyBeforeHttpCannotDeliver() = runTest {
        for (accountChange in listOf(true, false)) {
            val f = MetadataCommandFixture()
            val pending = f.prepared()
            f.reads.catalog.policyCall = {
                AppResult.Success(
                    f.base.policy.copy(serverTime = f.base.now, validUntil = f.base.now + 1.seconds)
                )
            }
            f.commands.afterUpdate = {
                if (accountChange)
                    f.base.sessions.value =
                        AppResult.Success(f.base.session.copy(id = "replacement"))
                else f.base.now += 2.seconds
            }
            assertIs<AppResult.Failed>(
                f.replay()(f.workspaceId, pending.command.change.operationId)
            )
            assertTrue(f.sent.isEmpty())
            assertIs<DocumentMetadataCommandOutcome.Unconfirmed>(f.record().outcome)
            assertFalse(f.mutex.isLocked)
        }
    }

    @Test
    fun currentEmploymentOwnershipIsRequiredEvenWhenAnOriginalReceiptMayExist() = runTest {
        val f = MetadataCommandFixture()
        val pending = f.prepared()
        f.reads.catalog.employmentCall = {
            AppResult.Success(
                DocumentEmploymentPage(emptyList(), "2026-10-30", "Asia/Jakarta", null, true)
            )
        }
        assertEquals(
            "document_access_denied",
            assertIs<AppResult.Failed>(
                    f.replay()(f.workspaceId, pending.command.change.operationId)
                )
                .failure
                .code,
        )
        assertTrue(f.sent.isEmpty())
        val view =
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(
                    f.observe(f.workspaceId).first()
                )
                .value
        assertTrue(view.records.isEmpty())
        assertEquals("document_access_denied", view.restrictions.single().failure.code)
    }

    @Test
    fun denialFencesCommandsEvenWhenOutcomeAndAdmissionStorageFail() = runTest {
        val f = MetadataCommandFixture()
        val pending = f.prepared()
        f.reads.admission.failStorage = true
        f.commands.updateFailureAt = 2
        f.saveCall = { documentFailure("mfa_required", FailureKind.AccessDenied) }
        assertIs<AppResult.Failed>(f.replay()(f.workspaceId, pending.command.change.operationId))
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.observe(f.workspaceId).first()).failure.code,
        )
        assertIs<DocumentMetadataCommandOutcome.Unconfirmed>(f.record().outcome)
        assertIs<AppResult.Failed>(f.replay()(f.workspaceId, pending.command.change.operationId))
        assertEquals(1, f.sent.size)
    }

    @Test
    fun aLateResponseCannotPublishIntoAReplacementSession() = runTest {
        val f = MetadataCommandFixture()
        val pending = f.prepared()
        f.saveCall = {
            f.base.sessions.value = AppResult.Success(f.base.session.copy(id = "replacement"))
            AppResult.Success(DocumentMutationReceipt(it.documentId, 4))
        }
        assertIs<AppResult.Failed>(f.replay()(f.workspaceId, pending.command.change.operationId))
        assertIs<DocumentMetadataCommandOutcome.Unconfirmed>(f.record().outcome)
        assertNull(f.reads.admission.state.value.denial)
    }

    @Test
    fun readOnlyEmployeeCannotFreezeACorrection() = runTest {
        val f = MetadataCommandFixture()
        f.base.workspaces.value = AppResult.Success(f.base.workspace)
        val view = f.view()
        assertEquals(
            "document_access_denied",
            assertIs<AppResult.Failed>(f.prepare(f.workspaceId, view, f.input, "Reason"))
                .failure
                .code,
        )
        assertTrue(f.commands.records.value.isEmpty())
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun commandOverviewGatesEachResourceAndReleasesAllSubscriptions() = runTest {
        val f = MetadataCommandFixture()
        val first = f.prepared()
        val second =
            first.copy(
                command =
                    first.command.copy(
                        documentTitle = "Second document",
                        change =
                            first.command.change.copy(
                                documentId = "88888888-8888-8888-8888-888888888888",
                                operationId = "99999999-9999-9999-9999-999999999999",
                            ),
                    )
            )
        f.commands.records.value = listOf(first, second)
        val gates = MutableStateFlow<Map<String, DocumentMetadataReadAdmission>>(emptyMap())
        var active = 0
        val admissions =
            object : DocumentMetadataAdmissionRepository by f.reads.admission {
                override fun observe(access: DocumentAccess, documentId: String) = flow {
                    active++
                    try {
                        emitAll(
                            gates.map {
                                AppResult.Success(
                                    it[documentId] ?: DocumentMetadataReadAdmission(null, null)
                                )
                            }
                        )
                    } finally {
                        active--
                    }
                }
            }
        val observe =
            ObserveDocumentMetadataCommands(
                f.commands,
                admissions,
                f.reads.catalog.store,
                f.base.sessionRepository,
                f.base.workspaceRepository,
                f.base.clock,
                f.reads.catalog.build,
            )
        val values = mutableListOf<AppResult<DocumentMetadataCommandsView>>()
        val observer =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observe(f.workspaceId).toList(values)
            }
        runCurrent()
        assertEquals(
            listOf(first, second),
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(values.last()).value.records,
        )
        assertEquals(2, active)
        gates.value =
            mapOf(
                first.command.change.documentId to
                    DocumentMetadataReadAdmission(
                        "denied",
                        Failure(FailureKind.AccessDenied, "Denied", code = "document_access_denied"),
                    )
            )
        runCurrent()
        assertEquals(
            listOf(second),
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(values.last()).value.records,
        )
        assertEquals(
            "document_access_denied",
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(values.last())
                .value
                .restrictions
                .single()
                .failure
                .code,
        )
        f.commands.records.value =
            listOf(
                first.copy(
                    revision = 1,
                    attempts = 1,
                    outcome = DocumentMetadataCommandOutcome.Unconfirmed(),
                ),
                second,
            )
        runCurrent()
        assertEquals(
            listOf(second),
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(values.last()).value.records,
        )
        gates.value = emptyMap()
        runCurrent()
        assertEquals(
            2,
            assertIs<AppResult.Success<DocumentMetadataCommandsView>>(values.last())
                .value
                .records
                .size,
        )
        observer.cancelAndJoin()
        assertEquals(0, active)
        assertTrue(f.sent.isEmpty())
    }
}
