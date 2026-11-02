package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.*
import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkStoreLease
import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.MemoryCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.util.Collections
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Encryption
import org.matrix.rustcomponents.sdk.NoHandle

internal suspend fun runtimeLeaseFixture(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    released: (AndroidCollaborationRuntimeLease) -> Unit = {},
): RuntimeLeaseFixture {
    val endpoint = CollaborationSdkEndpoint("https://hris.example.test/")
    val scope =
        collaborationSdkScope(
            setupOwner,
            SETUP_DEVICE,
            IdentityServer(SETUP_BASE, "Fictional"),
            endpoint,
        )
    val codec = CollaborationSdkRecordCodec(scope)
    val command =
        CollaborationSetupCommand(
            SETUP_OPERATION,
            setupOwner.setupBinding(),
            CollaborationNativePlatform.ANDROID,
            5,
            Instant.parse("2026-10-31T00:00:00Z"),
            CollaborationSetupAction.Bootstrap(SETUP_DEVICE, 1, null),
        )
    val gateway =
        Json.decodeFromString<CollaborationGatewayDto>(setupGatewayJson)
            .toGateway(setupOwner, setupBootstrap)
    val initial = codec.create(command, gateway, Base64.encode(ByteArray(32) { it.toByte() }))
    val store = MemoryCredentialStore().also { it.write(codec.encode(initial)) }
    return RuntimeLeaseFixture(
        initial,
        endpoint,
        SdkSessionPersistence(store, codec, initial),
        dispatcher,
        released,
    )
}

internal class RuntimeLeaseFixture(
    initial: CollaborationSdkRecord,
    endpoint: CollaborationSdkEndpoint,
    val persistence: SdkSessionPersistence,
    dispatcher: CoroutineDispatcher,
    private val released: (AndroidCollaborationRuntimeLease) -> Unit,
) {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var beforePause: suspend () -> Unit = {}
    private val http = HttpClient(MockEngine { error("Publication never sends HTTP requests") })
    private val sdk =
        object : Client(NoHandle) {
            override suspend fun enableAllSendQueues(enable: Boolean) {
                check(!enable)
                calls += "queues.disable"
            }

            override fun encryption(): Encryption =
                object : Encryption(NoHandle) {
                    override suspend fun waitForE2eeInitializationTasks() {
                        calls += "encryption.drain"
                    }

                    override fun close() {
                        calls += "encryption.close"
                    }
                }

            override suspend fun pause() {
                calls += "client.pause"
                beforePause()
            }

            override fun close() {
                calls += "client.close"
            }
        }
    private val directory =
        object : CollaborationSdkStoreLease {
            override val storeId = initial.storeId
            override val databasePath = "unused/database"
            override val cachePath = "unused/cache"
            override val initiallyOccupied = true

            override fun close() {
                calls += "directory.close"
            }
        }
    val lease =
        AndroidCollaborationRuntimeLease(
            setupOwner,
            sdk,
            directory,
            persistence,
            initial,
            CollaborationHistoryApi(http, endpoint),
            CollaborationPusherApi(http, endpoint),
            dispatcher,
        ) {
            calls += "released"
            released(it)
        }

    suspend fun close() {
        try {
            withTimeout(5_000) { assertEquals(AppResult.Success(Unit), lease.close()) }
        } finally {
            http.close()
        }
    }

    fun assertReleased() {
        assertEquals(
            listOf(
                "queues.disable",
                "encryption.drain",
                "encryption.close",
                "client.pause",
                "client.close",
                "directory.close",
                "released",
            ),
            calls.toList(),
        )
    }
}
