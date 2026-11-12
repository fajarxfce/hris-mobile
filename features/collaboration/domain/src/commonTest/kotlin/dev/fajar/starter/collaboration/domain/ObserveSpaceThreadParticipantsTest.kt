@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveSpaceThreadParticipantsTest {
    @Test
    fun bothHistoryOriginsCarryExactMetadataAndDistinctProvenSenderIdentities() = runTest {
        for (original in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(original)
            val value =
                assertIs<CollaborationSpaceThreadSnapshot.Bound>(
                    f.observe()(f.access, f.id)
                        .filterIsInstance<AppResult.Success<CollaborationSpaceThreadSnapshot>>()
                        .first()
                        .value
                )
            assertEquals(f.room.binding.conversation, value.conversation)
            assertEquals(AppResult.Success(f.participants.contact), value.participants.contact)
            assertEquals(
                AppResult.Success(f.participants.selected),
                value.participants.selectedIdentity,
            )
            assertEquals(
                AppResult.Success(if (original) f.participants.original else null),
                value.participants.originalIdentity,
            )
            assertEquals(if (original) 1 else 0, f.participants.originalReads)
            assertEquals(0, f.metadataReads)
            assertEquals(1, f.participants.contactStarts)
            assertEquals(1, f.participants.registrationStarts)
        }
    }

    @Test
    fun unresolvedConversationReadsCanonicalMetadataOnceAndNeverOriginalIdentity() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        f.room.retained = AppResult.Success(null)
        f.readMetadata = { AppResult.Success(f.room.conversation.localMetadata()) }
        f.participants.readOriginal = {
            error("Unresolved history has no original identity source")
        }
        val value =
            f.observe()(f.access, f.id)
                .filterIsInstance<AppResult.Success<CollaborationSpaceThreadSnapshot>>()
                .first()
                .value
        assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(value)
        assertEquals(AppResult.Success(f.participants.contact), value.participants.contact)
        assertEquals(
            AppResult.Success(f.participants.selected),
            value.participants.selectedIdentity,
        )
        assertEquals(1, f.metadataReads)
        assertEquals(0, f.participants.originalReads)
        assertEquals(0, f.originalReads + f.spaceReads)
    }

    @Test
    fun changedLabelsUpdateWithoutRestartingHistoryCommandsOrRegistrationReads() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        runCurrent()
        val previous = values.size
        val updated = f.participants.contact.copy(displayName = "Updated colleague")
        f.participants.contacts.value = AppResult.Success(updated)
        runCurrent()
        assertEquals(previous + 1, values.size)
        assertEquals(
            AppResult.Success(updated),
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(values.last())
                .value
                .participants
                .contact,
        )
        f.participants.contacts.value = AppResult.Success(updated.copy())
        f.commands.spaceFacts.value = AppResult.Success(listOf(f.commands.spaceRecord()))
        runCurrent()
        assertEquals(previous + 2, values.size)
        assertEquals(1, f.originalReads)
        assertEquals(1, f.commands.spaceStarts)
        assertEquals(1, f.commands.originalStarts)
        assertEquals(1, f.participants.contactStarts)
        assertEquals(1, f.participants.registrationStarts)
        assertEquals(1, f.participants.originalReads)
        job.cancelAndJoin()
    }

    @Test
    fun initialSnapshotWaitsForLocalParticipantsAndCancellationJoinsTheirReaders() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val contactEntered = CompletableDeferred<Unit>()
        val registrationEntered = CompletableDeferred<Unit>()
        val originalEntered = CompletableDeferred<Unit>()
        f.participants.readContacts = {
            flow {
                contactEntered.complete(Unit)
                awaitCancellation()
            }
        }
        f.participants.readRegistrations = {
            flow {
                registrationEntered.complete(Unit)
                awaitCancellation()
            }
        }
        f.participants.readOriginal = {
            originalEntered.complete(Unit)
            awaitCancellation()
        }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        contactEntered.await()
        registrationEntered.await()
        originalEntered.await()
        runCurrent()
        assertTrue(values.isEmpty())
        assertEquals(1, f.originalReads)
        job.cancelAndJoin()
        assertTrue(values.isEmpty())
        assertEquals(1, f.participants.contactStops)
        assertEquals(1, f.participants.registrationStops)
        assertEquals(1, f.participants.originalStops)
    }

    @Test
    fun missingParticipantsAndTheirFailuresRemainDistinctFromOwnedHistory() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        f.participants.contacts.value = AppResult.Success(null)
        f.participants.registrations.value = AppResult.Success(emptyList())
        val failure = Failure(FailureKind.Storage, "client.storage_failed")
        f.participants.readOriginal = { AppResult.Failed(failure) }
        val value =
            f.observe()(f.access, f.id)
                .filterIsInstance<AppResult.Success<CollaborationSpaceThreadSnapshot>>()
                .first()
                .value
        assertIs<CollaborationSpaceThreadSnapshot.Bound>(value)
        assertEquals(AppResult.Success(null), value.participants.contact)
        assertEquals(AppResult.Success(null), value.participants.selectedIdentity)
        assertEquals(AppResult.Failed(failure), value.participants.originalIdentity)
        assertEquals(f.page(), value.history.page)
    }

    @Test
    fun knownCommandFailureDoesNotWaitForMetadataOrAnyParticipant() = runTest {
        for (unresolved in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(true)
            if (unresolved) f.room.retained = AppResult.Success(null)
            f.readMetadata = { awaitCancellation() }
            f.participants.readContacts = { flow { awaitCancellation() } }
            f.participants.readRegistrations = { flow { awaitCancellation() } }
            f.participants.readOriginal = { awaitCancellation() }
            val failure = Failure(FailureKind.Storage, "client.storage_failed")
            f.commands.spaceFacts.value = AppResult.Failed(failure)
            assertEquals(AppResult.Failed(failure), f.observe()(f.access, f.id).first())
            assertEquals(1, f.participants.registrationStops)
        }
    }
}
