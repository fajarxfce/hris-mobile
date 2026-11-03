package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveCollaborationSavedRepliesTest {
    @Test
    fun boundedEnumerationIncludesHeldInputAndOnlyTheSelectedCompany() = runTest {
        val f = Fixture()
        f.rows =
            (0..63).map { index ->
                f.base.expected.copy(
                    id = "n-" + index.toString(16).padStart(64, '0'),
                    sequence = index.toLong(),
                    version = f.base.expected.version.copy(revision = index.toLong()),
                    review = CollaborationNotificationReplyReview.SessionChanged,
                    candidate =
                        f.base.expected.candidate.let { candidate ->
                            if (index % 2 == 0) candidate
                            else
                                candidate.copy(
                                    action =
                                        candidate.action.copy(
                                            target =
                                                candidate.action.target.copy(
                                                    companyId = f.base.id(700)
                                                )
                                        )
                                )
                        },
                )
            }
        val snapshot =
            assertIs<AppResult.Success<CollaborationSavedReplies>>(f.observe().first()).value
        assertEquals(32, snapshot.items.size)
        assertTrue(
            snapshot.items.all {
                it.input.candidate.action.target.companyId == snapshot.access.companyId
            }
        )
        assertEquals(listOf(null, 19L, 39L, 59L), f.pages)
        assertEquals(0, f.base.local.messageReads)
        assertEquals(0, f.base.native.opens)
    }

    @Test
    fun denialCancelsPendingPrivateReadBeforeEmittingFailure() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.afterPage = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val values = mutableListOf<AppResult<CollaborationSavedReplies>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        entered.await()
        f.base.local.gate.value = CollaborationListAdmission("denied", f.base.local.denied)
        runCurrent()
        assertTrue(joined)
        assertEquals(
            listOf<AppResult<CollaborationSavedReplies>>(AppResult.Failed(f.base.local.denied)),
            values,
        )
        job.cancelAndJoin()
    }

    @Test
    fun sessionReplacementCancelsPendingReadAndNeverPublishesOldText() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.afterPage = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val values = mutableListOf<AppResult<CollaborationSavedReplies>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        entered.await()
        f.session.value = null
        runCurrent()
        assertTrue(joined)
        assertTrue(values.isNotEmpty() && values.all { it is AppResult.Failed })
        job.cancelAndJoin()
    }

    @Test
    fun sourceCompletionMasksContentAndSubscriberCancellationJoinsSources() = runTest {
        val f = Fixture()
        f.completeSource = true
        val values = mutableListOf<AppResult<CollaborationSavedReplies>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        runCurrent()
        assertIs<AppResult.Failed>(values.last())
        job.cancelAndJoin()
        assertEquals(0, f.active)
    }

    @Test
    fun malformedPaginationAndForeignOwnedRowsFailWithoutPartialResults() = runTest {
        val f = Fixture()
        f.rows =
            listOf(
                f.base.expected.copy(
                    candidate =
                        f.base.expected.candidate.copy(
                            parent =
                                f.base.expected.candidate.parent.copy(accountId = f.base.id(701))
                        )
                )
            )
        assertIs<AppResult.Failed>(f.observe().first())
        f.rows = listOf(f.base.expected, f.base.expected)
        assertIs<AppResult.Failed>(f.observe().first())
    }

    @Test
    fun checkpointChangesReloadWithoutNetworkOrAgeEviction() = runTest {
        val f = Fixture()
        val values = mutableListOf<AppResult<CollaborationSavedReplies>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        runCurrent()
        assertEquals(
            1,
            assertIs<AppResult.Success<CollaborationSavedReplies>>(values.last()).value.items.size,
        )
        f.rows = emptyList()
        f.changes.value++
        runCurrent()
        assertTrue(
            assertIs<AppResult.Success<CollaborationSavedReplies>>(values.last())
                .value
                .items
                .isEmpty()
        )
        job.cancelAndJoin()
        assertEquals(0, f.active)
        assertEquals(0, f.base.local.setup.calls)
    }

    private class Fixture {
        val base = CollaborationNotificationReplyCompletionFixture()

        init {
            val workspace = requireNotNull(base.local.setup.workspace)
            base.local.setup.workspace =
                workspace.copy(
                    admission =
                        workspace.admission.copy(familyId = base.native.parent.nativeSessionId)
                )
        }

        var rows = listOf(base.expected)
        val changes = MutableStateFlow(0)
        val session = MutableStateFlow(base.local.setup.session)
        val workspace = MutableStateFlow(base.local.setup.workspace)
        val pages = mutableListOf<Long?>()
        var completeSource = false
        var active = 0
        var afterPage: suspend () -> Unit = {}
        val sessions =
            object : SessionRepository by base.local.setup.sessions {
                override fun observe() = session.map { AppResult.Success(it) }

                override suspend fun current() = AppResult.Success(session.value)
            }
        val workspaces =
            object : WorkspaceRepository by base.local.setup.workspaces {
                override fun observe(sessionId: String) = workspace.map { AppResult.Success(it) }

                override suspend fun current(sessionId: String) = AppResult.Success(workspace.value)
            }
        val inputs =
            object : CollaborationNotificationReplyRepository by base.inputs {
                override fun observeChanges(
                    access: CollaborationRecoveryAccess
                ): Flow<AppResult<Unit>> = flow {
                    active++
                    try {
                        if (completeSource) emit(AppResult.Success(Unit))
                        else emitAll(changes.map { AppResult.Success(Unit) })
                    } finally {
                        active--
                    }
                }

                override suspend fun page(
                    access: CollaborationRecoveryAccess,
                    after: Long?,
                    limit: Int,
                    pendingOnly: Boolean,
                ): AppResult<CollaborationNotificationReplyPage> {
                    assertFalse(pendingOnly)
                    pages += after
                    val remaining = rows.filter { after == null || it.sequence > after }
                    val selected = remaining.take(limit)
                    afterPage()
                    return AppResult.Success(
                        CollaborationNotificationReplyPage(
                            selected,
                            if (remaining.size > limit) selected.last().sequence else null,
                        )
                    )
                }
            }
        val useCase =
            ObserveCollaborationSavedReplies(
                inputs,
                base.local.lists,
                sessions,
                workspaces,
                base.local.server,
                base.local.build,
                base.local.clock,
            )

        fun observe() = useCase("selection")
    }
}
