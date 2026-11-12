@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SpaceThreadParticipantLifetimeTest {
    @Test
    fun denialOrLogoutJoinsEveryLateParticipantAndRejectsItsResult() = runTest {
        for (source in 0..2) for (denied in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lateContact = f.participants.contact.copy(displayName = "Late colleague")
            val lateIdentity = CollaborationSelfIdentity("@late:matrix.example.test")
            when (source) {
                0 -> f.participants.readContacts = {
                        flow {
                            entered.complete(Unit)
                            withContext(NonCancellable) { release.await() }
                            emit(AppResult.Success(lateContact))
                        }
                    }
                1 -> f.participants.readRegistrations = {
                        flow {
                            entered.complete(Unit)
                            withContext(NonCancellable) { release.await() }
                            emit(AppResult.Success(listOf(f.participants.record())))
                        }
                    }
                else -> f.participants.readOriginal = {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        AppResult.Success(lateIdentity)
                    }
            }
            val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.id).toList(values)
                }
            entered.await()
            if (denied) f.base.deny() else f.identity.value = AppResult.Success(null)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertIs<AppResult.Failed>(values.last())
            val snapshots =
                values.filterIsInstance<AppResult.Success<CollaborationSpaceThreadSnapshot>>()
            assertTrue(
                snapshots.none { it.value.participants.contact == AppResult.Success(lateContact) }
            )
            assertTrue(
                snapshots.none {
                    it.value.participants.originalIdentity == AppResult.Success(lateIdentity)
                }
            )
            assertTrue(snapshots.isEmpty())
            assertEquals(1, f.participants.contactStops)
            assertEquals(1, f.participants.registrationStops)
            assertEquals(1, f.participants.originalStops)
            job.cancelAndJoin()
        }
    }

    @Test
    fun slowConsumerCannotReceiveBufferedParticipantUpdatesAfterDenial() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).collect {
                    values += it
                    if (values.size == 1) {
                        first.complete(Unit)
                        release.await()
                    }
                }
            }
        first.await()
        f.participants.contacts.value =
            AppResult.Success(f.participants.contact.copy(displayName = "Changed while held"))
        runCurrent()
        f.base.deny()
        runCurrent()
        assertEquals(1, f.participants.contactStops)
        assertEquals(1, f.participants.registrationStops)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, values.size)
        assertEquals(AppResult.Failed(f.base.denied), values.last())
        job.cancelAndJoin()
    }

    @Test
    fun removingOriginalBindingRetiresItsIdentityAndStartsOnlyCanonicalParticipants() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val bindings =
            MutableStateFlow<AppResult<CollaborationSpaceRoomBinding?>>(
                AppResult.Success(f.room.binding)
            )
        f.readBindings = { bindings }
        f.readMetadata = { AppResult.Success(f.room.conversation.localMetadata()) }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        runCurrent()
        assertEquals(
            AppResult.Success(f.participants.original),
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(values.last())
                .value
                .participants
                .originalIdentity,
        )
        bindings.value = AppResult.Success(null)
        runCurrent()
        val current =
            assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(values.last()).value
            )
        assertEquals(AppResult.Success(null), current.participants.originalIdentity)
        assertEquals(1, f.metadataReads)
        assertEquals(1, f.participants.originalReads)
        assertEquals(1, f.participants.originalStops)
        assertEquals(2, f.participants.contactStarts)
        assertEquals(2, f.participants.registrationStarts)
        assertEquals(1, f.participants.contactStops)
        job.cancelAndJoin()
        assertEquals(2, f.participants.contactStops)
        assertEquals(2, f.participants.registrationStops)
    }

    @Test
    fun cancellingFinalAdmissionPublishesNoParticipantsAndJoinsTheirReaders() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val entered = CompletableDeferred<Unit>()
        var checks = 0
        f.currentAdmission = {
            checks++
            if (checks == 3) {
                entered.complete(Unit)
                awaitCancellation()
            }
            AppResult.Success(f.base.open)
        }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        entered.await()
        job.cancelAndJoin()
        assertTrue(values.isEmpty())
        assertEquals(1, f.participants.contactStarts)
        assertEquals(1, f.participants.contactStops)
        assertEquals(1, f.participants.registrationStops)
        assertEquals(1, f.participants.originalStops)
    }
}
