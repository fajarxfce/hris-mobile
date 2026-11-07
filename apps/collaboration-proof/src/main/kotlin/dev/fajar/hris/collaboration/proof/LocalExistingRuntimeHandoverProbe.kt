package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationSdkPrivateIdentitySource
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Original encrypted SDK/Keystore continuity through a closed store and replacement native parent.
 */
internal class LocalExistingRuntimeHandoverProbe(
    private val context: Context,
    private val directory: File,
    private val rotationPending: Boolean = false,
) {
    private val prefix = if (rotationPending) "rotation-handover" else "existing-handover"
    private val owned = File(directory, "$prefix-owned")
    private val checkpoint = File(owned, "checkpoint.json")

    suspend fun stage() =
        withContext(Dispatchers.IO) {
            check(!owned.exists() && owned.mkdirs())
            val f = LocalRuntimeHandoverFixture(context, owned)
            try {
                val lease = f.install()
                val identity = f.identity(lease)
                if (rotationPending) f.interruptCredentialReplacement()
                val parent = replacement(f, "replacement-login")
                f.select(parent.owner(f.owner.companyId))
                check(!f.success(f.runtime.isOpen(f.owner, f.device)))
                check(f.activeDirectories.get() == 0 && f.activeCredentials.get() == 0)
                val original = JSONObject(f.record())
                check(original.getBoolean("initialized") != rotationPending)
                val hashes = databaseHashes(f, original)
                check(privateIdentity(f, original) == identity)
                val target = target(f, identity)
                val constructions = f.directoryAcquisitions.get()

                stageThroughFailures(f, parent, target, original)
                val staged = JSONObject(f.record())
                original.keys().forEach { key ->
                    if (key !in setOf("schema", "credentialRevision"))
                        check(original.get(key) == staged.get(key))
                }
                check(staged.getInt("schema") == if (rotationPending) 3 else 2)
                check(
                    staged.getLong("credentialRevision") ==
                        original.getLong("credentialRevision") + 1
                )
                check(databaseHashes(f, staged) == hashes)
                check(privateIdentity(f, staged) == identity)
                check(f.directoryAcquisitions.get() == constructions)
                check(f.activeDirectories.get() == 0 && f.activeCredentials.get() == 0)
                checkpoint.writeText(
                    JSONObject()
                        .put("recordName", f.name.get())
                        .put("matrixUserId", identity.matrixUserId)
                        .put("matrixDeviceId", identity.matrixDeviceId)
                        .put("fingerprint", identity.fingerprint)
                        .put("databaseHashes", JSONObject(hashes))
                        .toString()
                )
                write(
                    "$prefix-stage.json",
                    JSONObject()
                        .put("oldSdkClosedBeforeStaging", true)
                        .put("replacementNativeParent", true)
                        .put("realDirectoryContentionRejected", true)
                        .put("wrongPrivateIdentityRejected", true)
                        .put("cancelBeforeWriteRecoverable", true)
                        .put("writeFailureRecoverable", true)
                        .put("cancelAfterCommitRetained", true)
                        .put("sameTargetReplay", true)
                        .put("originalRecordFieldsPreserved", true)
                        .put("originalDatabaseBytesPreserved", true)
                        .put("originalPrivateKeysPreserved", true)
                        .put("noSdkConstructionDuringStaging", true),
                )
            } finally {
                f.close()
            }
        }

    private suspend fun stageThroughFailures(
        f: LocalRuntimeHandoverFixture,
        parent: CollaborationNativeParent,
        target: CollaborationExistingRuntimeHandover,
        original: JSONObject,
    ) {
        val identity = target.original.identity
        val otherOwner = f.stores.acquire(original.getString("storeId"))
        try {
            check(f.runtime.stageExistingHandover(parent, target) is AppResult.Failed)
            check(JSONObject(f.record()).toString() == original.toString())
        } finally {
            otherOwner.close()
        }
        val wrong = if (identity.fingerprint == "a".repeat(64)) "b".repeat(64) else "a".repeat(64)
        val changed =
            target.copy(
                original = target.original.copy(identity = identity.copy(fingerprint = wrong)),
                intent = target.intent.copy(fingerprint = wrong),
            )
        check(f.runtime.stageExistingHandover(parent, changed) is AppResult.Failed)
        check(JSONObject(f.record()).toString() == original.toString())
        cancelWrite(f, parent, target, committed = false)
        check(f.success(f.runtime.handoverIntent(f.owner, f.device)) == null)
        check(privateIdentity(f, JSONObject(f.record())) == identity)
        f.beforeStageWrite = { throw IOException("Owned existing-store write failure") }
        try {
            check(f.runtime.stageExistingHandover(parent, target) is AppResult.Failed)
        } finally {
            f.beforeStageWrite = {}
        }
        check(JSONObject(f.record()).toString() == original.toString())
        cancelWrite(f, parent, target, committed = true)
        check(f.success(f.runtime.stageExistingHandover(parent, target)) == target.intent)
    }

    private suspend fun cancelWrite(
        f: LocalRuntimeHandoverFixture,
        parent: CollaborationNativeParent,
        target: CollaborationExistingRuntimeHandover,
        committed: Boolean,
    ) = coroutineScope {
        val reached = CompletableDeferred<Unit>()
        val pause: suspend () -> Unit = {
            reached.complete(Unit)
            awaitCancellation()
        }
        if (committed) f.afterStageWrite = pause else f.beforeStageWrite = pause
        val task = async { f.runtime.stageExistingHandover(parent, target) }
        try {
            withTimeout(15_000) { reached.await() }
            check(f.activeDirectories.get() == 1 && f.activeCredentials.get() == 1)
            check(f.runtime.stageExistingHandover(parent, target) is AppResult.Failed)
            check(f.runtime.restore(f.owner, f.device) is AppResult.Failed)
            check(f.runtime.acquireBackground(f.owner, f.device, 5_000) is AppResult.Failed)
        } finally {
            task.cancelAndJoin()
            f.beforeStageWrite = {}
            f.afterStageWrite = {}
        }
        check(f.activeDirectories.get() == 0 && f.activeCredentials.get() == 0)
    }

    suspend fun resume() =
        withContext(Dispatchers.IO) {
            check(owned.isDirectory && checkpoint.length() in 1..4096)
            val saved = JSONObject(checkpoint.readText())
            val identity =
                CollaborationLocalDeviceIdentity(
                    saved.getString("matrixUserId"),
                    saved.getString("matrixDeviceId"),
                    saved.getString("fingerprint"),
                )
            val expectedHashes =
                saved.getJSONObject("databaseHashes").let { obj ->
                    obj.keys().asSequence().associateWith(obj::getString)
                }
            val f = LocalRuntimeHandoverFixture(context, owned)
            f.name.set(saved.getString("recordName"))
            try {
                val parent = replacement(f, "replacement-login-after-process-death")
                f.select(parent.owner(f.owner.companyId))
                val target = target(f, identity)
                val original = f.record()
                check(JSONObject(original).getBoolean("initialized") != rotationPending)
                check(JSONObject(original).getInt("schema") == if (rotationPending) 3 else 2)
                check(f.success(f.runtime.stageExistingHandover(parent, target)) == target.intent)
                check(f.record() == original)
                check(
                    f.runtime.stageExistingHandover(
                        parent,
                        target.copy(intent = target.intent.copy(expectedDeviceVersion = 1)),
                    ) is AppResult.Failed
                )
                check(f.runtime.restore(f.owner, f.device) is AppResult.Failed)
                check(f.runtime.install(f.owner, f.command()) is AppResult.Failed)
                check(f.runtime.acquireBackground(f.owner, f.device, 5_000) is AppResult.Failed)
                check(f.record() == original)
                check(privateIdentity(f, JSONObject(original)) == identity)
                check(databaseHashes(f, JSONObject(original)) == expectedHashes)
                check(f.directoryAcquisitions.get() == 0)
                check(f.activeDirectories.get() == 0 && f.activeCredentials.get() == 0)
                f.clearRecord()
            } finally {
                f.close()
            }
            check(owned.deleteRecursively())
            write(
                "$prefix-result.json",
                JSONObject()
                    .put("durableAfterProcessDeath", true)
                    .put("replacementLocalLoginSameOriginalStore", true)
                    .put("sameTargetReplayWithoutWrite", true)
                    .put("targetImmutable", true)
                    .put("legacyAcquisitionRejected", true)
                    .put("noSdkConstructionDuringResume", true)
                    .put("originalDatabaseBytesPreserved", true)
                    .put("originalPrivateKeysPreserved", true)
                    .put("ownedResourcesReleased", true)
                    .put("ownedFilesRemoved", !owned.exists()),
            )
        }

    private fun replacement(f: LocalRuntimeHandoverFixture, login: String) =
        CollaborationNativeParent(
            login,
            f.owner.audience,
            f.owner.accountId,
            "b0000000-0000-4000-8000-000000000001",
        )

    private fun target(f: LocalRuntimeHandoverFixture, identity: CollaborationLocalDeviceIdentity) =
        CollaborationExistingRuntimeHandover(
            CollaborationSpaceLegacySdk(f.owner.setupBinding(), f.device, identity),
            f.intent(identity.fingerprint),
        )

    private suspend fun privateIdentity(
        f: LocalRuntimeHandoverFixture,
        record: JSONObject,
    ): CollaborationLocalDeviceIdentity {
        val lease = requireNotNull(f.inventories.inspect(record.getString("storeId")))
        return try {
            check(lease.cryptoDatabasePresent && lease.stateDatabasePresent)
            AndroidCollaborationSdkPrivateIdentitySource()
                .read(lease.databasePath, record.getString("passphrase"))
                .toLocalDeviceIdentity()
        } finally {
            lease.close()
        }
    }

    private suspend fun databaseHashes(
        f: LocalRuntimeHandoverFixture,
        record: JSONObject,
    ): Map<String, String> {
        val lease = requireNotNull(f.inventories.inspect(record.getString("storeId")))
        return try {
            listOf("matrix-sdk-state.sqlite3", "matrix-sdk-crypto.sqlite3").associateWith { name ->
                val file = File(lease.databasePath, name)
                check(file.length() in 1..8_388_608)
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                    "%02x".format(it)
                }
            }
        } finally {
            lease.close()
        }
    }

    private fun write(name: String, value: JSONObject) {
        File(directory, name)
            .writeText(
                value
                    .put("complete", true)
                    .put("providerRequired", false)
                    .put("interruptedCredentialReplacement", rotationPending)
                    .put("newMessagesSent", 0)
                    .toString()
            )
    }
}
