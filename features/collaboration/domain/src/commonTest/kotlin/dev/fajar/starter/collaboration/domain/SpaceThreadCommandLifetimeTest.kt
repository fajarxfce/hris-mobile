@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SpaceThreadCommandLifetimeTest {
    @Test
    fun denialRetiresEitherLateCommandSourceBeforeItCanPublish() = runTest {
        for (canonical in listOf(false, true)) {
            val f = SpaceThreadHistoryFixture(true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            if (canonical)
                f.commands.readSpace = {
                    flow {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        emit(AppResult.Success(listOf(f.commands.spaceRecord())))
                    }
                }
            else
                f.commands.readOriginal = {
                    flow {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        emit(AppResult.Success(listOf(f.commands.originalRecord())))
                    }
                }
            val values = mutableListOf<AppResult<CollaborationSpaceThreadSnapshot>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe()(f.access, f.id).toList(values)
                }
            entered.await()
            f.base.deny()
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(
                listOf<AppResult<CollaborationSpaceThreadSnapshot>>(
                    AppResult.Failed(f.base.denied)
                ),
                values,
            )
            assertEquals(1, f.commands.spaceStops)
            assertEquals(1, f.commands.originalStops)
            assertEquals(1, f.stops)
            job.cancelAndJoin()
        }
    }

    @Test
    fun aSlowConsumerCannotReceiveQueuedCommandsAfterDenialOrLogout() = runTest {
        for (deny in listOf(false, true)) {
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
            f.commands.spaceFacts.value = AppResult.Success(listOf(f.commands.spaceRecord()))
            f.commands.originalFacts.value = AppResult.Success(listOf(f.commands.originalRecord()))
            runCurrent()
            if (deny) f.base.deny() else f.identity.value = AppResult.Success(null)
            runCurrent()
            assertEquals(1, f.commands.spaceStops)
            assertEquals(1, f.commands.originalStops)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, values.size)
            assertIs<AppResult.Failed>(values.last())
            job.cancelAndJoin()
        }
    }

    @Test
    fun cancellingTheFinalGuardJoinsHistoryAndBothCommandReaders() = runTest {
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
        assertEquals(1, f.stops)
        assertEquals(1, f.commands.spaceStops)
        assertEquals(1, f.commands.originalStops)
    }

    @Test
    fun originalStagingIsFrozenWhileCanonicalCommandsAreStillReading() = runTest {
        val f = SpaceThreadHistoryFixture(true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val before = mutableSetOf("previous")
        val records =
            mutableListOf(
                f.commands
                    .originalRecord()
                    .copy(
                        revision = 1,
                        state = CollaborationMessageState.Staging(f.commands.device(), before),
                    )
            )
        f.commands.originalFacts.value = AppResult.Success(records)
        f.commands.readSpace = {
            flow {
                entered.complete(Unit)
                release.await()
                emit(AppResult.Success(emptyList()))
            }
        }
        val result = async { f.observe()(f.access, f.id).first() }
        entered.await()
        runCurrent()
        before += "changed"
        records.clear()
        release.complete(Unit)
        val snapshot =
            assertIs<AppResult.Success<CollaborationSpaceThreadSnapshot>>(result.await()).value
        assertEquals(
            setOf("previous"),
            assertIs<CollaborationMessageState.Staging>(snapshot.originalCommands.single().state)
                .before,
        )
    }
}
