@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceRecipientDestinationTest {
    @Test
    fun retainedDestinationUsesOnlyExactLocalFactsEvenWithOldCredentials() = runTest {
        val f = SpaceRecipientDestinationFixture()
        f.base.native.setup.now += 300.days
        assertEquals(AppResult.Success(f.canonical), f.observe()(f.access, f.peer).first())
        assertEquals(1, f.lookups)
        assertEquals(2, f.subscriptions)
        assertEquals(f.subscriptions, f.stops)
        assertEquals(0, f.owner.contactReads)
        assertTrue(f.base.requests.isEmpty())
    }

    @Test
    fun absentCatalogDoesNotLookupAndLaterCreationPublishesThePair() = runTest {
        val f = SpaceRecipientDestinationFixture()
        f.versions.value = AppResult.Success(null)
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceConversationMetadata?>>(AppResult.Success(null)),
            values,
        )
        assertEquals(0, f.lookups)
        f.versions.value = AppResult.Success(f.version)
        runCurrent()
        assertEquals(AppResult.Success(f.canonical), values.last())
        assertEquals(1, f.lookups)
        job.cancelAndJoin()
    }

    @Test
    fun unrelatedInsertionsDoNotReacquireAnImmutableKnownPair() = runTest {
        val f = SpaceRecipientDestinationFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        repeat(30) {
            f.versions.value = AppResult.Success(f.version.copy(revision = it + 2L))
            runCurrent()
        }
        assertEquals(
            listOf<AppResult<CollaborationSpaceConversationMetadata?>>(
                AppResult.Success(f.canonical)
            ),
            values,
        )
        assertEquals(1, f.lookups)
        assertEquals(2, f.subscriptions)
        job.cancelAndJoin()
        assertEquals(f.subscriptions, f.stops)
    }

    @Test
    fun unresolvedPeerIsLookedUpOncePerChangedPublicationUntilItExists() = runTest {
        val f = SpaceRecipientDestinationFixture()
        f.result = AppResult.Success(null)
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        assertEquals(1, f.lookups)
        f.versions.value = AppResult.Success(f.version)
        runCurrent()
        assertEquals(1, f.lookups)
        f.result = AppResult.Success(f.canonical)
        f.versions.value = AppResult.Success(f.version.copy(revision = 2))
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceConversationMetadata?>>(
                AppResult.Success(null),
                AppResult.Success(f.canonical),
            ),
            values,
        )
        assertEquals(2, f.lookups)
        job.cancelAndJoin()
    }

    @Test
    fun generationReplacementAndRevisionRegressionDiscardTheKnownPair() = runTest {
        val f = SpaceRecipientDestinationFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        val replacement = f.version.copy(generation = f.base.revision, revision = 3)
        f.result = AppResult.Success(null)
        f.versions.value = AppResult.Success(replacement)
        runCurrent()
        assertEquals(AppResult.Success(null), values.last())
        f.result = AppResult.Success(f.canonical)
        f.versions.value = AppResult.Success(replacement.copy(revision = 4))
        runCurrent()
        f.result = AppResult.Success(null)
        f.versions.value = AppResult.Success(replacement.copy(revision = 2))
        runCurrent()
        assertEquals(AppResult.Success(null), values.last())
        assertEquals(4, f.lookups)
        job.cancelAndJoin()
    }

    @Test
    fun absenceAndStorageFailureInvalidateTheRetainedAssociation() = runTest {
        val f = SpaceRecipientDestinationFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        f.versions.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.storageFailure), values.last())
        f.result = AppResult.Success(null)
        f.versions.value = AppResult.Success(f.version)
        runCurrent()
        assertEquals(AppResult.Success(null), values.last())
        assertEquals(2, f.lookups)
        f.versions.value = AppResult.Success(null)
        runCurrent()
        f.result = AppResult.Success(f.canonical)
        f.versions.value = AppResult.Success(f.version)
        runCurrent()
        assertEquals(AppResult.Success(f.canonical), values.last())
        assertEquals(3, f.lookups)
        job.cancelAndJoin()
    }

    @Test
    fun versionChangingDuringLookupNeverPublishesTheOldAssociation() = runTest {
        val f = SpaceRecipientDestinationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replacement = f.canonical.copy(id = f.base.revision)
        f.beforeLookup = {
            if (f.lookups == 1) {
                entered.complete(Unit)
                release.await()
            } else f.result = AppResult.Success(replacement)
        }
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        entered.await()
        f.versions.value = AppResult.Success(f.version.copy(generation = f.base.revision))
        release.complete(Unit)
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceConversationMetadata?>>(
                AppResult.Success(replacement)
            ),
            values,
        )
        assertEquals(2, f.lookups)
        job.cancelAndJoin()
    }

    @Test
    fun lookupFailureIdlesUntilAnActualPublicationChange() = runTest {
        val f = SpaceRecipientDestinationFixture()
        f.result = AppResult.Failed(f.base.storageFailure)
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.lookups)
        assertEquals(
            listOf<AppResult<CollaborationSpaceConversationMetadata?>>(
                AppResult.Failed(f.base.storageFailure)
            ),
            values,
        )
        f.result = AppResult.Success(f.canonical)
        f.versions.value = AppResult.Success(f.version.copy(revision = 2))
        runCurrent()
        assertEquals(AppResult.Success(f.canonical), values.last())
        assertEquals(2, f.lookups)
        job.cancelAndJoin()
    }

    @Test
    fun malformedVersionAndForeignPairNeverExposeContent() = runTest {
        val f = SpaceRecipientDestinationFixture()
        f.versions.value = AppResult.Success(f.version.copy(revision = -1))
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.observe()(f.access, f.peer).first()).failure.kind,
        )
        assertEquals(0, f.lookups)
        f.versions.value = AppResult.Success(f.version)
        f.result = AppResult.Success(f.canonical.copy(spaceId = f.base.revision))
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.observe()(f.access, f.peer).first()).failure.kind,
        )
    }

    @Test
    fun postReadDenialSuppressesThePrivateResultAndStaysLatchedThroughFailure() = runTest {
        val f = SpaceRecipientDestinationFixture()
        f.beforeLookup = { f.base.deny() }
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceConversationMetadata?>>(
                AppResult.Failed(f.base.denied)
            ),
            values,
        )
        f.base.gate.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceConversationMetadata?>>(
                AppResult.Failed(f.base.denied)
            ),
            values,
        )
        assertEquals(1, f.lookups)
        f.beforeLookup = {}
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(AppResult.Success(f.canonical), values.last())
        assertEquals(2, f.lookups)
        job.cancelAndJoin()
    }

    @Test
    fun accountReplacementJoinsAnUnfinishedLookupAndRejectsLateContent() = runTest {
        val f = SpaceRecipientDestinationFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.beforeLookup = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        entered.await()
        f.base.identity.value = AppResult.Success(null)
        runCurrent()
        assertTrue(stopped.isCompleted)
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.single()).failure.kind,
        )
        assertEquals(f.subscriptions, f.stops)
        job.cancelAndJoin()
    }

    @Test
    fun admissionCompletionClosesTheContentReader() = runTest {
        val f = SpaceRecipientDestinationFixture()
        val finish = CompletableDeferred<Unit>()
        f.owner.gateFacts = {
            flow {
                emit(AppResult.Success(f.base.open))
                finish.await()
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        assertEquals(AppResult.Success(f.canonical), values.last())
        finish.complete(Unit)
        runCurrent()
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(f.subscriptions, f.stops)
        job.cancelAndJoin()
    }

    @Test
    fun sessionCompletionRedactsTheResultAndClosesItsReader() = runTest {
        val f = SpaceRecipientDestinationFixture()
        val finish = CompletableDeferred<Unit>()
        f.owner.identityFacts = {
            flow {
                emit(f.base.identity.value)
                finish.await()
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        assertEquals(AppResult.Success(f.canonical), values.last())
        finish.complete(Unit)
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(f.subscriptions, f.stops)
        job.cancelAndJoin()
    }

    @Test
    fun catalogCompletionInvalidatesTheKnownAssociationWithoutRestarting() = runTest {
        val f = SpaceRecipientDestinationFixture()
        val finish = CompletableDeferred<Unit>()
        f.catalogFacts = {
            flow {
                emit(f.versions.value)
                finish.await()
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceConversationMetadata?>>()
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).toList(values) }
        runCurrent()
        assertEquals(AppResult.Success(f.canonical), values.last())
        finish.complete(Unit)
        runCurrent()
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(f.subscriptions, f.stops)
        assertEquals(1, f.lookups)
        job.cancelAndJoin()
    }

    @Test
    fun disposalCancelsTheLookupWithoutSchedulingAnother() = runTest {
        val f = SpaceRecipientDestinationFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.beforeLookup = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val job = backgroundScope.launch { f.observe()(f.access, f.peer).collect() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertEquals(f.subscriptions, f.stops)
        runCurrent()
        assertEquals(1, f.lookups)
    }

    @Test
    fun invalidRecipientCannotSubscribeOrLookup() = runTest {
        val f = SpaceRecipientDestinationFixture()
        for (peer in listOf("invalid", f.access.accountId)) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.observe()(f.access, peer).first()).failure.kind,
            )
        }
        assertEquals(0, f.lookups)
        assertEquals(0, f.subscriptions)
    }
}
