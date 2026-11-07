package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkEndpoint
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Instant
import kotlinx.coroutines.*
import org.json.JSONObject

/** Real SDK/Keystore with inert credentials and an unreachable loopback provider, emulator only. */
internal class LocalRuntimeHandoverFixture(private val context: Context, val owned: File) {
    val owner =
        CollaborationOwner(
            "local-handover",
            "http://127.0.0.1:9/api/v1/",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
        )
    val device = "44444444-4444-4444-8444-444444444444"
    val stores = FileCollaborationSdkStoreSource(File(owned, "stores"))
    val inventories = FileCollaborationSdkInventorySource(File(owned, "stores"))
    val inventoryAcquisitions = AtomicInteger()
    val clients = AndroidCollaborationSdkClientSource()
    val directoryAcquisitions = AtomicInteger()
    val activeDirectories = AtomicInteger()
    val activeCredentials = AtomicInteger()
    val name = AtomicReference<String?>()
    @Volatile var beforeStageWrite: suspend () -> Unit = {}
    @Volatile var afterStageWrite: suspend () -> Unit = {}
    @Volatile var afterReplacementWrite: suspend () -> Unit = {}
    private val http = HttpClient(MockEngine { error("No HRIS transport in local handover proof") })
    val runtime =
        AndroidCollaborationRuntimeRepository(
            object : CollaborationSdkStoreSource {
                override suspend fun acquire(storeId: String): CollaborationSdkStoreLease {
                    val lease = stores.acquire(storeId)
                    directoryAcquisitions.incrementAndGet()
                    activeDirectories.incrementAndGet()
                    return object : CollaborationSdkStoreLease by lease {
                        override fun close() {
                            try {
                                lease.close()
                            } finally {
                                activeDirectories.decrementAndGet()
                            }
                        }
                    }
                }
            },
            { recordName ->
                name.compareAndSet(null, recordName)
                check(name.get() == recordName)
                val record = AndroidCredentialStore(context, recordName)
                activeCredentials.incrementAndGet()
                object : AtomicCredentialStore by record {
                    override suspend fun write(value: String?) {
                        val saved = value?.let(::JSONObject)
                        val staging = saved?.optInt("schema")?.let { it in 2..3 } == true
                        if (staging) beforeStageWrite()
                        record.write(value)
                        if (staging) afterStageWrite()
                        else if (
                            saved != null &&
                                saved.optLong("bootstrapGatewayVersion") == 1L &&
                                !saved.optBoolean("initialized")
                        )
                            afterReplacementWrite()
                    }

                    override fun close() {
                        try {
                            record.close()
                        } finally {
                            activeCredentials.decrementAndGet()
                        }
                    }
                }
            },
            clients,
            AesGcmContentCipher(),
            IdentityServer(owner.audience, "Owned SDK handover"),
            CollaborationSdkEndpoint("http://127.0.0.1:9/", allowCleartext = true),
            http,
            inventories =
                object : CollaborationSdkInventorySource {
                    override suspend fun inspect(storeId: String): CollaborationSdkInventoryLease? {
                        val lease = inventories.inspect(storeId) ?: return null
                        inventoryAcquisitions.incrementAndGet()
                        activeDirectories.incrementAndGet()
                        return object : CollaborationSdkInventoryLease by lease {
                            override fun close() {
                                try {
                                    lease.close()
                                } finally {
                                    activeDirectories.decrementAndGet()
                                }
                            }
                        }
                    }
                },
            privateIdentities = AndroidCollaborationSdkPrivateIdentitySource(),
        )

    fun intent(fingerprint: String) =
        CollaborationRuntimeHandoverIntent(
            "a0000000-0000-0000-0000-000000000001",
            "a0000000-0000-0000-0000-000000000002",
            "a0000000-0000-0000-0000-000000000003",
            0,
            fingerprint,
        )

    suspend fun install(): AndroidCollaborationRuntimeLease {
        val lease = success(runtime.install(owner, command())) as AndroidCollaborationRuntimeLease
        success(lease.publish())
        select(owner)
        return lease
    }

