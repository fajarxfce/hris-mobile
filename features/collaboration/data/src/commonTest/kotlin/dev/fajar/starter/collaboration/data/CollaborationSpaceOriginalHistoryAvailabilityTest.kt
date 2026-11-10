package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.UnavailableCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceOriginalHistoryRepository
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceOriginalHistoryAvailabilityTest {
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
    fun aHostWithoutANativeBindingCannotSupplyOriginalHistory() = runTest {
        val repository: CollaborationSpaceOriginalHistoryRepository =
            UnavailableCollaborationRuntimeRepository()
        repeat(2) {
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(repository.read(runtime, "!original:example.test"))
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun unsupportedHostDoesNotSwallowCallerCancellation() = runTest {
        val repository: CollaborationSpaceOriginalHistoryRepository =
            UnavailableCollaborationRuntimeRepository()
        val job = launch {
            currentCoroutineContext().cancel()
            repository.read(runtime, "!original:example.test")
            error("Cancelled result")
        }
        job.join()
        assertTrue(job.isCancelled)
    }

    private companion object {
        const val ID = "10000000-0000-4000-8000-000000000001"
    }
}
