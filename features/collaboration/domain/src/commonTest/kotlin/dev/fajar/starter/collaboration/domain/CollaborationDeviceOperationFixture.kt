package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ActivateCollaborationPusher
import dev.fajar.starter.collaboration.domain.usecases.SynchronizeCollaborationDevice
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

internal class CollaborationDeviceOperationFixture {
    val setup = CollaborationSetupFixture()
    val device = "a0000000-0000-4000-8000-000000000001"
    val registered = setup.device.copy(id = device)
    val denied = Failure(FailureKind.AccessDenied, "Denied", code = "permission_denied")
    val storage = Failure(FailureKind.Storage, "Unavailable", code = "fixture.storage")
    var expectedOwner =
        CollaborationOwner(
            setup.initial.id,
            setup.initial.binding.audience,
            setup.initial.user.id,
            setup.selected.company.id,
            setup.initial.binding.serverId,
        )

    fun background(): CollaborationOperationScope.Background {
        val session =
            setup.initial.copy(
                user = setup.initial.user.copy(id = "a1000000-0000-4000-8000-000000000001"),
                binding =
                    setup.initial.binding.copy(serverId = "a1000000-0000-4000-8000-000000000002"),
            )
        setup.session = session
        setup.workspace = null
        expectedOwner =
            CollaborationOwner(
                session.id,
                session.binding.audience,
                session.user.id,
                "a1000000-0000-4000-8000-000000000003",
                session.binding.serverId,
            )
        return CollaborationOperationScope.Background(
            CollaborationBackgroundAdmission(
                CollaborationAdmissionObservation(
                    expectedOwner,
                    device,
                    registered.version,
                    0,
                    0,
                    setup.now,
                    setup.now + 60.seconds,
                ),
                setup.now,
                setup.now + 60.seconds,
                TestTimeSource().markNow() + 60.seconds,
            )
        )
    }

    var calls = 0
    var onActivate: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }

    init {
        setup.onRead = { AppResult.Success(registered) }
    }

    val runtime =
        object : CollaborationRuntimeRepository by CollaborationDeliveryFixture().runtimes {
            override suspend fun updateRetention(
                retention:
                    dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeRetention
            ): AppResult<Unit> = closeOtherOwners(retention.foreground)

            override suspend fun acquireBackground(
                owner: CollaborationOwner,
                deviceId: String,
                lifetimeMillis: Long,
            ): AppResult<
                dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease?
            > = error("No implicit background runtime")

            override suspend fun synchronizeDevice(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> {
                assertEquals(device, deviceId)
                assertEquals(expectedOwner, owner)
                calls++
                return onActivate()
            }

            override suspend fun activatePusher(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<Unit> {
                assertEquals(device, deviceId)
                assertEquals(expectedOwner, owner)
                calls++
                return onActivate()
            }
        }
    val useCase =
        ActivateCollaborationPusher(
            runtime,
            setup.remote,
            setup.admission.repository,
            setup.sessions,
            setup.workspaces,
            IdentityServer(setup.initial.binding.audience, "Fictional Android"),
            AppBuild(AppPlatform.Android, 5, "test", null),
            object : Clock {
                override fun now() = setup.now
            },
        )

    val synchronize =
        SynchronizeCollaborationDevice(
            runtime,
            setup.remote,
            setup.admission.repository,
            setup.sessions,
            setup.workspaces,
            IdentityServer(setup.initial.binding.audience, "Fictional Android"),
            AppBuild(AppPlatform.Android, 5, "test", null),
            object : Clock {
                override fun now() = setup.now
            },
        )

    suspend fun activate() = useCase("selection", device)
}
