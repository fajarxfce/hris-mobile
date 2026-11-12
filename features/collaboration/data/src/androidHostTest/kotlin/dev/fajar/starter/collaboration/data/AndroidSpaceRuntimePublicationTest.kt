package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRuntimeLease
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.*

class AndroidSpaceRuntimePublicationTest {
    @Test
    fun subscriberBeforeInstallationSeesOnlyPublishedClient() = runBlocking {
        val f = Fixture.create()
        try {
            f.observe(this)
            assertEquals(AppResult.Success(null), f.next())
            assertEquals(0, f.storage.acquisitions)
            val lease = f.install()
            assertEquals(AppResult.Success(Unit), lease.publish())
            assertEquals(f.runtime, f.ready().runtime)
            f.assertReadOnlyObservation()
        } finally {
            f.close()
        }
    }

    @Test
    fun unpublishedInstallationIsAbsentForLateSubscriber() = runBlocking {
        val f = Fixture.create()
        try {
            f.install()
            assertEquals(
                AppResult.Success(null),
                f.storage.repo.observePublication(f.runtime).first(),
            )
            f.assertReadOnlyObservation()
        } finally {
            f.close()
        }
    }

    @Test
    fun lateSubscriberReceivesCurrentPublishedInstance() = runBlocking {
        val f = Fixture.create()
        try {
            val lease = f.install()
            assertEquals(AppResult.Success(Unit), lease.publish())
            f.observe(this)
            assertEquals(f.runtime, f.ready().runtime)
            f.assertReadOnlyObservation()
        } finally {
            f.close()
        }
    }

    @Test
    fun repeatedPublishKeepsTheSameInstanceWithoutNewStorageOrSync() = runBlocking {
        val f = Fixture.create()
        try {
            val lease = f.install()
            assertEquals(AppResult.Success(Unit), lease.publish())
            val before = f.storage.repo.observePublication(f.runtime).first()
            val writes = f.storage.writes
            repeat(5) { assertEquals(AppResult.Success(Unit), lease.publish()) }
            assertEquals(before, f.storage.repo.observePublication(f.runtime).first())
            assertEquals(writes, f.storage.writes)
            f.assertReadOnlyObservation()
        } finally {
            f.close()
        }
    }

