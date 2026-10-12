@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentEvidencePage
import dev.fajar.starter.leave.presentation.evidence.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*

class LeaveEvidenceViewModelTest {
    @Test
    fun pagingReplacesTheBoundedPageAndOnlyItsCurrentDocumentCanBeSelectedOnce() = runTest {
        withPicker { f, model, _ ->
            val first = (1..20).map(::leaveEvidence)
            f.read = { query ->
                AppResult.Success(
                    if (query.after == null) DocumentEvidencePage(first, first.last().documentId)
                    else DocumentEvidencePage(listOf(leaveEvidence(21)))
                )
            }
            val effects = mutableListOf<LeaveEvidenceEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.toList(effects)
            }
            model.onEvent(LeaveEvidenceEvent.Entered)
            model.onEvent(LeaveEvidenceEvent.Entered)
            runCurrent()
            assertEquals(1, f.queries.size)
            assertEquals(20, model.state.value.snapshot?.page?.items?.size)
            model.onEvent(LeaveEvidenceEvent.PageRequested("foreign-cursor"))
            runCurrent()
            assertEquals(1, f.queries.size)
            model.onEvent(LeaveEvidenceEvent.PageRequested(first.last().documentId))
            runCurrent()
            assertEquals(listOf(leaveEvidence(21)), model.state.value.snapshot?.page?.items)
            model.onEvent(LeaveEvidenceEvent.Selected(first.first().revisionId))
            runCurrent()
            assertTrue(effects.isEmpty())
            model.onEvent(LeaveEvidenceEvent.Selected(leaveEvidence(21).revisionId))
            model.onEvent(LeaveEvidenceEvent.Selected(leaveEvidence(21).revisionId))
            runCurrent()
            assertEquals(
                listOf<LeaveEvidenceEffect>(LeaveEvidenceEffect.Selected(leaveEvidence(21))),
                effects,
            )
            assertTrue(model.state.value.closing)
            assertNull(model.state.value.snapshot)
        }
    }

    @Test
    fun aPreviouslyAttachedDocumentCannotBeSelectedThroughANewerRevision() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = LeaveEvidenceFixture { Instant.parse("2026-10-12T01:00:00Z") }
        val document = leaveEvidence()
        val model =
            f.model(
                LeaveEvidenceTarget(
                    f.target.workspaceId,
                    f.target.employeeId,
                    setOf(document.documentId),
                )
            )
        val owner = ViewModelStore().apply { put("evidence", model) }
        try {
            val replacement =
                document.copy(revisionId = leaveEvidence(2).revisionId, revisionNumber = 2)
            f.read = { AppResult.Success(DocumentEvidencePage(listOf(replacement))) }
            val effects = mutableListOf<LeaveEvidenceEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.toList(effects)
            }
            model.onEvent(LeaveEvidenceEvent.Entered)
            runCurrent()
            model.onEvent(LeaveEvidenceEvent.Selected(replacement.revisionId))
            runCurrent()
            assertTrue(effects.isEmpty())
            assertFalse(model.state.value.closing)
            model.onEvent(LeaveEvidenceEvent.Dismissed)
            model.onEvent(LeaveEvidenceEvent.Dismissed)
            runCurrent()
            assertEquals(listOf<LeaveEvidenceEffect>(LeaveEvidenceEffect.Dismiss), effects)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun retainedRefreshContentExpiresEvenWhileANewRequestIsPendingAndAFailureCannotReopenIt() =
        runTest {
            withPicker { f, model, _ ->
                model.onEvent(LeaveEvidenceEvent.Entered)
                runCurrent()
                val original = model.state.value.snapshot
                advanceTimeBy(45.seconds)
                val release = CompletableDeferred<Unit>()
                f.read = {
                    release.await()
                    AppResult.Failed(Failure(FailureKind.Network, "Offline"))
                }
                try {
                    model.onEvent(LeaveEvidenceEvent.ReloadRequested)
                    runCurrent()
                    assertTrue(model.state.value.loading)
                    assertEquals(original, model.state.value.snapshot)
                    advanceTimeBy(15.seconds)
                    runCurrent()
                    assertNull(model.state.value.snapshot)
                    assertEquals(
                        "client.document_evidence_expired",
                        model.state.value.visibleFailure?.code,
                    )
                    release.complete(Unit)
                    runCurrent()
                    assertEquals(
                        "client.document_evidence_expired",
                        model.state.value.visibleFailure?.code,
                    )
                    f.read = { AppResult.Success(DocumentEvidencePage(listOf(leaveEvidence(2)))) }
                    model.onEvent(LeaveEvidenceEvent.ReloadRequested)
                    runCurrent()
                    assertNull(model.state.value.restriction)
                    assertEquals(listOf(leaveEvidence(2)), model.state.value.snapshot?.page?.items)
                } finally {
                    release.complete(Unit)
                }
            }
        }

    @Test
    fun aReadDeadlineUsesTheRemainingLeaseAfterAcquisitionInsteadOfStartingAFullNewPeriod() =
        runTest {
            withPicker { f, model, _ ->
                val release = CompletableDeferred<Unit>()
                f.read = {
                    release.await()
                    AppResult.Success(DocumentEvidencePage(listOf(leaveEvidence())))
                }
                try {
                    model.onEvent(LeaveEvidenceEvent.Entered)
                    runCurrent()
                    advanceTimeBy(20.seconds)
                    release.complete(Unit)
                    runCurrent()
                    assertNotNull(model.state.value.snapshot)
                    advanceTimeBy(40.seconds)
                    model.onEvent(LeaveEvidenceEvent.Selected(leaveEvidence().revisionId))
                    runCurrent()
                    assertNull(model.state.value.snapshot)
                    assertEquals(
                        "client.document_evidence_expired",
                        model.state.value.visibleFailure?.code,
                    )
                    assertFalse(model.state.value.closing)
                } finally {
                    release.complete(Unit)
                }
            }
        }

    @Test
    fun lateDeniedResultsFromAStoppedOwnerCannotReplaceTheNewPage() = runTest {
        withPicker { f, model, _ ->
            val release = CompletableDeferred<Unit>()
            f.read = {
                withContext(NonCancellable) { release.await() }
                AppResult.Failed(
                    Failure(
                        FailureKind.AccessDenied,
                        "Old denial",
                        code = "mfa_verification_required",
                    )
                )
            }
            try {
                model.onEvent(LeaveEvidenceEvent.Entered)
                runCurrent()
                model.onEvent(LeaveEvidenceEvent.Stopped)
                runCurrent()
                assertFalse(model.state.value.loading)
                assertNull(model.state.value.snapshot)
                f.read = { AppResult.Success(DocumentEvidencePage(listOf(leaveEvidence(2)))) }
                model.onEvent(LeaveEvidenceEvent.Entered)
                runCurrent()
                val current = model.state.value
                release.complete(Unit)
                runCurrent()
                assertEquals(current, model.state.value)
                assertEquals(listOf(leaveEvidence(2)), current.snapshot?.page?.items)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun anMfaDenialStaysAuthoritativeThroughPendingAndFailedRefreshUntilFreshAdmissionSucceeds() =
        runTest {
            withPicker { f, model, _ ->
                f.read = {
                    AppResult.Failed(
                        Failure(
                            FailureKind.AccessDenied,
                            "Verify",
                            code = "mfa_verification_required",
                        )
                    )
                }
                model.onEvent(LeaveEvidenceEvent.Entered)
                runCurrent()
                assertTrue(model.state.value.needsVerification)
                val release = CompletableDeferred<Unit>()
                f.read = {
                    release.await()
                    AppResult.Failed(Failure(FailureKind.Network, "Offline"))
                }
                try {
                    model.onEvent(LeaveEvidenceEvent.ReloadRequested)
                    runCurrent()
                    assertTrue(model.state.value.needsVerification)
                    release.complete(Unit)
                    runCurrent()
                    assertTrue(model.state.value.needsVerification)
                    assertNull(model.state.value.snapshot)
                    f.read = { AppResult.Success(DocumentEvidencePage(listOf(leaveEvidence()))) }
                    model.onEvent(LeaveEvidenceEvent.ReloadRequested)
                    runCurrent()
                    assertNull(model.state.value.restriction)
                    assertNotNull(model.state.value.snapshot)
                } finally {
                    release.complete(Unit)
                }
            }
        }

    @Test
    fun clearingTheDialogCancelsItsRequestAndEventsCannotRestartTheDisposedOwner() = runTest {
        withPicker { f, model, owner ->
            var active = 0
            f.read = {
                active++
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
            model.onEvent(LeaveEvidenceEvent.Entered)
            runCurrent()
            assertEquals(1, active)
            owner.clear()
            runCurrent()
            assertEquals(0, active)
            val disposed = model.state.value
            model.onEvent(LeaveEvidenceEvent.Entered)
            model.onEvent(LeaveEvidenceEvent.ReloadRequested)
            advanceTimeBy(120.seconds)
            runCurrent()
            assertEquals(disposed, model.state.value)
            assertEquals(1, f.queries.size)
        }
    }

    private suspend fun TestScope.withPicker(
        block:
            suspend TestScope.(LeaveEvidenceFixture, LeaveEvidenceViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val origin = Instant.parse("2026-10-12T01:00:00Z")
        val f = LeaveEvidenceFixture { origin + testScheduler.currentTime.milliseconds }
        val model = f.model()
        val owner = ViewModelStore().apply { put("evidence", model) }
        try {
            block(f, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
