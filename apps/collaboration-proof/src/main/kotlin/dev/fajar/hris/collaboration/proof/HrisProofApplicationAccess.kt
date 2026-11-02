package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.api.*
import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.network.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.Url
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.flowOf

/** Seeded local login/workspace, actual HRIS control HTTP adapters. This is not login UI proof. */
internal class HrisProofApplicationAccess(
    val owner: CollaborationOwner,
    private val accessToken: String,
    private val backgroundOnly: Boolean = false,
) {
    private val apiOrigin = owner.audience.trimEnd('/') + "/api/v1/"

    init {
        val origin = Url(apiOrigin)
        require(origin.host == "127.0.0.1" && origin.port in 1024..65535)
        require(
            accessToken.length in 1..8192 &&
                accessToken.none { it.isWhitespace() || it.isISOControl() }
        )
    }

    val clock = Clock.System
    val build = AppBuild(AppPlatform.Android, 1, "proof", null)
    val server = IdentityServer(owner.audience, "Fictional sender proof")
    private val now = clock.now()
    private val session =
        Session(
            owner.sessionId,
            User(owner.accountId, "Fictional sender", "sender@example.test"),
            SessionTokens(
                accessToken,
                "unused-fixture-refresh",
                (now + 5.minutes).toEpochMilliseconds(),
            ),
            SessionBinding(
                owner.nativeSessionId,
                owner.audience,
                (now + 5.minutes).toEpochMilliseconds(),
            ),
        )
    private val policy =
        CompanyPolicy(
            1,
            setOf(CompanyModule.COLLABORATION),
            CompanyMinimumBuilds(0, 0, 0),
            null,
            false,
            now,
            now + 5.minutes,
        )
    private val workspace =
        Workspace(
            "sender-proof",
            owner.sessionId,
            owner.audience,
            CompanyMembership(owner.companyId, "Fictional SDK company", "SDK", "UTC"),
            setOf("collaboration.chat"),
            WorkspaceAdmission(owner.accountId, owner.nativeSessionId, now, now + 5.minutes),
            CompanyPolicySnapshot(policy, now),
        )
    val workspaceId
        get() = workspace.id

    val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe() = flowOf(AppResult.Success(session))

            override suspend fun current() = AppResult.Success(session)

            override suspend fun compareAndSet(
                expected: Session?,
                updated: Session?,
            ): AppResult<Boolean> = error("No login mutation in sender proof")

            override suspend fun clear(sessionId: String): AppResult<Boolean> =
                error("No logout in sender proof")
        }
    val workspaces =
        object : WorkspaceRepository {
            override fun observe(
                sessionId: String
            ): kotlinx.coroutines.flow.Flow<AppResult<Workspace?>> {
                check(!backgroundOnly) { "Incoming proof cannot observe selected workspace" }
                return flowOf(AppResult.Success(workspace.takeIf { sessionId == owner.sessionId }))
            }

            override suspend fun current(sessionId: String): AppResult<Workspace?> {
                check(!backgroundOnly) { "Incoming proof cannot read selected workspace" }
                return AppResult.Success(workspace.takeIf { sessionId == owner.sessionId })
            }

            override suspend fun compareAndSet(
                sessionId: String,
                expected: Workspace?,
                updated: Workspace?,
            ): AppResult<Boolean> = error("No workspace mutation in sender proof")

            override suspend fun permissions(
                sessionId: String,
                companyId: String,
            ): AppResult<Set<String>> = error("Use actual collaboration API admission")

            override suspend fun clientPolicy(
                sessionId: String,
                companyId: String,
            ): AppResult<CompanyPolicy> = error("Use actual collaboration API admission")
        }
    val client =
        createHttpClient(OkHttp.create(), HttpClientSettings(apiOrigin, allowCleartext = true)) {
            defaultRequest {
                header("X-HRIS-Client-Platform", "ANDROID")
                header("X-HRIS-Client-Build", "1")
                header("X-HRIS-Sender-Proof", "1")
            }
            install(SessionAuthentication) {
                origin = Url(apiOrigin)
                acquire = { sessionId, rejected ->
                    check(sessionId == owner.sessionId)
                    AppResult.Success(accessToken.takeIf { rejected == null })
                }
            }
        }
    val setup =
        RemoteCollaborationSetupRepository(
            ApiCollaborationSetupRemoteSource(CollaborationSetupApi(client)),
            server,
        )
    val conversations =
        RemoteCollaborationConversationRepository(
            ApiCollaborationConversationRemoteSource(CollaborationConversationApi(client)),
            server,
        )

    fun close() = client.cancelAndClose()
}
