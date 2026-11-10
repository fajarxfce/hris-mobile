package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.UnavailableCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpacePusherRepository
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationSpacePusherAvailabilityTest {
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
    fun aHostWithoutANativeBindingCannotAcknowledgeRegistration() = runTest {
        val repository: CollaborationSpacePusherRepository =
            UnavailableCollaborationRuntimeRepository()
        repeat(2) {
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(repository.activate(runtime)).failure.kind,
            )
        }
    }

    @Test
    fun unsupportedHostDoesNotSwallowCallerCancellation() = runTest {
        val repository: CollaborationSpacePusherRepository =
            UnavailableCollaborationRuntimeRepository()
        val job = launch {
            currentCoroutineContext().cancel()
            repository.activate(runtime)
            error("Cancelled result")
        }
        job.join()
        assertTrue(job.isCancelled)
    }

    private companion object {
        const val ID = "10000000-0000-4000-8000-000000000001"
    }
}
