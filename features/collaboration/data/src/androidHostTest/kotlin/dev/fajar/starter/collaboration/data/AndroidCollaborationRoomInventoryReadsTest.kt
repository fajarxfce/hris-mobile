package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.CollaborationRoomInventorySource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.RequestFailureException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationRoomInventoryReadsTest {
    @Test
    fun mappedPagesRetainExactOriginalIdentityAndReleaseChildCapacity() = runTest {
        val f = InventoryReadFixture(backgroundScope, 1)
        repeat(2) {
            val page =
                assertIs<AppResult.Success<CollaborationSpaceRoomInventoryPage>>(f.read()).value
            assertEquals(f.runtime, page.runtime)
            assertEquals(f.original, page.source)
            assertEquals("!a:test", page.rooms.single().roomId)
            assertNull(page.nextAfterRoomId)
        }
        assertEquals(2, f.source.calls)
        assertEquals(AppResult.Success(Unit), f.children.close())
    }

    @Test
    fun invalidCoordinatesCannotAcquireNativeResources() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        for (limit in listOf(0, -1, 65)) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.reader.read(f.runtime, null, limit)).failure.kind,
        )
        for (cursor in listOf("bad", "!", "!a b:test")) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.reader.read(f.runtime, cursor, 16)).failure.kind,
        )
        assertEquals(0, f.source.calls)
    }

    @Test
    fun alreadyCancelledCallerDoesNotStartEnumeration() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        val caller = launch {
            currentCoroutineContext().cancel()
            f.read()
            error("Cancelled caller returned inventory")
        }
        caller.join()
        assertTrue(caller.isCancelled)
        assertEquals(0, f.source.calls)
    }

    @Test
    fun existingDenialPreventsLocalSdkAccess() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        f.failure = denied
        assertEquals(AppResult.Failed(denied), f.read())
        assertEquals(0, f.source.calls)
    }

    @Test
    fun denialWhileInitializationIsQueuedPreventsEnumeration() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        val caller = async(start = CoroutineStart.UNDISPATCHED) { f.read() }
        f.failure = denied
        assertEquals(AppResult.Failed(denied), caller.await())
        assertEquals(0, f.source.calls)
    }

    @Test
    fun denialAfterRawReadRejectsTheDetachedPage() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        f.source.result = { after, _ ->
            f.failure = denied
            inventoryReadDto(after)
        }
        assertEquals(AppResult.Failed(denied), f.read())
        assertEquals(1, f.source.calls)
    }

    @Test
    fun lateDenialSurvivesASimultaneousRawFailure() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        f.source.result = { _, _ ->
            f.failure = denied
            error("Fixture local SDK failure")
        }
        assertEquals(AppResult.Failed(denied), f.read())
    }

    @Test
    fun classifiedSdkRejectionsAreNotConvertedToStorageFailures() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        for (kind in listOf(FailureKind.Unauthorized, FailureKind.AccessDenied)) {
            val failure = Failure(kind, "Fixture rejection", code = "fixture.inventory_rejected")
            f.source.result = { _, _ -> throw RequestFailureException(failure) }
            assertSame(failure, assertIs<AppResult.Failed>(f.read()).failure)
        }
    }

    @Test
    fun malformedMetadataReleasesCapacityWithoutAnInlineRetry() = runTest {
        val f = InventoryReadFixture(backgroundScope, 1)
        f.source.result = { _, _ -> inventoryReadDto("!wrong:test") }
        assertIs<AppResult.Failed>(f.read())
        assertEquals(1, f.source.calls)
        f.source.result = { after, _ -> inventoryReadDto(after) }
        assertIs<AppResult.Success<*>>(f.read())
        assertEquals(2, f.source.calls)
    }

    @Test
    fun stoppedParentCannotStartAnotherRead() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        assertEquals(AppResult.Success(Unit), f.children.close())
        assertEquals(
            "client.collaboration_children_stopped",
            assertIs<AppResult.Failed>(f.read()).failure.code,
        )
        assertEquals(0, f.source.calls)
    }

    @Test
    fun cancelledWaiterLeavesLateReadOwnedUntilItFinishes() = runTest {
        val f = InventoryReadFixture(backgroundScope, 1)
        val release = CompletableDeferred<Unit>()
        f.source.result = { after, _ ->
            release.await()
            inventoryReadDto(after)
        }
        val caller = async { f.read() }
        runCurrent()
        caller.cancel()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(caller.isCompleted)
        assertEquals(
            "client.collaboration_children_full",
            assertIs<AppResult.Failed>(f.read()).failure.code,
        )
        assertEquals(1, f.source.calls)
        release.complete(Unit)
        runCurrent()
        assertIs<AppResult.Success<*>>(f.read())
        assertEquals(2, f.source.calls)
    }

    @Test
    fun expiredWaiterRetainsCapacityWithoutRepeatingEnumeration() = runTest {
        val f = InventoryReadFixture(backgroundScope, 1)
        val release = CompletableDeferred<Unit>()
        f.source.result = { after, _ ->
            release.await()
            inventoryReadDto(after)
        }
        val caller = async { f.read() }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(
            "client.collaboration_child_pending",
            assertIs<AppResult.Failed>(caller.await()).failure.code,
        )
        assertEquals(
            "client.collaboration_children_full",
            assertIs<AppResult.Failed>(f.read()).failure.code,
        )
        assertEquals(1, f.source.calls)
        release.complete(Unit)
        runCurrent()
        assertIs<AppResult.Success<*>>(f.read())
    }

    @Test
    fun closingTheParentWaitsForTheReadAndRejectsItsResult() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        val release = CompletableDeferred<Unit>()
        f.source.result = { after, _ ->
            release.await()
            inventoryReadDto(after)
        }
        val caller = async { f.read() }
        runCurrent()
        val closing = async { f.children.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        assertIs<AppResult.Failed>(caller.await())
        assertEquals(AppResult.Success(Unit), closing.await())
        assertEquals(1, f.source.calls)
    }

    @Test
    fun overlappingReadsKeepSeparateCursorsWhenFinishingInReverseOrder() = runTest {
        val f = InventoryReadFixture(backgroundScope)
        val release = CompletableDeferred<Unit>()
        f.source.result = { after, _ ->
            if (after == null) release.await()
            inventoryReadDto(after)
        }
        val first = async { f.read() }
        runCurrent()
        val second =
            assertIs<AppResult.Success<CollaborationSpaceRoomInventoryPage>>(
                    f.reader.read(f.runtime, "!a:test", 16)
                )
                .value
        assertEquals("!a:test", second.afterRoomId)
        assertEquals("!b:test", second.rooms.single().roomId)
        release.complete(Unit)
        val original =
            assertIs<AppResult.Success<CollaborationSpaceRoomInventoryPage>>(first.await()).value
        assertNull(original.afterRoomId)
        assertEquals("!a:test", original.rooms.single().roomId)
        assertEquals(2, f.source.calls)
    }

    private val denied =
        Failure(FailureKind.AccessDenied, "Fixture denial", code = "fixture.denied")
}

