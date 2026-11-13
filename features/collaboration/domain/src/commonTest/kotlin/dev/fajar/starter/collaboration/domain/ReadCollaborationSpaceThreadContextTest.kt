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

class ReadCollaborationSpaceThreadContextTest {
    @Test
    fun ownedLocalContextDoesNotNeedSelectedCompanyOrUnexpiredCredentials() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.native.setup.workspace = null
        f.native.setup.beforeWorkspace = { error("No selected HR company") }
        f.native.setup.now += 100.days
        assertEquals(
            AppResult.Success(requireNotNull(f.canonical)),
            context(f)(f.access, f.room.conversation.id),
        )
        assertEquals(1, f.canonicalReads)
        assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
        assertEquals(0, f.reads)
    }

    @Test
    fun explicitRenewedAccessReopensTheSameOwnedContext() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val renewed = f.access.copy(sessionId = "renewed")
        f.identity.value = AppResult.Success(f.native.session.copy(id = renewed.sessionId))
        assertEquals(
            AppResult.Success(requireNotNull(f.canonical)),
            context(f)(renewed, f.room.conversation.id),
        )
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(context(f)(f.access, f.room.conversation.id)).failure.kind,
        )
        assertEquals(1, f.canonicalReads)
    }

    @Test
    fun knownDenialPreventsPrivateMetadataAcquisition() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.base.deny()
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(context(f)(f.access, f.room.conversation.id)).failure.kind,
        )
        assertEquals(0, f.canonicalReads)
    }

    @Test
    fun signOutDuringAdmissionPreventsPrivateMetadataAcquisition() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.base.beforeAdmission = { f.identity.value = AppResult.Success(null) }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(context(f)(f.access, f.room.conversation.id)).failure.kind,
        )
        assertEquals(0, f.canonicalReads)
    }

    @Test
    fun lateDenialOrAccountReplacementCannotPublishTheCapturedContext() = runTest {
        for (denied in listOf(true, false)) {
            val f = SpaceMessageUseCaseFixture()
            f.beforeCanonical = {
                if (denied) f.base.deny() else f.identity.value = AppResult.Success(null)
            }
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(context(f)(f.access, f.room.conversation.id))
                    .failure
                    .kind,
            )
            assertEquals(1, f.canonicalReads)
        }
    }

    @Test
    fun missingOrForeignMetadataDoesNotAuthorizeTheAction() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val original = requireNotNull(f.canonical)
        for (value in
            listOf(
                null,
                original.copy(spaceId = "e1000000-0000-4000-8000-000000000999"),
                original.copy(id = "e1000000-0000-4000-8000-000000000998"),
            )) {
            f.canonical = value
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(context(f)(f.access, f.room.conversation.id))
                    .failure
                    .kind,
            )
        }
        assertEquals(3, f.canonicalReads)
    }

    @Test
    fun invalidConversationIsRejectedBeforeAnyMetadataRead() = runTest {
        val f = SpaceMessageUseCaseFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(context(f)(f.access, "invalid")).failure.kind,
        )
        assertEquals(0, f.canonicalReads)
    }

    @Test
    fun metadataStorageFailureKeepsItsClassificationWithoutRetry() = runTest {
        val f = SpaceMessageUseCaseFixture()
        var calls = 0
        val metadata =
            object : CollaborationSpaceConversationMetadataRepository by f.metadata {
                override suspend fun getById(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String,
                ): AppResult<CollaborationSpaceConversationMetadata?> {
                    calls++
                    return AppResult.Failed(f.base.storageFailure)
                }
            }
        val read =
            ReadCollaborationSpaceThreadContext(
                metadata,
                f.base.directory,
                f.sessions,
                f.native.server,
            )
        assertEquals(
            AppResult.Failed(f.base.storageFailure),
            read(f.access, f.room.conversation.id),
        )
        assertEquals(1, calls)
    }

    @Test
    fun failedSessionReadDoesNotFallBackToPreviouslyKnownOwnership() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.identity.value = AppResult.Failed(f.base.storageFailure)
        assertEquals(
            AppResult.Failed(f.base.storageFailure),
            context(f)(f.access, f.room.conversation.id),
        )
        assertEquals(0, f.canonicalReads)
    }

    @Test
    fun aPendingLocalReadHasAFiniteActionDeadline() = runTest {
        val f = SpaceMessageUseCaseFixture()
        var released = false
        f.beforeCanonical = {
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        val result = async { context(f)(f.access, f.room.conversation.id) }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(result.await()).failure.kind)
        assertTrue(released)
        assertEquals(1, f.canonicalReads)
    }

    @Test
    fun cancellationDiscardsALateNonCooperativeMetadataResult() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        f.beforeCanonical = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val result = launch {
            context(f)(f.access, f.room.conversation.id)
            published = true
        }
        entered.await()
        result.cancel()
        release.complete(Unit)
        result.join()
        assertTrue(result.isCancelled)
        assertFalse(published)
        assertEquals(1, f.canonicalReads)
    }

    private fun context(f: SpaceMessageUseCaseFixture) =
        ReadCollaborationSpaceThreadContext(
            f.metadata,
            f.base.directory,
            f.sessions,
            f.native.server,
        )
}
