package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.matrix.rustcomponents.sdk.*

/** Actual repository acquisition and registry, with a controllable raw native response. */
internal class SpaceRuntimeSyncClientSource(
    private val identity: CollaborationSdkIdentityData,
    val registrations: PendingRuntimeClientSource = PendingRuntimeClientSource(),
) : AndroidCollaborationClientSource by registrations {
    val entered = CompletableDeferred<Unit>()
    val responses = Channel<SyncResponseV2>(Channel.UNLIMITED)
    private val attempts = AtomicInteger()
    private val releases = AtomicInteger()
    val requests
        get() = attempts.get()

    val closes
        get() = releases.get()

    private val native =
        object : Client(NoHandle) {
            override fun userId() = identity.userId

            override fun deviceId() = identity.deviceId

            override suspend fun storedSyncPositionV2(): String? = null

            override suspend fun syncOnceV2(settings: SyncSettingsV2): SyncResponseV2 {
                attempts.incrementAndGet()
                entered.complete(Unit)
                return responses.receive()
            }

            override suspend fun enableAllSendQueues(enable: Boolean) {
                assertFalse(enable)
            }

            override fun encryption(): Encryption =
                object : Encryption(NoHandle) {
                    override suspend fun waitForE2eeInitializationTasks() = Unit

                    override fun close() = Unit
                }

            override suspend fun pause() = Unit

            override fun close() {
                releases.incrementAndGet()
            }
        }

    override suspend fun create(
        directory: CollaborationSdkStoreLease,
        passphrase: String,
        initialSession: Session,
        delegate: ClientSessionDelegate,
    ): Client = native

    override suspend fun identity(client: Client) = identity

    fun response() =
        SyncResponseV2(
            previousBatch = null,
            nextBatch = "retained-response",
            rooms = SyncResponseRoomsV2(emptyList(), emptyList(), emptyList(), emptyList()),
            timelineChanges =
                SyncTimelineChangesV2(
                    listOf(
                        SyncRoomTimelineChangeV2(
                            "!room:matrix.test",
                            SyncTimelineMembershipV2.JOINED,
                            listOf("\$event"),
                            false,
                        )
                    ),
                    false,
                ),
        )
}