    suspend fun interruptCredentialReplacement() = coroutineScope {
        val original = JSONObject(record())
        check(original.getBoolean("initialized"))
        val first = command()
        val gateway = (first.outcome as CollaborationSetupOutcome.Bootstrapped).gateway
        val nextGateway =
            CollaborationGateway(
                gateway.owner,
                gateway.deviceId,
                gateway.sessionId,
                gateway.version + 1,
                gateway.matrixUserId,
                gateway.matrixDeviceId,
                "hris_mx_a_" + "e".repeat(43),
                "hris_mx_r_" + "f".repeat(43),
                gateway.accessExpiresAtEpochMillis,
                gateway.expiresAtEpochMillis,
            )
        val next =
            first.copy(
                command =
                    first.command.copy(
                        id = "77777777-7777-4777-8777-777777777777",
                        action = CollaborationSetupAction.Bootstrap(device, 1, gateway.version),
                    ),
                outcome = CollaborationSetupOutcome.Bootstrapped(nextGateway),
            )
        val committed = CompletableDeferred<Unit>()
        afterReplacementWrite = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val request = async { runtime.install(owner, next) }
        try {
            withTimeout(15_000) { committed.await() }
            check(!success(runtime.isOpen(owner, device)))
            check(activeDirectories.get() == 1 && activeCredentials.get() == 1)
        } finally {
            request.cancelAndJoin()
            afterReplacementWrite = {}
        }
        check(request.isCancelled)
        check(activeDirectories.get() == 0 && activeCredentials.get() == 0)
        val retained = JSONObject(record())
        check(!retained.getBoolean("initialized") && retained.getInt("schema") == 1)
        check(retained.getString("bootstrapOperationId") == next.command.id)
        check(retained.getLong("bootstrapGatewayVersion") == gateway.version + 1)
        for (field in
            listOf(
                "storeId",
                "passphrase",
                "matrixUserId",
                "matrixDeviceId",
                "nativeSessionId",
                "companyId",
                "queueOperationId",
            )) {
            check(retained.get(field) == original.get(field))
        }
    }

    suspend fun restore(): AndroidCollaborationRuntimeLease {
        val lease =
            requireNotNull(success(runtime.restore(owner, device)))
                as AndroidCollaborationRuntimeLease
        success(lease.publish())
        return lease
    }

    suspend fun select(selected: CollaborationOwner) =
        success(
            runtime.updateRetention(
                CollaborationRuntimeRetention(
                    CollaborationNativeParent(
                        selected.sessionId,
                        selected.audience,
                        selected.accountId,
                        selected.nativeSessionId,
                    ),
                    selected,
                )
            )
        )

    suspend fun identity(lease: AndroidCollaborationRuntimeLease) =
        clients.identity(lease.nativeClient).toLocalDeviceIdentity()

    suspend fun record(): String {
        val credentials = AndroidCredentialStore(context, requireNotNull(name.get()))
        return try {
            requireNotNull(credentials.read())
        } finally {
            credentials.close()
        }
    }

    suspend fun clearRecord() {
        val credentials = AndroidCredentialStore(context, requireNotNull(name.get()))
        try {
            credentials.write(null)
        } finally {
            credentials.close()
        }
    }

    fun command(): CollaborationSetupCommandRecord {
        val command =
            CollaborationSetupCommand(
                "55555555-5555-4555-8555-555555555555",
                owner.setupBinding(),
                CollaborationNativePlatform.ANDROID,
                5,
                Instant.parse("2026-01-01T00:00:00Z"),
                CollaborationSetupAction.Bootstrap(device, 1, null),
            )
        val gateway =
            CollaborationGateway(
                owner,
                device,
                "66666666-6666-4666-8666-666666666666",
                0,
                "@hris_${owner.companyId.replace("-", "")}_${owner.accountId.replace("-", "")}:proof.invalid",
                "HRIS_${device.replace("-", "")}",
                "hris_mx_a_" + "a".repeat(43),
                "hris_mx_r_" + "b".repeat(43),
                Instant.parse("2026-01-01T00:05:00Z").toEpochMilliseconds(),
                Instant.parse("2026-01-02T00:00:00Z").toEpochMilliseconds(),
            )
        return CollaborationSetupCommandRecord(
            command,
            1,
            1,
            CollaborationSetupOutcome.Bootstrapped(gateway),
        )
    }

    suspend fun close() =
        withContext(NonCancellable) {
            try {
                success(runtime.closeOtherOwners(null))
            } finally {
                http.close()
            }
            check(activeDirectories.get() == 0 && activeCredentials.get() == 0)
        }

    fun <T> success(value: AppResult<T>): T =
        when (value) {
            is AppResult.Success -> value.value
            is AppResult.Failed -> throw HrisProofFailure(value.failure)
        }
}
