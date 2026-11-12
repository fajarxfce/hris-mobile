@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveUnresolvedSpaceThreadTest {
    @Test
    fun knownMetadataPublishesCanonicalCommandsWithoutInventingHistory() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        f.room.retained = AppResult.Success(null)
        val metadata = f.room.conversation.localMetadata()
        f.readMetadata = { AppResult.Success(metadata) }
        val queued = f.commands.spaceRecord()
        f.commands.spaceFacts.value = AppResult.Success(listOf(queued))
        f.commands.readOriginal = {
            error("Unresolved conversation cannot select Company commands")
        }
        val value =
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(
                    f.observe()(f.access, f.id).first()
                )
                .value
        val unresolved = assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(value)
        assertEquals(f.access, unresolved.access)
        assertEquals(metadata, unresolved.conversation)
        assertEquals(listOf(queued), unresolved.spaceCommands)
        assertEquals(0, f.spaceReads)
        assertEquals(0, f.originalReads)
        assertEquals(0, f.commands.originalStarts)
        assertEquals(1, f.metadataReads)
        assertEquals(1, f.bindingStarts)
        assertEquals(1, f.bindingStops)
    }

    @Test
    fun eitherBindingArrivesWithoutReopeningAndDuplicateValuesDoNotRestartReaders() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(legacy)
            val binding =
                MutableStateFlow<AppResult<CollaborationSpaceRoomBinding?>>(AppResult.Success(null))
            f.readBindings = {
                binding.transform {
                    emit(it)
                    emit(it)
                }
            }
            f.readMetadata = { AppResult.Success(f.room.conversation.localMetadata()) }
            val queued = f.commands.spaceRecord()
            f.commands.spaceFacts.value = AppResult.Success(listOf(queued))
            val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.id).toList(values)
                }
            runCurrent()
            assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(values.single()).value
            )
            assertEquals(0, f.commands.originalStarts)
            binding.value = AppResult.Success(f.room.binding)
            runCurrent()
            assertEquals(2, values.size)
            val bound =
                assertIs<CollaborationSpaceThreadSnapshot.Bound>(
                    assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(values.last())
                        .value
                )
            assertEquals(f.page(), bound.history.page)
            assertEquals(listOf(queued), bound.spaceCommands)
            assertEquals(if (legacy) 0 else 1, f.spaceReads)
            assertEquals(if (legacy) 1 else 0, f.originalReads)
            assertEquals(if (legacy) 1 else 0, f.commands.originalStarts)
            assertEquals(2, f.commands.spaceStarts)
            assertEquals(1, f.metadataReads)
            assertEquals(1, f.bindingStarts)
            job.cancelAndJoin()
            assertEquals(1, f.bindingStops)
        }
    }

    @Test
    fun anExistingBindingDoesNotWaitForOrReadSeparateConversationMetadata() = runTest {
        val f = SpaceThreadHistoryFixture()
        f.readMetadata = { error("A bound conversation already has exact metadata") }
        val value =
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(
                    f.observe()(f.access, f.id).first()
                )
                .value
        assertIs<CollaborationSpaceThreadSnapshot.Bound>(value)
        assertEquals(0, f.metadataReads)
    }

    @Test
    fun unknownForeignOrMalformedMetadataCannotPublishAnUnresolvedThread() = runTest {
        val template = SpaceThreadHistoryFixture()
        val valid = template.room.conversation.localMetadata()
        val other = "b0000000-0000-4000-8000-000000000001"
        for (metadata in
            listOf(
                null,
                valid.copy(id = other),
                valid.copy(spaceId = other),
                valid.copy(firstAccountId = other),
                valid.copy(version = 1),
            )) {
            val f = SpaceThreadHistoryFixture()
            f.room.retained = AppResult.Success(null)
            f.readMetadata = { AppResult.Success(metadata) }
            f.commands.spaceFacts.value = AppResult.Success(listOf(f.commands.spaceRecord()))
            val failure = assertIs<AppResult.Failed>(f.observe()(f.access, f.id).first()).failure
            assertEquals(
                if (metadata == null) FailureKind.Unavailable else FailureKind.Unexpected,
                failure.kind,
            )
            assertEquals(0, f.spaceReads)
            assertEquals(0, f.originalReads)
            assertEquals(0, f.commands.originalStarts)
        }
    }

    @Test
    fun aKnownUnresolvedReadFailureDoesNotWaitForTheOtherStore() = runTest {
        for (contextFails in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture()
            f.room.retained = AppResult.Success(null)
            f.readMetadata = {
                if (contextFails) AppResult.Failed(f.base.storageFailure) else awaitCancellation()
            }
            f.commands.readSpace = {
                if (contextFails) flow { awaitCancellation() }
                else flowOf(AppResult.Failed(f.base.storageFailure))
            }
            val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.id).toList(values)
                }
            runCurrent()
            assertEquals(
                listOf<AppResult<CollaborationSpaceThreadSnapshot>>(
                    AppResult.Failed(f.base.storageFailure)
                ),
                values,
            )
            assertEquals(0, f.spaceReads)
            assertEquals(0, f.originalReads)
            job.cancelAndJoin()
            assertEquals(1, f.commands.spaceStops)
            assertEquals(1, f.bindingStops)
        }
    }

    @Test
    fun unresolvedCommandsAreCapturedBeforeWaitingForMetadata() = runTest {
        val f = SpaceThreadHistoryFixture()
        f.room.retained = AppResult.Success(null)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.readMetadata = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.room.conversation.localMetadata())
        }
        val before = mutableSetOf("previous")
        val records =
            mutableListOf(
                f.commands
                    .spaceRecord()
                    .copy(
                        revision = 1,
                        state =
                            CollaborationMessageState.Staging(
                                f.commands.device(),
                                before,
                                CollaborationMessageOrigin(
                                    "@self:matrix.example.test",
                                    "a".repeat(64),
                                ),
                            ),
                    )
            )
        f.commands.spaceFacts.value = AppResult.Success(records)
        val result = async { f.observe()(f.access, f.id).first() }
        entered.await()
        runCurrent()
        before += "changed"
        records.clear()
        release.complete(Unit)
        val snapshot =
            assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(result.await()).value
            )
        assertEquals(
            setOf("previous"),
            assertIs<CollaborationMessageState.Staging>(snapshot.spaceCommands.single().state)
                .before,
        )
    }

    @Test
    fun anArrivingBindingCancelsHeldMetadataAndPublishesOnlyBoundHistory() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val binding =
            MutableStateFlow<AppResult<CollaborationSpaceRoomBinding?>>(AppResult.Success(null))
        f.readBindings = { binding }
        val entered = CompletableDeferred<Unit>()
        var retired = false
        f.readMetadata = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                retired = true
            }
        }
        val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe()(f.access, f.id).toList(values)
            }
        entered.await()
        binding.value = AppResult.Success(f.room.binding)
        runCurrent()
        val snapshot =
            assertIs<CollaborationSpaceThreadSnapshot.Bound>(
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(values.single()).value
            )
        assertTrue(retired)
        assertEquals(f.page(), snapshot.history.page)
        assertEquals(1, f.originalReads)
        assertEquals(1, f.metadataReads)
        job.cancelAndJoin()
        assertEquals(1, f.bindingStops)
    }
}
