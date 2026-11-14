package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceRecipientDraftFixture {
    val base = SpaceDirectoryUseCaseFixture()
    val access = base.access
    val original =
        CollaborationSpaceRecipientDraft(
            base.native.contact.accountId,
            base.revision,
            "  exact\nlocal text  ",
            base.native.setup.now,
        )
    var contact: CollaborationSpaceContact? = base.native.contact
    var editor: CollaborationSpaceRecipientDraft? = null
    val facts =
        MutableStateFlow<AppResult<CollaborationSpaceRecipientDraft?>>(AppResult.Success(null))
    val writes = mutableListOf<CollaborationSpaceRecipientDraft>()
    var reads = 0
    var writeCalls = 0
    var contactReads = 0
    var contactStops = 0
    var observations = 0
    var stops = 0
    var beforeRead: suspend () -> Unit = {}
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var beforeContact: suspend () -> Unit = {}
    var readFailure: Failure? = null
    var writeFailure: Failure? = null
    var forceWriteFalse = false
    var readFacts: () -> Flow<AppResult<CollaborationSpaceRecipientDraft?>> = { facts }
    var identityFacts: () -> Flow<AppResult<Session?>> = { base.identity }
    var gateFacts: () -> Flow<AppResult<CollaborationSpaceDirectoryAdmission>> = { base.gate }

    val sessions =
        object : SessionRepository by base.sessions {
            override suspend fun current() = base.identity.value

            override fun observe() = identityFacts()
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override fun observeAdmission(access: CollaborationSpaceLocalAccess) = gateFacts()

            override fun observeContact(
                access: CollaborationSpaceLocalAccess,
                accountId: String,
            ): Flow<AppResult<CollaborationSpaceContact?>> = flow {
                assertEquals(original.peerAccountId, accountId)
                assertEquals(
                    this@SpaceRecipientDraftFixture.access.messageOwner(),
                    access.messageOwner(),
                )
                contactReads++
                try {
                    beforeContact()
                    emit(AppResult.Success(contact))
                } finally {
                    contactStops++
                }
            }
        }
    val drafts =
        object : CollaborationSpaceRecipientDraftRepository {
            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceRecipientDraft?> {
                assertEquals(original.peerAccountId, peerAccountId)
                reads++
                beforeRead()
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(editor)
            }

            override fun observe(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): Flow<AppResult<CollaborationSpaceRecipientDraft?>> = flow {
                assertEquals(original.peerAccountId, peerAccountId)
                observations++
                try {
                    emitAll(readFacts())
                } finally {
                    stops++
                }
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRecipientDraft?,
                replacement: CollaborationSpaceRecipientDraft,
            ): AppResult<Boolean> {
                writeCalls++
                beforeWrite()
                if (forceWriteFalse || (editor != expected && editor != replacement))
                    return AppResult.Success(false)
                if (editor != replacement) {
                    editor = replacement
                    writes += replacement
                }
                afterWrite()
                return writeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }

    fun edit() =
        EditCollaborationSpaceRecipientDraft(drafts, directory, sessions, base.native.server)

    fun observe() =
        ObserveCollaborationSpaceRecipientDraft(drafts, directory, sessions, base.native.server)
}
