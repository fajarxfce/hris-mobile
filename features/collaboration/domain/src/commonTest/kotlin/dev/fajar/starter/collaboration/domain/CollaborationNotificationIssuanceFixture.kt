package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.io.encoding.Base64
import kotlin.test.*

internal class CollaborationNotificationIssuanceFixture {
    val projection = CollaborationNotificationProjectionFixture()
    val local = projection.local

    init {
        val session = local.setup.session!!
        local.setup.session = session.copy(binding = session.binding.copy(serverId = id(7001)))
        projection.preference(
            CollaborationNotificationSettings(preview = CollaborationNotificationPreview.Message)
        )
    }

    val parent
        get() =
            local.setup.session!!.let {
                CollaborationNativeParent(
                    it.id,
                    it.binding.audience,
                    it.user.id,
                    it.binding.serverId,
                )
            }

    var stored: CollaborationNotificationPublication? = null
    var seals = 0
    var opens = 0
    var reads = 0
    var writes = 0
    var readFailure: Failure? = null
    var writeFailure: Failure? = null
    var sealFailure: Failure? = null
    var writeOutcome: CollaborationNotificationPublicationWrite? = null
    var commit = true
    var wrongOpenedAction = false
    var afterSeal: suspend () -> Unit = {}
    var afterOpen: suspend () -> Unit = {}
    var afterRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    val tokens =
        mutableMapOf<
            CollaborationNotificationActionToken,
            Pair<CollaborationNativeParent, CollaborationNotificationAction>,
        >()
    val actions =
        object : CollaborationNotificationActionRepository {
            override suspend fun seal(
                parent: CollaborationNativeParent,
                action: CollaborationNotificationAction,
            ): AppResult<CollaborationNotificationActionToken> {
                seals++
                val token =
                    CollaborationNotificationActionToken(
                        "v1." + Base64.UrlSafe.encode("test-token-$seals".encodeToByteArray())
                    )
                tokens[token] = parent to action
                afterSeal()
                return sealFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(token)
            }

            override suspend fun open(
                parent: CollaborationNativeParent,
                token: CollaborationNotificationActionToken,
            ): AppResult<CollaborationNotificationAction> {
                opens++
                val saved = tokens[token]
                afterOpen()
                if (saved == null || saved.first != parent)
                    return AppResult.Failed(Failure(FailureKind.Unauthorized, "Rejected"))
                val action =
                    if (wrongOpenedAction)
                        CollaborationNotificationAction.Dismiss(id(7999), saved.second.target)
                    else saved.second
                return AppResult.Success(action)
            }
        }
    val journal =
        object : CollaborationNotificationPublicationRepository {
            override suspend fun read(
                access: CollaborationRecoveryAccess,
                companyId: String,
                conversationId: String,
            ): AppResult<CollaborationNotificationPublication?> {
                reads++
                assertEquals(parent.accountId, access.accountId)
                assertEquals(projection.conversation, conversationId)
                val value = stored
                afterRead()
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(value)
            }

            override suspend fun replace(
                access: CollaborationRecoveryAccess,
                expected: CollaborationNotificationPublication?,
                body: CollaborationNotificationPublicationBody,
            ): AppResult<CollaborationNotificationPublicationWrite> {
                writes++
                if (stored != expected)
                    return AppResult.Success(CollaborationNotificationPublicationWrite.Contended)
                val value =
                    CollaborationNotificationPublication(
                        expected?.id ?: "p-" + "a".repeat(64),
                        CollaborationNotificationPublicationRevision(
                            expected?.version?.generation ?: id(7002),
                            (expected?.version?.revision ?: -1) + 1,
                        ),
                        body,
                    )
                if (commit) stored = value
                afterWrite()
                writeFailure?.let {
                    return AppResult.Failed(it)
                }
                return AppResult.Success(
                    writeOutcome ?: CollaborationNotificationPublicationWrite.Stored(value)
                )
            }

            override suspend fun page(
                access: CollaborationRecoveryAccess,
                after: String?,
                limit: Int,
                preparedOnly: Boolean,
            ): AppResult<CollaborationNotificationPublicationPage> =
                error("Issuer does not scan notifications")
        }
    val issuer =
        IssueCollaborationMessageNotification(
            journal,
            actions,
            projection.settings.repo,
            projection.read.repo,
            projection.history,
            projection.identities,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    val validator =
        ValidateCollaborationNotificationPublication(
            journal,
            actions,
            projection.settings.repo,
            projection.read.repo,
            projection.history,
            projection.identities,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )
    val finisher =
        FinishCollaborationNotificationPublication(
            journal,
            local.setup.sessions,
            local.server,
            local.build,
        )

    val prepareMute =
        PrepareCollaborationMutedNotification(
            journal,
            projection.settings.repo,
            projection.history,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )
    val commitMute =
        CommitCollaborationMutedNotification(
            journal,
            projection.settings.repo,
            projection.history,
            local.lists,
            local.setup.sessions,
            local.setup.workspaces,
            local.server,
            local.build,
            local.clock,
        )

    fun mute() {
        projection.preference(
            CollaborationNotificationSettings(CollaborationNotificationMute.Forever)
        )
    }

    suspend fun mutePlan(): CollaborationMutedNotificationPlan =
        assertIs<CollaborationMutedNotificationPreparation.Ready>(
                assertIs<AppResult.Success<*>>(
                        prepareMute(
                            parent,
                            scope().access,
                            projection.conversation,
                            projection.room,
                            projection.event,
                        )
                    )
                    .value
            )
            .plan

    fun scope() = projection.settings.scope().copy(nativeSessionId = parent.nativeSessionId)

    suspend fun message(): CollaborationMessageNotification =
        assertIs<CollaborationNotificationProjection.Ready>(
                assertIs<AppResult.Success<CollaborationNotificationProjection>>(
                        projection.prepare(
                            scope(),
                            projection.conversation,
                            projection.room,
                            projection.original!!.primaryEventId,
                        )
                    )
                    .value
            )
            .message

    suspend fun issue(prepared: CollaborationMessageNotification? = null) =
        issuer(scope(), prepared ?: message())

    fun replaceFamily() {
        val session = local.setup.session!!
        local.setup.session = session.copy(binding = session.binding.copy(serverId = id(7003)))
    }

    fun inactive() {
        val value = stored!!
        stored =
            value.copy(
                version = value.version.copy(revision = value.version.revision + 1),
                body =
                    value.body.copy(
                        state = CollaborationNotificationPublicationState.Dismissed,
                        message = null,
                        actions = null,
                    ),
            )
    }

    companion object {
        fun id(value: Int) = CollaborationReadStateFixture.id(value)
    }
}
