@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceInboxLabelsTest {
    @Test
    fun aFullVisiblePageOwnsOnlyFiftyExactContactReadersAndJoinsThemAll() = runTest {
        val f = SpaceInboxLabelsFixture()
        val ids = (1..50).map { "81000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
        val peers = ids.associateWith { it.replaceFirst("81", "82") }
        f.readMetadata = { id -> AppResult.Success(f.canonical(id, requireNotNull(peers[id]))) }
        peers.values.forEach { peer ->
            f.contacts[peer] = MutableStateFlow(AppResult.Success(f.contact.copy(accountId = peer)))
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceInboxLabel>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, ids).toList(values)
            }
        runCurrent()
        assertEquals(ids, f.metadataReads)
        assertEquals(50, f.contactStarts.size)
        assertEquals(
            ids,
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last())
                .value
                .map { it.conversationId },
        )
        f.versions.value = AppResult.Success(f.version.copy(revision = 2))
        runCurrent()
        assertEquals(50, f.metadataReads.size)
        assertEquals(50, f.contactStarts.size)
        job.cancelAndJoin()
        assertEquals(f.contactStarts.sorted(), f.contactStops.sorted())
    }

    @Test
    fun cancellationWaitsForContactCleanupAndPublishesNoLateSnapshot() = runTest {
        val f = SpaceInboxLabelsFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.contactSource = {
            flow {
                try {
                    emit(AppResult.Success(f.contact))
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            }
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceInboxLabel>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, listOf(f.id)).toList(values)
            }
        runCurrent()
        val count = values.size
        job.cancel()
        entered.await()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.join()
        assertEquals(count, values.size)
        assertEquals(f.contactStarts, f.contactStops)
        assertEquals(1, f.catalogStops)
    }

    @Test
    fun ownedLabelsStayAvailableWithoutNativeReadinessOrFreshTokens() = runTest {
        val f = SpaceInboxLabelsFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        val result =
            f.observer(f.access, listOf(f.id)).first {
                it is AppResult.Success && it.value.any { label -> label.displayName != null }
            }
        val label =
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(result).value.single()
        assertEquals(f.contact.displayName, label.displayName)
        assertEquals(listOf(f.id), f.metadataReads)
        assertEquals(listOf(f.contact.accountId), f.contactStarts)
        assertEquals(f.contactStarts, f.contactStops)
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun immutableCanonicalBindingsSurviveUnrelatedInsertionsWithoutRestartingContacts() = runTest {
        val f = SpaceInboxLabelsFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceInboxLabel>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, listOf(f.id)).toList(values)
            }
        runCurrent()
        val count = values.size
        f.versions.value = AppResult.Success(f.version.copy(revision = 2))
        runCurrent()
        val session = f.base.native.session
        f.base.identity.value =
            AppResult.Success(session.copy(tokens = session.tokens.copy(accessToken = "rotated")))
        runCurrent()
        assertEquals(listOf(f.id), f.metadataReads)
        assertEquals(1, f.contactStarts.size)
        assertEquals(count, values.size)
        job.cancelAndJoin()
        assertEquals(1, f.catalogStops)
        assertEquals(f.contactStarts, f.contactStops)
    }

    @Test
    fun aSlowContactDoesNotHideAlreadyResolvedLabelsAndItsAbsenceIsExplicit() = runTest {
        val f = SpaceInboxLabelsFixture()
        f.readMetadata = {
            AppResult.Success(
                if (it == f.id) f.base.room.conversation.localMetadata() else f.canonical()
            )
        }
        val waiting = CompletableDeferred<Unit>()
        f.contactSource = { peer ->
            if (peer == f.otherPeer)
                flow {
                    waiting.await()
                    emit(AppResult.Success(null))
                }
            else requireNotNull(f.contacts[peer])
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceInboxLabel>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, listOf(f.id, f.other)).toList(values)
            }
        runCurrent()
        val partial =
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last()).value
        assertEquals(listOf(f.id), partial.map { it.conversationId })
        assertEquals(f.contact.displayName, partial.single().displayName)
        waiting.complete(Unit)
        runCurrent()
        val complete =
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last()).value
        assertEquals(listOf(f.id, f.other), complete.map { it.conversationId })
        assertNull(complete.last().displayName)
        assertNull(complete.last().failure)
        job.cancelAndJoin()
        assertEquals(f.contactStarts.sorted(), f.contactStops.sorted())
    }

    @Test
    fun aFailedOrMissingCanonicalLabelWaitsForARealCatalogChange() = runTest {
        val f = SpaceInboxLabelsFixture()
        f.readMetadata = {
            if (it == f.id) AppResult.Failed(f.failure) else AppResult.Success(null)
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceInboxLabel>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, listOf(f.id, f.other)).toList(values)
            }
        runCurrent()
        val labels =
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last()).value
        assertEquals(f.failure, labels.first().failure)
        assertNull(labels.last().failure)
        assertTrue(f.contactStarts.isEmpty())
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(2, f.metadataReads.size)
        f.readMetadata = {
            AppResult.Success(if (it == f.id) f.base.room.conversation.localMetadata() else null)
        }
        f.versions.value = AppResult.Success(f.version.copy(revision = 2))
        runCurrent()
        assertEquals(4, f.metadataReads.size)
        assertEquals(
            f.contact.displayName,
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last())
                .value
                .first()
                .displayName,
        )
        job.cancelAndJoin()
    }

    @Test
    fun replacingTheCatalogGenerationDiscardsPreviouslyCachedBindings() = runTest {
        val f = SpaceInboxLabelsFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceInboxLabel>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, listOf(f.id)).toList(values)
            }
        runCurrent()
        f.readMetadata = { AppResult.Success(null) }
        f.versions.value = AppResult.Success(f.version.copy(generation = f.otherPeer))
        runCurrent()
        assertEquals(2, f.metadataReads.size)
        assertEquals(
            listOf(CollaborationSpaceInboxLabel(f.id)),
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last()).value,
        )
        assertEquals(1, f.contactStops.size)
        job.cancelAndJoin()
    }

    @Test
    fun contactUpdatesFailuresAndRemovalArePublishedWithoutAnotherMetadataRead() = runTest {
        val f = SpaceInboxLabelsFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceInboxLabel>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, listOf(f.id)).toList(values)
            }
        runCurrent()
        val contact = requireNotNull(f.contacts[f.contact.accountId])
        contact.value = AppResult.Success(f.contact.copy(displayName = "Updated colleague"))
        runCurrent()
        assertEquals(
            "Updated colleague",
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last())
                .value
                .single()
                .displayName,
        )
        contact.value = AppResult.Failed(f.failure)
        runCurrent()
        assertEquals(
            f.failure,
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last())
                .value
                .single()
                .failure,
        )
        contact.value = AppResult.Success(null)
        runCurrent()
        val removed =
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last())
                .value
                .single()
        assertNull(removed.failure)
        assertNull(removed.displayName)
        assertEquals(1, f.metadataReads.size)
        job.cancelAndJoin()
    }

    @Test
    fun confirmedDenialClosesEveryContactAndFailedRecoveryCannotReopenIt() = runTest {
        val f = SpaceInboxLabelsFixture()
        val values = mutableListOf<AppResult<List<CollaborationSpaceInboxLabel>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, listOf(f.id)).toList(values)
            }
        runCurrent()
        f.base.base.deny()
        runCurrent()
        assertEquals(AppResult.Failed(f.base.base.denied), values.last())
        assertEquals(1, f.contactStops.size)
        f.base.base.gate.value = AppResult.Failed(f.failure)
        runCurrent()
        f.base.currentAdmission = { AppResult.Failed(f.failure) }
        f.base.base.gate.value = AppResult.Success(f.base.base.open)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.base.denied), values.last())
        assertEquals(1, f.contactStarts.size)
        f.base.base.gate.value = AppResult.Failed(f.failure)
        runCurrent()
        f.base.currentAdmission = { AppResult.Success(f.base.base.open) }
        f.base.base.gate.value = AppResult.Success(f.base.base.open)
        runCurrent()
        assertEquals(2, f.contactStarts.size)
        assertEquals(
            f.contact.displayName,
            assertIs<AppResult.Success<List<CollaborationSpaceInboxLabel>>>(values.last())
                .value
                .single()
                .displayName,
        )
        job.cancelAndJoin()
    }

    @Test
    fun separateCollectorsNeverShareCanonicalCacheOrCancellation() = runTest {
        val f = SpaceInboxLabelsFixture()
        val flow = f.observer(f.access, listOf(f.id))
        val one = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect() }
        val two = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect() }
        runCurrent()
        assertEquals(2, f.metadataReads.size)
        assertEquals(2, f.catalogStarts)
        one.cancelAndJoin()
        assertEquals(1, f.catalogStops)
        assertTrue(two.isActive)
        two.cancelAndJoin()
        assertEquals(2, f.catalogStops)
        assertEquals(2, f.contactStops.size)
    }

    @Test
    fun ownershipReplacementRejectsLateUncooperativeCanonicalReads() = runTest {
        val f = SpaceInboxLabelsFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.readMetadata = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
            AppResult.Success(f.base.room.conversation.localMetadata())
        }
        val values = mutableListOf<AppResult<List<CollaborationSpaceInboxLabel>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer(f.access, listOf(f.id)).toList(values)
            }
        entered.await()
        f.base.identity.value = AppResult.Success(null)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertFalse(values.any { it is AppResult.Success })
        assertTrue(f.contactStarts.isEmpty())
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        job.cancelAndJoin()
        assertEquals(1, f.catalogStops)
    }

    @Test
    fun callerMutationCannotChangeTheBoundedIdentityRequest() = runTest {
        val f = SpaceInboxLabelsFixture()
        val ids = mutableListOf(f.id)
        val flow = f.observer(f.access, ids)
        ids.clear()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect() }
        runCurrent()
        assertEquals(listOf(f.id), f.metadataReads)
        job.cancelAndJoin()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.observer(f.access, List(51) { f.id }).first()).failure.kind,
        )
        assertEquals(1, f.catalogStarts)
    }
}
