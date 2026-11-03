package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationNotificationChanges
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*

internal class NotificationChangesFixture {
    val local = MessageIntentFixture()

    init {
        val old = requireNotNull(local.setup.session)
        local.setup.session =
            old.copy(binding = old.binding.copy(serverId = "e1000000-0000-4000-8000-000000000001"))
    }

    private val session = requireNotNull(local.setup.session)
    val parent =
        CollaborationNativeParent(
            session.id,
            session.binding.audience,
            session.user.id,
            session.binding.serverId,
        )
    val company = requireNotNull(local.summary).conversation.companyId
    val conversation = requireNotNull(local.summary).conversation.id
    val sessionEvents = MutableStateFlow<AppResult<Session?>>(AppResult.Success(session))
    var sessions: Flow<AppResult<Session?>> = sessionEvents
    var admissions: Flow<AppResult<CollaborationListAdmission>>? = null
    var finiteGate: CollaborationListAdmission? = null
    var beforeSessionRead: suspend () -> Unit = {}
    var source: Flow<AppResult<Unit>> = flow {
        emit(AppResult.Success(Unit))
        awaitCancellation()
    }
    var started = 0
    var active = 0
    private val changes =
        object : CollaborationNotificationChangeRepository {
            override fun observe(access: CollaborationLocalAccess, conversationId: String) = flow {
                assertEquals(
                    CollaborationLocalAccess(
                        parent.sessionId,
                        parent.audience,
                        parent.accountId,
                        company,
                    ),
                    access,
                )
                assertEquals(conversation, conversationId)
                started++
                active++
                try {
                    emitAll(source)
                } finally {
                    active--
                }
            }
        }
    private val sessionRepository =
        object : SessionRepository by local.setup.sessions {
            override fun observe() = flow { emitAll(sessions) }

            override suspend fun current(): AppResult<Session?> {
                beforeSessionRead()
                return local.setup.sessions.current()
            }
        }
    private val lists =
        object : CollaborationListRepository by local.lists {
            override fun observeAdmission(access: CollaborationLocalAccess) = flow {
                emitAll(admissions ?: local.lists.observeAdmission(access))
            }

            override suspend fun admission(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationListAdmission> =
                finiteGate?.let { AppResult.Success(it) } ?: local.lists.admission(access)
        }
    val observer =
        ObserveCollaborationNotificationChanges(
            changes,
            lists,
            sessionRepository,
            local.server,
            local.build,
        )

    fun observe() = observer(parent, company, conversation)
}
