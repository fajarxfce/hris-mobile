@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SpaceComposerProgressLifetimeTest {
    @Test
    fun accountSessionAudienceReplacementAndLogoutCancelAllExactRecordReaders() = runTest {
        for (replacement in 0..3) {
            val f = SpaceComposerProgressFixture().apply { requested.value = listOf(first, second) }
            val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.requested).toList(values)
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
            assertEquals(1, f.stops.getOrElse(f.first.id) { 0 })
            assertEquals(1, f.stops.getOrElse(f.second.id) { 0 })
            f.slot(f.first.id).value = AppResult.Success(f.record())
            f.slot(f.second.id).value = AppResult.Success(f.record(f.second))
            runCurrent()
            assertEquals(2, values.size)
            job.cancelAndJoin()
            f.assertReadOnly()
        }
    }

    @Test
    fun denialCancelsPendingReadersAndFailedCleanupDoesNotReopenBeforeAdmittedObservation() =
        runTest {
            val f = SpaceComposerProgressFixture().apply { requested.value = listOf(first, second) }
            val entered = CompletableDeferred<Unit>()
            f.customReads[f.first.id] = {
                flow {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.requested).toList(values)
                }
            entered.await()
            f.membership.afterRestrict = { AppResult.Failed(f.storage) }
            assertIs<AppResult.Failed>(
                f.membership.directory.restrict(f.access, f.membership.denied)
            )
            runCurrent()
            assertEquals(f.membership.denied, assertIs<AppResult.Failed>(values.single()).failure)
            assertEquals(1, f.stops.getOrElse(f.first.id) { 0 })
            assertEquals(1, f.stops.getOrElse(f.second.id) { 0 })
            f.membership.gate.value = AppResult.Failed(f.storage)
            f.customReads[f.first.id] = { f.slot(f.first.id) }
            runCurrent()
            assertEquals(f.membership.denied, assertIs<AppResult.Failed>(values.last()).failure)
            assertEquals(1, f.starts.getOrElse(f.first.id) { 0 })
            f.membership.gate.value = AppResult.Success(f.membership.open)
            runCurrent()
            assertEquals(
                AppResult.Success(
                    listOf(
                        CollaborationSpaceComposerMessageProgress(f.first, null),
                        CollaborationSpaceComposerMessageProgress(f.second, null),
                    )
                ),
                values.last(),
            )
            assertEquals(2, f.starts.getOrElse(f.first.id) { 0 })
            job.cancelAndJoin()
            f.assertReadOnly()
        }

    @Test
    fun invalidAdmissionOrFailedSessionNeverOpensExactRecordReaders() = runTest {
        val f = SpaceComposerProgressFixture().apply { requested.value = listOf(first, second) }
        for (gate in
            listOf(
                CollaborationSpaceDirectoryAdmission(null, f.membership.denied),
                CollaborationSpaceDirectoryAdmission(f.membership.revision, null),
                CollaborationSpaceDirectoryAdmission(f.membership.revision, f.storage),
            )) {
            f.membership.gate.value = AppResult.Success(gate)
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.observe()(f.access, f.requested).first()).failure.kind,
            )
        }
        f.membership.gate.value = AppResult.Success(f.membership.open)
        f.identity.value = AppResult.Failed(f.storage)
        assertEquals(AppResult.Failed(f.storage), f.observe()(f.access, f.requested).first())
        assertEquals(
            0,
            f.starts.getOrElse(f.first.id) { 0 } + f.starts.getOrElse(f.second.id) { 0 },
        )
        f.assertReadOnly()
    }

    @Test
    fun accountReplacementDuringANonCancellableAdmissionReadCannotPublishCapturedContent() =
        runTest {
            val f = SpaceComposerProgressFixture().apply { requested.value = listOf(first, second) }
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
            val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.requested).toList(values)
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
            assertEquals(1, f.stops.getOrElse(f.first.id) { 0 })
            assertEquals(1, f.stops.getOrElse(f.second.id) { 0 })
            job.cancelAndJoin()
            f.assertReadOnly()
        }

    @Test
    fun cancellationJoinsRequestedFlowAndAllPendingRecordReaders() = runTest {
        val f = SpaceComposerProgressFixture().apply { requested.value = listOf(first, second) }
        val draftStarted = CompletableDeferred<Unit>()
        val outboxStarted = CompletableDeferred<Unit>()
        f.customReads[f.first.id] = {
            flow {
                draftStarted.complete(Unit)
                awaitCancellation()
            }
        }
        f.customReads[f.second.id] = {
            flow {
                outboxStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val original = f.requested.value
        var requestedStopped = false
        val requested = flow {
            try {
                emitAll(f.requested)
            } finally {
                requestedStopped = true
            }
        }
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, requested).collect { error("Neither source has a result") }
            }
        draftStarted.await()
        outboxStarted.await()
        job.cancelAndJoin()
        assertEquals(1, f.stops.getOrElse(f.first.id) { 0 })
        assertEquals(1, f.stops.getOrElse(f.second.id) { 0 })
        assertEquals(original, f.requested.value)
        assertTrue(requestedStopped)
        f.assertReadOnly()
    }

    @Test
    fun twoCollectorsOfTheSameFlowOwnIndependentDenialLatches() = runTest {
        val f = SpaceComposerProgressFixture().apply { requested.value = listOf(first, second) }
        var reads = 0
        f.currentAdmission = {
            if (++reads == 2)
                AppResult.Success(
                    CollaborationSpaceDirectoryAdmission(f.membership.revision, f.membership.denied)
                )
            else f.membership.gate.value
        }
        val observed = f.observe()(f.access, f.requested)
        val first = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observed.toList(first)
            }
        runCurrent()
        assertEquals(f.membership.denied, assertIs<AppResult.Failed>(first.single()).failure)
        assertEquals(
            AppResult.Success(
                listOf(
                    CollaborationSpaceComposerMessageProgress(f.first, null),
                    CollaborationSpaceComposerMessageProgress(f.second, null),
                )
            ),
            observed.first(),
        )
        f.slot(f.first.id).value = AppResult.Success(f.record())
        runCurrent()
        assertEquals(f.membership.denied, assertIs<AppResult.Failed>(first.last()).failure)
        job.cancelAndJoin()
        assertEquals(f.starts.getOrElse(f.first.id) { 0 }, f.stops.getOrElse(f.first.id) { 0 })
        assertEquals(f.starts.getOrElse(f.second.id) { 0 }, f.stops.getOrElse(f.second.id) { 0 })
        f.assertReadOnly()
    }

    @Test
    fun aSlowConsumerCannotReceiveQueuedOldContentAfterOwnerOrAdmissionReplacement() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceComposerProgressFixture().apply { requested.value = listOf(first, second) }
            val first = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.requested).collect {
                        values += it
                        if (values.size == 1) {
                            first.complete(Unit)
                            release.await()
                        }
                    }
                }
            first.await()
            f.slot(f.first.id).value = AppResult.Success(f.record())
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
        val f = SpaceComposerProgressFixture().apply { requested.value = listOf(first, second) }
        f.customReads[f.first.id] = { flowOf(AppResult.Failed(f.storage)) }
        val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.requested).toList(values)
            }
        runCurrent()
        assertEquals(AppResult.Failed(f.storage), values.single())
        f.customReads[f.first.id] = { f.slot(f.first.id) }
        f.identity.value =
            AppResult.Success(
                f.native.session.copy(
                    tokens = f.native.session.tokens.copy(accessToken = "rotated")
                )
            )
        runCurrent()
        assertEquals(1, f.starts.getOrElse(f.first.id) { 0 })
        job.cancelAndJoin()
        assertEquals(
            AppResult.Success(
                listOf(
                    CollaborationSpaceComposerMessageProgress(f.first, null),
                    CollaborationSpaceComposerMessageProgress(f.second, null),
                )
            ),
            f.observe()(f.access, f.requested).first(),
        )
        assertEquals(2, f.starts.getOrElse(f.first.id) { 0 })
        f.assertReadOnly()
    }

    @Test
    fun invalidRequestsCancelPreviousReadersBeforeLaterValidRequestsCanResume() = runTest {
        val f = SpaceComposerProgressFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.requested).toList(values)
            }
        runCurrent()
        f.requested.value = listOf(f.first, f.first)
        runCurrent()
        assertIs<AppResult.Failed>(values.last())
        assertEquals(f.starts, f.stops)
        f.slot(f.first.id).value = AppResult.Success(f.record())
        runCurrent()
        assertEquals(2, values.size)
        f.requested.value = listOf(f.first)
        runCurrent()
        val record =
            assertIs<AppResult.Success<List<CollaborationSpaceComposerMessageProgress>>>(
                    values.last()
                )
                .value
                .single()
                .record
        assertEquals(f.record(), record)
        assertEquals(2, f.starts[f.first.id])
        job.cancelAndJoin()
        assertEquals(f.starts, f.stops)
        f.assertReadOnly()
    }

    @Test
    fun theMaximumRequestInventoryCompletesAndCancellationJoinsEveryReader() = runTest {
        val f = SpaceComposerProgressFixture()
        val originals = List(128) { f.original(it + 10) }
        f.requested.value = originals
        val values = mutableListOf<AppResult<List<CollaborationSpaceComposerMessageProgress>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.requested).toList(values)
            }
        runCurrent()
        assertEquals(
            originals,
            assertIs<AppResult.Success<List<CollaborationSpaceComposerMessageProgress>>>(
                    values.single()
                )
                .value
                .map { it.original },
        )
        assertEquals(128, f.starts.size)
        assertTrue(f.starts.values.all { it == 1 })
        f.requested.value = originals.reversed()
        runCurrent()
        assertEquals(
            originals.reversed(),
            assertIs<AppResult.Success<List<CollaborationSpaceComposerMessageProgress>>>(
                    values.last()
                )
                .value
                .map { it.original },
        )
        assertTrue(f.stops.isEmpty())
        job.cancelAndJoin()
        assertEquals(f.starts, f.stops)
        f.assertReadOnly()
    }
}
