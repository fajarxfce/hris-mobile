@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceComposerIntent
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SpaceComposerRecoveryPendingTest {
    @Test
    fun anEmptyOutboxStillReportsQueuedMessagesUntilTheirActualCancellation() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.original()
        val queued =
            CollaborationSpaceMessageRecord(
                collaborationSpaceComposerIntent(f.access, original),
                0,
                CollaborationMessageState.Queued,
            )
        f.records[original.id] = queued
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(queued, f.records[original.id])
        assertTrue(f.inserted.isEmpty() && f.retired.isEmpty() && f.retained.isEmpty())
        assertEquals(0, f.cursorWrites)
        assertEquals(1, f.inventoryReads)
        assertEquals(1, f.inventoryStops)
        f.records[original.id] = queued.copy(state = CollaborationMessageState.Cancelled)
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, false)),
            f.recover()(),
        )
        assertTrue(f.inserted.isEmpty() && f.retired.isEmpty())
    }

    @Test
    fun failedMessageInventoryCannotClaimAnIdleRecovery() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.original()
        val queued =
            CollaborationSpaceMessageRecord(
                collaborationSpaceComposerIntent(f.access, original),
                0,
                CollaborationMessageState.Queued,
            )
        f.records[original.id] = queued
        f.readInventory = { flowOf(AppResult.Failed(f.base.base.storageFailure)) }
        assertEquals(AppResult.Failed(f.base.base.storageFailure), f.recover()())
        assertEquals(mapOf(original.id to queued), f.records)
        assertTrue(f.inserted.isEmpty() && f.retired.isEmpty())
        assertEquals(1, f.inventoryReads)
        assertEquals(1, f.inventoryStops)
    }

    @Test
    fun missingMalformedForeignOrOversizedInventoryCannotClaimIdle() = runTest {
        for (invalid in 0..3) {
            val f = SpaceComposerRecoveryFixture()
            val original = f.original()
            val queued =
                CollaborationSpaceMessageRecord(
                    collaborationSpaceComposerIntent(f.access, original),
                    0,
                    CollaborationMessageState.Queued,
                )
            f.records[original.id] = queued
            f.readInventory = {
                when (invalid) {
                    0 -> emptyFlow()
                    1 -> flowOf(AppResult.Success(listOf(queued.copy(revision = -1))))
                    2 ->
                        flowOf(
                            AppResult.Success(
                                listOf(
                                    queued.copy(
                                        intent =
                                            queued.intent.copy(
                                                owner =
                                                    queued.intent.owner.copy(
                                                        spaceId = deliveryId(8000)
                                                    )
                                            )
                                    )
                                )
                            )
                        )
                    else ->
                        flowOf(
                            AppResult.Success(
                                (0..128).map { index ->
                                    queued.copy(
                                        intent = queued.intent.copy(id = deliveryId(8000 + index))
                                    )
                                }
                            )
                        )
                }
            }
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.recover()()).failure.kind,
            )
            assertEquals(mapOf(original.id to queued), f.records)
            assertTrue(f.inserted.isEmpty() && f.retired.isEmpty())
            assertEquals(1, f.inventoryReads)
            assertEquals(1, f.inventoryStops)
        }
    }

    @Test
    fun cancellationDuringFinalInventoryPreservesTheAlreadyMaterializedOriginal() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        val entered = CompletableDeferred<Unit>()
        f.readInventory = {
            flow {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val job = async { f.recover()() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(
            collaborationSpaceComposerIntent(f.access, original),
            f.records[original.id]?.intent,
        )
        assertEquals(listOf(original), f.retired)
        assertTrue(f.retained.isEmpty())
        assertEquals(1, f.inserted.size)
        assertEquals(1, f.inventoryStops)
    }

    @Test
    fun finalInventoryUsesTheSameFiveSecondPassBudgetWithoutAnInternalRetry() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        f.readInventory = { flow { awaitCancellation() } }
        val before = testScheduler.currentTime
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.recover()()).failure.kind)
        assertEquals(5000L, testScheduler.currentTime - before)
        assertEquals(
            collaborationSpaceComposerIntent(f.access, original),
            f.records[original.id]?.intent,
        )
        assertEquals(listOf(original), f.retired)
        assertEquals(1, f.inventoryReads)
        assertEquals(1, f.inventoryStops)
        assertEquals(1, f.inserted.size)
    }

    @Test
    fun lateDenialOrAccountReplacementCannotPublishCapturedPendingFacts() = runTest {
        for (deny in listOf(false, true)) {
            val f = SpaceComposerRecoveryFixture()
            val original = f.retain(1).single()
            var captured = false
            f.readInventory = {
                flow {
                    captured = true
                    emit(AppResult.Success(f.records.values.toList()))
                }
            }
            f.base.base.beforeAdmission = {
                if (captured) {
                    if (deny) f.base.base.deny()
                    else f.base.identity.value = AppResult.Success(null)
                }
            }
            assertEquals(
                if (deny) FailureKind.AccessDenied else FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.recover()()).failure.kind,
            )
            assertEquals(
                collaborationSpaceComposerIntent(f.access, original),
                f.records[original.id]?.intent,
            )
            assertEquals(listOf(original), f.retired)
            assertEquals(1, f.inserted.size)
            assertEquals(1, f.inventoryStops)
        }
    }
}
