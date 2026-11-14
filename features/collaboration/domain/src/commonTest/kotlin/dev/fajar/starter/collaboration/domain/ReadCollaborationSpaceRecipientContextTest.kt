@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceConversationMetadataRepository
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceThreadContext
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ReadCollaborationSpaceRecipientContextTest {
    @Test
    fun admittedAbsenceDoesNotCreateOrDiscoverAConversation() = runTest {
        val f = Fixture()
        f.value = null
        assertEquals(AppResult.Success(null), f.read.recipient(f.source.access, f.peer))
        assertEquals(1, f.reads)
        assertEquals(0, f.source.canonicalReads)
        assertTrue(f.source.writes.isEmpty() && f.source.schedules.isEmpty())
    }

    @Test
    fun exactLocalPairRemainsReadableWithoutSelectedCompanyOrFreshCredentials() = runTest {
        val f = Fixture()
        f.source.native.setup.workspace = null
        f.source.native.setup.beforeWorkspace = { error("No remote workspace acquisition") }
        f.source.native.setup.now += 100.days
        assertEquals(AppResult.Success(f.value), f.read.recipient(f.source.access, f.peer))
        assertEquals(1, f.reads)
        assertEquals(0, f.source.reads)
        assertTrue(f.source.writes.isEmpty() && f.source.schedules.isEmpty())
    }

    @Test
    fun invalidOrSelfPeerIsRejectedBeforeReadingPrivateMetadata() = runTest {
        val f = Fixture()
        for (peer in listOf("invalid", f.source.access.accountId)) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.read.recipient(f.source.access, peer)).failure.kind,
            )
        }
        assertEquals(0, f.reads)
    }

    @Test
    fun wrongPairOrSpaceDoesNotAuthorizeARecipientAction() = runTest {
        val f = Fixture()
        val original = requireNotNull(f.value)
        for (value in
            listOf(
                original.copy(spaceId = "e1000000-0000-4000-8000-000000000999"),
                original.copy(
                    firstAccountId = f.source.access.accountId,
                    secondAccountId = "e1000000-0000-4000-8000-000000000998",
                ),
                original.copy(id = "invalid"),
            )) {
            f.value = value
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.read.recipient(f.source.access, f.peer)).failure.kind,
            )
        }
        assertEquals(3, f.reads)
    }

    @Test
    fun knownDenialOrSignOutPreventsThePeerLookup() = runTest {
        for (denied in listOf(true, false)) {
            val f = Fixture()
            if (denied) f.source.base.deny() else f.source.identity.value = AppResult.Success(null)
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read.recipient(f.source.access, f.peer)).failure.kind,
            )
            assertEquals(0, f.reads)
        }
    }

    @Test
    fun lateDenialOrAccountReplacementDiscardsEvenAnAbsentPair() = runTest {
        for (denied in listOf(true, false)) {
            val f = Fixture()
            f.value = null
            f.beforeRead = {
                if (denied) f.source.base.deny()
                else f.source.identity.value = AppResult.Success(null)
            }
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read.recipient(f.source.access, f.peer)).failure.kind,
            )
            assertEquals(1, f.reads)
        }
    }

    @Test
    fun storageFailureIsReturnedWithoutAutomaticRetry() = runTest {
        val f = Fixture()
        f.failure = f.source.base.storageFailure
        assertEquals(AppResult.Failed(f.failure!!), f.read.recipient(f.source.access, f.peer))
        advanceTimeBy(120_000)
        assertEquals(1, f.reads)
    }

    @Test
    fun pendingPeerLookupHasAFiniteDeadlineAndReleasesItsReader() = runTest {
        val f = Fixture()
        var released = false
        f.beforeRead = {
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        val result = async { f.read.recipient(f.source.access, f.peer) }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(result.await()).failure.kind)
        assertTrue(released)
        assertEquals(1, f.reads)
    }

    @Test
    fun cancellationRejectsANonCooperativeLatePeerLookup() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        f.beforeRead = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val job = launch {
            f.read.recipient(f.source.access, f.peer)
            published = true
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
        assertEquals(1, f.reads)
    }

    private class Fixture {
        val source = SpaceMessageUseCaseFixture()
        val peer =
            requireNotNull(source.canonical).let {
                if (it.firstAccountId == source.access.accountId) it.secondAccountId
                else it.firstAccountId
            }
        var value = source.canonical
        var failure: Failure? = null
        var reads = 0
        var beforeRead: suspend () -> Unit = {}
        val metadata =
            object : CollaborationSpaceConversationMetadataRepository by source.metadata {
                override suspend fun findByPeer(
                    access: CollaborationSpaceLocalAccess,
                    peerAccountId: String,
                ): AppResult<CollaborationSpaceConversationMetadata?> {
                    assertEquals(source.access, access)
                    assertEquals(peer, peerAccountId)
                    reads++
                    beforeRead()
                    return failure?.let { AppResult.Failed(it) } ?: AppResult.Success(value)
                }
            }
        val read =
            ReadCollaborationSpaceThreadContext(
                metadata,
                source.base.directory,
                source.sessions,
                source.native.server,
            )
    }
}
