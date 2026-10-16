package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.datasources.MemoryLeaveReadAdmissionDataSource
import dev.fajar.starter.leave.data.encoding.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.EncryptedLeaveReplicaRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.initialLeaveCheckpoint
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class LeaveRequestReplicaMigrationTest {
    @Test
    fun aDenseLegacyViewKeepsItsSelectedHistoryUntilTheExpandedReplicaPublishes() = runTest {
        withMigration { f ->
            val request = f.raw.requestFacts(version = 21, dense = true)
            val saved =
                f.snapshot(request)
                    .copy(
                        selection = LeaveRequestSelection(request.id, 12),
                        request = request.copy(history = f.raw.historyPage(21, 12, dense = true)),
                    )
            seedLegacy(f, saved)
            assertEquals(saved, f.request())
            seedSummaryReplica(f, request)
            assertEquals(1, f.checkpoint()?.recordSchema)
            assertEquals(saved, f.request())
            val old = requireNotNull(f.checkpoint())
            val replacement = initialLeaveCheckpoint(f.access, "expanded", f.raw.now)
            assertEquals(AppResult.Success(true), f.repository.begin(replacement, old.generation))
            f.publish(listOf(f.raw.requestFacts(version = 22)), publish = false)
            assertEquals(saved, f.request())
            f.publish(listOf(f.raw.requestFacts(version = 22)))
            assertEquals(LeaveCheckpoint.CURRENT_RECORD_SCHEMA, f.checkpoint()?.recordSchema)
            assertEquals(22L, f.request()?.request?.version)
            assertNull(f.request()?.selection?.historyAfter)
        }
    }

    @Test
    fun anAuthoritativeEmptyReplacementNeverResurrectsAnOldRequest() = runTest {
        withMigration { f ->
            val saved = f.snapshot(f.raw.requestFacts())
            seedLegacy(f, saved)
            assertEquals(saved, f.request())
            f.publish(emptyList())
            assertNull(f.request())
            val other = f.raw.requestFacts(replicaRequestId(8))
            f.publish(listOf(other))
            assertNull(f.request(saved.request.id))
            assertEquals(other, f.request(other.id)?.request)
        }
    }

    @Test
    fun aMissingLegacyKeyOrDateCannotBecomeAPartialViewOrBlockTheNewFormat() = runTest {
        withMigration { f ->
            val saved = f.snapshot(f.raw.requestFacts(dense = true))
            seedLegacy(f, saved)
            val raw =
                f.db.replicas
                    .observe(
                        ReplicaScope(f.access.sessionId),
                        leaveRequestPartition(f.access),
                        limit = 32,
                    )
                    .first()
            val missing =
                object : ReplicaStore by f.db.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ): Flow<ReplicaPage> =
                        if (partition == leaveRequestPartition(f.access))
                            flowOf(raw.copy(documents = raw.documents.filter { it.id != "day/00" }))
                        else f.db.replicas.observe(scope, partition, after, limit)
                }
            val repository =
                EncryptedLeaveReplicaRepository(
                    missing,
                    f.cipher,
                    f.keys,
                    f.legacyKeys,
                    MemoryLeaveReadAdmissionDataSource(),
                )
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(
                        repository.observeRequest(f.access, saved.selection).first()
                    )
                    .failure
                    .kind,
            )
            f.legacyKeys.write(null)
            assertIs<AppResult.Failed>(
                f.repository.observeRequest(f.access, saved.selection).first()
            )
            f.publish(listOf(saved.request))
            assertEquals(saved.request, f.request()?.request)
        }
    }

    @Test
    fun anInitialStagedBootstrapCanPublishADirectReadWithoutClaimingACompleteDirectory() = runTest {
        withMigration { f ->
            val initial = initialLeaveCheckpoint(f.access, "pending", f.raw.now)
            assertEquals(AppResult.Success(true), f.repository.begin(initial, null))
            val request = f.snapshot(f.raw.requestFacts())
            f.fresh(request)
            assertEquals(request, f.request())
            assertNull(f.checkpoint()?.refreshedAt)
            assertNull(
                assertIs<AppResult.Success<LeaveReplicaDirectory>>(
                        f.repository.observeDirectory(f.access).first()
                    )
                    .value
                    .checkpoint
            )
            f.publish(listOf(request.request))
            val completed = requireNotNull(f.checkpoint())
            val replacement = initialLeaveCheckpoint(f.access, "replacement", f.raw.now)
            assertEquals(
                AppResult.Success(true),
                f.repository.begin(replacement, completed.generation),
            )
            val newer = f.snapshot(f.raw.requestFacts(version = 1))
            assertEquals(
                AppResult.Success(false),
                f.repository.replaceRequest(f.access, newer.request.id, f.writeCheckpoint(), newer),
            )
            assertEquals(request.request, f.request()?.request)
        }
    }
}

private suspend fun seedLegacy(f: LeaveRequestReplicaFixture, snapshot: LeaveRequestSnapshot) {
    val key = f.cipher.generateKey()
    try {
        f.legacyKeys.write(Base64.encode(key))
        val old =
            encodeLeaveRequestCache(
                snapshot.toDto(),
                snapshot.generation,
                f.cipher,
                key,
                snapshot.access,
            )
        assertTrue(
            f.db.replicas.replace(
                ReplicaScope(f.access.sessionId),
                leaveRequestPartition(f.access),
                null,
                old,
            )
        )
    } finally {
        key.fill(0)
    }
}

private suspend fun seedSummaryReplica(
    f: LeaveRequestReplicaFixture,
    request: LeaveRequestDetails,
) {
    val key = f.cipher.generateKey()
    try {
        f.keys.write(Base64.encode(key))
        val checkpoint =
            initialLeaveCheckpoint(f.access, "summaries", f.raw.now)
                .copy(
                    phase = LeaveSyncPhase.CHANGES,
                    cursor = "old-cursor",
                    published = true,
                    refreshedAt = f.raw.now,
                    recordSchema = 1,
                )
        val summary = request.toSummary()
        val documents =
            listOf(
                encodeLeaveDocument(
                    f.cipher,
                    key,
                    checkpoint,
                    "request/${request.id}",
                    request.version,
                    summary.replicaSortKey(),
                    leaveSummaryTags(key, f.access, summary, includeIdentity = false),
                    Json.encodeToString(summary.toDto()),
                ),
                encodeLeaveDocument(
                    f.cipher,
                    key,
                    checkpoint,
                    "directory",
                    0,
                    "directory",
                    emptySet(),
                    Json.encodeToString(f.raw.directory),
                ),
            )
        assertTrue(
            f.db.replicas.replace(
                leaveReplicaScope(f.access),
                leaveReplicaPartition(f.access),
                null,
                ReplicaSnapshot(
                    checkpoint.generation,
                    encodeLeaveCheckpoint(f.cipher, key, checkpoint),
                    documents,
                ),
            )
        )
    } finally {
        key.fill(0)
    }
}

private suspend fun withMigration(block: suspend (LeaveRequestReplicaFixture) -> Unit) {
    val folder = Files.createTempDirectory("leave-request-upgrade").toFile()
    val f = LeaveRequestReplicaFixture(createAppDatabase(folder))
    try {
        f.activate()
        block(f)
    } finally {
        f.close()
        folder.deleteRecursively()
    }
}
