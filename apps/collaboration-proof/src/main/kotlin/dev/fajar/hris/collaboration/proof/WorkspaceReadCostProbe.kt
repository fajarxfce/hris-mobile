package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.entities.CompanyMembership
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.workspace.data.datasources.WorkspaceRemoteDataSource
import dev.fajar.starter.workspace.data.repositories.EncryptedWorkspaceRepository
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.entities.WorkspaceAdmission
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Synthetic, network-free measurement; never opens the product's database or credential records.
 */
internal class WorkspaceReadCostProbe(private val context: Context, private val output: File) {
    suspend fun run() {
        check(context.packageName == "dev.fajar.hris.collaboration.proof")
        check(!File(output, "workmanager-enabled.json").exists())
        val recordName = "workspace-read-${UUID.randomUUID()}"
        val owned = File(context.noBackupFilesDir, recordName)
        check(owned.mkdir())
        val database =
            RoomAppDatabase(
                Room.databaseBuilder<StarterDatabase>(
                    context,
                    File(owned, "measurement.db").absolutePath,
                )
            )
        val credentials = AndroidCredentialStore(context, recordName)
        val content = AesGcmContentCipher()
        val timings = ReadTimings()
        val server = IdentityServer("https://workspace-proof.example.invalid/", "Local fixture")
        val now = Instant.parse("2026-11-08T00:00:00Z")
        val workspace =
            Workspace(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                server.audience,
                CompanyMembership(UUID.randomUUID().toString(), "Fixture", "TEST", "UTC"),
                setOf("people.self.read"),
                WorkspaceAdmission(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    now,
                    now + 1.hours,
                ),
            )
        val repository =
            EncryptedWorkspaceRepository(
                object : ReplicaStore by database.replicas {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                        timings.measure("checkpoint") {
                            database.replicas.checkpoint(scope, partition)
                        }
                },
                object : WorkspaceRemoteDataSource {
                    override suspend fun permissions(sessionId: String, companyId: String) =
                        error("This measurement cannot use network authority")

                    override suspend fun clientPolicy(sessionId: String, companyId: String) =
                        error("This measurement cannot use network authority")
                },
                object : ContentCipher by content {
                    override suspend fun decrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        ciphertext: ByteArray,
                    ) =
                        timings.measure("contentDecrypt") {
                            content.decrypt(key, associatedData, ciphertext)
                        }
                },
                object : AtomicCredentialStore by credentials {
                    override suspend fun read() =
                        timings.measure("credentialRead") { credentials.read() }
                },
                server,
            )
        val result = JSONObject().put("synthetic", true).put("networkRequests", 0)
        try {
            database.accounts.activate(workspace.sessionId)
            check(
                repository.compareAndSet(workspace.sessionId, null, workspace) ==
                    AppResult.Success(true)
            )
            for ((name, lanes, perLane) in
                listOf(
                    Triple("first", 1, 1),
                    Triple("sequential", 1, 30),
                    Triple("concurrent", 2, 15),
                )) {
                timings.clear()
                val started = SystemClock.elapsedRealtimeNanos()
                coroutineScope {
                    List(lanes) {
                            async(Dispatchers.Default) {
                                repeat(perLane) {
                                    val read =
                                        timings.measure("current") {
                                            repository.current(workspace.sessionId)
                                        }
                                    check(read == AppResult.Success(workspace))
                                }
                            }
                        }
                        .awaitAll()
                }
                val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                result.put(name, timings.snapshot(lanes * perLane).put("wallMillis", elapsed))
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    database.close()
                } finally {
                    try {
                        credentials.write(null)
                        credentials.close()
                        KeyStore.getInstance("AndroidKeyStore").apply {
                            load(null)
                            deleteEntry(context.packageName + "." + recordName)
                        }
                        File(context.noBackupFilesDir, "$recordName.lock").delete()
                    } finally {
                        check(owned.deleteRecursively())
                    }
                }
            }
        }
        result.put("ownedFixtureRemoved", !owned.exists())
        output.mkdirs()
        File(output, "workspace-read-cost.json").writeText(result.toString())
    }

    /**
     * Closed timing buckets only; no workspace values, keys, payloads or credential identifiers.
     */
    private class ReadTimings {
        private val samples = mutableMapOf<String, MutableList<Double>>()

        suspend fun <T> measure(stage: String, block: suspend () -> T): T {
            val started = SystemClock.elapsedRealtimeNanos()
            return try {
                block()
            } finally {
                val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                synchronized(samples) { samples.getOrPut(stage) { mutableListOf() }.add(elapsed) }
            }
        }

        fun clear() = synchronized(samples) { samples.clear() }

        fun snapshot(expected: Int): JSONObject =
            synchronized(samples) {
                check(
                    samples.keys ==
                        setOf("checkpoint", "credentialRead", "contentDecrypt", "current")
                )
                JSONObject().apply {
                    samples.forEach { (stage, values) ->
                        check(values.size == expected)
                        put(stage, JSONArray(values))
                    }
                }
            }
    }
}
