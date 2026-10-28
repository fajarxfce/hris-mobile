package dev.fajar.starter.identity.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.identity.domain.push.repositories.*
import dev.fajar.starter.identity.domain.push.usecases.ReconcileNativePush
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.repositories.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

internal class NativePushFixture {
    var now = Instant.parse("2026-10-28T00:00:00Z")
    val clock =
        object : Clock {
            override fun now() = now
        }
    val server = IdentityServer("https://hris.invalid/api/v1/", "Test")
    var session: Session? =
        Session(
            "local",
            User("11111111-1111-4111-8111-111111111111", "Test", "test@example.invalid"),
            SessionTokens("access", "refresh", (now + 1.days).toEpochMilliseconds()),
            SessionBinding(
                "22222222-2222-4222-8222-222222222222",
                server.audience,
                (now + 90.days).toEpochMilliseconds(),
            ),
        )
    var state = NativePushState()
    var failStorage = false
    var permission = NotificationAccess.Granted
    var token = "provider-token-0000000001"
    var deletes = 0
    var acquisitions = 0
    var failDeletion = false
    var loseResponse = false
    var reads = 0
    val commands = mutableListOf<Pair<NativePushOwner, NativePushCommand>>()
    val revocations = mutableListOf<Pair<String, String>>()
    val remote = mutableMapOf<String, NativePushRegistration>()
    val receipts = mutableMapOf<String, NativePushReceipt>()
    var beforeSend: suspend () -> Unit = {}
    val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe(): Flow<AppResult<Session?>> = flow { emit(current()) }

            override suspend fun current() = AppResult.Success(session)

            override suspend fun compareAndSet(
                expected: Session?,
                updated: Session?,
            ): AppResult<Boolean> {
                if (session != expected) return AppResult.Success(false)
                session = updated
                return AppResult.Success(true)
            }

            override suspend fun clear(sessionId: String): AppResult<Boolean> {
                if (session?.id != sessionId) return AppResult.Success(false)
                session = null
                return AppResult.Success(true)
            }
        }
    val storage =
        object : NativePushStateRepository {
            override val persistent = true

            override suspend fun read() = AppResult.Success(state)

            override fun observe() = flow { emit(read()) }

            override suspend fun compareAndSet(
                expected: NativePushState,
                updated: NativePushState,
            ): AppResult<Boolean> {
                if (failStorage) return failure(FailureKind.Storage)
                if (state != expected) return AppResult.Success(false)
                state = updated
                return AppResult.Success(true)
            }
        }
    val tokens =
        object : PushTokenRepository {
            override suspend fun token(): AppResult<String> {
                acquisitions++
                return AppResult.Success(token)
            }

            override suspend fun deleteToken(): AppResult<Unit> {
                deletes++
                if (failDeletion) return failure(FailureKind.Unavailable)
                token = "provider-token-${deletes.toString().padStart(10, '0')}"
                return AppResult.Success(Unit)
            }

            override fun observeTokens() = flowOf(AppResult.Success(token))
        }
    val permissions =
        object : NotificationAccessRepository {
            override suspend fun check() = AppResult.Success(permission)

            override suspend fun request(): AppResult<NotificationAccess> =
                error("Reconciliation must not request permission.")
        }
    val devices =
        object : DeviceSessionRepository {
            override suspend fun list(sessionId: String): AppResult<List<DeviceSession>> =
                error("Not used")

            override suspend fun revoke(sessionId: String, remoteId: String): AppResult<Unit> {
                revocations += sessionId to remoteId
                return AppResult.Success(Unit)
            }
        }
    val registrations =
        object : NativePushRegistrationRepository {
            override suspend fun read(owner: NativePushOwner): AppResult<NativePushRegistration?> {
                reads++
                return AppResult.Success(remote[owner.familyId])
            }

            override suspend fun send(
                owner: NativePushOwner,
                command: NativePushCommand,
            ): AppResult<NativePushReceipt> {
                commands += owner to command
                beforeSend()
                receipts[command.operationId]?.let {
                    return AppResult.Success(it)
                }
                val previous = remote[owner.familyId]
                if (previous?.version != command.expectedVersion)
                    return AppResult.Failed(
                        Failure(FailureKind.Conflict, "Changed", code = "stale_version")
                    )
                val value =
                    when (command) {
                        is NativePushCommand.Save ->
                            NativePushRegistration(
                                owner.familyId,
                                command.platform,
                                true,
                                (previous?.version ?: -1) + 1,
                                previous?.registeredAt ?: now,
                                now,
                                now + 30.days,
                            )
                        is NativePushCommand.Disable ->
                            requireNotNull(previous)
                                .copy(enabled = false, version = previous.version + 1)
                    }
                remote[owner.familyId] = value
                val receipt = NativePushReceipt(owner.familyId, value.version)
                receipts[command.operationId] = receipt
                if (loseResponse) {
                    loseResponse = false
                    return failure(FailureKind.Network)
                }
                return AppResult.Success(receipt)
            }
        }
    private val mutations = Mutex()

    fun useCase(platform: AppPlatform = AppPlatform.Android) =
        ReconcileNativePush(
            sessions,
            registrations,
            storage,
            tokens,
            permissions,
            devices,
            server,
            AppBuild(platform, 1, "1", null),
            clock,
            mutations,
        )

    fun failure(kind: FailureKind) =
        AppResult.Failed(Failure(kind, "Fixture failed.", code = "fixture.failure"))
}
