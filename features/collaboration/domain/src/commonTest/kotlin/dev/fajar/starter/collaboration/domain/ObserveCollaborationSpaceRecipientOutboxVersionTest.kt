@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceRecipientOutboxVersionTest {
    @Test
    fun oneOwnedSubscriptionSuppressesDuplicateMetadataWithoutReadingMessages() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        val input =
            MutableSharedFlow<AppResult<CollaborationSpaceRecipientOutboxVersion?>>(
                replay = 1,
                extraBufferCapacity = 8,
            )
        input.emit(AppResult.Success(f.version))
        f.readVersions = { input }
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientOutboxVersion?>>()
        val job = backgroundScope.launch { f.observe()(f.access).toList(values) }
        runCurrent()
        repeat(3) { input.emit(AppResult.Success(f.version.copy())) }
        runCurrent()
        assertEquals<List<AppResult<CollaborationSpaceRecipientOutboxVersion?>>>(
            listOf(AppResult.Success(f.version)),
            values,
        )
        val next = f.version.copy(revision = 1)
        input.emit(AppResult.Success(next))
        runCurrent()
        assertEquals(AppResult.Success(next), values.last())
        assertEquals(1, f.starts)
        assertEquals(0, f.draft.contactReads)
        assertTrue(f.pages.isEmpty())
        assertTrue(f.base.requests.isEmpty())
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun deniedOwnerNeverOpensTheSource() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        f.base.deny()
        assertEquals(AppResult.Failed(f.base.denied), f.observe()(f.access).first())
        assertEquals(0, f.starts)
    }

    @Test
    fun confirmedDenialStaysLatchedUntilAnAdmittedAcquisition() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        val observedGate =
            MutableStateFlow<AppResult<CollaborationSpaceDirectoryAdmission>>(
                AppResult.Success(f.base.open)
            )
        f.draft.gateFacts = { observedGate }
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientOutboxVersion?>>()
        val job = backgroundScope.launch { f.observe()(f.access).toList(values) }
        runCurrent()
        f.base.deny()
        f.versions.value = AppResult.Success(f.version.copy(revision = 1))
        runCurrent()
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        val count = values.size
        f.base.gate.value = AppResult.Success(f.base.open)
        f.versions.value = AppResult.Success(f.version.copy(revision = 2))
        runCurrent()
        observedGate.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(count, values.size)
        observedGate.value = AppResult.Success(f.base.open)
        runCurrent()
        assertEquals(f.versions.value, values.last())
        assertEquals(2, f.starts)
        job.cancelAndJoin()
        assertEquals(2, f.stops)
    }

    @Test
    fun accountReplacementJoinsTheOldSourceAndRejectsLateVersions() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientOutboxVersion?>>()
        val job = backgroundScope.launch { f.observe()(f.access).toList(values) }
        runCurrent()
        f.base.identity.value = AppResult.Success(null)
        runCurrent()
        assertEquals(1, f.stops)
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(values.last()).failure.kind,
        )
        val count = values.size
        f.versions.value = AppResult.Success(f.version.copy(revision = 4))
        runCurrent()
        assertEquals(count, values.size)
        assertEquals(1, f.starts)
        job.cancelAndJoin()
    }

    @Test
    fun invalidVersionsAreRejectedWhileAbsenceAndExplicitEmptyRemainReadable() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientOutboxVersion?>>()
        val job = backgroundScope.launch { f.observe()(f.access).toList(values) }
        runCurrent()
        f.versions.value = AppResult.Success(f.version.copy(revision = -1))
        runCurrent()
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(values.last()).failure.kind)
        f.versions.value = AppResult.Success(null)
        runCurrent()
        assertEquals(AppResult.Success(null), values.last())
        f.versions.value = AppResult.Success(f.version.copy(revision = null))
        runCurrent()
        assertEquals(f.versions.value, values.last())
        assertEquals(1, f.starts)
        job.cancelAndJoin()
    }

    @Test
    fun aStorageFailureRecoversOnANewFactWithoutResubscribingOrPolling() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientOutboxVersion?>>()
        val job = backgroundScope.launch { f.observe()(f.access).toList(values) }
        runCurrent()
        f.versions.value = AppResult.Failed(f.base.storageFailure)
        runCurrent()
        assertEquals(AppResult.Failed(f.base.storageFailure), values.last())
        val count = values.size
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(count, values.size)
        f.versions.value = AppResult.Success(f.version)
        runCurrent()
        assertEquals(AppResult.Success(f.version), values.last())
        assertEquals(1, f.starts)
        job.cancelAndJoin()
    }

    @Test
    fun cancellationWhileRecheckingAdmissionClosesTheMetadataReader() = runTest {
        val f = SpaceRecipientRecoveryFixture()
        val values = mutableListOf<AppResult<CollaborationSpaceRecipientOutboxVersion?>>()
        val job = backgroundScope.launch { f.observe()(f.access).toList(values) }
        runCurrent()
        val entered = CompletableDeferred<Unit>()
        f.base.beforeAdmission = {
            entered.complete(Unit)
            awaitCancellation()
        }
        f.versions.value = AppResult.Success(f.version.copy(revision = 1))
        entered.await()
        job.cancelAndJoin()
        assertEquals<List<AppResult<CollaborationSpaceRecipientOutboxVersion?>>>(
            listOf(AppResult.Success(f.version)),
            values,
        )
        assertEquals(1, f.stops)
    }
}
