package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationSdkPrivateIdentitySource
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import java.io.File
import java.security.MessageDigest
import kotlin.time.Instant
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Actual SDK/Keystore, inert loopback credentials, no sync or messages; preserves original keys.
 */
internal class LocalSpaceRuntimeAdoptionProbe(
    private val context: Context,
    private val directory: File,
) {
    private val owned = File(directory, "space-adoption-owned")
    private val checkpoint = File(owned, "checkpoint.json")

    suspend fun stage() =
        withContext(Dispatchers.IO) {
            check(!owned.exists() && owned.mkdirs())
            val f = LocalRuntimeHandoverFixture(context, owned)
            try {
                val originalLease = f.install()
                val identity = f.identity(originalLease)
                f.success(originalLease.close())
                val target = target(f, identity)
                f.success(f.runtime.stageExistingHandover(parent(f), target))
                val before = JSONObject(f.record())
                val expectedOriginalDigest = originalDigest(before)
                interruptedAdoption(f, target)
                val staged = JSONObject(f.record())
                check(staged.getInt("schema") == 4)
                check(!staged.getJSONObject("adoption").getBoolean("initialized"))
                check(originalDigest(staged) == expectedOriginalDigest)
                check(privateIdentity(f, staged) == identity)
                check(f.activeDirectories.get() == 0 && f.activeCredentials.get() == 0)
                checkpoint.writeText(
                    JSONObject()
                        .put("recordName", f.name.get())
                        .put("matrixUserId", identity.matrixUserId)
                        .put("matrixDeviceId", identity.matrixDeviceId)
                        .put("fingerprint", identity.fingerprint)
                        .put("originalRecordDigest", expectedOriginalDigest)
                        .toString()
                )
                write(
                    "space-adoption-stage.json",
                    JSONObject()
                        .put("originalSdkClosed", true)
                        .put("committedBeforeCancellation", true)
                        .put("uninitializedAdoptionRetained", true)
                        .put("originalFieldsAndQueueNamespacePreserved", true)
                        .put("originalPrivateKeysPreserved", true)
                        .put("ownedResourcesReleased", true),
                )
            } finally {
                f.close()
            }
        }

    private suspend fun interruptedAdoption(
        f: LocalRuntimeHandoverFixture,
        target: CollaborationExistingRuntimeHandover,
    ) = coroutineScope {
        val committed = CompletableDeferred<Unit>()
        f.afterAdoptionWrite = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val task = async { f.runtime.installOriginal(space(f), target, setup(f, target)) }
        try {
            withTimeout(15_000) { committed.await() }
            check(f.activeDirectories.get() == 1 && f.activeCredentials.get() == 1)
            check(f.runtime.restore(f.owner, f.device) is AppResult.Failed)
            check(f.runtime.installOriginal(space(f), target, setup(f, target)) is AppResult.Failed)
        } finally {
            task.cancelAndJoin()
            f.afterAdoptionWrite = {}
        }
        check(task.isCancelled)
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
            val f = LocalRuntimeHandoverFixture(context, owned)
            f.name.set(saved.getString("recordName"))
            try {
                f.select(f.owner)
                val target = target(f, identity)
                val setup = setup(f, target)
                val before = JSONObject(f.record())
                check(!before.getJSONObject("adoption").getBoolean("initialized"))
                check(originalDigest(before) == saved.getString("originalRecordDigest"))
                check(privateIdentity(f, before) == identity)
                val lease = f.success(f.runtime.installOriginal(space(f), target, setup))
                check(
                    !f.success(
                        f.runtime.isSpaceOpen(space(f), target.intent.deviceId, setup.command.id)
                    )
                )
                check(
                    lease.owner == space(f) &&
                        lease.deviceId == target.intent.deviceId &&
                        lease.installedOperationId == setup.command.id
                )
                f.success(lease.publish())
                check(
                    f.success(
                        f.runtime.isSpaceOpen(space(f), target.intent.deviceId, setup.command.id)
                    )
                )
                f.select(f.owner.copy(companyId = "88888888-8888-4888-8888-888888888888"))
                f.success(
                    f.runtime.updateRetention(
                        CollaborationRuntimeRetention(parent(f), null, f.owner.companyId)
                    )
                )
                check(
                    f.success(
                        f.runtime.isSpaceOpen(space(f), target.intent.deviceId, setup.command.id)
                    )
                )
                check(!f.success(f.runtime.isOpen(f.owner, f.device)))
                check(f.runtime.restore(f.owner, f.device) is AppResult.Failed)
                f.success(lease.close())
                val initialized = JSONObject(f.record())
                check(initialized.getJSONObject("adoption").getBoolean("initialized"))
                check(originalDigest(initialized) == saved.getString("originalRecordDigest"))
                check(privateIdentity(f, initialized) == identity)
                val stable = f.record()
                val reopened = f.success(f.runtime.installOriginal(space(f), target, setup))
                f.success(reopened.close())
                check(f.record() == stable)
                check(privateIdentity(f, JSONObject(stable)) == identity)
                check(f.activeDirectories.get() == 0 && f.activeCredentials.get() == 0)
                f.clearRecord()
            } finally {
                f.success(f.runtime.closeSpace(space(f)))
                f.close()
            }
            check(owned.deleteRecursively())
            write(
                "space-adoption-result.json",
                JSONObject()
                    .put("durableAfterProcessDeath", true)
                    .put("actualExistingOnlySdkRestore", true)
                    .put("unpublishedUntilAcceptance", true)
                    .put("hrCompanySelectionAndDenialIndependent", true)
                    .put("legacyRuntimeLookupExcluded", true)
                    .put("originalFieldsAndQueueNamespacePreserved", true)
                    .put("originalPrivateKeysPreserved", true)
                    .put("sameBootstrapReplayWithoutWrite", true)
                    .put("ownedResourcesReleased", true)
                    .put("ownedFilesRemoved", !owned.exists())
                    .put("liveGroupActivation", false),
            )
        }

    private fun parent(f: LocalRuntimeHandoverFixture) =
        CollaborationNativeParent(
            f.owner.sessionId,
            f.owner.audience,
            f.owner.accountId,
            f.owner.nativeSessionId,
        )

    private fun space(f: LocalRuntimeHandoverFixture) =
        CollaborationSpaceOwner(parent(f), "a0000000-0000-0000-0000-000000000002")

    private fun target(f: LocalRuntimeHandoverFixture, identity: CollaborationLocalDeviceIdentity) =
        CollaborationExistingRuntimeHandover(
            CollaborationSpaceLegacySdk(f.owner.setupBinding(), f.device, identity),
            f.intent(identity.fingerprint),
        )

    private fun setup(
        f: LocalRuntimeHandoverFixture,
        target: CollaborationExistingRuntimeHandover,
    ): CollaborationSpaceSetupRecord {
        val initial = f.command()
        val original = (initial.outcome as CollaborationSetupOutcome.Bootstrapped).gateway
        val binding =
            CollaborationSpaceDeviceBinding(
                f.owner.audience,
                f.owner.accountId,
                target.intent.spaceId,
                f.owner.nativeSessionId,
            )
        val provider =
            CollaborationSpaceIdentity(
                target.intent.spaceId,
                f.owner.accountId,
                "proof.invalid",
                original.matrixUserId,
                CollaborationSpaceIdentityOrigin.LEGACY,
                f.owner.companyId,
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
            )
        val bootstrap =
            CollaborationSpaceBootstrap(
                "c0000000-0000-4000-8000-000000000001",
                binding,
                CollaborationNativePlatform.ANDROID,
                5,
                target.intent.deviceId,
                target.intent.expectedDeviceVersion + 1,
                null,
                provider,
                original.matrixDeviceId,
            )
        val command =
            CollaborationSpaceSetupCommand(
                "c0000000-0000-4000-8000-000000000002",
                bootstrap.platform,
                bootstrap.clientBuild,
                initial.command.createdAt,
                bootstrap,
            )
        val gateway =
            CollaborationSpaceGateway(
                binding,
                target.intent.deviceId,
                "c0000000-0000-4000-8000-000000000003",
                0,
                original.matrixUserId,
                original.matrixDeviceId,
                "hris_mxg_a_" + "c".repeat(43),
                "hris_mxg_r_" + "d".repeat(43),
                original.accessExpiresAtEpochMillis,
                original.expiresAtEpochMillis,
            )
        return CollaborationSpaceSetupRecord(
            command,
            2,
            1,
            CollaborationSpaceSetupOutcome.Bootstrapped(gateway),
        )
    }

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

    private fun originalDigest(record: JSONObject): String {
        val fields =
            record
                .keys()
                .asSequence()
                .filter { it !in setOf("schema", "credentialRevision", "adoption") }
                .sorted()
                .joinToString("\n") { it + ":" + record.get(it).toString() }
        return MessageDigest.getInstance("SHA-256").digest(fields.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun write(name: String, result: JSONObject) {
        File(directory, name)
            .writeText(
                result
                    .put("passed", true)
                    .put("complete", true)
                    .put("newMessagesSent", 0)
                    .toString()
            )
    }
}
