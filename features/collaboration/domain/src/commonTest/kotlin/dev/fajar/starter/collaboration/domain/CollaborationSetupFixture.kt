package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSetupRepository
import dev.fajar.starter.collaboration.domain.usecases.LoadCollaborationSetup
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.flowOf

internal class CollaborationSetupFixture {
    var now = Instant.parse("2026-10-31T00:00:00Z")
    val initial =
        Session(
            "local",
            User("account", "Fictional employee", "fictional@example.test"),
            SessionTokens("access", "refresh", (now + 1.hours).toEpochMilliseconds()),
            SessionBinding(
                "native-family",
                "https://hris.example.test/",
                (now + 2.hours).toEpochMilliseconds(),
            ),
        )
    var session: Session? = initial
    val selected =
        Workspace(
            "selection",
            initial.id,
            initial.binding.audience,
            CompanyMembership("company", "Fictional company", "FC", "Asia/Jakarta"),
            setOf("collaboration.chat"),
            WorkspaceAdmission(initial.user.id, initial.binding.serverId, now, now + 1.hours),
            CompanyPolicySnapshot(
                CompanyPolicy(
                    1,
                    setOf(CompanyModule.COLLABORATION),
                    CompanyMinimumBuilds(0, 0, 0),
                    null,
                    false,
                    now,
                    now + 5.minutes,
                ),
                now,
            ),
        )
    var workspace: Workspace? = selected
    val device =
        CollaborationDeviceSetup(
            "device",
            1,
            CollaborationDeviceState.ACTIVE,
            CollaborationNativePlatform.ANDROID,
            null,
            null,
        )
    var onRead: suspend () -> AppResult<CollaborationDeviceSetup?> = { AppResult.Success(device) }
    var calls = 0
    var observed: CollaborationOwner? = null
    var beforeWorkspace: suspend () -> Unit = {}
    val sessions =
        object : SessionRepository {
            override val persistent = false

            override fun observe() = flowOf(AppResult.Success(session))

            override suspend fun current() = AppResult.Success(session)

            override suspend fun compareAndSet(
                expected: Session?,
                updated: Session?,
            ): AppResult<Boolean> = error("No session mutation")

            override suspend fun clear(sessionId: String): AppResult<Boolean> =
                error("No session deletion")
        }
    val workspaces =
        object : WorkspaceRepository {
            override fun observe(sessionId: String) = flowOf(AppResult.Success(workspace))

            override suspend fun current(sessionId: String): AppResult<Workspace?> {
                beforeWorkspace()
                return AppResult.Success(workspace)
            }

            override suspend fun compareAndSet(
                sessionId: String,
                expected: Workspace?,
                updated: Workspace?,
            ): AppResult<Boolean> = error("No workspace mutation")

            override suspend fun permissions(
                sessionId: String,
                companyId: String,
            ): AppResult<Set<String>> = error("No permission refresh")

            override suspend fun clientPolicy(
                sessionId: String,
                companyId: String,
            ): AppResult<CompanyPolicy> = error("No policy refresh")
        }
    val remote =
        object : CollaborationSetupRepository {
            override suspend fun read(
                owner: CollaborationOwner
            ): AppResult<CollaborationDeviceSetup?> {
                calls++
                observed = owner
                return onRead()
            }

            override suspend fun register(
                owner: CollaborationOwner,
                command: CollaborationRegistration,
            ): AppResult<CollaborationSetupReceipt> = error("No registration")

            override suspend fun retry(
                owner: CollaborationOwner,
                command: CollaborationProvisioningRetry,
            ): AppResult<CollaborationSetupReceipt> = error("No provisioning retry")

            override suspend fun bootstrap(
                owner: CollaborationOwner,
                command: CollaborationBootstrap,
            ): AppResult<CollaborationGateway> = error("No gateway issuance")
        }

    fun loader(platform: AppPlatform = AppPlatform.Android) =
        LoadCollaborationSetup(
            sessions,
            workspaces,
            remote,
            IdentityServer(initial.binding.audience, "Fictional Android"),
            AppBuild(platform, 5, "test", null),
            object : Clock {
                override fun now() = now
            },
        )
}
