package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class SpaceInboxHistoryProgressAdmissionTest {
    @Test
    fun exactReadReturnsRetainedOlderWorkOrAbsenceWithoutStartingAnything() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        assertEquals(AppResult.Success(f.stored), f.read())
        f.stored = null
        assertEquals(AppResult.Success(null), f.read())
        assertEquals(2, f.reads)
        assertTrue(f.writes.isEmpty())
        assertEquals(0, f.observations)
    }

    @Test
    fun initializationAndUnchangedReopenUseExactRepositoryReceipts() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        f.stored = null
        val first =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCheckpoint>>(f.write()).value
        assertEquals(0L, first.version.revision)
        val unchanged =
            CollaborationSpaceInboxHistoryChange.Observe(first.progress.latest, f.other, null)
        assertEquals(AppResult.Success(first), f.write(change = unchanged))
        assertEquals(2, f.writes.size)
        assertEquals(1, f.commits)
        assertEquals(0, f.reads)
    }

    @Test
    fun wrongWriteGenerationRevisionOrProgressNeverClaimsSuccess() = runTest {
        for (kind in 0..2) {
            val f = GlobalHistoryAdmissionFixture()
            f.onWrite = {
                val saved = f.save(it)
                AppResult.Success(
                    when (kind) {
                        0 -> saved.copy(version = saved.version.copy(generation = f.other))
                        1 ->
                            saved.copy(
                                version = saved.version.copy(revision = saved.version.revision + 1)
                            )
                        else -> saved.copy(progress = f.initial.progress)
                    }
                )
            }
            assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.write()).failure.kind)
            assertEquals(1, f.writes.size)
        }
    }

    @Test
    fun lostCommitResponseCanBeReadWithoutBlindlyReplayingItsOldExpectedState() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        val old = f.stored
        f.onWrite = {
            f.save(it)
            AppResult.Failed(f.failure)
        }
        assertEquals(AppResult.Failed(f.failure), f.write())
        assertEquals(AppResult.Success(f.stored), f.read())
        f.onWrite = { AppResult.Success(null) }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.write(expected = old)).failure.kind,
        )
        assertEquals(2, f.writes.size)
        assertEquals(1, f.commits)
    }

    @Test
    fun expectedAndReturnedQueuesAreDetachedBeforeAdmissionSuspends() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        val ids = mutableListOf(f.other)
        val queue =
            CollaborationSpaceInboxHistoryTraversal.Catalog(
                null,
                CollaborationSpaceInboxHistoryBatch(listOf(f.other), null),
            )
        val actual =
            f.initial.copy(
                progress =
                    f.initial.progress.copy(
                        pass = f.initial.progress.pass!!.copy(traversal = queue)
                    )
            )
        f.stored = actual
        val caller =
            actual.copy(
                progress =
                    actual.progress.copy(
                        pass =
                            actual.progress.pass!!.copy(
                                traversal =
                                    queue.copy(
                                        batch = CollaborationSpaceInboxHistoryBatch(ids, null)
                                    )
                            )
                    )
            )
        f.before = { if (it == "identity") ids.clear() }
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCheckpoint>>(
            f.write(expected = caller)
        )
        assertEquals(actual, f.writes.single().expected)
        assertTrue(ids.isEmpty())

        val readIds = mutableListOf(f.other)
        val readValue =
            actual.copy(
                progress =
                    actual.progress.copy(
                        pass =
                            actual.progress.pass.copy(
                                traversal =
                                    queue.copy(
                                        batch = CollaborationSpaceInboxHistoryBatch(readIds, null)
                                    )
                            )
                    )
            )
        f.stored = readValue
        f.before = { if (it == "admission" && f.reads > 0) readIds.clear() }
        assertEquals(AppResult.Success(actual), f.read())
        assertTrue(readIds.isEmpty())
    }

    @Test
    fun denialOverridesReadWriteAndEvenAContendedNullResponse() = runTest {
        for (write in listOf(false, true)) for (boundary in
            listOf("identity", "admission", if (write) "write" else "read")) {
            val f = GlobalHistoryAdmissionFixture()
            f.before = { if (it == boundary) f.base.base.deny() }
            if (write) f.onWrite = { AppResult.Success(null) }
            assertEquals(AppResult.Failed(f.base.base.denied), if (write) f.write() else f.read())
        }
    }

    @Test
    fun sessionAccountAndAudienceReplacementCannotExposeLateGlobalState() = runTest {
        for (write in listOf(false, true)) for (kind in 0..3) {
            val f = GlobalHistoryAdmissionFixture()
            val session = f.base.base.native.session
            f.before = {
                if (it == if (write) "write" else "read")
                    f.base.base.identity.value =
                        AppResult.Success(
                            when (kind) {
                                0 -> null
                                1 -> session.copy(id = "replacement")
                                2 -> session.copy(user = session.user.copy(id = f.other))
                                else ->
                                    session.copy(
                                        binding =
                                            session.binding.copy(
                                                audience = "https://foreign.example.test/"
                                            )
                                    )
                            }
                        )
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(if (write) f.write() else f.read()).failure.kind,
            )
        }
    }

    @Test
    fun cancellationStopsEachBoundaryAndDiscardsAnUncooperativeCommittedWrite() = runTest {
        for (write in listOf(false, true)) for (boundary in
            listOf("identity", "admission", if (write) "write" else "read")) {
            val f = GlobalHistoryAdmissionFixture()
            val entered = CompletableDeferred<Unit>()
            f.before = {
                if (it == boundary) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val task = async { if (write) f.write() else f.read() }
            entered.await()
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
        }
        val f = GlobalHistoryAdmissionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onWrite = {
            val saved = f.save(it)
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(saved)
        }
        val task = async { f.write() }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, f.commits)
    }

    @Test
    fun storageFailuresAndMalformedCheckpointsStayDistinctFromAbsence() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        f.readFailure = f.failure
        assertEquals(AppResult.Failed(f.failure), f.read())
        f.readFailure = null
        f.stored = f.initial.copy(version = f.initial.version.copy(revision = -1))
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.read()).failure.kind)
        f.stored = f.initial
        f.onWrite = { AppResult.Failed(f.failure) }
        assertEquals(AppResult.Failed(f.failure), f.write())
        assertEquals(1, f.writes.size)
    }

    @Test
    fun invalidOwnerAndExhaustedProgressDoNotWrite() = runTest {
        val f = GlobalHistoryAdmissionFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.reader(f.access.copy(spaceId = "invalid"))).failure.kind,
        )
        val exhausted =
            f.initial.copy(
                version = f.initial.version.copy(revision = COLLABORATION_SPACE_INBOX_MAX_ORDER)
            )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.write(expected = exhausted)).failure.kind,
        )
        assertTrue(f.writes.isEmpty())
        assertEquals(0, f.reads)
    }
}