    @Test
    fun retirementIsVisibleWhileNativeDrainStillWaits() = runBlocking {
        val f = Fixture.create()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val lease = f.install()
            lease.publish()
            f.observe(this)
            f.ready()
            f.native.beforePause = {
                entered.complete(Unit)
                release.await()
            }
            val closing = async { lease.close() }
            withTimeout(5_000) { entered.await() }
            assertEquals(AppResult.Success(null), f.next())
            assertTrue(closing.isActive)
            assertEquals(0, f.native.closes)
            release.complete(Unit)
            assertEquals(AppResult.Success(Unit), withTimeout(5_000) { closing.await() })
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun failedNativeDrainDoesNotRepublishRetiredClient() =
        runBlocking<Unit> {
            val f = Fixture.create()
            try {
                val lease = f.install()
                lease.publish()
                f.observe(this)
                f.ready()
                f.native.beforePause = { error("fixture drain failure") }
                assertIs<AppResult.Failed>(lease.close())
                assertEquals(AppResult.Success(null), f.next())
                assertEquals(
                    AppResult.Success(null),
                    f.storage.repo.observePublication(f.runtime).first(),
                )
                assertIs<AppResult.Failed>(lease.publish())
            } finally {
                f.native.beforePause = {}
                f.close()
            }
        }

    @Test
    fun reopeningIdenticalCoordinatesPublishesANewInstance() = runBlocking {
        val f = Fixture.create()
        try {
            val first = f.install()
            first.publish()
            f.observe(this)
            val before = f.ready()
            assertEquals(AppResult.Success(Unit), first.close())
            assertEquals(AppResult.Success(null), f.next())
            val second = f.install()
            second.publish()
            val after = f.ready()
            assertEquals(before.runtime, after.runtime)
            assertNotEquals(before.instanceId, after.instanceId)
            assertEquals(2, f.storage.acquisitions)
            assertEquals(0, f.native.requests)
        } finally {
            f.close()
        }
    }

    @Test
    fun foreignCoordinatesNeverExposeExistingClient() = runBlocking {
        val f = Fixture.create()
        try {
            f.install().publish()
            for (runtime in
                listOf(
                    f.runtime.copy(deviceId = SETUP_JOB),
                    f.runtime.copy(operationId = SETUP_DEVICE),
                    f.runtime.copy(owner = f.runtime.owner.copy(spaceId = SETUP_JOB)),
                )) {
                assertNotEquals(f.runtime, runtime)
                assertEquals(
                    AppResult.Success(null),
                    f.storage.repo.observePublication(runtime).first(),
                )
            }
            f.assertReadOnlyObservation()
        } finally {
            f.close()
        }
    }

    @Test
    fun cancellingObservationLeavesPublishedRuntimeAndOtherObserverAlive() = runBlocking {
        val f = Fixture.create()
        try {
            f.install().publish()
            val first = f.observe(this)
            val before = f.ready()
            first.cancelAndJoin()
            assertEquals(0, f.native.closes)
            assertEquals(
                AppResult.Success(true),
                f.storage.repo.isSpaceOpen(
                    f.runtime.owner,
                    f.runtime.deviceId,
                    f.runtime.operationId,
                ),
            )
            val second = f.observe(this)
            assertEquals(before, f.ready())
            assertTrue(second.isActive)
            f.assertReadOnlyObservation()
        } finally {
            f.close()
        }
    }

    @Test
    fun parentRetirementClearsExistingPublication() = runBlocking {
        val f = Fixture.create()
        try {
            f.install().publish()
            f.observe(this)
            f.ready()
            assertEquals(
                AppResult.Success(Unit),
                f.storage.repo.updateRetention(CollaborationRuntimeRetention(null, null)),
            )
            assertEquals(AppResult.Success(null), f.next())
            assertEquals(1, f.native.closes)
        } finally {
            f.close()
        }
    }

    @Test
    fun authenticationFailureRemainsTypedWithoutObservationRetry() = runBlocking {
        val f = Fixture.create()
        try {
            f.install().publish()
            f.observe(this)
            f.ready()
            f.auth.didReceiveAuthError(false)
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.next()).failure.kind,
            )
            assertEquals(1, f.storage.acquisitions)
        } finally {
            f.close()
        }
    }

    @Test
    fun credentialWriteFailureAndConfirmedRetryPreserveInstanceIdentity() = runBlocking {
        val f = Fixture.create()
        try {
            val lease = f.install()
            lease.publish()
            f.observe(this)
            val before = f.ready()
            val session =
                f.session.retrieveSessionFromKeychain(f.storage.data.original.matrixUserId)
            f.storage.beforeWrite = { error("fixture credential write") }
            f.session.saveSessionInKeychain(
                session.copy(
                    accessToken = "hris_mxg_a_" + "e".repeat(43),
                    refreshToken = "hris_mxg_r_" + "f".repeat(43),
                )
            )
            assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(f.next()).failure.kind)
            f.storage.beforeWrite = {}
            assertEquals(AppResult.Success(Unit), lease.retryCredentialWrite())
            assertEquals(before, f.ready())
            assertEquals(1, f.storage.acquisitions)
        } finally {
            f.close()
        }
    }

    @Test
    fun failedAcquisitionNeverLeavesAPublishedInstance() = runBlocking {
        val f = Fixture.create()
        try {
            f.storage.select()
            f.storage.present = false
            assertIs<AppResult.Failed>(f.storage.install())
            assertEquals(
                AppResult.Success(null),
                f.storage.repo.observePublication(f.runtime).first(),
            )
            assertEquals(0, f.native.requests)
            assertEquals(0, f.native.keySubscriptions)
        } finally {
            f.close()
        }
    }

    private class Fixture private constructor() {
        lateinit var storage: SpaceAdoptionStorageFixture
        lateinit var native: SpaceRuntimeSyncClientSource
        lateinit var auth: ClientDelegate
        lateinit var session: ClientSessionDelegate
        val runtime
            get() =
                CollaborationSpaceRuntimeReady(
                    storage.owner,
                    storage.setup.command.request.deviceId,
                    storage.setup.command.request.operationId,
                )

        private val values =
            Channel<AppResult<CollaborationSpaceRuntimePublication?>>(Channel.UNLIMITED)
        private val observers = mutableListOf<Job>()

        fun observe(scope: CoroutineScope): Job =
            scope
                .launch(start = CoroutineStart.UNDISPATCHED) {
                    storage.repo.observePublication(runtime).collect { values.send(it) }
                }
                .also(observers::add)

        suspend fun next() = withTimeout(5_000) { values.receive() }

        suspend fun ready() =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationSpaceRuntimePublication?>>(next()).value
            )

        suspend fun install(): CollaborationSpaceRuntimeLease {
            storage.select()
            return assertIs<AppResult.Success<CollaborationSpaceRuntimeLease>>(storage.install())
                .value
        }

        fun assertReadOnlyObservation() {
            assertEquals(1, storage.acquisitions)
            assertEquals(0, native.requests)
            assertEquals(0, native.keySubscriptions)
            assertEquals(0, native.inventoryRequests)
        }

        suspend fun close() {
            observers.forEach { it.cancelAndJoin() }
            native.beforePause = {}
            native.responses.close()
            storage.beforeWrite = {}
            storage.repo.closeSpace(storage.owner)
            storage.close()
            values.close()
        }

        companion object {
            suspend fun create(): Fixture {
                val f = Fixture()
                f.storage =
                    SpaceAdoptionStorageFixture.create { identity ->
                        val source = SpaceRuntimeSyncClientSource(identity).also { f.native = it }
                        object : AndroidCollaborationClientSource by source {
                            override fun observeAuthentication(
                                client: Client,
                                delegate: ClientDelegate,
                            ): TaskHandle? {
                                f.auth = delegate
                                return source.observeAuthentication(client, delegate)
                            }

                            override suspend fun create(
                                directory: CollaborationSdkStoreLease,
                                passphrase: String,
                                initialSession: Session,
                                delegate: ClientSessionDelegate,
                            ): Client {
                                f.session = delegate
                                return source.create(
                                    directory,
                                    passphrase,
                                    initialSession,
                                    delegate,
                                )
                            }
                        }
                    }
                return f
            }
        }
    }
}
