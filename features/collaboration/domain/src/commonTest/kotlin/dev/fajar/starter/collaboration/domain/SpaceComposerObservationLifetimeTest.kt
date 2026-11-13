@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SpaceComposerObservationLifetimeTest {
    @Test
    fun accountSessionAudienceReplacementAndLogoutCancelBothOldContentReaders() = runTest {
        for (replacement in 0..3) {
            val f = SpaceComposerObservationFixture()
            val values = mutableListOf<AppResult<CollaborationSpaceComposerSnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.conversationId).toList(values)
                }
            runCurrent()
            assertIs<AppResult.Success<*>>(values.single())
            f.identity.value =
                AppResult.Success(
                    when (replacement) {
                        0 -> null
                        1 -> f.native.session.copy(id = "another-local-session")
                        2 ->
                            f.native.session.copy(
                                user = f.native.session.user.copy(id = f.native.contact.accountId)
                            )
                        else ->
                            f.native.session.copy(
                                binding =
                                    f.native.session.binding.copy(
                                        audience = "https://foreign.example.test/"
                                    )
                            )
                    }
                )
            runCurrent()
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(values.last()).failure.kind,
            )
            assertEquals(1, f.draftStops)
            assertEquals(1, f.outboxStops)
            f.draft.value = AppResult.Success(f.editor.copy(text = "Late old account edit"))
            f.entries.value = AppResult.Success(emptyList())
            runCurrent()
            assertEquals(2, values.size)
            job.cancelAndJoin()
            f.assertReadOnly()
        }
    }

    @Test
    fun denialCancelsPendingReadersAndFailedCleanupDoesNotReopenBeforeAdmittedObservation() =
        runTest {
            val f = SpaceComposerObservationFixture()
            val entered = CompletableDeferred<Unit>()
            f.readDraft = {
                flow {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val values = mutableListOf<AppResult<CollaborationSpaceComposerSnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.conversationId).toList(values)
                }
            entered.await()
            f.membership.afterRestrict = { AppResult.Failed(f.storageFailure) }
            assertIs<AppResult.Failed>(
                f.membership.directory.restrict(f.access, f.membership.denied)
            )
            runCurrent()
            assertEquals(f.membership.denied, assertIs<AppResult.Failed>(values.single()).failure)
            assertEquals(1, f.draftStops)
            assertEquals(1, f.outboxStops)
            f.membership.gate.value = AppResult.Failed(f.storageFailure)
            f.readDraft = { f.draft }
            runCurrent()
            assertEquals(f.membership.denied, assertIs<AppResult.Failed>(values.last()).failure)
            assertEquals(1, f.draftStarts)
            f.membership.gate.value = AppResult.Success(f.membership.open)
            runCurrent()
            assertEquals(AppResult.Success(f.snapshot()), values.last())
            assertEquals(2, f.draftStarts)
            job.cancelAndJoin()
            f.assertReadOnly()
        }

    @Test
    fun invalidAdmissionOrFailedSessionNeverOpensDraftOrOutbox() = runTest {
        val f = SpaceComposerObservationFixture()
        for (gate in
            listOf(
                CollaborationSpaceDirectoryAdmission(null, f.membership.denied),
                CollaborationSpaceDirectoryAdmission(f.membership.revision, null),
                CollaborationSpaceDirectoryAdmission(f.membership.revision, f.storageFailure),
            )) {
            f.membership.gate.value = AppResult.Success(gate)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe()(f.access, f.conversationId).first())
                    .failure
                    .kind,
            )
        }
        f.membership.gate.value = AppResult.Success(f.membership.open)
        f.identity.value = AppResult.Failed(f.storageFailure)
        assertEquals(
            AppResult.Failed(f.storageFailure),
            f.observe()(f.access, f.conversationId).first(),
        )
        assertEquals(0, f.draftStarts + f.outboxStarts)
        f.assertReadOnly()
    }

    @Test
    fun accountReplacementDuringANonCancellableAdmissionReadCannotPublishCapturedContent() =
        runTest {
            val f = SpaceComposerObservationFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            f.currentAdmission = {
                val captured = f.membership.gate.value
                if (++reads == 2)
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                captured
            }
            val values = mutableListOf<AppResult<CollaborationSpaceComposerSnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.conversationId).toList(values)
                }
            entered.await()
            f.identity.value = AppResult.Success(null)
            runCurrent()
            assertTrue(values.isEmpty())
            release.complete(Unit)
            runCurrent()
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(values.single()).failure.kind,
            )
            assertEquals(1, f.draftStops)
            assertEquals(1, f.outboxStops)
            job.cancelAndJoin()
            f.assertReadOnly()
        }

    @Test
    fun cancellationJoinsBothPendingReadersWithoutChangingOriginals() = runTest {
        val f = SpaceComposerObservationFixture()
        val draftStarted = CompletableDeferred<Unit>()
        val outboxStarted = CompletableDeferred<Unit>()
        f.readDraft = {
            flow {
                draftStarted.complete(Unit)
                awaitCancellation()
            }
        }
        f.readEntries = {
            flow {
                outboxStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val original = f.snapshot()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.conversationId).collect {
                    error("Neither source has a result")
                }
            }
        draftStarted.await()
        outboxStarted.await()
        job.cancelAndJoin()
        assertEquals(1, f.draftStops)
        assertEquals(1, f.outboxStops)
        assertEquals(original, f.snapshot())
        f.assertReadOnly()
    }

    @Test
    fun twoCollectorsOfTheSameFlowOwnIndependentDenialLatches() = runTest {
        val f = SpaceComposerObservationFixture()
        var reads = 0
        f.currentAdmission = {
            if (++reads == 2)
                AppResult.Success(
                    CollaborationSpaceDirectoryAdmission(f.membership.revision, f.membership.denied)
                )
            else f.membership.gate.value
        }
        val observed = f.observe()(f.access, f.conversationId)
        val first = mutableListOf<AppResult<CollaborationSpaceComposerSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observed.toList(first)
            }
        runCurrent()
        assertEquals(f.membership.denied, assertIs<AppResult.Failed>(first.single()).failure)
        assertEquals(AppResult.Success(f.snapshot()), observed.first())
        f.draft.value =
            AppResult.Success(f.editor.copy(text = "New edit remains denied for first collector"))
        runCurrent()
        assertEquals(f.membership.denied, assertIs<AppResult.Failed>(first.last()).failure)
        job.cancelAndJoin()
        assertEquals(f.draftStarts, f.draftStops)
        assertEquals(f.outboxStarts, f.outboxStops)
        f.assertReadOnly()
    }

    @Test
    fun aSlowConsumerCannotReceiveQueuedOldContentAfterOwnerOrAdmissionReplacement() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceComposerObservationFixture()
            val first = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val values = mutableListOf<AppResult<CollaborationSpaceComposerSnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.conversationId).collect {
                        values += it
                        if (values.size == 1) {
                            first.complete(Unit)
                            release.await()
                        }
                    }
                }
            first.await()
            f.draft.value = AppResult.Success(f.editor.copy(text = "Queued old edit"))
            runCurrent()
            if (deny) f.membership.deny() else f.identity.value = AppResult.Success(null)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(2, values.size)
            assertEquals(
                if (deny) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(values.last()).failure.kind,
            )
            job.cancelAndJoin()
            f.assertReadOnly()
        }
    }

    @Test
    fun aFinishedFailedReaderNeedsAnExplicitNewSubscriptionToReadAgain() = runTest {
        val f = SpaceComposerObservationFixture()
        f.readDraft = { flowOf(AppResult.Failed(f.storageFailure)) }
        val values = mutableListOf<AppResult<CollaborationSpaceComposerSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.conversationId).toList(values)
            }
        runCurrent()
        assertEquals(AppResult.Failed(f.storageFailure), values.single())
        f.readDraft = { f.draft }
        f.identity.value =
            AppResult.Success(
                f.native.session.copy(
                    tokens = f.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        runCurrent()
        assertEquals(1, f.draftStarts)
        job.cancelAndJoin()
        assertEquals(
            AppResult.Success(f.snapshot()),
            f.observe()(f.access, f.conversationId).first(),
        )
        assertEquals(2, f.draftStarts)
        f.assertReadOnly()
    }
}