internal class GlobalHistoryAdmissionFixture {
    val base = SpaceInboxRepairUseCaseFixture()
    val access = base.access
    val other = base.id(900)
    val trigger = CollaborationSpaceInboxHistoryTrigger(null, null, null, null)
    val initial =
        CollaborationSpaceInboxHistoryCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(base.id(901), 5),
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                    observeCollaborationSpaceInboxHistory(null, trigger, base.id(902))
                )
                .value,
        )
    val change =
        CollaborationSpaceInboxHistoryChange.Observe(
            trigger.copy(historySignal = other),
            base.id(903),
            null,
        )
    var stored: CollaborationSpaceInboxHistoryCheckpoint? = initial
    val failure = base.base.storageFailure
    var readFailure: Failure? = null
    var before: suspend (String) -> Unit = {}
    var reads = 0
    var commits = 0
    val writes = mutableListOf<CollaborationSpaceInboxHistoryMutation>()
    val destinations =
        MutableStateFlow<AppResult<CollaborationSpaceInboxVersion?>>(AppResult.Success(null))
    var source: () -> Flow<AppResult<CollaborationSpaceInboxVersion?>> = { destinations }
    var observations = 0
    var stops = 0
    var onWrite:
        suspend (CollaborationSpaceInboxHistoryMutation) -> AppResult<
                CollaborationSpaceInboxHistoryCheckpoint?
            > =
        {
            if (stored != it.expected) AppResult.Success(null) else AppResult.Success(save(it))
        }

    init {
        base.beforeCurrent = { before("identity") }
        base.currentGate = {
            before("admission")
            base.base.gate.value
        }
    }

    fun save(
        mutation: CollaborationSpaceInboxHistoryMutation
    ): CollaborationSpaceInboxHistoryCheckpoint {
        val old = mutation.expected
        if (old != null && old.progress == mutation.replacement) return old
        val saved =
            CollaborationSpaceInboxHistoryCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(
                    old?.version?.generation ?: base.id(901),
                    old?.version?.revision?.plus(1) ?: 0L,
                ),
                mutation.replacement,
            )
        commits++
        stored = saved
        return saved
    }

    val repository =
        object : CollaborationSpaceInboxHistoryProgressRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceInboxHistoryCheckpoint?> {
                assertEquals(this@GlobalHistoryAdmissionFixture.access, access)
                reads++
                before("read")
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(stored)
            }

            override suspend fun mutate(
                access: CollaborationSpaceLocalAccess,
                mutation: CollaborationSpaceInboxHistoryMutation,
            ): AppResult<CollaborationSpaceInboxHistoryCheckpoint?> {
                assertEquals(this@GlobalHistoryAdmissionFixture.access, access)
                writes += mutation
                before("write")
                return onWrite(mutation)
            }

            override fun observeDestination(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<CollaborationSpaceInboxVersion?>> = flow {
                assertEquals(this@GlobalHistoryAdmissionFixture.access, access)
                observations++
                try {
                    emitAll(source())
                } finally {
                    stops++
                }
            }
        }
    val reader =
        ReadCollaborationSpaceInboxHistoryProgress(
            repository,
            base.directory,
            base.sessions,
            base.base.native.server,
        )
    val writer =
        ChangeCollaborationSpaceInboxHistoryProgress(
            repository,
            base.directory,
            base.sessions,
            base.base.native.server,
        )
    val observer =
        ObserveCollaborationSpaceInboxDestination(
            repository,
            base.directory,
            base.sessions,
            base.base.native.server,
        )

    suspend fun read() = reader(access)

    suspend fun write(
        expected: CollaborationSpaceInboxHistoryCheckpoint? = stored,
        change: CollaborationSpaceInboxHistoryChange = this.change,
    ) = writer(access, expected, change)
}
