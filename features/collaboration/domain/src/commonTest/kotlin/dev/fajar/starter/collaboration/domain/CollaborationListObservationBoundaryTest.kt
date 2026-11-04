@file:OptIn(
    kotlin.time.ExperimentalTime::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationList
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationListObservationBoundaryTest {
    @Test
    fun accessDenialOrSelectionChangeDiscardsPendingOldRowsBeforeConsumerResumes() = runTest {
        for (deny in listOf(false, true)) {
            val f = LocalListFixture()
            val firstList = CollaborationLocalList(null, listOf(f.summary), null)
            val queuedList = CollaborationLocalList(null, emptyList(), null)
            val nextList =
                firstList.copy(
                    items =
                        listOf(
                            f.summary.copy(
                                peer =
                                    CollaborationParticipant(
                                        "next-peer",
                                        "Fictional next colleague",
                                    )
                            )
                        )
                )
            val rows =
                MutableStateFlow<AppResult<CollaborationLocalList>>(AppResult.Success(firstList))
            val selection = MutableStateFlow(CollaborationListSelection())
            var active = 0
            val replica =
                object : CollaborationListRepository by f.replica {
                    override fun observe(
                        access: CollaborationLocalAccess,
                        selection: CollaborationListSelection,
                    ): Flow<AppResult<CollaborationLocalList>> = flow {
                        active++
                        try {
                            if (selection == CollaborationListSelection()) emitAll(rows)
                            else {
                                emit(AppResult.Success(nextList))
                                awaitCancellation()
                            }
                        } finally {
                            active--
                        }
                    }
                }
            val observer =
                ObserveCollaborationList(
                    replica,
                    f.setup.sessions,
                    f.setup.workspaces,
                    IdentityServer(f.setup.initial.binding.audience, "Fictional Android"),
                    AppBuild(AppPlatform.Android, 5, "test", null),
                    object : Clock {
                        override fun now() = f.setup.now
                    },
                )
            val first = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val values = mutableListOf<AppResult<CollaborationLocalList>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    observer("selection", selection).collect {
                        values += it
                        if (values.size == 1) {
                            first.complete(Unit)
                            resume.await()
                        }
                    }
                }
            first.await()
            rows.value = AppResult.Success(queuedList)
            runCurrent()
            if (deny) f.gate.value = CollaborationListAdmission("new-denial", f.denied)
            else selection.value = CollaborationListSelection("next", 12)
            runCurrent()
            resume.complete(Unit)
            runCurrent()
            assertEquals(2, values.size, "deny=$deny must discard queued old facts")
            if (deny) assertEquals(f.denied, assertIs<AppResult.Failed>(values.last()).failure)
            else assertEquals(AppResult.Success(nextList), values.last())
            job.cancelAndJoin()
            assertEquals(0, active)
            assertEquals(0, f.remoteCalls)
        }
    }
}
