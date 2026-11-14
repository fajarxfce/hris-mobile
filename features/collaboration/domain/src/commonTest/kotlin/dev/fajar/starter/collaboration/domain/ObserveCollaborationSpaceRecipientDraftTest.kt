@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceRecipientDraftTest {
    @Test
    fun retainedAndChangedBodiesStayLocalWithoutContactOrTransportAcquisition() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.base.native.setup.now += 300.days
        f.contact = null
        f.facts.value = AppResult.Success(f.original)
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientDraft?>>()
        val job =
            backgroundScope.launch {
                f.observe()(f.access, f.original.peerAccountId).toList(values)
            }
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceRecipientDraft?>>(AppResult.Success(f.original)),
            values,
        )
        val updated = f.original.copy(revision = f.base.generation, text = "Updated local text")
        f.facts.value = AppResult.Success(updated)
        runCurrent()
        assertEquals(AppResult.Success(updated), values.last())
        assertEquals(1, f.observations)
        assertEquals(0, f.contactReads)
        assertTrue(f.base.requests.isEmpty())
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun denialSurvivesFailedRetriesUntilAnAdmittedPublicationReopensTheReader() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.facts.value = AppResult.Success(f.original)
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientDraft?>>()
        val job =
            backgroundScope.launch {
                f.observe()(f.access, f.original.peerAccountId).toList(values)
            }
        runCurrent()
        f.base.deny()
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        assertEquals(1, f.stops)
        f.base.gate.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        assertEquals(1, f.observations)
        f.base.gate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(AppResult.Success(f.original), values.last())
        assertEquals(2, f.observations)
        job.cancelAndJoin()
        assertEquals(2, f.stops)
    }

    @Test
    fun aLostAccountClosesTheOldDraftSubscription() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.facts.value = AppResult.Success(f.original)
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientDraft?>>()
        val job =
            backgroundScope.launch {
                f.observe()(f.access, f.original.peerAccountId).toList(values)
            }
        runCurrent()
        f.base.identity.value = AppResult.Success(null)
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(1, f.stops)
        f.facts.value = AppResult.Success(f.original.copy(text = "Late old data"))
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(1, f.observations)
        job.cancelAndJoin()
    }

    @Test
    fun disposalJoinsAPendingLocalReader() = runTest {
        val f = SpaceRecipientDraftFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.readFacts = {
            flow {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        val job =
            backgroundScope.launch { f.observe()(f.access, f.original.peerAccountId).collect() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertEquals(1, f.stops)
    }

    @Test
    fun denialDuringTheReadGuardDoesNotPublishTheCapturedPrivateBody() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.facts.value = AppResult.Success(f.original)
        var guards = 0
        f.base.beforeAdmission = { if (++guards == 2) f.base.deny() }
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientDraft?>>()
        val job =
            backgroundScope.launch {
                f.observe()(f.access, f.original.peerAccountId).toList(values)
            }
        runCurrent()
        assertFalse(values.any { it is AppResult.Success })
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        job.cancelAndJoin()
        assertEquals(f.observations, f.stops)
    }

    @Test
    fun aSubstitutedPeerValueFailsInsteadOfBeingDisplayed() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.facts.value = AppResult.Success(f.original.copy(peerAccountId = f.access.accountId))
        val result = f.observe()(f.access, f.original.peerAccountId).first()
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(result).failure.kind)
        assertEquals(1, f.stops)
    }

    @Test
    fun invalidOrSelfRecipientNeverOpensStorage() = runTest {
        for (self in listOf(false, true)) {
            val f = SpaceRecipientDraftFixture()
            val peer = if (self) f.access.accountId else "invalid"
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.observe()(f.access, peer).first()).failure.kind,
            )
            assertEquals(0, f.observations)
        }
    }

    @Test
    fun admissionSourceCompletionCancelsContentWithoutRestartingIt() = runTest {
        val f = SpaceRecipientDraftFixture()
        val finish = CompletableDeferred<Unit>()
        f.gateFacts = {
            flow {
                emit(AppResult.Success(f.base.open))
                finish.await()
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientDraft?>>()
        val job =
            backgroundScope.launch {
                f.observe()(f.access, f.original.peerAccountId).toList(values)
            }
        runCurrent()
        assertEquals(1, f.observations)
        finish.complete(Unit)
        runCurrent()
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(1, f.stops)
        runCurrent()
        assertEquals(1, f.observations)
        job.cancelAndJoin()
    }

    @Test
    fun identitySourceCompletionCannotLeaveItsAdmittedReaderAlive() = runTest {
        val f = SpaceRecipientDraftFixture()
        val finish = CompletableDeferred<Unit>()
        f.identityFacts = {
            flow {
                emit(f.base.identity.value)
                finish.await()
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientDraft?>>()
        val job =
            backgroundScope.launch {
                f.observe()(f.access, f.original.peerAccountId).toList(values)
            }
        runCurrent()
        assertEquals(1, f.observations)
        finish.complete(Unit)
        runCurrent()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        assertEquals(1, f.stops)
        job.cancelAndJoin()
    }

    @Test
    fun unchangedLocalValuesNeitherReopenTheSourceNorRepublishTheBody() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.facts.value = AppResult.Success(f.original)
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientDraft?>>()
        val job =
            backgroundScope.launch {
                f.observe()(f.access, f.original.peerAccountId).toList(values)
            }
        runCurrent()
        repeat(5) {
            f.facts.value = AppResult.Success(f.original.copy())
            runCurrent()
        }
        assertEquals(
            listOf<AppResult<CollaborationSpaceRecipientDraft?>>(AppResult.Success(f.original)),
            values,
        )
        assertEquals(1, f.observations)
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }
}
