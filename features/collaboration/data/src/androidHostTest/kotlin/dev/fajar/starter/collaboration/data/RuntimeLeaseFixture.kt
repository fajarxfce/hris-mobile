package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.*
import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationHttpObservation
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
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import java.util.Collections
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Encryption
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SyncResponseRoomsV2
import org.matrix.rustcomponents.sdk.SyncResponseV2
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.SyncTimelineChangesV2
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ToDeviceMessageListener

internal suspend fun runtimeLeaseFixture(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    httpObservation: AndroidCollaborationHttpObservation? = null,
    credentialState: (CollaborationCredentialState) -> Unit = {},
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
        httpObservation,
        store,
        codec,
        credentialState,
    )
}

internal class RuntimeLeaseFixture(
    val initial: CollaborationSdkRecord,
    endpoint: CollaborationSdkEndpoint,
    val persistence: SdkSessionPersistence,
    dispatcher: CoroutineDispatcher,
    private val released: (AndroidCollaborationRuntimeLease) -> Unit,
    val httpObservation: AndroidCollaborationHttpObservation?,
    val store: MemoryCredentialStore,
    val codec: CollaborationSdkRecordCodec,
    credentialState: (CollaborationCredentialState) -> Unit,
) {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var beforePause: suspend () -> Unit = {}
    var beforeIdentity: () -> Unit = {}
    var beforeSync: suspend () -> Unit = {}
    var readRoom: (String) -> Room? = { error("No room read expected") }
    var joinRoom: suspend (String) -> Room = { error("No room join expected") }
    var readinessListener: ToDeviceMessageListener? = null
    var expectedReadinessEventType = "dev.fajar.hris.recipient_ready"
    var beforeReadinessSubscribe: () -> Unit = {}
    var failReadinessSubscribe = false
    var syncRequests = 0
    private var storedSyncPosition: String? = null
    val syncSettings = mutableListOf<SyncSettingsV2>()
    var signingKey: String? = Base64.encode(ByteArray(32) { it.toByte() })
    var providerRequest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        error("Publication never sends HTTP requests")
    }
    var providerRequests = 0
    var sessionReads = 0
    var currentSession =
        Session(
            initial.accessToken,
            initial.refreshToken,
            initial.matrixUserId,
            initial.matrixDeviceId,
            initial.homeserverUrl,
            null,
            SlidingSyncVersion.NONE,
        )
    private val http =
        HttpClient(
            MockEngine {
                providerRequests++
                providerRequest(this, it)
            }
        )
    private val sdk =
        object : Client(NoHandle) {
            override fun session(): Session {
                sessionReads++
                return currentSession.copy()
            }

            override fun getRoom(roomId: String): Room? {
                calls += "room.lookup"
                return readRoom(roomId)
            }

            override suspend fun joinRoomById(roomId: String): Room {
                calls += "room.join"
                return joinRoom(roomId)
            }

            override fun subscribeToCustomToDeviceMessages(
                eventTypes: List<String>,
                listener: ToDeviceMessageListener,
            ): TaskHandle {
                assertEquals(listOf(expectedReadinessEventType), eventTypes)
                calls += "readiness.subscribe"
                readinessListener = listener
                beforeReadinessSubscribe()
                if (failReadinessSubscribe) error("Fictional subscribe failure")
                return object : TaskHandle(NoHandle) {
                    override fun cancel() {
                        calls += "readiness.cancel"
                    }

                    override fun close() {
                        calls += "readiness.close"
                    }
                }
            }

            override fun userId(): String {
                beforeIdentity()
                return "@native:proof.invalid"
            }

            override fun deviceId(): String = "PROOF_NATIVE"

            override suspend fun storedSyncPositionV2(): String? = storedSyncPosition

            override suspend fun syncOnceV2(settings: SyncSettingsV2): SyncResponseV2 {
                assertFalse(settings.fullState)
                syncRequests++
                syncSettings += settings
                beforeSync()
                val previous = storedSyncPosition
                storedSyncPosition = "fixture-next"
                return SyncResponseV2(
                    previousBatch = previous,
                    nextBatch = "fixture-next",
                    rooms = SyncResponseRoomsV2(emptyList(), emptyList(), emptyList(), emptyList()),
                    timelineChanges = SyncTimelineChangesV2(emptyList(), false),
                )
            }

            override suspend fun enableAllSendQueues(enable: Boolean) {
                check(!enable)
                calls += "queues.disable"
            }

            override fun encryption(): Encryption =
                object : Encryption(NoHandle) {
                    override suspend fun ed25519Key(): String? = signingKey

                    override suspend fun curve25519Key(): String =
                        Base64.encode(ByteArray(32) { (it + 32).toByte() })

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
            credentialState = credentialState,
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
