package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRuntimeLease
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.matrix.rustcomponents.sdk.RoomKeyChangeBatch

class AndroidSpaceRoomKeyBindingTest {
    @Test
    fun retirementDuringRegistrationCannotPublishSuccessOrAbandonTheLateHandle() = runBlocking {
        val f = RoomKeyBindingFixture.create()
        val release = CompletableDeferred<Unit>()
        f.native.beforeKeySubscribe = { release.await() }
        try {
            val opening = async { f.storage.repo.startKeyObservation(f.runtime, f.source) }
            withTimeout(5_000) { f.native.keySubscriptionEntered.await() }
            val closing = async { f.storage.repo.closeSpace(f.runtime.owner) }
            yield()
            assertNull(f.storage.repo.incomingSource(f.runtime))
            assertFalse(opening.isCompleted)
            assertEquals(0, f.native.closes)
            release.complete(Unit)
            assertEquals(AppResult.Success(Unit), closing.await())
            assertIs<AppResult.Failed>(opening.await())
            assertEquals(1, f.native.keySubscriptions)
            assertEquals(1, f.native.keyCloses)
            assertEquals(1, f.native.closes)
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun explicitRegistrationAndReadingUseOnlyTheInstalledOriginalClient() = runBlocking {
        val f = RoomKeyBindingFixture.create()
        try {
            repeat(2) {
                assertEquals(
                    AppResult.Success(Unit),
                    f.storage.repo.startKeyObservation(f.runtime, f.source),
                )
            }
            f.emit("!room:test")
            val captured = f.batch()
            assertEquals(f.runtime, captured.runtime)
            assertEquals(f.source, captured.source)
            assertEquals(listOf("!room:test"), captured.roomIds)
            assertEquals(1, f.native.keySubscriptions)
            assertEquals(0, f.native.requests)
            assertEquals(1, f.storage.acquisitions)
            assertEquals(AppResult.Success(true), f.storage.repo.acknowledgeKeys(captured))
            assertEquals(AppResult.Success(false), f.storage.repo.acknowledgeKeys(captured))
        } finally {
            f.close()
        }
        assertEquals(1, f.native.keyCloses)
        assertEquals(1, f.native.closes)
    }

    @Test
    fun collectingAndCancellingAnIdleStreamNeverRegistersOrStartsSync() = runBlocking {
        val f = RoomKeyBindingFixture.create()
        try {
            val observer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    f.storage.repo.observeKeys(f.runtime, f.source).collect()
                }
            yield()
            observer.cancelAndJoin()
            assertEquals(0, f.native.keySubscriptions)
            assertEquals(0, f.native.requests)
            assertEquals(0, f.native.keyCloses)
        } finally {
            f.close()
        }
    }

    @Test
    fun unpublishedRuntimeCannotRegisterOrExposeKeys() = runBlocking {
        val f = RoomKeyBindingFixture.create(publish = false)
        try {
            assertIs<AppResult.Failed>(f.storage.repo.startKeyObservation(f.runtime, f.source))
            assertIs<AppResult.Failed>(f.storage.repo.observeKeys(f.runtime, f.source).single())
            assertEquals(0, f.native.keySubscriptions)
        } finally {
            f.close()
        }
    }

    @Test
    fun foreignPhysicalSourceCannotRegisterObserveOrAcknowledgeTheCurrentBatch() = runBlocking {
        val f = RoomKeyBindingFixture.create()
        try {
            f.storage.repo.startKeyObservation(f.runtime, f.source)
            f.emit("!room:test")
            val captured = f.batch()
            for (foreign in
                listOf(
                    f.source.copy(storeId = "b".repeat(64)),
                    f.source.copy(matrixUserId = "@other:test"),
                    f.source.copy(matrixDeviceId = "OTHER"),
                )) {
                assertIs<AppResult.Failed>(f.storage.repo.startKeyObservation(f.runtime, foreign))
                assertIs<AppResult.Failed>(f.storage.repo.observeKeys(f.runtime, foreign).single())
                assertIs<AppResult.Failed>(
                    f.storage.repo.acknowledgeKeys(captured.copy(source = foreign))
                )
            }
            assertEquals(captured, f.batch())
            assertEquals(AppResult.Success(true), f.storage.repo.acknowledgeKeys(captured))
            assertEquals(1, f.native.keySubscriptions)
        } finally {
            f.close()
        }
    }

    @Test
    fun foreignLogicalRuntimeCannotConsumeTheCurrentReceipt() = runBlocking {
        val f = RoomKeyBindingFixture.create()
        try {
            f.storage.repo.startKeyObservation(f.runtime, f.source)
            f.emit("!room:test")
            val captured = f.batch()
            for (runtime in
                listOf(
                    f.runtime.copy(deviceId = SETUP_JOB),
                    f.runtime.copy(operationId = SETUP_DEVICE),
                    f.runtime.copy(owner = f.runtime.owner.copy(spaceId = SETUP_JOB)),
                )) {
                assertIs<AppResult.Failed>(f.storage.repo.startKeyObservation(runtime, f.source))
                assertIs<AppResult.Failed>(f.storage.repo.observeKeys(runtime, f.source).single())
                assertIs<AppResult.Failed>(
                    f.storage.repo.acknowledgeKeys(captured.copy(runtime = runtime))
                )
            }
            assertEquals(captured, f.batch())
            assertEquals(AppResult.Success(true), f.storage.repo.acknowledgeKeys(captured))
        } finally {
            f.close()
        }
    }

    @Test
    fun readingABatchNeverAcknowledgesAndEqualRoomOccurrencesRemainDistinct() = runBlocking {
        val f = RoomKeyBindingFixture.create()
        try {
            f.storage.repo.startKeyObservation(f.runtime, f.source)
            f.emit("!same:test")
            val first = f.batch()
            assertEquals(first, f.batch())
            f.emit("!same:test")
            assertEquals(first, f.batch())
            assertEquals(AppResult.Success(true), f.storage.repo.acknowledgeKeys(first))
            val next = f.batch()
            assertEquals(first.roomIds, next.roomIds)
            assertNotEquals(first.receiptId, next.receiptId)
            assertEquals(AppResult.Success(false), f.storage.repo.acknowledgeKeys(first))
            assertEquals(next, f.batch())
            assertEquals(AppResult.Success(true), f.storage.repo.acknowledgeKeys(next))
        } finally {
            f.close()
        }
    }

    @Test
    fun retiredRuntimeEndsObservationAndRejectsBufferedAndLateAcknowledgment() = runBlocking {
        val f = RoomKeyBindingFixture.create()
        try {
            f.storage.repo.startKeyObservation(f.runtime, f.source)
            f.emit("!room:test")
            val captured = f.batch()
            val terminal =
                async(start = CoroutineStart.UNDISPATCHED) {
                    f.storage.repo.observeKeys(f.runtime, f.source).first { it is AppResult.Failed }
                }
            assertEquals(AppResult.Success(Unit), f.storage.repo.closeSpace(f.runtime.owner))
            assertIs<AppResult.Failed>(withTimeout(5_000) { terminal.await() })
            f.emit("!late:test")
            assertIs<AppResult.Failed>(f.storage.repo.acknowledgeKeys(captured))
            assertIs<AppResult.Failed>(f.storage.repo.observeKeys(f.runtime, f.source).single())
            assertEquals(1, f.native.keyCloses)
        } finally {
            f.close()
        }
    }

    @Test
    fun absentNativeSubscriptionDoesNotRetryEveryExplicitWaiter() = runBlocking {
        val f = RoomKeyBindingFixture.create()
        f.native.keySubscriptionAvailable = false
        try {
            repeat(4) {
                assertIs<AppResult.Failed>(f.storage.repo.startKeyObservation(f.runtime, f.source))
            }
            assertEquals(1, f.native.keySubscriptions)
            assertEquals(0, f.native.requests)
            assertEquals(1, f.storage.acquisitions)
        } finally {
            f.close()
        }
        assertEquals(0, f.native.keyCloses)
    }
}

private class RoomKeyBindingFixture(
    val storage: SpaceAdoptionStorageFixture,
    val native: SpaceRuntimeSyncClientSource,
    val runtime: CollaborationSpaceRuntimeReady,
    val source: CollaborationSpaceIncomingSource,
) {
    fun emit(room: String) {
        checkNotNull(native.keyListener).onUpdate(RoomKeyChangeBatch(listOf(room), false))
    }

    suspend fun batch() =
        withTimeout(5_000) {
            assertIs<AppResult.Success<CollaborationSpaceRoomKeyBatch>>(
                    storage.repo.observeKeys(runtime, source).first()
                )
                .value
        }

    suspend fun close() {
        native.responses.close()
        storage.repo.closeSpace(storage.owner)
        storage.close()
    }

    companion object {
        suspend fun create(publish: Boolean = true): RoomKeyBindingFixture {
            lateinit var native: SpaceRuntimeSyncClientSource
            val storage =
                SpaceAdoptionStorageFixture.create { identity ->
                    SpaceRuntimeSyncClientSource(identity).also { native = it }
                }
            storage.select()
            val lease =
                assertIs<AppResult.Success<CollaborationSpaceRuntimeLease>>(storage.install()).value
            if (publish) assertEquals(AppResult.Success(Unit), lease.publish())
            val original = storage.data.original
            return RoomKeyBindingFixture(
                storage,
                native,
                CollaborationSpaceRuntimeReady(
                    lease.owner,
                    lease.deviceId,
                    lease.installedOperationId,
                ),
                CollaborationSpaceIncomingSource(
                    original.storeId,
                    original.matrixUserId,
                    original.matrixDeviceId,
                ),
            )
        }
    }
}
