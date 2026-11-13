package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.flow.*

internal class SpaceInboxRepairUseCaseFixture {
    val catalog = SpaceCatalogUseCaseFixture()
    val base = catalog.base
    val access = base.access
    val conversation = catalog.conversation
    val sample = SpaceInboxCommandFixture()
    val record =
        sample.queued().let {
            it.copy(
                intent =
                    it.intent.copy(owner = access.messageOwner(), conversationId = conversation.id)
            )
        }
    val reference = CollaborationSpaceInboxRepairReference(record.intent, 1)
    val version = CollaborationSpaceInboxVersion(base.generation, 1, 1)
    val page = CollaborationSpaceInboxRepairPage.Items(version, listOf(reference), null)
    val initial = CollaborationSpaceInboxRepairProgress(id(1), id(2), null, null)
    var stored: CollaborationSpaceInboxRepairProgress? = initial
    var checkpointReads = 0
    val replacements =
        mutableListOf<
            Pair<CollaborationSpaceInboxRepairProgress?, CollaborationSpaceInboxRepairProgress>
        >()
    val requests = mutableListOf<Pair<String?, Int>>()
    val lookups = mutableListOf<Pair<String, String>>()
    var beforeCurrent: suspend () -> Unit = {}
    var currentGate: suspend () -> AppResult<CollaborationSpaceDirectoryAdmission> = {
        base.directory.admission(access)
    }
    var onPage: suspend () -> AppResult<CollaborationSpaceInboxRepairPage> = {
        AppResult.Success(page)
    }
    var onGet: suspend () -> AppResult<CollaborationSpaceInboxRepairReference?> = {
        AppResult.Success(reference)
    }
    var onCheckpoint: suspend () -> AppResult<CollaborationSpaceInboxRepairProgress?> = {
        AppResult.Success(stored)
    }
    var onReplace:
        suspend (
            CollaborationSpaceInboxRepairProgress?, CollaborationSpaceInboxRepairProgress,
        ) -> AppResult<CollaborationSpaceInboxRepairProgress?> =
        { expected, next ->
            if (stored != expected) AppResult.Success(null)
            else {
                stored = next
                AppResult.Success(next)
            }
        }
    val records =
        MutableStateFlow<AppResult<List<CollaborationSpaceMessageRecord>>>(
            AppResult.Success(listOf(record))
        )
    var source: () -> Flow<AppResult<List<CollaborationSpaceMessageRecord>>> = { records }
    var expectedConversation: String? = null
    var observations = 0
    var stops = 0
    val sessions =
        object : SessionRepository by base.sessions {
            override suspend fun current(): AppResult<Session?> {
                beforeCurrent()
                return base.identity.value
            }
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override suspend fun admission(access: CollaborationSpaceLocalAccess) = currentGate()
        }
    val repairs =
        object : CollaborationSpaceInboxRepairRepository {
            override suspend fun page(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceInboxRepairPage> {
                check(access == this@SpaceInboxRepairUseCaseFixture.access)
                requests += after to limit
                return onPage()
            }

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                messageId: String,
            ): AppResult<CollaborationSpaceInboxRepairReference?> {
                check(access == this@SpaceInboxRepairUseCaseFixture.access)
                lookups += conversationId to messageId
                return onGet()
            }
        }
    val progress =
        object : CollaborationSpaceInboxRepairProgressRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceInboxRepairProgress?> {
                check(access == this@SpaceInboxRepairUseCaseFixture.access)
                checkpointReads++
                return onCheckpoint()
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceInboxRepairProgress?,
                replacement: CollaborationSpaceInboxRepairProgress,
            ): AppResult<CollaborationSpaceInboxRepairProgress?> {
                check(access == this@SpaceInboxRepairUseCaseFixture.access)
                replacements += expected to replacement
                return onReplace(expected, replacement)
            }
        }
    val messages =
        object : CollaborationSpaceMessageRepository by sample.source.base.messages {
            override fun observe(access: CollaborationSpaceLocalAccess, conversationId: String?) =
                flow {
                    check(
                        access == this@SpaceInboxRepairUseCaseFixture.access &&
                            conversationId == expectedConversation
                    )
                    observations++
                    try {
                        emitAll(source())
                    } finally {
                        stops++
                    }
                }
        }

    fun id(number: Int) = "d1000000-0000-4000-8000-${number.toString().padStart(12, '0')}"

    fun read() =
        ReadCollaborationSpaceInboxRepairs(repairs, directory, sessions, base.native.server)

    fun exact() =
        ReadCollaborationSpaceInboxRepair(repairs, directory, sessions, base.native.server)

    fun checkpoint() =
        ReadCollaborationSpaceInboxRepairProgress(progress, directory, sessions, base.native.server)

    fun change() =
        ChangeCollaborationSpaceInboxRepairProgress(
            progress,
            directory,
            sessions,
            base.native.server,
        )

    fun observe() =
        ObserveCollaborationSpaceMessages(messages, directory, sessions, base.native.server)
}