private class InventoryReadFixture(scope: CoroutineScope, capacity: Int = 8) {
    var failure: Failure? = null
    val original = CollaborationSpaceIncomingSource("a".repeat(64), "@original:test", "ORIGINAL")
    val runtime =
        CollaborationSpaceRuntimeReady(
            CollaborationSpaceOwner(
                CollaborationNativeParent("local", "https://fixture.test/", ID, ID),
                ID,
            ),
            ID,
            ID,
        )
    val children = SdkChildResources(scope, capacity)
    val source = InventoryReadSource()
    val reader = AndroidCollaborationRoomInventoryReads(original, source, children) { failure }

    suspend fun read() = reader.read(runtime, null, 16)

    private companion object {
        const val ID = "10000000-0000-4000-8000-000000000001"
    }
}

private class InventoryReadSource : CollaborationRoomInventorySource {
    var calls = 0
    var result: suspend (String?, Int) -> CollaborationSdkRoomInventoryDto = { after, _ ->
        inventoryReadDto(after)
    }

    override suspend fun read(afterRoomId: String?, limit: Int): CollaborationSdkRoomInventoryDto {
        calls++
        return result(afterRoomId, limit)
    }
}

private fun inventoryReadDto(after: String?) =
    CollaborationSdkRoomInventoryDto(
        after,
        listOf(
            CollaborationSdkInventoryRoomDto(
                if (after == null) "!a:test" else "!b:test",
                CollaborationSdkInventoryMembership.JOINED,
            )
        ),
        null,
    )
