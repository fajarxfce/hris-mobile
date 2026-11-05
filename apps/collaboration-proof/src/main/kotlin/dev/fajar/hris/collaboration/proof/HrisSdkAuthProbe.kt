package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.util.AtomicFile
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.policies.collaborationContentResponseClosesRead
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import java.io.File
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.SyncSettingsV2

/** Real rejected-refresh callback against an isolated HRIS server and fictional SDK owner. */
internal class HrisSdkAuthProbe(private val context: Context, private val directory: File) {
    suspend fun run() =
        withContext(Dispatchers.IO) {
            val input = File(directory, "auth-fixture.json")
            check(input.length() in 1..16_384)
            val fixture = JSONObject(input.readText())
            val home = URI(fixture.getString("baseUrl"))
            require(
                home.scheme == "http" &&
                    home.host == "127.0.0.1" &&
                    home.port in 1024..65535 &&
                    home.rawUserInfo == null &&
                    home.rawQuery == null &&
                    home.rawFragment == null &&
                    home.path in listOf("", "/")
            )
            val http =
                createHttpClient(
                    createPlatformHttpEngine(),
                    HttpClientSettings(home.toString(), allowCleartext = true),
                )
            var lease: AndroidCollaborationRuntimeLease? = null
            var queueSession: HrisAuthQueueSession? = null
            var primary: Exception? = null
            try {
                val owner =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("owner"), home, http)
                val active = owner.installInitial()
                lease = active
                val originalKeys = active.proofKeyFingerprint()
                check(active.startSync() is AppResult.Success)
                val queues =
                    if (fixture.has("roomId")) HrisAuthQueueSession(context, fixture, home, http)
                    else null
                queueSession = queues
                queues?.prepare(owner, active)
                write("auth-ready", JSONObject().put("ready", true))
                val rejected =
                    withTimeout(60_000) { active.credentialFailures.first { it != null } }!!
                check(
                    rejected.kind == FailureKind.Unauthorized &&
                        rejected.code == "client.collaboration_sdk_unauthorized"
                )
                check(!collaborationContentResponseClosesRead(rejected))
                val reopening =
                    owner.repository.isOpen(owner.currentOwner, owner.registeredDeviceId)
                check(reopening is AppResult.Failed && reopening.failure == rejected)
                val syncing = active.startSync()
                check(syncing is AppResult.Failed && syncing.failure == rejected)
                check(active.proofKeyFingerprint() == originalKeys)
                val replacement = File(directory, "auth-replacement.json")
                withTimeout(30_000) { while (!replacement.exists()) delay(100) }
                check(replacement.length() in 1..16_384)
                // Product repository must drain its exact old client before restoring this
                // original protected store. The fixture never closes the old lease first.
                val restored = owner.installConfirmed(JSONObject(replacement.readText()))
                lease = restored
                check(restored !== active && restored.proofKeyFingerprint() == originalKeys)
                check(
                    owner.repository.isOpen(owner.currentOwner, owner.registeredDeviceId) ==
                        AppResult.Success(true)
                )
                check(restored.credentialFailures.value == null)
                restored.nativeClient.syncOnceV2(
                    SyncSettingsV2(timeoutMs = 1_000uL, fullState = false)
                )
                check(restored.credentialFailures.value == null)
                lease =
                    if (queues == null) restored
                    else recoverLegacyQueue(owner, restored, queues, originalKeys)
                lease.closeProofRuntime()
                lease = null
                write("auth-result", result(queues != null))
            } catch (failure: Exception) {
                primary = failure
                throw failure
            } finally {
                closeHrisProofResources(
                    primary,
                    listOf(
                        { queueSession?.close() },
                        { lease?.closeProofRuntime() },
                        { http.close() },
                    ),
                )
            }
        }

    private fun result(queueRecovered: Boolean) =
        JSONObject()
            .put("complete", true)
            .put("typedAuthRejection", true)
            .put("liveSyncRejected", true)
            .put("runtimeEntryRejected", true)
            .put("localReadPolicyPreserved", true)
            .put("originalKeysPreserved", true)
            .put("ownedRuntimeDrained", true)
            .put("sameStoreCredentialsReplaced", true)
            .put("actualSdkSyncRestored", true)
            .put("queueOperationPreserved", queueRecovered)
            .put("legacyQueueRecovered", queueRecovered)
            .put("oldAndNewEncrypted", queueRecovered)
            .put("retainedApiFailureRetried", queueRecovered)

    private suspend fun recoverLegacyQueue(
        owner: ProtectedHrisProofOwner,
        restored: AndroidCollaborationRuntimeLease,
        queues: HrisAuthQueueSession,
        originalKeys: String,
    ): AndroidCollaborationRuntimeLease {
        queues.checkStableQueue(restored)
        restored.closeProofRuntime()
        val legacy = owner.restoreLegacyQueueBinding()
        try {
            check(legacy.proofKeyFingerprint() == originalKeys)
            check(legacy.startSync() is AppResult.Success)
            queues.recoverLegacy(legacy)
            return legacy
        } catch (failure: Exception) {
            closeHrisProofResources(failure, listOf({ legacy.closeProofRuntime() }))
            throw failure
        }
    }

    private fun write(name: String, value: JSONObject) {
        val file = AtomicFile(File(directory, "$name.json"))
        val stream = file.startWrite()
        try {
            stream.write(value.toString().toByteArray())
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}
