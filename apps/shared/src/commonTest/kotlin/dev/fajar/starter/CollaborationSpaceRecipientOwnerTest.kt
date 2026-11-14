@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationInboxOwner
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationSpaceRecipientOwnerTest {
    @Test
    fun replacingTheAccountJoinsRecipientAndIndexChildrenBeforeStartingTheNextOwner() = runTest {
        val first = SpaceRecipientTransferWorkerFixture()
        val second = SpaceRecipientTransferWorkerFixture(account = 4)
        first.add(1)
        second.add(2)
        val entered = CompletableDeferred<Unit>()
        var transferStopped = 0
        var repairStarts = 0
        var repairStops = 0
        first.onTransfer = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                transferStopped++
            }
        }
        second.beforeSource = {
            assertEquals(1, transferStopped)
            assertEquals(1, first.sourceStops)
            assertEquals(1, first.catalogStops)
            assertEquals(1, first.destinationStops)
            assertEquals(1, repairStops)
        }
        val owner =
            MutableStateFlow<AppResult<CollaborationSpaceLocalAccess?>>(
                AppResult.Success(first.access)
            )
        val lifetime =
            ApplicationCollaborationInboxOwner(
                observe = { owner },
                repair = { access ->
                    val f = if (access == first.access) first else second
                    merge(
                        f.worker()(access),
                        flow<AppResult<Unit>> {
                            repairStarts++
                            try {
                                awaitCancellation()
                            } finally {
                                repairStops++
                            }
                        },
                    )
                },
            )
        val job = backgroundScope.launch { lifetime.collect() }
        entered.await()
        owner.value = AppResult.Success(second.access)
        runCurrent()
        assertEquals(1, first.calls.size)
        assertEquals(1, second.calls.size)
        assertEquals(2, repairStarts)
        assertEquals(1, repairStops)
        job.cancelAndJoin()
        assertEquals(2, repairStops)
        assertEquals(1, second.sourceStops)
        assertEquals(1, second.catalogStops)
        assertEquals(1, second.destinationStops)
        assertEquals(1, first.rows.size)
    }
}
