package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent
import dev.fajar.starter.common.result.AppResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

/** Tests repository handover against the real encrypted SDK and Keystore, without a server. */
internal class LocalRuntimeHandoverProbe(
    private val context: Context,
    private val directory: File,
) {
    private val owned = File(directory, "local-handover-owned")
    private val checkpoint = File(owned, "checkpoint.json")

    suspend fun stage() =
        withContext(Dispatchers.IO) {
            check(!owned.exists() && owned.mkdirs())
            val f = LocalRuntimeHandoverFixture(context, owned)
            try {
                var lease = f.install()
                val identity = f.identity(lease)
                val intent = f.intent(identity.fingerprint)
                val changed =
                    intent.copy(
                        fingerprint =
                            if (identity.fingerprint == "a".repeat(64)) "b".repeat(64)
                            else "a".repeat(64)
                    )
                check(f.runtime.stageHandover(f.owner, f.device, changed) is AppResult.Failed)
                check(f.success(f.runtime.isOpen(f.owner, f.device)))
                check(f.success(f.runtime.handoverIntent(f.owner, f.device)) == null)

                val borrow =
                    requireNotNull(
                        f.success(f.runtime.acquireBackground(f.owner, f.device, 60_000))
                    )
                try {
                    cancelWrite(f, intent, committed = false)
                    check(borrow.interruptions.value != null)
                } finally {
                    f.success(borrow.close())
                }
                check(f.success(f.runtime.handoverIntent(f.owner, f.device)) == null)
                check(JSONObject(f.record()).getInt("schema") == 1)
                lease = f.restore()
                check(f.identity(lease) == identity)

                f.beforeStageWrite = { throw IOException("Owned fixture write failure") }
                try {
                    check(f.runtime.stageHandover(f.owner, f.device, intent) is AppResult.Failed)
                } finally {
                    f.beforeStageWrite = {}
                }
                check(f.success(f.runtime.handoverIntent(f.owner, f.device)) == null)
                check(f.activeDirectories.get() == 0 && f.activeCredentials.get() == 0)
                lease = f.restore()
                check(f.identity(lease) == identity)
                val original = JSONObject(f.record())
                cancelWrite(f, intent, committed = true)
                check(f.success(f.runtime.handoverIntent(f.owner, f.device)) == intent)
                check(f.success(f.runtime.stageHandover(f.owner, f.device, intent)) == intent)
                check(
                    f.runtime.stageHandover(
                        f.owner,
                        f.device,
                        intent.copy(expectedDeviceVersion = 1),
                    ) is AppResult.Failed
                )
                val staged = JSONObject(f.record())
                original.keys().forEach { key ->
                    if (key !in setOf("schema", "credentialRevision"))
                        check(original.get(key) == staged.get(key))
                }
                check(
                    staged.getLong("credentialRevision") ==
                        original.getLong("credentialRevision") + 1
                )
                check(staged.getInt("schema") == 2)
                checkpoint.writeText(
                    JSONObject()
                        .put("recordName", f.name.get())
                        .put("fingerprint", identity.fingerprint)
                        .toString()
                )
                write(
                    "local-handover-stage.json",
                    JSONObject()
                        .put("wrongFingerprintRejected", true)
                        .put("cancelBeforeWriteRecoverable", true)
                        .put("writeFailureRecoverable", true)
                        .put("cancelAfterCommitRetained", true)
                        .put("foregroundAndBackgroundExcluded", true)
                        .put("borrowerInterrupted", true)
                        .put("originalRecordFieldsPreserved", true)
                        .put("sameTargetReplay", true),
                )
            } finally {
                f.close()
            }
        }

    private suspend fun cancelWrite(
        f: LocalRuntimeHandoverFixture,
        intent: CollaborationRuntimeHandoverIntent,
        committed: Boolean,
    ) = coroutineScope {
        val reached = CompletableDeferred<Unit>()
        val pause: suspend () -> Unit = {
            reached.complete(Unit)
            awaitCancellation()
        }
        if (committed) f.afterStageWrite = pause else f.beforeStageWrite = pause
        val task = async { f.runtime.stageHandover(f.owner, f.device, intent) }
        try {
            withTimeout(15_000) { reached.await() }
            check(!f.success(f.runtime.isOpen(f.owner, f.device)))
            // The old SDK has fully released the directory; only the staged writer holds it.
            check(f.activeDirectories.get() == 1 && f.activeCredentials.get() == 1)
            val count = f.directoryAcquisitions.get()
            check(f.runtime.restore(f.owner, f.device) is AppResult.Failed)
            check(f.runtime.acquireBackground(f.owner, f.device, 5_000) is AppResult.Failed)
            check(f.directoryAcquisitions.get() == count)
        } finally {
            task.cancelAndJoin()
            f.beforeStageWrite = {}
            f.afterStageWrite = {}
        }
        check(f.activeDirectories.get() == 0 && f.activeCredentials.get() == 0)
    }

    suspend fun resume() =
        withContext(Dispatchers.IO) {
            check(owned.isDirectory && checkpoint.length() in 1..2048)
            val saved = JSONObject(checkpoint.readText())
            val f = LocalRuntimeHandoverFixture(context, owned)
            f.name.set(saved.getString("recordName"))
            try {
                val restored = f.owner.copy(sessionId = "replaced-local-login")
                val intent = f.intent(saved.getString("fingerprint"))
                val original = f.record()
                check(f.success(f.runtime.handoverIntent(restored, f.device)) == intent)
                check(f.success(f.runtime.stageHandover(restored, f.device, intent)) == intent)
                check(f.directoryAcquisitions.get() == 0)
                check(
                    f.runtime.stageHandover(
                        restored,
                        f.device,
                        intent.copy(operationId = "b0000000-0000-0000-0000-000000000001"),
                    ) is AppResult.Failed
                )
                check(f.runtime.restore(restored, f.device) is AppResult.Failed)
                check(f.runtime.install(f.owner, f.command()) is AppResult.Failed)
                f.select(restored)
                check(f.runtime.acquireBackground(restored, f.device, 5_000) is AppResult.Failed)
                check(f.record() == original)
                check(f.activeDirectories.get() == 0 && f.activeCredentials.get() == 0)
                retainedKeys(f, JSONObject(original), intent.fingerprint)
                f.clearRecord()
            } finally {
                f.close()
            }
            check(owned.deleteRecursively())
            write(
                "local-handover-result.json",
                JSONObject()
                    .put("durableAfterProcessDeath", true)
                    .put("expiredCredentialsRemainReadable", true)
                    .put("replacedLocalLoginSameStore", true)
                    .put("legacyAcquisitionRejected", true)
                    .put("replayWithoutSdkConstruction", true)
                    .put("targetImmutable", true)
                    .put("encryptedKeysPreserved", true)
                    .put("ownedResourcesReleased", true)
                    .put("ownedFilesRemoved", !owned.exists()),
            )
        }

    /**
     * Deliberate fixture-only bypass verifies retained crypto files; product legacy reopen is
     * denied.
     */
    private suspend fun retainedKeys(
        f: LocalRuntimeHandoverFixture,
        record: JSONObject,
        fingerprint: String,
    ) {
        val session =
            Session(
                record.getString("accessToken"),
                record.getString("refreshToken"),
                record.getString("matrixUserId"),
                record.getString("matrixDeviceId"),
                record.getString("homeserverUrl"),
                null,
                SlidingSyncVersion.NONE,
            )
        val delegate =
            object : ClientSessionDelegate {
                override fun retrieveSessionFromKeychain(userId: String): Session {
                    check(userId == session.userId)
                    return session
                }

                override fun saveSessionInKeychain(session: Session) {
                    error("No fixture session write")
                }
            }
        val store = f.stores.acquire(record.getString("storeId"))
        var client: Client? = null
        try {
            check(store.initiallyOccupied)
            client = f.clients.create(store, record.getString("passphrase"), session, delegate)
            f.clients.restorePaused(client, session)
            check(f.clients.identity(client).toLocalDeviceIdentity().fingerprint == fingerprint)
        } finally {
            try {
                client?.close()
            } finally {
                store.close()
            }
        }
    }

    private fun write(name: String, value: JSONObject) {
        File(directory, name)
            .writeText(value.put("complete", true).put("providerRequired", false).toString())
    }
}
