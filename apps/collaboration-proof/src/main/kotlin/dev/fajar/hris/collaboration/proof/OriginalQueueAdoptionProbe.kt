package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import java.io.File
import java.net.URI
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.SyncSettingsV2

/** Real paused SDK transactions; the only initial sync uses a synthetic loopback room. */
internal class OriginalQueueAdoptionProbe(directory: File) {
    val baseUrl: String
    private val roomId = "!original-queue:proof.invalid"

    init {
        val file = File(directory, "space-queue-fixture.json")
        check(file.isFile && file.length() in 1..1024)
        val value = JSONObject(file.readText()).getString("baseUrl")
        val home = URI(value)
        require(home.scheme == "http" && home.host == "127.0.0.1" && home.port in 1024..65535)
        require(
            home.rawPath == "/" &&
                home.rawUserInfo == null &&
                home.rawQuery == null &&
                home.rawFragment == null
        )
        baseUrl = value
    }

    suspend fun seed(
        f: LocalRuntimeHandoverFixture,
        runtime: AndroidCollaborationRuntimeLease,
    ): JSONObject {
        runtime.nativeClient.syncOnceV2(SyncSettingsV2(timeoutMs = 1_000uL, fullState = true))
        val queue = f.success(f.runtime.acquireMessageQueue(f.owner, f.device, roomId))
        var primary: Throwable? = null
        try {
            check(f.success(queue.snapshot()).isEmpty())
            for ((id, text) in
                listOf(
                    "b0000000-0000-4000-8000-000000000001" to "Original queued message A",
                    "b0000000-0000-4000-8000-000000000002" to "Original queued message B",
                )) {
                val origin = f.success(queue.prepareText(id, text))
                f.success(queue.enqueue(id, text, origin))
            }
            val original = f.success(queue.originals())
            check(original.size == 2 && original.mapNotNull { it.logicalId }.toSet().size == 2)
            check(f.success(queue.snapshot()) == original.map { it.transactionId }.toSet())
            return JSONObject()
                .put("nativeSessionId", queue.device.nativeSessionId)
                .put("deviceId", queue.device.deviceId)
                .put("bootstrapId", queue.device.bootstrapOperationId)
                .put("messages", serialize(original))
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                try {
                    f.success(queue.close())
                } catch (failure: Throwable) {
                    if (primary != null) primary.addSuppressed(failure) else throw failure
                }
            }
        }
    }

    suspend fun rejectUnpublished(
        f: LocalRuntimeHandoverFixture,
        ready: CollaborationSpaceRuntimeReady,
    ) {
        check(f.runtime.inspectOriginalQueue(ready, roomId) is AppResult.Failed)
        check(f.runtime.acquireOriginalQueue(ready, roomId) is AppResult.Failed)
    }

    suspend fun verify(
        f: LocalRuntimeHandoverFixture,
        ready: CollaborationSpaceRuntimeReady,
        expected: JSONObject,
    ) {
        for (changed in
            listOf(
                ready.copy(deviceId = "d0000000-0000-4000-8000-000000000001"),
                ready.copy(operationId = "d0000000-0000-4000-8000-000000000002"),
                ready.copy(
                    owner = ready.owner.copy(spaceId = "d0000000-0000-4000-8000-000000000003")
                ),
                ready.copy(
                    owner =
                        ready.owner.copy(
                            parent =
                                ready.owner.parent.copy(
                                    nativeSessionId = "d0000000-0000-4000-8000-000000000004"
                                )
                        )
                ),
            )) {
            check(f.runtime.inspectOriginalQueue(changed, roomId) is AppResult.Failed)
            check(f.runtime.acquireOriginalQueue(changed, roomId) is AppResult.Failed)
        }
        check(f.runtime.acquireMessageQueue(f.owner, f.device, roomId) is AppResult.Failed)
        val result = f.success(f.runtime.inspectOriginalQueue(ready, roomId))
        check(result.runtime == ready)
        val original = result.original
        check(original.binding == f.owner.setupBinding())
        check(original.device.roomId == roomId)
        check(original.device.nativeSessionId == expected.getString("nativeSessionId"))
        check(original.device.deviceId == expected.getString("deviceId"))
        check(original.device.bootstrapOperationId == expected.getString("bootstrapId"))
        check(original.device.deviceId != ready.deviceId)
        check(original.device.bootstrapOperationId != ready.operationId)
        val actual = serialize(original.messages)
        val before = expected.getJSONArray("messages")
        check(actual.length() == before.length())
        for (index in 0 until actual.length()) {
            for (key in
                listOf(
                    "transactionId",
                    "logicalId",
                    "eventType",
                    "serializedContent",
                    "sender",
                    "contentHash",
                )) {
                check(
                    actual.getJSONObject(index).getString(key) ==
                        before.getJSONObject(index).getString(key)
                )
            }
        }
        verifyHeldQueue(f, ready, result)
    }

    private suspend fun verifyHeldQueue(
        f: LocalRuntimeHandoverFixture,
        ready: CollaborationSpaceRuntimeReady,
        expected: CollaborationSpaceOriginalQueueSnapshot,
    ) {
        val queue = f.success(f.runtime.acquireOriginalQueue(ready, roomId))
        var primary: Throwable? = null
        try {
            check(queue.runtime == ready && queue.binding == expected.original.binding)
            check(
                queue.device == expected.original.device && queue.device.deviceId != ready.deviceId
            )
            check(f.success(queue.snapshot()) == expected)
            check(f.runtime.acquireOriginalQueue(ready, roomId) is AppResult.Failed)
            check(f.runtime.inspectOriginalQueue(ready, roomId) is AppResult.Failed)
            val partial =
                expected.copy(
                    original = expected.original.copy(messages = expected.original.messages.take(1))
                )
            check(
                queue.resume(partial) { error("A partial original queue cannot send") }
                    is AppResult.Failed
            )
            val changed =
                expected.copy(
                    original =
                        expected.original.copy(
                            messages =
                                expected.original.messages.map {
                                    it.copy(serializedContent = "changed")
                                }
                        )
                )
            check(
                queue.resume(changed) { error("Changed original content cannot send") }
                    is AppResult.Failed
            )
            check(f.success(queue.snapshot()) == expected)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                try {
                    f.success(queue.close())
                } catch (failure: Throwable) {
                    if (primary != null) primary.addSuppressed(failure) else throw failure
                }
            }
        }
        check(queue.snapshot() is AppResult.Failed)
        check(f.success(f.runtime.inspectOriginalQueue(ready, roomId)) == expected)
    }

    private fun serialize(messages: List<CollaborationQueuedOriginal>) =
        JSONArray(
            messages
                .sortedBy { it.transactionId }
                .map {
                    JSONObject()
                        .put("transactionId", it.transactionId)
                        .put("logicalId", it.logicalId)
                        .put("eventType", it.eventType)
                        .put("serializedContent", it.serializedContent)
                        .put("sender", it.origin.sender)
                        .put("contentHash", it.origin.contentHash)
                }
        )
}
