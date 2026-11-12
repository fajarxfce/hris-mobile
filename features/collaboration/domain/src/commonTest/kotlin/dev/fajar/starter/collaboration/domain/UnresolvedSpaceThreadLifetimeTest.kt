@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class UnresolvedSpaceThreadLifetimeTest {
    @Test
    fun aSlowConsumerReceivesNoQueuedSnapshotFromThePreviousBindingSelection() = runTest {
        for (startsUnresolved in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture()
            val binding =
                MutableStateFlow<AppResult<CollaborationSpaceRoomBinding?>>(
                    AppResult.Success(if (startsUnresolved) null else f.room.binding)
                )
            f.readBindings = { binding }
            f.readMetadata = { AppResult.Success(f.room.conversation.localMetadata()) }
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
            val queued = f.commands.spaceRecord()
            f.commands.spaceFacts.value = AppResult.Success(listOf(queued))
            runCurrent()
            binding.value = AppResult.Success(if (startsUnresolved) f.room.binding else null)
            runCurrent()
            assertEquals(1, f.commands.spaceStops)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, values.size)
            val published =
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(values.last()).value
            if (startsUnresolved) {
                val bound = assertIs<CollaborationSpaceThreadSnapshot.Bound>(published)
                assertEquals(listOf(queued), bound.spaceCommands)
            } else {
                val unresolved = assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(published)
                assertEquals(listOf(queued), unresolved.spaceCommands)
            }
            assertEquals(1, f.metadataReads)
            assertEquals(1, f.spaceReads)
            job.cancelAndJoin()
            assertEquals(2, f.commands.spaceStops)
            assertEquals(1, f.bindingStops)
        }
    }

    @Test
    fun bindingSelectionJoinsALateCommandReadBeforeStartingTheNewReader() = runTest {
        val f = SpaceThreadHistoryFixture()
        val binding =
            MutableStateFlow<AppResult<CollaborationSpaceRoomBinding?>>(AppResult.Success(null))
        f.readBindings = { binding }
        f.readMetadata = { AppResult.Success(f.room.conversation.localMetadata()) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        f.commands.readSpace = {
            flow {
                reads++
                if (reads == 1) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    emit(AppResult.Success(listOf(f.commands.spaceRecord())))
                } else emitAll(f.commands.spaceFacts)
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
        assertEquals(1, reads)
        assertTrue(values.isEmpty())
        release.complete(Unit)
        runCurrent()
        val bound =
            assertIs<CollaborationSpaceThreadSnapshot.Bound>(
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(values.single()).value
            )
        assertTrue(bound.spaceCommands.isEmpty())
        assertEquals(2, reads)
        assertEquals(1, f.commands.spaceStops)
        job.cancelAndJoin()
        assertEquals(2, f.commands.spaceStops)
    }

    @Test
    fun denialAndLogoutRetireLateUnresolvedMetadataBeforeItCanPublish() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture()
            f.room.retained = AppResult.Success(null)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var retired = false
            f.readMetadata = {
                try {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    AppResult.Success(f.room.conversation.localMetadata())
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
            if (deny) f.base.deny() else f.identity.value = AppResult.Success(null)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            val failure = assertIs<AppResult.Failed>(values.single())
            if (deny) assertEquals(f.base.denied, failure.failure)
            assertTrue(retired)
            assertEquals(1, f.commands.spaceStops)
            assertEquals(0, f.originalReads)
            assertEquals(0, f.spaceReads)
            job.cancelAndJoin()
        }
    }

    @Test
    fun aSlowUnresolvedConsumerReceivesNoQueuedCommandsAfterDenialOrLogout() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture()
            f.room.retained = AppResult.Success(null)
            f.readMetadata = { AppResult.Success(f.room.conversation.localMetadata()) }
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
            assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(
                assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(values.single()).value
            )
            f.commands.spaceFacts.value = AppResult.Success(listOf(f.commands.spaceRecord()))
            runCurrent()
            if (deny) f.base.deny() else f.identity.value = AppResult.Success(null)
            runCurrent()
            assertEquals(1, f.commands.spaceStops)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, values.size)
            assertIs<AppResult.Failed>(values.last())
            job.cancelAndJoin()
        }
    }
}
