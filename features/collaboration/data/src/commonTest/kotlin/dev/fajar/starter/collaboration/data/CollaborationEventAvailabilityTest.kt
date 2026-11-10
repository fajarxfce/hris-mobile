package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.UnavailableCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationEventAvailabilityTest {
    private val repository = UnavailableCollaborationRuntimeRepository()
    private val owner = CollaborationOwner("local", "https://fixture.test/", ID, ID, ID)
    private val runtime =
        CollaborationSpaceRuntimeReady(
            CollaborationSpaceOwner(
                CollaborationNativeParent("local", "https://fixture.test/", ID, ID),
                ID,
            ),
            ID,
            ID,
        )

    @Test
    fun absentNativeBindingCannotReturnAPhysicalEvent() = runTest {
        val events: CollaborationEventRepository = repository
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(events.read(owner, ID, "!room:test", "\$event")).failure.kind,
        )
    }

    @Test
    fun absentNativeBindingCannotReturnASpaceEvent() = runTest {
        val events: CollaborationSpaceEventRepository = repository
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(events.read(runtime, "!room:test", "\$event")).failure.kind,
        )
    }

    @Test
    fun absentNativeBindingCannotClaimEmptyProviderHistory() = runTest {
        val events: CollaborationSpaceEventRepository = repository
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(events.findHead(runtime, "!room:test")).failure.kind,
        )
    }

    @Test
    fun absentNativeBindingCannotClaimAnExhaustedSpacePage() = runTest {
        val history: CollaborationSpaceProviderHistoryRepository = repository
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(history.readPage(runtime, "!room:test", null)).failure.kind,
        )
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(history.readPage(runtime, "!room:test", "next", "until"))
                .failure
                .kind,
        )
    }

    @Test
    fun unavailableOperationsPreserveCallerCancellation() = runTest {
        val operations: List<suspend () -> AppResult<*>> =
            listOf(
                { repository.read(owner, ID, "!room:test", "\$event") },
                { repository.read(runtime, "!room:test", "\$event") },
                { repository.findHead(runtime, "!room:test") },
                { repository.readPage(runtime, "!room:test", null) },
            )
        for (operation in operations) {
            val caller = launch {
                currentCoroutineContext().cancel()
                operation()
                error("Cancelled read returned")
            }
            caller.join()
            assertTrue(caller.isCancelled)
        }
    }

    private companion object {
        const val ID = "10000000-0000-4000-8000-000000000001"
    }
}
