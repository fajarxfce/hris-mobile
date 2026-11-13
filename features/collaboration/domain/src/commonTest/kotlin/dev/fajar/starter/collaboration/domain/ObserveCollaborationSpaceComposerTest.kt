@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceComposerTest {
    @Test
    fun expiredCredentialsAndNoSelectedCompanyKeepExactOwnedDraftAndSubmission() = runTest {
        val f = SpaceComposerObservationFixture()
        f.native.setup.now += 365.days
        f.native.setup.workspace = null
        f.native.setup.beforeWorkspace = { error("No company lookup") }
        f.native.onPage = { error("No network acquisition") }
        assertEquals(
            AppResult.Success(f.snapshot()),
            f.observe()(f.access, f.conversationId).first(),
        )
        assertEquals(1, f.draftStarts)
        assertEquals(1, f.outboxStarts)
        assertEquals(1, f.draftStops)
        assertEquals(1, f.outboxStops)
        f.assertReadOnly()
    }

    @Test
    fun knownAbsenceKeepsAnEmptyDraftAndOutboxAsLocalFacts() = runTest {
        val f = SpaceComposerObservationFixture()
        f.draft.value = AppResult.Success(null)
        f.entries.value = AppResult.Success(emptyList())
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerSnapshot(null, emptyList())),
            f.observe()(f.access, f.conversationId).first(),
        )
        f.assertReadOnly()
    }

    @Test
    fun heldOriginalsRemainVisibleAndOtherConversationsAreFiltered() = runTest {
        val f = SpaceComposerObservationFixture()
        val held = CollaborationComposerOutboxEntry(f.original, true)
        val other =
            f.original.copy(
                original =
                    f.original.original.copy(
                        conversationId = "e3000000-0000-4000-8000-000000000001",
                        revision = "e3000000-0000-4000-8000-000000000002",
                    )
            )
        f.entries.value =
            AppResult.Success(listOf(CollaborationComposerOutboxEntry(other, false), held))
        val value =
            assertIs<AppResult.Success<CollaborationSpaceComposerSnapshot>>(
                    f.observe()(f.access, f.conversationId).first()
                )
                .value
        assertEquals(f.editor, value.draft)
        assertEquals(listOf(held), value.submissions)
        f.assertReadOnly()
    }

    @Test
    fun malformedDraftCannotClaimAnotherConversationOrRevision() = runTest {
        for (invalid in 0..2) {
            val f = SpaceComposerObservationFixture()
            f.draft.value =
                AppResult.Success(
                    when (invalid) {
                        0 -> f.editor.copy(conversationId = "e3000000-0000-4000-8000-000000000001")
                        1 -> f.editor.copy(revision = "invalid")
                        else -> f.editor.copy(text = "x".repeat(16_385))
                    }
                )
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe()(f.access, f.conversationId).first())
                    .failure
                    .kind,
            )
            f.assertReadOnly()
        }
    }

    @Test
    fun malformedOtherConversationAndDuplicateOriginalsCannotHideBehindFiltering() = runTest {
        for (invalid in 0..2) {
            val f = SpaceComposerObservationFixture()
            val original = CollaborationComposerOutboxEntry(f.original, false)
            val malformed =
                f.original.copy(
                    original =
                        f.original.original.copy(
                            conversationId = "e3000000-0000-4000-8000-000000000001",
                            text = "",
                        )
                )
            f.entries.value =
                AppResult.Success(
                    when (invalid) {
                        0 -> listOf(original, original)
                        1 -> listOf(original, CollaborationComposerOutboxEntry(malformed, false))
                        else ->
                            (0..128).map { index ->
                                CollaborationComposerOutboxEntry(
                                    f.original.copy(
                                        original =
                                            f.original.original.copy(
                                                revision =
                                                    "e3000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                                            )
                                    ),
                                    false,
                                )
                            }
                    }
                )
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe()(f.access, f.conversationId).first())
                    .failure
                    .kind,
            )
            f.assertReadOnly()
        }
    }

    @Test
    fun invalidRouteAndOwnerDoNotOpenLocalContentReaders() = runTest {
        val f = SpaceComposerObservationFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.observe()(f.access, "not-a-conversation").first())
                .failure
                .kind,
        )
        assertIs<AppResult.Failed>(
            f.observe()(f.access.copy(accountId = "invalid"), f.conversationId).first()
        )
        assertEquals(0, f.draftStarts + f.outboxStarts)
        f.assertReadOnly()
    }

    @Test
    fun eachReadFailurePublishesBeforeTheOtherReadHasAnInitialResult() = runTest {
        for (failedDraft in listOf(false, true)) {
            val f = SpaceComposerObservationFixture()
            if (failedDraft) {
                f.readDraft = { flowOf(AppResult.Failed(f.storageFailure)) }
                f.readEntries = { flow { awaitCancellation() } }
            } else {
                f.readDraft = { flow { awaitCancellation() } }
                f.readEntries = { flowOf(AppResult.Failed(f.storageFailure)) }
            }
            assertEquals(
                AppResult.Failed(f.storageFailure),
                f.observe()(f.access, f.conversationId).first(),
            )
            assertEquals(f.draftStarts, f.draftStops)
            assertEquals(f.outboxStarts, f.outboxStops)
            f.assertReadOnly()
        }
    }

    @Test
    fun successWaitsForActualEditorAndOutboxWithoutFabricatingAbsence() = runTest {
        val f = SpaceComposerObservationFixture()
        val ready = CompletableDeferred<Unit>()
        f.readEntries = {
            flow {
                ready.await()
                emitAll(f.entries)
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceComposerSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.conversationId).toList(values)
            }
        runCurrent()
        assertTrue(values.isEmpty())
        ready.complete(Unit)
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceComposerSnapshot>>(AppResult.Success(f.snapshot())),
            values,
        )
        job.cancelAndJoin()
        f.assertReadOnly()
    }

    @Test
    fun equalLocalResultsAndTokenRotationDoNotRestartReadersOrRepublish() = runTest {
        val f = SpaceComposerObservationFixture()
        f.readDraft = {
            flow {
                emit(f.draft.value)
                emit(f.draft.value)
                emitAll(f.draft)
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceComposerSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.conversationId).toList(values)
            }
        runCurrent()
        f.identity.value =
            AppResult.Success(
                f.native.session.copy(
                    tokens = f.native.session.tokens.copy(accessToken = "rotated-token")
                )
            )
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceComposerSnapshot>>(AppResult.Success(f.snapshot())),
            values,
        )
        assertEquals(1, f.draftStarts)
        assertEquals(1, f.outboxStarts)
        job.cancelAndJoin()
        f.assertReadOnly()
    }

    @Test
    fun capturedEntriesAreDetachedBeforeSuspendedAdmission() = runTest {
        val f = SpaceComposerObservationFixture()
        val raw = mutableListOf(CollaborationComposerOutboxEntry(f.original, false))
        f.entries.value = AppResult.Success(raw)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var reads = 0
        f.currentAdmission = {
            if (++reads == 2) {
                entered.complete(Unit)
                resume.await()
            }
            f.membership.gate.value
        }
        val result = async { f.observe()(f.access, f.conversationId).first() }
        entered.await()
        raw.clear()
        resume.complete(Unit)
        val captured =
            assertIs<AppResult.Success<CollaborationSpaceComposerSnapshot>>(result.await()).value
        assertEquals(
            listOf(CollaborationComposerOutboxEntry(f.original, false)),
            captured.submissions,
        )
        f.assertReadOnly()
    }
}
