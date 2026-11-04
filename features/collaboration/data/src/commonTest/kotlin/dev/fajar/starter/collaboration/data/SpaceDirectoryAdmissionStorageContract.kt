package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceDirectoryAdmissionSource
import dev.fajar.starter.collaboration.data.replica.SpaceDirectoryAdmissionDto
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceDirectoryAdmission
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class SpaceDirectoryAdmissionStorageContract {
    protected abstract fun databaseOwner(): SpaceDirectoryDatabaseOwner

    @Test
    fun denialArrivingAtTheFinalBufferClearWinsOverBothPersistenceAndAdmission() =
        withDatabase { f ->
            for (admitting in listOf(false, true)) {
                assertEquals(AppResult.Success(true), f.repository.restrict(f.access, f.denied))
                val before = f.admission()
                val newer =
                    SpaceDirectoryAdmissionDto(
                        spaceDirectoryId(70000),
                        "AccessDenied",
                        "mfa_required",
                    )
                val source =
                    object : CollaborationSpaceDirectoryAdmissionSource by f.admissions {
                        override fun clearIfCurrent(
                            key: String,
                            expected: SpaceDirectoryAdmissionDto?,
                        ): Boolean {
                            f.admissions.write(key, newer)
                            return f.admissions.clearIfCurrent(key, expected)
                        }
                    }
                val repository = f.repository(pending = source)
                if (admitting)
                    assertEquals(
                        AppResult.Success(false),
                        repository.admit(f.access, before.revision),
                    )
                else assertEquals(AppResult.Success(true), repository.restrict(f.access, f.denied))
                assertEquals(newer.code, f.admission().denial!!.code)
                assertEquals(newer.revision, f.admission().revision)
                assertEquals(AppResult.Success(true), f.repository.admit(f.access, newer.revision))
            }
        }

    @Test
    fun invalidOwnersStayInsideTheSafeStorageBoundaryAndCreateNoKey() = withDatabase { f ->
        for (invalid in
            listOf(
                f.access.copy(sessionId = ""),
                f.access.copy(audience = "a".repeat(2049)),
                f.access.copy(audience = "https://example.test/\n"),
                f.access.copy(accountId = "invalid"),
                f.access.copy(spaceId = "invalid"),
            )) {
            assertIs<AppResult.Failed>(f.repository.admission(invalid))
            assertIs<AppResult.Failed>(f.repository.restrict(invalid, f.denied))
        }
        assertNull(f.keys.read())
        assertNull(f.admission().denial)
    }

    @Test
    fun persistedDenialSurvivesRestartAndOnlyTheObservedRevisionCanReopen() = withDatabase { f ->
        f.seed(1)
        assertEquals(AppResult.Success(true), f.repository.restrict(f.access, f.denied))
        val before = f.admission()
        val renewed = f.access.copy(sessionId = "renewed")
        f.reopen(renewed)
        assertEquals(before, f.admission(renewed))
        assertEquals(AppResult.Success(false), f.repository.admit(renewed, null))
        assertEquals(AppResult.Success(true), f.repository.admit(renewed, before.revision))
        assertNull(f.admission(renewed).denial)
        assertEquals(listOf(spaceDirectoryContact(1)), f.snapshot(renewed).contacts)
    }

    @Test
    fun failedOrRejectedWritesKeepDenialBufferedThroughReauthentication() = withDatabase { f ->
        for (throws in listOf(false, true)) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                    ): Boolean {
                        if (throws) error("Fixture persistence unavailable")
                        return false
                    }
                }
            val repository = f.repository(store)
            val denied = repository.restrict(f.access, f.denied)
            if (throws) assertIs<AppResult.Failed>(denied)
            else assertEquals(AppResult.Success(false), denied)
            val renewed = f.access.copy(sessionId = "renewed")
            f.activate(renewed)
            val admission =
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                        repository.admission(renewed)
                    )
                    .value
            assertEquals(f.denied.code, admission.denial!!.code)
            val admit = repository.admit(renewed, admission.revision)
            if (throws) assertIs<AppResult.Failed>(admit)
            else assertEquals(AppResult.Success(false), admit)
            assertEquals(AppResult.Success(admission), repository.admission(renewed))
            assertEquals(AppResult.Success(true), f.repository.admit(renewed, admission.revision))
            f.activate()
        }
    }

    @Test
    fun newerDenialDuringPendingAdmissionRemainsClosed() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.repository.restrict(f.access, f.denied))
        val before = f.admission()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    if (snapshot == null) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return f.database.replicas.replace(scope, partition, expected, snapshot)
                }
            }
        val repository = f.repository(store)
        coroutineScope {
            val admitting = async { repository.admit(f.access, before.revision) }
            entered.await()
            val later = f.denied.copy(code = "mfa_required")
            val denial =
                async(start = CoroutineStart.UNDISPATCHED) { repository.restrict(f.access, later) }
            assertEquals(
                later.code,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                        repository.admission(f.access)
                    )
                    .value
                    .denial!!
                    .code,
            )
            release.complete(Unit)
            assertEquals(AppResult.Success(false), admitting.await())
            assertEquals(AppResult.Success(true), denial.await())
            assertEquals(later.code, f.admission().denial!!.code)
        }
        f.reopen()
        assertEquals("mfa_required", f.admission().denial!!.code)
    }

    @Test
    fun staleSessionCannotClearASameAccountDenial() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.repository.restrict(f.access, f.denied))
        val admission = f.admission()
        val renewed = f.access.copy(sessionId = "renewed")
        f.activate(renewed)
        assertEquals(AppResult.Success(false), f.repository.admit(f.access, admission.revision))
        assertEquals(admission, f.admission(renewed))
        assertEquals(AppResult.Success(true), f.repository.admit(renewed, admission.revision))
    }

    @Test
    fun providerOutageCannotBeStoredAsAnAccessDenial() = withDatabase { f ->
        assertIs<AppResult.Failed>(
            f.repository.restrict(
                f.access,
                Failure(FailureKind.Unavailable, "Unavailable", code = "collaboration_unavailable"),
            )
        )
        assertNull(f.admission().denial)
        assertNull(f.keys.read())
    }

    @Test
    fun pendingGateCapacityFailsClosedWithoutEvictingOtherOwners() = withDatabase { f ->
        for (index in 1..128) f.admissions.write(
            "owner-$index",
            SpaceDirectoryAdmissionDto(spaceDirectoryId(index), "AccessDenied", "permission_denied"),
        )
        assertFailsWith<IllegalStateException> {
            f.admissions.write(
                "overflow",
                SpaceDirectoryAdmissionDto(spaceDirectoryId(200), "AccessDenied", null),
            )
        }
        assertIs<AppResult.Failed>(f.repository.admission(f.access))
        assertFailsWith<IllegalStateException> { f.admissions.current("owner-1") }
    }

    private fun withDatabase(block: suspend (SpaceDirectoryStorageFixture) -> Unit) = runTest {
        val fixture = SpaceDirectoryStorageFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
