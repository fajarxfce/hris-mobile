package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationSpacePushSignal
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.flow.Flow

internal class SpacePushFixture {
    val base = SpaceRegistrationFixture()
    val owner = base.owner
    val initial = base.native.session
    var session: Session? = initial
    val storage = Failure(FailureKind.Storage, "Fictional storage failure", code = "test.storage")
    val command =
        CollaborationSpaceRegistrationCommand(
            "60000000-0000-4000-8000-000000000001",
            CollaborationSpaceDeviceBinding(
                owner.parent.audience,
                owner.parent.accountId,
                owner.spaceId,
                owner.parent.nativeSessionId,
            ),
            CollaborationNativePlatform.ANDROID,
            5,
            base.native.setup.now,
            base.provider,
            "Phone",
            CollaborationSpaceDeviceOrigin.LEGACY,
            CollaborationSpaceLegacySdk(
                CollaborationSetupBinding(
                    owner.parent.audience,
                    owner.parent.accountId,
                    base.company,
                    owner.parent.nativeSessionId,
                ),
                base.sdkDevice,
                base.sdkIdentity,
            ),
        )
    val registered =
        CollaborationSpaceRegistrationRecord(
            command,
            2,
            1,
            CollaborationSpaceRegistrationOutcome.Registered(base.receipt),
        )
    var registration: CollaborationSpaceRegistrationRecord? = registered
    var root: CollaborationSpaceRoot? = CollaborationSpaceRoot(owner.spaceId)
    var failedStage: String? = null
    var after: suspend (String) -> Unit = {}
    val calls = mutableListOf<String>()
    val scheduled = mutableListOf<String>()
    var retained: CollaborationSpaceRecoveryWake? = null
    var writes = 0
    val sessions =
        object : SessionRepository by base.base.sessions {
            override suspend fun current(): AppResult<Session?> {
                calls += "session"
                val captured = session
                after("session")
                return if (failedStage == "session") AppResult.Failed(storage)
                else AppResult.Success(captured)
            }
        }
    val roots =
        object : CollaborationSpaceRootRepository {
            override fun observe(
                access: CollaborationSpaceAccountAccess
            ): Flow<AppResult<CollaborationSpaceRoot?>> = error("No observation")

            override suspend fun retain(
                access: CollaborationSpaceAccountAccess,
                root: CollaborationSpaceRoot,
            ): AppResult<Boolean> = error("No discovery")

            override suspend fun current(
                access: CollaborationSpaceAccountAccess
            ): AppResult<CollaborationSpaceRoot?> {
                check(access == owner.parent.localSpaceAccountAccess())
                calls += "root"
                val captured = root
                after("root")
                return if (failedStage == "root") AppResult.Failed(storage)
                else AppResult.Success(captured)
            }
        }
    val registrations =
        object : CollaborationSpaceRegistrationCommandRepository by base.commands {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): AppResult<CollaborationSpaceRegistrationRecord?> {
                check(
                    access == owner.localDirectoryAccess() &&
                        nativeSessionId == owner.parent.nativeSessionId
                )
                calls += "registration"
                val captured = registration
                after("registration")
                return if (failedStage == "registration") AppResult.Failed(storage)
                else AppResult.Success(captured)
            }
        }
    val wakes =
        object : CollaborationSpaceRecoveryWakeRepository {
            override suspend fun pending(): AppResult<CollaborationSpaceRecoveryWake?> =
                error("No sweep")

            override suspend fun acknowledge(
                wake: CollaborationSpaceRecoveryWake
            ): AppResult<Boolean> = error("No completion")

            override suspend fun request(): AppResult<CollaborationSpaceRecoveryWake> {
                calls += "wake"
                after("beforeWake")
                if (failedStage == "wake") return AppResult.Failed(storage)
                val next = CollaborationSpaceRecoveryWake("generation-${++writes}")
                retained = next
                after("wake")
                return AppResult.Success(next)
            }
        }
    val schedules =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                calls += "schedule"
                after("beforeSchedule")
                if (failedStage == "schedule") return AppResult.Failed(storage)
                scheduled += key
                after("schedule")
                return AppResult.Success(Unit)
            }
        }

    fun receiver(platform: AppPlatform = AppPlatform.Android) =
        QueueCollaborationSpacePushSignal(
            sessions,
            roots,
            registrations,
            wakes,
            schedules,
            base.native.server,
            AppBuild(platform, 88, "test", null),
        )

    fun fields(visible: Boolean = false) = buildMap {
        put("schemaVersion", "1")
        put("eventCode", "collaboration.space_chat_available")
        put("eventId", "70000000-0000-4000-8000-000000000001")
        put("accountId", owner.parent.accountId)
        put("spaceId", owner.spaceId)
        put("nativeSessionId", owner.parent.nativeSessionId)
        put("deviceId", base.receipt.id)
        put("revision", "1")
        put("issuedAt", "1")
        put("expiresAt", "86401")
        if (visible) {
            put("matrixRoomId", "!fixture:matrix.test")
            put("matrixEventId", "$" + "fixture")
        }
    }
}
