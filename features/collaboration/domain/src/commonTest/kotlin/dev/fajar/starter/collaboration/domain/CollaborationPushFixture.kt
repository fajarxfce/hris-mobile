package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryWakeRepository
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationPushSignal
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository

internal class CollaborationPushFixture {
    private val setup = CollaborationSetupFixture()
    val initial =
        setup.initial.copy(
            user = setup.initial.user.copy(id = ACCOUNT),
            binding = setup.initial.binding.copy(serverId = FAMILY),
        )
    var session: Session? = initial
    var sessionFailure: Failure? = null
    var writeFailure: Failure? = null
    var scheduleFailure: Failure? = null
    var onRead: suspend (Int) -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var beforeSchedule: suspend () -> Unit = {}
    var afterSchedule: suspend () -> Unit = {}
    var retained: CollaborationRecoveryWake? = null
    val calls = mutableListOf<String>()
    val scheduled = mutableListOf<String>()
    var reads = 0
    var writes = 0
    val storage = Failure(FailureKind.Storage, "Fictional storage failure", code = "test.storage")
    val unavailable =
        Failure(FailureKind.Network, "Fictional schedule failure", code = "test.schedule")
    val sessions =
        object : SessionRepository by setup.sessions {
            override fun observe() = error("Receiver does not observe session or workspace")

            override suspend fun current(): AppResult<Session?> {
                calls += "read"
                val captured = session
                onRead(++reads)
                return sessionFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
            }
        }
    val wakes =
        object : CollaborationRecoveryWakeRepository {
            override suspend fun pending(): AppResult<CollaborationRecoveryWake?> =
                error("No receiver sweep")

            override suspend fun acknowledge(wake: CollaborationRecoveryWake): AppResult<Boolean> =
                error("No receiver acknowledgment")

            override suspend fun request(): AppResult<CollaborationRecoveryWake> {
                calls += "write"
                beforeWrite()
                writeFailure?.let {
                    return AppResult.Failed(it)
                }
                val wake = CollaborationRecoveryWake("generation-${++writes}")
                retained = wake
                afterWrite()
                return AppResult.Success(wake)
            }
        }
    val schedules =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                calls += "schedule"
                beforeSchedule()
                scheduleFailure?.let {
                    return AppResult.Failed(it)
                }
                scheduled += key
                afterSchedule()
                return AppResult.Success(Unit)
            }
        }

    fun receiver(platform: AppPlatform = AppPlatform.Android) =
        QueueCollaborationPushSignal(
            sessions,
            schedules,
            wakes,
            IdentityServer(initial.binding.audience, "Fictional receiver"),
            AppBuild(platform, 16, "test", null),
        )

    companion object {
        const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
        const val FAMILY = "22222222-2222-4222-8222-222222222222"

        fun fields(visible: Boolean = false) = buildMap {
            put("schemaVersion", "1")
            put("eventCode", "collaboration.chat_available")
            put("eventId", "33333333-3333-4333-8333-333333333333")
            put("accountId", ACCOUNT)
            put("companyId", "44444444-4444-4444-8444-444444444444")
            put("nativeSessionId", FAMILY)
            put("deviceId", "55555555-5555-4555-8555-555555555555")
            put("revision", "1")
            put("issuedAt", "1793491200")
            put("expiresAt", "1793491500")
            if (visible) {
                put("matrixRoomId", "!room:example.test")
                put("matrixEventId", "$" + "event")
            }
        }
    }
}
