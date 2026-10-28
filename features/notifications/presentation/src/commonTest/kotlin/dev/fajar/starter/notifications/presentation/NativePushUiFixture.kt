package dev.fajar.starter.notifications.presentation

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.identity.domain.push.repositories.*
import dev.fajar.starter.identity.domain.push.usecases.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.repositories.*
import dev.fajar.starter.notifications.domain.usecases.EnableNotifications
import dev.fajar.starter.notifications.presentation.nativepush.NativePushViewModel
import kotlin.time.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

internal class NativePushUiFixture {
    var now = Instant.parse("2026-10-28T00:00:00Z")
    val clock =
        object : Clock {
            override fun now() = now
        }
    val server = IdentityServer("https://hris.invalid/api/v1/", "Test")
    val original =
        Session(
            "first",
            User("11111111-1111-4111-8111-111111111111", "Test", "test@example.invalid"),
            SessionTokens("access", "refresh", (now + 1.days).toEpochMilliseconds()),
            SessionBinding(
                "22222222-2222-4222-8222-222222222222",
                server.audience,
                (now + 90.days).toEpochMilliseconds(),
            ),
        )
    val identities = MutableStateFlow<AppResult<Session?>>(AppResult.Success(original))
    val saved = MutableStateFlow(NativePushState())
    val providerToken = MutableStateFlow("provider-token-0000000000")
    val remote = mutableMapOf<String, NativePushRegistration>()
    val receipts = mutableMapOf<String, NativePushReceipt>()
    val commands = mutableListOf<Pair<NativePushOwner, NativePushCommand>>()
    val revoked = mutableListOf<Pair<String, String>>()
    var permission = NotificationAccess.Granted
    var permissionRequests = 0
    var listeners = 0
    var deletes = 0
    var loseResponse = false
    var failStorage = false
    var beforePermission: suspend () -> Unit = {}
    var beforeSend: suspend () -> Unit = {}
    val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe() = identities

            override suspend fun current() = identities.value

            override suspend fun compareAndSet(
                expected: Session?,
                updated: Session?,
            ): AppResult<Boolean> {
                if (identities.value != AppResult.Success(expected)) return AppResult.Success(false)
                identities.value = AppResult.Success(updated)
                return AppResult.Success(true)
            }

            override suspend fun clear(sessionId: String): AppResult<Boolean> {
                val value = (identities.value as? AppResult.Success)?.value
                if (value?.id != sessionId) return AppResult.Success(false)
                identities.value = AppResult.Success(null)
                return AppResult.Success(true)
            }
        }
    val storage =
        object : NativePushStateRepository {
            override val persistent = true

            override suspend fun read(): AppResult<NativePushState> =
                if (failStorage) failed(FailureKind.Storage) else AppResult.Success(saved.value)

            override fun observe() = saved.map { read() }

            override suspend fun compareAndSet(
                expected: NativePushState,
                updated: NativePushState,
            ): AppResult<Boolean> {
                if (failStorage) return failed(FailureKind.Storage)
                if (saved.value != expected) return AppResult.Success(false)
                saved.value = updated
                return AppResult.Success(true)
            }
        }
    val permissions =
        object : NotificationAccessRepository {
            override suspend fun check() = AppResult.Success(permission)

            override suspend fun request(): AppResult<NotificationAccess> {
                permissionRequests++
                beforePermission()
                permission = NotificationAccess.Granted
                return AppResult.Success(permission)
            }
        }
    val tokens =
        object : PushTokenRepository {
            override suspend fun token() = AppResult.Success(providerToken.value)

            override suspend fun deleteToken(): AppResult<Unit> {
                check(listeners == 0) { "Observer must stop before retirement" }
                deletes++
                providerToken.value = "provider-token-${deletes.toString().padStart(10, '0')}"
                return AppResult.Success(Unit)
            }

            override fun observeTokens(): Flow<AppResult<String>> = flow {
                listeners++
                try {
                    emitAll(providerToken.map { AppResult.Success(it) })
                } finally {
                    listeners--
                }
            }
        }
    val devices =
        object : DeviceSessionRepository {
            override suspend fun list(sessionId: String): AppResult<List<DeviceSession>> =
                error("Not used")

            override suspend fun revoke(sessionId: String, remoteId: String): AppResult<Unit> {
                revoked += sessionId to remoteId
                return AppResult.Success(Unit)
            }
        }
    val registrations =
        object : NativePushRegistrationRepository {
            override suspend fun read(owner: NativePushOwner) =
                AppResult.Success(remote[owner.familyId])

            override suspend fun send(
                owner: NativePushOwner,
                command: NativePushCommand,
            ): AppResult<NativePushReceipt> {
                commands += owner to command
                check(saved.value.pending == command)
                beforeSend()
                receipts[command.operationId]?.let {
                    return AppResult.Success(it)
                }
                val previous = remote[owner.familyId]
                if (previous?.version != command.expectedVersion)
                    return AppResult.Failed(
                        Failure(FailureKind.Conflict, "Changed", code = "stale_version")
                    )
                val registration =
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
                                .copy(
                                    enabled = false,
                                    version = previous.version + 1,
                                    updatedAt = now,
                                )
                    }
                remote[owner.familyId] = registration
                val receipt = NativePushReceipt(owner.familyId, registration.version)
                receipts[command.operationId] = receipt
                return if (loseResponse) failed(FailureKind.Network) else AppResult.Success(receipt)
            }
        }
    val build = AppBuild(AppPlatform.Android, 1, "1", null)

    fun status() = ObserveNativePushStatus(sessions, storage, permissions, server, build, clock)

    fun viewModel() =
        NativePushViewModel(
            ObserveSessionIdentity(sessions),
            status(),
            ReconcileNativePush(
                sessions,
                registrations,
                storage,
                tokens,
                permissions,
                devices,
                server,
                build,
                clock,
                Mutex(),
            ),
            EnableNotifications(permissions),
        )

    fun failed(kind: FailureKind) = AppResult.Failed(Failure(kind, "Unavailable"))
}
