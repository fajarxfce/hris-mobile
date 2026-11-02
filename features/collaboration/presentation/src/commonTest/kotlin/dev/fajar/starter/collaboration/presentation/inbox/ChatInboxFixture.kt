package dev.fajar.starter.collaboration.presentation.inbox

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.*

internal class ChatInboxFixture {
    var now = Instant.parse("2026-11-01T00:00:00Z")
    val clock =
        object : Clock {
            override fun now() = this@ChatInboxFixture.now
        }
    val server = IdentityServer("https://hris.example.test/", "Fixture")
    val build = AppBuild(AppPlatform.Android, 1, "1", null)
    val initial =
        Session(
            "local",
            User(chatId(1), "Fictional employee", "fictional@example.test"),
            SessionTokens("access", "refresh", (now + 1.hours).toEpochMilliseconds()),
            SessionBinding(chatId(2), server.audience, (now + 2.hours).toEpochMilliseconds()),
        )
    val session = MutableStateFlow<Session?>(initial)
    val workspace =
        MutableStateFlow<Workspace?>(
            Workspace(
                "selection",
                initial.id,
                server.audience,
                CompanyMembership(chatId(3), "Fictional company", "FC", "UTC"),
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
                        now + 1.hours,
                    ),
                    now,
                ),
            )
        )
    val network = Failure(FailureKind.Network, "Fixture unavailable")
    val denied = Failure(FailureKind.Service, "Fixture unavailable", code = "company_maintenance")
    val records = MutableStateFlow<List<CollaborationDirectSummary>>(emptyList())
    val checkpoint = MutableStateFlow<CollaborationListCheckpoint?>(null)
    val gate = MutableStateFlow(CollaborationListAdmission(null, null))
    val calls = mutableListOf<String?>()
    var observers = 0
    var completeObservation = false
    var readSummaries:
        suspend (String?) -> AppResult<CollaborationPage<CollaborationDirectSummary>> =
        {
            AppResult.Success(CollaborationPage(records.value.take(30), null))
        }

    fun item(index: Int) =
        CollaborationDirectSummary(
            CollaborationDirectConversation(
                chatId(100 + index),
                chatId(3),
                initial.user.id,
                chatId(1000 + index),
                initial.user.id,
                index.toLong(),
                1,
            ),
            CollaborationParticipant(chatId(1000 + index), "Colleague $index"),
        )

    val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe() = session.map { AppResult.Success(it) }

            override suspend fun current() = AppResult.Success(session.value)

            override suspend fun compareAndSet(
                expected: Session?,
                updated: Session?,
            ): AppResult<Boolean> = error("No login mutation")

            override suspend fun clear(sessionId: String): AppResult<Boolean> = error("No logout")
        }
    val workspaces =
        object : WorkspaceRepository {
            override fun observe(sessionId: String) = workspace.map { AppResult.Success(it) }

            override suspend fun current(sessionId: String) = AppResult.Success(workspace.value)

            override suspend fun compareAndSet(
                sessionId: String,
                expected: Workspace?,
                updated: Workspace?,
            ): AppResult<Boolean> = error("No workspace mutation")

            override suspend fun permissions(
                sessionId: String,
                companyId: String,
            ): AppResult<Set<String>> = error("No permissions acquisition")

            override suspend fun clientPolicy(
                sessionId: String,
                companyId: String,
            ): AppResult<CompanyPolicy> = error("No policy acquisition")
        }
    val local =
        object : CollaborationListRepository {
            override suspend fun get(access: CollaborationLocalAccess, conversationId: String) =
                AppResult.Success(
                    records.value.firstOrNull { it.conversation.id == conversationId }
                )

            override fun observe(
                access: CollaborationLocalAccess,
                selection: CollaborationListSelection,
            ): Flow<AppResult<CollaborationLocalList>> = flow {
                observers++
                try {
                    if (completeObservation) {
                        emit(AppResult.Failed(network))
                        return@flow
                    }
                    emitAll(
                        combine(records, checkpoint) { rows, point ->
                            val from = selection.after?.toInt() ?: 0
                            val items = rows.drop(from).take(selection.limit)
                            AppResult.Success(
                                CollaborationLocalList(
                                    point,
                                    items,
                                    (from + selection.limit).takeIf { it < rows.size }?.toString(),
                                )
                            )
                        }
                    )
                } finally {
                    observers--
                }
            }

            override suspend fun checkpoint(access: CollaborationLocalAccess) =
                AppResult.Success(checkpoint.value)

            override suspend fun merge(
                access: CollaborationLocalAccess,
                expected: CollaborationListCheckpoint?,
                items: List<CollaborationDirectSummary>,
                acquiredAt: Instant,
            ): AppResult<Boolean> {
                if (checkpoint.value != expected || session.value?.id != access.sessionId)
                    return AppResult.Success(false)
                records.value = (records.value + items).distinctBy { it.conversation.id }
                checkpoint.value =
                    CollaborationListCheckpoint(
                        chatId(4),
                        (expected?.revision ?: -1) + 1,
                        records.value.size,
                        acquiredAt,
                    )
                return AppResult.Success(true)
            }

            override fun observeAdmission(access: CollaborationLocalAccess) =
                gate.map { AppResult.Success(it) }

            override suspend fun admission(access: CollaborationLocalAccess) =
                AppResult.Success(gate.value)

            override suspend fun restrict(
                access: CollaborationLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                gate.value = CollaborationListAdmission("denied", failure)
                return AppResult.Success(true)
            }

            override suspend fun admit(
                access: CollaborationLocalAccess,
                expectedRevision: String?,
            ): AppResult<Boolean> {
                if (gate.value.revision != expectedRevision) return AppResult.Success(false)
                gate.value = CollaborationListAdmission(expectedRevision, null)
                return AppResult.Success(true)
            }
        }
    val remote =
        object : CollaborationConversationRepository {
            override suspend fun summaryByRoom(
                owner: CollaborationOwner,
                roomId: String,
            ): AppResult<CollaborationDirectSummary> = error("No implicit room lookup")

            override suspend fun summaries(
                owner: CollaborationOwner,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationDirectSummary>> {
                calls += after
                return readSummaries(after)
            }

            override suspend fun participants(
                owner: CollaborationOwner,
                query: String,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationParticipant>> =
                error("No participant query")

            override suspend fun list(
                owner: CollaborationOwner,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationDirectConversation>> =
                error("No raw conversation list")

            override suspend fun get(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectConversation> = error("No direct acquisition")

            override suspend fun summary(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectSummary> = error("No direct summary")

            override suspend fun room(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectRoom> = error("No room acquisition")

            override suspend fun open(
                owner: CollaborationOwner,
                command: CollaborationOpenDirect,
            ): AppResult<CollaborationSetupReceipt> = error("No conversation creation")

            override suspend fun requestRoom(
                owner: CollaborationOwner,
                command: CollaborationRequestDirectRoom,
            ): AppResult<CollaborationSetupReceipt> = error("No room provisioning")
        }

    fun model() =
        ChatInboxViewModel(
            "selection",
            ObserveCollaborationList(local, sessions, workspaces, server, build, clock),
            RefreshCollaborationList(local, remote, sessions, workspaces, server, build, clock),
        )
}

internal fun chatId(index: Int) = "d1000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
